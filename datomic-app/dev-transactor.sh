#!/usr/bin/env bash
# Bring up the Datomic dev transactor for datomic-app.
#
# Unlike xtdb-app's config.yaml (whose !Local paths are relative to CWD), the
# Datomic transactor cd's into its own read-only Guix-store install dir before
# reading the properties, so data-dir/log-dir/pid-file MUST be absolute — a
# committed properties file with relative paths cannot work. This script
# derives absolute paths from its own location, writes a properties file under
# data/ (git-ignored), and launches the transactor from the in-repo Guix
# package (datomic-pro.scm). A clone + Guix is self-sufficient — no
# pre-installed datomic-transactor assumed.
#
#   ./dev-transactor.sh          # serves dev:// on 4334, data under data/
#
# The app (port 3001) then connects to datomic:dev://localhost:4334/hr-triad.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
data="$here/data"
mkdir -p "$data"

props="$data/dev-transactor.properties"
cat > "$props" <<EOF
protocol=dev
host=localhost
port=4334
h2-port=4335
memory-index-threshold=32m
memory-index-max=256m
object-cache-max=128m
data-dir=$data/db
log-dir=$data/log
pid-file=$data/transactor.pid
EOF

exec guix shell -f "$here/datomic-pro.scm" -- datomic-transactor "$props"
