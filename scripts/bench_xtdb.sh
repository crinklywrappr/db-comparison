#!/usr/bin/env bash
# XTDB benchmark runner — one node, one session.
#
# Bring the node up (it inherits the shell's file-descriptor limit — see the
# note below), then run the shared harness against it exactly like the other
# two apps. scripts/bench.clj seeds 50k on first run and writes bench/xtdb.edn.
#
#   scripts/bench_xtdb.sh          # (wipe bench/xtdb.edn first for a clean set)
#
# fd note: XTDB memory-maps every Arrow run, and a bulk-loaded 50k node keeps
# the bitemporal employees table as a few thousand un-compacted runs, so a
# single scan opens ~3.5k files. That is fine under a normal `nofile` limit
# (65k here) but blows a 4096-capped VM — if you see "Too many open files",
# raise `ulimit -n`, don't blame the query. datomic/datalevin (peer/embedded)
# have no such concern; run their bench.clj directly.
set -uo pipefail
cd "$(dirname "$0")/../xtdb-app"

for p in $(ps -eo pid,args | grep '[x]tdb.main' | awk '{print $1}'); do kill -9 "$p" 2>/dev/null; done
sleep 2
guix shell -f xtdb.scm -- xtdb-standalone -f config.yaml > /tmp/xtdb-node.log 2>&1 &
until (exec 3<>/dev/tcp/localhost/15432) 2>/dev/null; do sleep 2; done; exec 3>&- 3<&-

guix shell openjdk@21:jdk clojure-tools -- clojure -J-Xmx2g -M:bench ../scripts/bench.clj

for p in $(ps -eo pid,args | grep '[x]tdb.main' | awk '{print $1}'); do kill -9 "$p" 2>/dev/null; done
echo "BENCH_XTDB DONE"
