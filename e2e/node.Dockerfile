# A disposable stand-in for a bare Ubuntu VPS: sshd + sudo user + its own Docker daemon (DinD).
# Used only by the end-to-end test harness.
FROM docker.io/library/ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openssh-server sudo curl ca-certificates iproute2 openssl diffutils iptables ufw wireguard-tools \
      docker.io docker-compose-v2 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m -s /bin/bash -G docker deploy \
    && echo 'deploy ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/deploy \
    && mkdir -p /run/sshd /home/deploy/.ssh && chmod 700 /home/deploy/.ssh \
    && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
COPY node-entrypoint.sh /usr/local/bin/node-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/node-entrypoint.sh"]
