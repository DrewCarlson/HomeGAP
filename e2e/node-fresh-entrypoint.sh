#!/bin/sh
set -e
ssh-keygen -A >/dev/null
dockerd >/var/log/dockerd.log 2>&1 &
exec /usr/sbin/sshd -D -e
