package homegap.provision

import homegap.clients.SshSession.Companion.shQuote

/**
 * The scripts that change the port of the SSH server of a node. One file names the ports,
 * [DROP_IN]. During a move, the server listens on the old port and on the new one, so a mistake
 * does not close the only way in.
 */
object SshPort {
    /** It sorts before the other files of `sshd_config.d`. No other file can name a port. */
    const val DROP_IN = "/etc/ssh/sshd_config.d/00-homegap-port.conf"

    /** How long the script waits for the SSH server to listen on a port, in seconds. */
    private const val LISTEN_WAIT_SECONDS = 15

    private const val NO_INCLUDE = "sshd_config does not include sshd_config.d: change the port by hand"

    /**
     * Makes the SSH server listen on exactly [ports]. With [firewallSpecs], the script first adds
     * these `ufw` rules, if `ufw` is active. If the server does not listen on each port in the end,
     * the script puts the old file back and fails.
     */
    fun listen(ports: List<Int>, firewallSpecs: List<String> = emptyList()): String {
        require(ports.isNotEmpty() && ports.all { it in 1..65535 }) { "not a list of ports: $ports" }
        val wanted = ports.distinct()
        val lines = wanted.joinToString("\\n") { "Port $it" }
        val rules = firewallSpecs.joinToString("\n") { "  ufw $it >/dev/null" }
        val shown = wanted.joinToString(" and ")
        return $$"""
            |CONF=$$DROP_IN
            |MAIN=/etc/ssh/sshd_config
            |grep -Eiq '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' "$MAIN" || { echo "$$NO_INCLUDE" >&2; exit 1; }
            |# A Port line in a different file adds a port. Only this file names the ports.
            |OTHER=$(grep -lEi '^[[:space:]]*Port[[:space:]]' "$MAIN" /etc/ssh/sshd_config.d/*.conf 2>/dev/null | grep -vxF "$CONF" || true)
            |[ -z "$OTHER" ] || { echo "a different file sets the SSH port ($(echo $OTHER)): remove its Port line, then try again" >&2; exit 1; }
            |if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
            |  :
            |$$rules
            |fi
            |restore() { if [ -f "$CONF.homegap-prev" ]; then mv -f "$CONF.homegap-prev" "$CONF"; else rm -f "$CONF"; fi; }
            |apply() {
            |  if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet ssh.socket 2>/dev/null; then
            |    systemctl daemon-reload; systemctl restart ssh.socket
            |  elif command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet ssh 2>/dev/null; then systemctl reload ssh
            |  elif command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet sshd 2>/dev/null; then systemctl reload sshd
            |  elif [ -s /run/sshd.pid ]; then kill -HUP "$(cat /run/sshd.pid)"
            |  else pkill -HUP -o -x sshd
            |  fi
            |}
            |listens() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
            |all_listen() {
            |  for port in $${wanted.joinToString(" ")}; do
            |    waited=0
            |    until listens "$port"; do
            |      waited=$((waited + 1)); [ "$waited" -le $$LISTEN_WAIT_SECONDS ] || return 1
            |      sleep 1
            |    done
            |  done
            |}
            |rm -f "$CONF.homegap-prev"
            |[ -f "$CONF" ] && cp -p "$CONF" "$CONF.homegap-prev"
            |printf '$$lines\n' > "$CONF.tmp"
            |chmod 0644 "$CONF.tmp"
            |mv -f "$CONF.tmp" "$CONF"
            |if ! sshd -t; then restore; echo "sshd rejected the new ports; restored" >&2; exit 1; fi
            |apply
            |if ! all_listen; then
            |  restore; apply
            |  echo "the SSH server does not listen on $$shown; restored" >&2
            |  exit 1
            |fi
            |rm -f "$CONF.homegap-prev"
            |echo $${shQuote("CHANGED sshd listens on $shown")}
        """.trimMargin()
    }
}
