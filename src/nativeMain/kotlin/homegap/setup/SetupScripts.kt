package homegap.setup

import homegap.clients.SshSession.Companion.shQuote
import homegap.config.ConfigLoader

/**
 * Shell scripts the add-node wizard runs on the server, as root. Each is idempotent so a failed
 * setup can simply be run again.
 *
 * Changes that could lock the orchestrator out (sshd settings, the firewall) are applied with a
 * dead-man switch: a background timer reverts them after [REVERT_AFTER_SECONDS] unless [disarm] runs
 * first, which happens only after a fresh connection proved access still works.
 */
object SetupScripts {
    const val REVERT_AFTER_SECONDS = 120

    /** sshd drop-in; `01-` sorts before distribution defaults such as `50-cloud-init.conf` (first value wins). */
    const val SSHD_DROP_IN = "/etc/ssh/sshd_config.d/01-homegap.conf"

    /** Facts about the server, printed as KEY=value lines (run as the login user, not root). */
    val PROBE = $$"""
        |. /etc/os-release
        |echo "ID=$ID"
        |echo "VERSION_ID=$VERSION_ID"
        |echo "ARCH=$(uname -m)"
        |echo "CLIENT=${SSH_CLIENT%% *}"
        |if command -v docker >/dev/null 2>&1; then echo "DOCKER=yes"; else echo "DOCKER=no"; fi
        |if [ "$(id -u)" = 0 ]; then echo "PRIV=root"
        |elif sudo -n true 2>/dev/null; then echo "PRIV=nopasswd"
        |elif command -v sudo >/dev/null 2>&1; then echo "PRIV=maybe"
        |else echo "PRIV=none"; fi
    """.trimMargin()

    /**
     * Prepares [user] for the orchestrator: creates it when [create], authorizes [publicKey],
     * and (for non-root users) grants passwordless sudo and, with [dockerGroup], the docker group.
     */
    fun prepareUser(user: String, publicKey: String, create: Boolean, dockerGroup: Boolean): String = $$"""
        |U=$${shQuote(user)}
        |KEY=$${shQuote(publicKey)}
        |$${if (create) CREATE_USER else REQUIRE_USER}
        |if [ "$U" != root ]; then
        |  getent group sudo >/dev/null && usermod -aG sudo "$U"
        |  $${if (dockerGroup) DOCKER_GROUP else ":"}
        |  F=/etc/sudoers.d/90-homegap-$U
        |  printf '%s ALL=(ALL) NOPASSWD:ALL\n' "$U" > "$F.tmp"
        |  chmod 0440 "$F.tmp"
        |  visudo -cf "$F.tmp" >/dev/null
        |  if ! cmp -s "$F.tmp" "$F" 2>/dev/null; then mv -f "$F.tmp" "$F"; echo "CHANGED passwordless sudo for $U"; else rm -f "$F.tmp"; fi
        |fi
        |H=$(getent passwd "$U" | cut -d: -f6)
        |G=$(id -gn "$U")
        |install -d -m 0700 -o "$U" -g "$G" "$H/.ssh"
        |touch "$H/.ssh/authorized_keys"
        |if ! grep -qxF "$KEY" "$H/.ssh/authorized_keys"; then printf '%s\n' "$KEY" >> "$H/.ssh/authorized_keys"; echo "CHANGED authorized the management key for $U"; fi
        |chown "$U:$G" "$H/.ssh/authorized_keys"
        |chmod 0600 "$H/.ssh/authorized_keys"
    """.trimMargin()

    private const val CREATE_USER =
        $$"""if ! id -u "$U" >/dev/null 2>&1; then useradd -m -s /bin/bash "$U"; echo "CHANGED created user $U"; fi"""

    private const val REQUIRE_USER =
        $$"""id -u "$U" >/dev/null 2>&1 || { echo "user $U does not exist" >&2; exit 1; }"""

    private const val DOCKER_GROUP =
        $$"""getent group docker >/dev/null || groupadd docker; usermod -aG docker "$U""""

    /**
     * Writes the sshd drop-in, checks the effective configuration, arms the revert timer and
     * reloads sshd. Existing sessions (the orchestrator's master connection) are unaffected.
     *
     * The effective settings are checked with `sshd -T -C` for each of [users] connecting from
     * [clientAddr], so a `Match` block that would override them for those users or this address is
     * caught. That replaces trying a password login and expecting it to fail, which a login blocker
     * would count against the orchestrator's address.
     */
    fun hardenSsh(
        disableRootLogin: Boolean,
        disablePasswordLogin: Boolean,
        users: List<String> = listOf("root"),
        clientAddr: String? = null,
    ): String {
        val settings = buildList {
            if (disableRootLogin) add("PermitRootLogin no")
            if (disablePasswordLogin) {
                add("PasswordAuthentication no")
                add("KbdInteractiveAuthentication no")
            }
        }
        val expected = settings.map { it.lowercase() }
        val addr = clientAddr?.takeIf { IP_ADDRESS.matches(it) } ?: "127.0.0.1"
        val who = users.filter { ConfigLoader.UNIX_USER.matches(it) }.distinct().ifEmpty { listOf("root") }
        return $$"""
            |CONF=$$SSHD_DROP_IN
            |grep -Eiq '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' /etc/ssh/sshd_config || {
            |  echo "sshd_config does not include sshd_config.d; harden it manually" >&2; exit 1; }
            |rm -f "$CONF.homegap-prev"
            |[ -f "$CONF" ] && cp -p "$CONF" "$CONF.homegap-prev"
            |cat > "$CONF.tmp" <<'HOMEGAP_SSHD'
            |# Managed by HomeGAP (add-node wizard). This file sorts first, so these settings win.
            |$${settings.joinToString("\n")}
            |HOMEGAP_SSHD
            |chmod 0644 "$CONF.tmp"
            |mv -f "$CONF.tmp" "$CONF"
            |if ! sshd -t; then $$RESTORE_SSHD; echo "sshd rejected the new settings; restored" >&2; exit 1; fi
            |for who in $${who.joinToString(" ")}; do
            |  EFFECTIVE=$(sshd -T -C "user=$who,host=$(hostname),addr=$$addr" 2>/dev/null | tr 'A-Z' 'a-z')
            |  for want in $${expected.joinToString(" ") { shQuote(it) }}; do
            |    printf '%s\n' "$EFFECTIVE" | grep -qxF "$want" || { $$RESTORE_SSHD; echo "another sshd setting overrides for $who: $want" >&2; exit 1; }
            |  done
            |done
            |$${arm("ssh", "$RESTORE_SSHD; $RELOAD_SSHD")}
            |$$RELOAD_SSHD
            |echo "CHANGED sshd: $${settings.joinToString(", ")}"
        """.trimMargin()
    }

    /**
     * Exempts [ip] (the orchestrator, as the server sees it) from login blockers that are installed:
     * fail2ban, sshguard and CrowdSec. Each one bans addresses that open many SSH logins quickly,
     * which setup and provisioning do. Any current ban on [ip] is lifted. Best effort: a blocker
     * this cannot configure is reported, not fatal. If the orchestrator's address later changes, it
     * only loses the exemption.
     */
    fun exemptFromBlockers(ip: String): String {
        require(IP_ADDRESS.matches(ip)) { "not an IP address: $ip" }
        return $$"""
            |set +e
            |IP=$$ip
            |if command -v fail2ban-client >/dev/null 2>&1 && [ -d /etc/fail2ban/jail.d ]; then
            |  F=/etc/fail2ban/jail.d/zz-homegap.local
            |  if ! grep -qF "$IP" "$F" 2>/dev/null; then
            |    # Extend an ignoreip set earlier; when none is (the default), "known/" cannot resolve,
            |    # so write fail2ban's stock loopback entries plus the orchestrator instead.
            |    SAVED=no
            |    for LIST in "%(known/ignoreip)s $IP" "127.0.0.1/8 ::1 $IP"; do
            |      printf '# Managed by HomeGAP: never ban the orchestrator.\n[DEFAULT]\nignoreip = %s\n' "$LIST" > "$F"
            |      if fail2ban-client --test >/dev/null 2>&1; then SAVED=yes; break; fi
            |    done
            |    if [ "$SAVED" = yes ]; then echo "CHANGED fail2ban ignores $IP"
            |    else rm -f "$F"; echo "CHANGED fail2ban ignores $IP until it restarts (its config did not accept a permanent entry)"; fi
            |    fail2ban-client reload >/dev/null 2>&1
            |  fi
            |  for jail in $(fail2ban-client status 2>/dev/null | sed -n 's/.*Jail list:[[:space:]]*//p' | tr ',' ' '); do
            |    fail2ban-client set "$jail" addignoreip "$IP" >/dev/null 2>&1
            |    fail2ban-client set "$jail" unbanip "$IP" >/dev/null 2>&1
            |  done
            |fi
            |if command -v sshguard >/dev/null 2>&1 && [ -f /etc/sshguard/sshguard.conf ]; then
            |  WL=$(sed -n 's/^WHITELIST_FILE=//p' /etc/sshguard/sshguard.conf | tr -d '"' | tail -n 1)
            |  if [ -z "$WL" ]; then WL=/etc/sshguard/whitelist; echo "WHITELIST_FILE=$WL" >> /etc/sshguard/sshguard.conf; fi
            |  if ! grep -qxF "$IP" "$WL" 2>/dev/null; then
            |    echo "$IP" >> "$WL"
            |    systemctl restart sshguard >/dev/null 2>&1
            |    echo "CHANGED sshguard ignores $IP"
            |  fi
            |fi
            |if command -v cscli >/dev/null 2>&1 && [ -d /etc/crowdsec/parsers ]; then
            |  D=/etc/crowdsec/parsers/s02-enrich
            |  F=$D/homegap-whitelist.yaml
            |  if ! grep -qF "$IP" "$F" 2>/dev/null; then
            |    mkdir -p "$D"
            |    printf 'name: homegap/orchestrator-whitelist\ndescription: "Never ban the HomeGAP orchestrator"\nwhitelist:\n  reason: "HomeGAP orchestrator"\n  ip:\n    - "%s"\n' "$IP" > "$F"
            |    systemctl reload crowdsec >/dev/null 2>&1 || systemctl restart crowdsec >/dev/null 2>&1
            |    echo "CHANGED CrowdSec ignores $IP"
            |  fi
            |  cscli decisions delete --ip "$IP" >/dev/null 2>&1
            |fi
            |exit 0
        """.trimMargin()
    }

    /** Applies the firewall [specs] and enables `ufw`, with a revert timer that disables it again. */
    fun firewall(specs: List<String>): String = $$"""
        |$${homegap.provision.FirewallRules.script(specs, enable = false)}
        |$${arm("ufw", "ufw --force disable >/dev/null")}
        |ufw status | grep -q '^Status: active' || { ufw --force enable >/dev/null && echo "CHANGED ufw enabled"; }
    """.trimMargin()

    /** Restores the sshd drop-in from before [hardenSsh] and reloads sshd (used when verification fails). */
    val REVERT_SSH = "CONF=$SSHD_DROP_IN; $RESTORE_SSHD; $RELOAD_SSHD; rm -f ${marker("ssh")}"

    /** Turns the firewall off again (used when verification fails). */
    val REVERT_UFW = "ufw --force disable >/dev/null; rm -f ${marker("ufw")}"

    /** Cancels the revert timer for [name] once access was verified. */
    fun disarm(name: String): String = "rm -f ${marker(name)}"

    private const val RESTORE_SSHD =
        $$"""if [ -f "$CONF.homegap-prev" ]; then mv -f "$CONF.homegap-prev" "$CONF"; else rm -f "$CONF"; fi"""

    /** Reload with whatever manages sshd here; sshd also rereads its config for each new connection. */
    private const val RELOAD_SSHD =
        $$"""if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet ssh 2>/dev/null; then systemctl reload ssh; """ +
            $$"""elif command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet sshd 2>/dev/null; then systemctl reload sshd; """ +
            $$"""elif [ -s /run/sshd.pid ]; then kill -HUP "$(cat /run/sshd.pid)"; fi"""

    /** Whether [value] is a plain IPv4 or IPv6 address. */
    fun isIpAddress(value: String) = IP_ADDRESS.matches(value)

    /** An IPv4 or IPv6 address (no CIDR), safe to put in scripts unquoted. */
    private val IP_ADDRESS = Regex("""^(\d{1,3}(\.\d{1,3}){3}|[0-9a-fA-F:]*:[0-9a-fA-F:.]*)$""")

    private fun marker(name: String) = "/run/homegap-setup-$name.armed"

    /** Starts a detached timer that runs [revert] unless the marker is removed first. */
    private fun arm(name: String, revert: String): String {
        val mark = marker(name)
        val job = "sleep $REVERT_AFTER_SECONDS; if [ -f $mark ]; then CONF=$SSHD_DROP_IN; $revert; rm -f $mark; fi"
        return "touch $mark; nohup setsid sh -c ${shQuote(job)} >/dev/null 2>&1 < /dev/null &"
    }
}
