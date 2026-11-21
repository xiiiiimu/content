#!/bin/bash
# Round-1 like load test, one phase (before|after). Fresh dedicated DB per phase; never touches schema `content`.
# Usage: PERF_OUT=target/perf-r1-<ts> run-phase.sh <before|after> <jar> [runs...]   runs default: a50 a100 a200 b50 b100 b200 c50 c100 c200
set -u
PHASE=$1; JAR=$2; shift 2
RUNS=${*:-"a50 a100 a200 b50 b100 b200 c50 c100 c200"}
TOOLS=$(cd "$(dirname "$0")" && pwd)
ROOT=${PERF_OUT:?set PERF_OUT to a new directory under target/, e.g. target/perf-r1-<timestamp>}
OUT=$ROOT/$PHASE; mkdir -p $OUT
K6=${K6:-tools/k6-v0.55.0/k6-v0.55.0-windows-amd64/k6.exe}
JAVA=${JAVA:-$JAVA_HOME/bin/java}
PORT=28180; BASE=http://127.0.0.1:$PORT
DB=content_perf_r1_${PHASE}_$(date +%H%M%S)
KEYFILE=$ROOT/private-keys.env   # private, target/ only
[ -f $KEYFILE ] || printf 'IDENTITY_KEY=perf-identity-%s\nCURSOR_KEY=perf-cursor-%s\n' "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" > $KEYFILE
. $KEYFILE
ROOTPW=$(docker exec content-mysql-1 printenv MYSQL_ROOT_PASSWORD)
sqlq(){ docker exec -i content-mysql-1 sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N $DB" 2>&1 | grep -v 'Using a password'; }
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a $OUT/runner.log; }
metric(){ curl -s "$BASE/actuator/metrics/$1" | python -c "import sys,json
try:
 d=json.load(sys.stdin);print(' '.join(f\"{m['statistic']}={m['value']}\" for m in d['measurements']))
except Exception: print('NA')"; }
lockstat(){ echo "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time');" | sqlq | tr '\t\n' '= ' ; }

log "phase=$PHASE jar=$(basename $JAR) db=$DB runs=$RUNS"
echo "CREATE DATABASE $DB;" | docker exec -i content-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' 2>&1 | grep -v 'Using a password'
CONTENT_DB_URL="jdbc:mysql://127.0.0.1:23306/$DB?connectionTimeZone=UTC" CONTENT_MYSQL_USER=root CONTENT_MYSQL_PASSWORD="$ROOTPW" \
CONTENT_API_PORT=$PORT CONTENT_REDIS_URI=redis://127.0.0.1:26379/7 CONTENT_IDENTITY_KEY=$IDENTITY_KEY CONTENT_CURSOR_KEY=$CURSOR_KEY \
  "$JAVA" -Xms1g -Xmx1g -jar "$(cygpath -w $JAR)" --content.feed.enabled=true \
  --management.endpoints.web.exposure.include=health,metrics > $OUT/app.log 2>&1 &
APP=$!
echo $APP > $OUT/app.pid
for i in $(seq 120); do curl -sf $BASE/actuator/health >/dev/null && break; sleep 1; done
curl -sf $BASE/actuator/health >/dev/null || { log "app failed to start"; tail -50 $OUT/app.log; kill $APP; exit 1; }
log "app up pid=$APP; flyway: $(echo "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;" | sqlq)"
HAS_TOPIC=$(echo "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB' AND table_name='posts' AND column_name='topic';" | sqlq)

# ---- seed: 1 author, 4000 followers, posts per run (r index), optional posts.topic ----
cat > $OUT/seed.sql <<'EOF'
SET SESSION cte_max_recursion_depth=100000;
INSERT INTO author_feed_mode(author_id,mode,mode_epoch,publish_seq) VALUES(500000,'PULL',0,100000);
INSERT INTO follows(follower_id,followee_id,status,follow_epoch,follow_cutoff_seq,history_since_ms,history_limit)
 WITH RECURSIVE s(n) AS (SELECT 600001 UNION ALL SELECT n+1 FROM s WHERE n<604000) SELECT n,500000,'FOLLOWING',1,0,0,0 FROM s;
-- r=0 warmup(4) r=1..3 (a, 100 posts) r=4..9 (b/c, 4 posts)
INSERT INTO posts(id,author_id,status,body,version,published_at_ms,publish_seq,delivery_mode,delivery_epoch)
 WITH RECURSIVE r(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM r WHERE n<9), k(m) AS (SELECT 0 UNION ALL SELECT m+1 FROM k WHERE m<99)
 SELECT 9200000000+r.n*1000+k.m,500000,'PUBLISHED','perf-r1',1,CAST(UNIX_TIMESTAMP(SYSDATE(3))*1000 AS UNSIGNED),r.n*1000+k.m+1,'PULL',0
 FROM r CROSS JOIN k WHERE (r.n BETWEEN 1 AND 3) OR k.m<4;
SELECT 'posts',COUNT(*) FROM posts; SELECT 'follows',COUNT(*) FROM follows;
EOF
sqlq < $OUT/seed.sql | tee -a $OUT/runner.log
if [ "$HAS_TOPIC" = "1" ]; then
  echo "UPDATE posts SET topic='r1warm' WHERE id BETWEEN 9200000000 AND 9200000003;
  UPDATE posts SET topic=CASE FLOOR((id-9200000000)/1000) WHEN 4 THEN 'r1b50' WHEN 5 THEN 'r1b100' WHEN 6 THEN 'r1b200' WHEN 7 THEN 'r1c50' WHEN 8 THEN 'r1c100' WHEN 9 THEN 'r1c200' END
   WHERE id BETWEEN 9200004000 AND 9200009003;
  SELECT 'topics',topic,COUNT(*) FROM posts GROUP BY topic;" | sqlq | tee -a $OUT/runner.log
fi

wait_idle(){ for i in $(seq 300); do a=$(metric hikaricp.connections.active); p=$(metric hikaricp.connections.pending)
  [[ "$a" == "VALUE=0.0" && "$p" == "VALUE=0.0" ]] && return 0; sleep 1; done; log "WARN not idle: active=$a pending=$p"; }

run_k6(){ # name rate postBase postCount topic
  local name=$1 rate=$2 pbase=$3 pcount=$4 topic=$5
  wait_idle; sleep 5
  local l0; l0=$(lockstat); local acq0; acq0=$(metric hikaricp.connections.acquire)
  ( maxp=0; while [ ! -f $OUT/$name.stop ]; do p=$(metric hikaricp.connections.pending | sed 's/VALUE=//;s/\..*//'); [[ "$p" =~ ^[0-9]+$ ]] && [ $p -gt $maxp ] && maxp=$p; echo $maxp > $OUT/$name.maxpending; sleep 1; done ) &
  local poller=$!
  log "run $name rate=$rate posts=$pbase+$pcount topic=${topic:-<none>}"
  BASE_URL=$BASE RATE=$rate DURATION=${K6_DUR:-60s} POST_BASE=$pbase POST_COUNT=$pcount USER_BASE=600001 USER_COUNT=4000 TOPIC=$topic IDENTITY_KEY=$IDENTITY_KEY MAX_VUS=400 \
    "$K6" run --quiet --no-color --summary-export "$(cygpath -w $OUT/$name.json)" "$(cygpath -w $TOOLS/like.k6.js)" > $OUT/$name.k6.txt 2>&1
  touch $OUT/$name.stop; wait $poller
  wait_idle
  local l1; l1=$(lockstat); local acq1; acq1=$(metric hikaricp.connections.acquire)
  printf '%s\tlock_before=%s\tlock_after=%s\tacq_before=%s\tacq_after=%s\tmax_pending=%s\n' "$name" "$l0" "$l1" "$acq0" "$acq1" "$(cat $OUT/$name.maxpending)" >> $OUT/db-metrics.tsv
  log "done $name"
}

run_k6 warmup 20 9200000000 4 r1warm
SEEDED_C=0
for r in $RUNS; do
  case $r in
    a50) run_k6 a50 50 9200001000 100 "";; a100) run_k6 a100 100 9200002000 100 "";; a200) run_k6 a200 200 9200003000 100 "";;
    b50) run_k6 b50 50 9200004000 4 r1b50;; b100) run_k6 b100 100 9200005000 4 r1b100;; b200) run_k6 b200 200 9200006000 4 r1b200;;
    c50|c100|c200)
      if [ $SEEDED_C = 0 ]; then
        log "seeding 1.44M historical buckets (1000 topics x 1440 min, 61..1500 min ago)"
        echo "SET SESSION cte_max_recursion_depth=100000; SET @m=FLOOR(UNIX_TIMESTAMP(SYSDATE(3))*1000/60000)*60000;
        INSERT INTO topic_minute_buckets(topic,bucket_start_ms,net_events,active_interactions)
         WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM t WHERE n<1000), m(k) AS (SELECT 61 UNION ALL SELECT k+1 FROM m WHERE k<1500)
         SELECT CONCAT('r1hist_',t.n), @m-m.k*60000, 1, 1 FROM t CROSS JOIN m;
        ANALYZE TABLE topic_minute_buckets; SELECT 'buckets',COUNT(*) FROM topic_minute_buckets;" | sqlq | tee -a $OUT/runner.log
        SEEDED_C=1
      fi
      case $r in c50) run_k6 c50 50 9200007000 4 r1c50;; c100) run_k6 c100 100 9200008000 4 r1c100;; c200) run_k6 c200 200 9200009000 4 r1c200;; esac;;
  esac
  sleep 10
done
echo "SELECT 'likes',COUNT(*) FROM post_likes; SELECT 'buckets',COUNT(*) FROM topic_minute_buckets; SELECT 'ledger',COUNT(*) FROM topic_event_ledger; SELECT 'risks',COUNT(*) FROM trend_risk_events;
SELECT topic,SUM(net_events) FROM topic_minute_buckets WHERE topic NOT LIKE 'r1hist_%' GROUP BY topic;" | sqlq | tee -a $OUT/runner.log
kill $APP; wait $APP 2>/dev/null
log "phase $PHASE finished db=$DB"
