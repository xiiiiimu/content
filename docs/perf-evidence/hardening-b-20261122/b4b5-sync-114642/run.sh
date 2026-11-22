#!/bin/bash
# B4/B5 before/after: identical measurement IT, flags and heap; beafbea export first, then the current tree.
set -u
export JAVA_HOME=/c/Users/西木/.jdks/ms-17.0.17 CONTENT_REAL_MQ_IT=true
MVN=/e/content/tools/apache-maven-3.9.6/bin/mvn
OUT=$2; mkdir -p $OUT
run(){ # run <label> <tree>
  echo "[$(date +%T)] start $1 tree=$2" | tee -a $OUT/runner.log
  (cd $2 && git -C /e/content rev-parse --short HEAD >/dev/null; $MVN -o -B -ntp -Pintegration verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
    "-Dit.test=CommerceSyncPerfIT#perfCacheSync" \
    "-DargLine=--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -Xmx1g -Dcontent.b5.perf=true -Dcontent.b5.out=$OUT/$1") > $OUT/$1.mvn.log 2>&1
  echo "[$(date +%T)] done $1 rc=$? $(grep -E 'Tests run:.*CommerceSyncPerf' $OUT/$1.mvn.log | tail -1)" | tee -a $OUT/runner.log
}
[ "$1" = before ] && run before_beafbea /e/content/target/src-beafbea

[ "$1" = after ] && run after_b4b5 /e/content
echo $OUT
