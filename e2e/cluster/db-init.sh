#!/bin/sh
# Runs once on a fresh primary: a replication role, reachable only from the mesh.
set -eu
psql -v ON_ERROR_STOP=1 -U postgres <<EOF
CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '$POSTGRES_PASSWORD';
EOF
echo "host replication replicator 10.77.0.0/24 scram-sha-256" >> "$PGDATA/pg_hba.conf"
