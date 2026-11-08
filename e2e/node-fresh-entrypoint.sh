#!/bin/sh
set -e
ssh-keygen -A >/dev/null
dockerd >/var/log/dockerd.log 2>&1 &
# No syslog or journald here: sshd's log is written as syslog lines (fail2ban needs the timestamp
# and the "sshd[pid]:" prefix) to the file fail2ban watches.
touch /var/log/auth.log
fail2ban-client -x start >/dev/null
/usr/sbin/sshd -D -e 2>&1 | while IFS= read -r line; do
  printf '%s node-fr1 sshd[1]: %s\n' "$(date '+%b %e %H:%M:%S')" "$line"
done >> /var/log/auth.log
