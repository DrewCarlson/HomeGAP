#!/bin/sh
set -e
cp /keys/authorized_keys /home/deploy/.ssh/authorized_keys
chown -R deploy:deploy /home/deploy/.ssh && chmod 600 /home/deploy/.ssh/authorized_keys
ssh-keygen -A >/dev/null
dockerd >/var/log/dockerd.log 2>&1 &
exec /usr/sbin/sshd -D -e
