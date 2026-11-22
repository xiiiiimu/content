#!/bin/bash
# Login load test, one phase. Fresh dedicated DB per phase (never schema `content`), Redis db 7.
# Usage: PERF_OUT=target/perf-login-<ts> login-phase.sh <phase> <jar> [extra app args...]
#   runs: warmup 10 rps, then 50 / 100 / 200 rps, 30 s each; every request logs in a distinct seeded user.
# Records k6 summaries plus process/system CPU from actuator (1 s samples) per run in cpu-<run>.tsv.
set -u
PHASE=$1; JAR=$2; shift 2
EXTRA=("$@")
TOOLS=$(cd "$(dirname "$0")" && pwd)
ROOT=${PERF_OUT:?set PERF_OUT to a new directory under target/}
OUT=$ROOT/$PHASE; mkdir -p $OUT
K6=${K6:-tools/k6-v0.55.0/k6-v0.55.0-windows-amd64/k6.exe}
JAVA=${JAVA:-$JAVA_HOME/bin/java}
PORT=28181; BASE=http://127.0.0.1:$PORT
DB=content_perf_login_${PHASE//-/_}_$(date +%H%M%S)
USERS=7000; PASSWORD=login-perf-pw1
KEYFILE=$ROOT/private-keys.env   # private, target/ only
[ -f $KEYFILE ] || printf 'IDENTITY_KEY=perf-identity-%s\nCURSOR_KEY=perf-cursor-%s\n' "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" > $KEYFILE
. $KEYFILE
ROOTPW=$(docker exec content-mysql-1 printenv MYSQL_ROOT_PASSWORD)
sqlq(){ docker exec -i content-mysql-1 sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N $DB" 2>&1 | grep -v 'Using a password'; }
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a $OUT/runner.log; }
metric(){ curl -s "$BASE/actuator/metrics/$1" | python -c "import sys,json
try:
 d=json.load(sys.stdin);print(d['measurements'][0]['value'])
except Exception: print('NA')"; }

log "phase=$PHASE jar=$(basename $JAR) db=$DB extra=${EXTRA[*]:-<none>}"
echo "CREATE DATABASE $DB;" | docker exec -i content-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' 2>&1 | grep -v 'Using a password'
# A fresh Redis db 7 namespace per phase: drop limiter keys from earlier phases.
docker exec content-redis-1 sh -c 'redis-cli -n 7 --scan --pattern "rl:v1:*" | xargs -r redis-cli -n 7 del' >/dev/null 2>&1
CONTENT_DB_URL="jdbc:mysql://127.0.0.1:23306/$DB?connectionTimeZone=UTC" CONTENT_MYSQL_USER=root CONTENT_MYSQL_PASSWORD="$ROOTPW" \
CONTENT_API_PORT=$PORT CONTENT_REDIS_URI=redis://127.0.0.1:26379/7 CONTENT_IDENTITY_KEY=$IDENTITY_KEY CONTENT_CURSOR_KEY=$CURSOR_KEY \
  "$JAVA" -Xms1g -Xmx1g -jar "$(cygpath -w $JAR)" --content.feed.enabled=true \
  --management.endpoints.web.exposure.include=health,metrics "${EXTRA[@]}" > $OUT/app.log 2>&1 &
APP=$!
for i in $(seq 120); do curl -sf $BASE/actuator/health >/dev/null && break; sleep 1; done
curl -sf $BASE/actuator/health >/dev/null || { log "app failed to start"; tail -50 $OUT/app.log; kill $APP; exit 1; }
log "app up pid=$APP cores=$(metric system.cpu.count)"

# seed: one user through the API (real BCrypt hash), then USERS copies of that hash
curl -s -o /dev/null -w "seed register %{http_code}\n" -H 'Content-Type: application/json' \
  -d "{\"username\":\"lt_seed\",\"password\":\"$PASSWORD\"}" $BASE/api/auth/register | tee -a $OUT/runner.log
echo "SET SESSION cte_max_recursion_depth=100000;
INSERT INTO users(username,password_hash,status,created_at_ms)
 WITH RECURSIVE s(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM s WHERE n<$USERS-1)
 SELECT CONCAT('lt_',n),(SELECT password_hash FROM users WHERE username='lt_seed'),'ACTIVE',0 FROM s;
SELECT 'users',COUNT(*) FROM users;" | sqlq | tee -a $OUT/runner.log

run(){ # name rate duration
  local name=$1 rate=$2 dur=$3
  sleep 5
  ( while [ ! -f $OUT/$name.stop ]; do printf '%s\t%s\t%s\n' "$(date +%s)" "$(metric process.cpu.usage)" "$(metric system.cpu.usage)" >> $OUT/cpu-$name.tsv; sleep 1; done ) &
  local poller=$!
  log "run $name rate=$rate"
  BASE_URL=$BASE RATE=$rate DURATION=$dur USER_COUNT=$USERS PASSWORD=$PASSWORD MAX_VUS=400 \
    "$K6" run --quiet --no-color --summary-export "$(cygpath -w $OUT/$name.json)" "$(cygpath -w $TOOLS/login.k6.js)" > $OUT/$name.k6.txt 2>&1
  touch $OUT/$name.stop; wait $poller
  log "done $name"
  # logins use distinct users, so each run starts from user 0 again: wipe sessions-side effects only (none) and
  # let the per-IP login bucket refill fully before the next run (20/min → 60 s).
  sleep 65
}
run warmup 10 10s
run l50 50 30s
run l100 100 30s
run l200 200 30s
kill $APP; wait $APP 2>/dev/null
log "phase $PHASE finished db=$DB"
