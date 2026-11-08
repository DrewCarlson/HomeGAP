# Single-arch image for local e2e runs (the release Dockerfile expects both arches' binaries).
FROM docker.io/fosrl/newt:1.20.0 AS newt
FROM docker.io/library/ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates openssh-client tini curl iputils-ping iproute2 iptables wireguard-tools redis-tools && rm -rf /var/lib/apt/lists/*
COPY --from=newt /usr/local/bin/newt /usr/local/bin/newt
ARG BINARY=build/bin/linuxArm64/releaseExecutable/homegap.kexe
COPY ${BINARY} /usr/local/bin/homegap
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/homegap"]
CMD ["run"]
