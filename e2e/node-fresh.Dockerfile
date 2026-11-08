# A stand-in for a freshly created VPS, for the add-node wizard: root logs in with a password,
# there is no management user or key yet, and a cloud-init style drop-in enables password auth
# (the wizard's sshd drop-in must override it). fail2ban runs in a strict mode, as on providers
# that ship a login blocker, so the wizard must stay under it and exempt the orchestrator. Docker
# is preinstalled because the container cannot run systemd; everything else is set up by the
# wizard. Used only by the end-to-end test harness.
FROM docker.io/library/ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openssh-server sudo curl ca-certificates iproute2 openssl diffutils iptables nftables ufw fail2ban \
      docker.io docker-compose-v2 \
    && rm -rf /var/lib/apt/lists/* \
    && echo 'root:e2e-fresh-root' | chpasswd \
    && mkdir -p /run/sshd \
    && sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config \
    && printf 'PasswordAuthentication yes\n' > /etc/ssh/sshd_config.d/50-cloud-init.conf \
    && printf '[sshd]\nenabled = true\nbackend = auto\nlogpath = /var/log/auth.log\nmode = aggressive\nmaxretry = 3\nfindtime = 10m\nbantime = 10m\n' \
      > /etc/fail2ban/jail.d/e2e.local
COPY node-fresh-entrypoint.sh /usr/local/bin/node-fresh-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/node-fresh-entrypoint.sh"]
