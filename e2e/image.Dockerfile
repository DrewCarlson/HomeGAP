# Single-arch image for local e2e runs (the release Dockerfile expects both arches' binaries).
FROM docker.io/fosrl/newt:1.20.0 AS newt
FROM docker.io/library/ubuntu:24.04 AS lego
ARG LEGO_VERSION=5.6.0
ARG LEGO_SHA256_AMD64=f02fc11b6f56b97e387fa7caa0268f86f5a336953b22458e6430e4651fea439a
ARG LEGO_SHA256_ARM64=34d87a6552d69390c4a4560237045c47713a8e148d899e16db0e97040894ed1f
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
    && ARCH=$(dpkg --print-architecture) \
    && if [ "$ARCH" = amd64 ]; then SUM="$LEGO_SHA256_AMD64"; else SUM="$LEGO_SHA256_ARM64"; fi \
    && curl -fsSL -o /tmp/lego.tar.gz "https://github.com/go-acme/lego/releases/download/v${LEGO_VERSION}/lego_v${LEGO_VERSION}_linux_${ARCH}.tar.gz" \
    && echo "$SUM  /tmp/lego.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/lego.tar.gz -C /usr/local/bin lego

FROM docker.io/library/ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates openssh-client tini curl iputils-ping iproute2 iptables wireguard-tools redis-tools && rm -rf /var/lib/apt/lists/* \
    && install -d -m 0755 /mesh /tunnels /cluster-config
COPY --from=newt /usr/local/bin/newt /usr/local/bin/newt
COPY --from=lego /usr/local/bin/lego /usr/local/bin/lego
ARG BINARY=build/bin/linuxArm64/releaseExecutable/homegap.kexe
COPY ${BINARY} /usr/local/bin/homegap
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/homegap"]
CMD ["run"]
