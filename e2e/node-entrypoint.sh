#!/bin/sh
set -e
cp /keys/authorized_keys /home/deploy/.ssh/authorized_keys
chown -R deploy:deploy /home/deploy/.ssh && chmod 600 /home/deploy/.ssh/authorized_keys
ssh-keygen -A >/dev/null
# A container restart leaves the previous daemon's pid files behind.
rm -f /var/run/docker.pid /var/run/docker/containerd/containerd.pid
dockerd >/var/log/dockerd.log 2>&1 &
exec /usr/sbin/sshd -D -e
