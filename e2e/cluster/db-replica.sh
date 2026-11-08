#!/bin/sh
# A streaming replica: on first start, clones the primary over the mesh (pg_basebackup -R writes
# standby.signal and the connection), then runs PostgreSQL as a hot standby.
set -eu
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  mkdir -p "$PGDATA"
  chown postgres:postgres "$PGDATA"
  chmod 700 "$PGDATA"
  until PGPASSWORD="$POSTGRES_PASSWORD" gosu postgres pg_basebackup -h "$PRIMARY_HOST" -U replicator -D "$PGDATA" -R -X stream; do
    echo "waiting for the primary at $PRIMARY_HOST"
    sleep 2
  done
fi
exec docker-entrypoint.sh postgres -c listen_addresses='*'
