# PostgreSQL 17 managed by Patroni (from the PostgreSQL apt repository the image already uses).
# Patroni elects the primary through etcd and fails over or switches over between members.
FROM docker.io/library/postgres:17
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends patroni python3-etcd \
    && rm -rf /var/lib/apt/lists/*
USER postgres
ENTRYPOINT ["patroni", "/node/patroni.yml"]
