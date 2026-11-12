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
# The SHA-256 of the etcd release archives. Change them together with ETCD_VERSION.
ARG ETCD_SHA256_AMD64=eff6ac621d41711085d0f38fab17d8fa3705f6326c3ff11301a1f5a71fc94edd
ARG ETCD_SHA256_ARM64=7d717a62520bf39fa1115dfbb1df79479ff74b5eda0914f4132bfa60a48b9549
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       'patroni=4.1.*' python3-etcd haproxy curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" \
    && if [ "$ARCH" = amd64 ]; then SUM="$ETCD_SHA256_AMD64"; else SUM="$ETCD_SHA256_ARM64"; fi \
    && curl -fsSL -o /tmp/etcd.tar.gz "https://github.com/etcd-io/etcd/releases/download/v${ETCD_VERSION}/etcd-v${ETCD_VERSION}-linux-${ARCH}.tar.gz" \
    && echo "$SUM  /tmp/etcd.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/etcd.tar.gz -C /tmp && rm /tmp/etcd.tar.gz \
    && install -m 0755 "/tmp/etcd-v${ETCD_VERSION}-linux-${ARCH}/etcd" "/tmp/etcd-v${ETCD_VERSION}-linux-${ARCH}/etcdctl" /usr/local/bin/ \
    && rm -rf /tmp/etcd-v*
COPY --from=valkey /usr/local/bin/valkey-server /usr/local/bin/valkey-cli /usr/local/bin/
RUN ln -s valkey-server /usr/local/bin/valkey-sentinel
COPY hg-member.sh /usr/local/bin/hg-member
ENTRYPOINT ["/usr/local/bin/hg-member"]
