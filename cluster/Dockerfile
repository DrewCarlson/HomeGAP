# The HomeGAP cluster image: everything a cluster member runs, one role per container
# (see hg-member.sh): PostgreSQL 17 managed by Patroni, etcd (Patroni's vote), Valkey with
# Sentinel, and HAProxy (on nodes: stable local addresses for the current primaries).
#
#   docker buildx build --platform linux/amd64,linux/arm64 -t homegap-cluster cluster
ARG VALKEY_VERSION=8.1
FROM docker.io/valkey/valkey:${VALKEY_VERSION} AS valkey

FROM docker.io/library/postgres:17
ARG TARGETARCH
ARG ETCD_VERSION=3.5.17
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       'patroni=4.1.*' python3-etcd haproxy curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" \
    && curl -fsSL "https://github.com/etcd-io/etcd/releases/download/v${ETCD_VERSION}/etcd-v${ETCD_VERSION}-linux-${ARCH}.tar.gz" | tar -xz -C /tmp \
    && install -m 0755 "/tmp/etcd-v${ETCD_VERSION}-linux-${ARCH}/etcd" "/tmp/etcd-v${ETCD_VERSION}-linux-${ARCH}/etcdctl" /usr/local/bin/ \
    && rm -rf /tmp/etcd-v*
COPY --from=valkey /usr/local/bin/valkey-server /usr/local/bin/valkey-cli /usr/local/bin/
RUN ln -s valkey-server /usr/local/bin/valkey-sentinel
COPY hg-member.sh /usr/local/bin/hg-member
ENTRYPOINT ["/usr/local/bin/hg-member"]
