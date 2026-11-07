# HomeGAP orchestrator image.
#
# Build the native binaries first (CI does this on a Linux runner):
#   ./gradlew linkReleaseExecutableLinuxX64 linkReleaseExecutableLinuxArm64
# then:  docker buildx build --platform linux/amd64,linux/arm64 -t homegap .
#
# The same image runs the controller (`homegap run`, default) and the tunnel supervisor
# (`homegap tunnels`), which keeps one Newt connector per regional node.

ARG NEWT_VERSION=1.20.0
FROM docker.io/fosrl/newt:${NEWT_VERSION} AS newt

FROM docker.io/library/ubuntu:24.04
ARG TARGETARCH

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates openssh-client tini \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin homegap \
    && install -d -o homegap -g homegap -m 0700 /data

COPY --from=newt /usr/local/bin/newt /usr/local/bin/newt
COPY build/bin/linuxX64/releaseExecutable/homegap.kexe /tmp/homegap-amd64
COPY build/bin/linuxArm64/releaseExecutable/homegap.kexe /tmp/homegap-arm64
RUN if [ "${TARGETARCH:-$(dpkg --print-architecture)}" = "amd64" ]; then mv /tmp/homegap-amd64 /usr/local/bin/homegap; else mv /tmp/homegap-arm64 /usr/local/bin/homegap; fi \
    && rm -f /tmp/homegap-* && chmod 0755 /usr/local/bin/homegap

ENV HOMEGAP_CONFIG=/config/config.yml
VOLUME ["/data"]
EXPOSE 8088
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/homegap"]
CMD ["run"]
