package homegap.setup

import homegap.clients.SshSession.Companion.shQuote
import homegap.config.ConfigLoader
import homegap.provision.FirewallRules

/**
 * The shell scripts that the add-node wizard runs on the server, as root. Each script is
 * idempotent, so a setup that failed can run again.
 *
 * A change that can lock the orchestrator out (the sshd settings, the firewall) has a dead-man
 * switch. A background timer reverts the change after [REVERT_AFTER_SECONDS], unless [disarm] or
 * [confirm] runs first. That occurs only after a new connection proves that access still works.
 */
object SetupScripts {
    const val REVERT_AFTER_SECONDS = 120

    /**
     * The sshd drop-in. `01-` sorts before the defaults of the distribution, such as
     * `50-cloud-init.conf`, and sshd uses the first value of a setting.
     */
    const val SSHD_DROP_IN = "/etc/ssh/sshd_config.d/01-homegap.conf"

    /** Prints facts about the server as KEY=value lines. The login user runs it, not root. */
    val PROBE = $$"""
        |. /etc/os-release
        |echo "ID=$ID"
        |echo "VERSION_ID=$VERSION_ID"
        |echo "ARCH=$(uname -m)"
        |echo "CLIENT=${SSH_CLIENT%% *}"
        |set -- $SSH_CONNECTION
        |echo "SERVER_ADDR=${3:-}"
        |echo "SERVER_PORT=${4:-}"
        |if command -v docker >/dev/null 2>&1; then echo "DOCKER=yes"; else echo "DOCKER=no"; fi
        |if [ "$(id -u)" = 0 ]; then echo "PRIV=root"
        |elif sudo -n true 2>/dev/null; then echo "PRIV=nopasswd"
        |elif command -v sudo >/dev/null 2>&1; then echo "PRIV=maybe"
        |else echo "PRIV=none"; fi
    """.trimMargin()

    /**
     * Prepares [user] for the orchestrator. The script creates the user if [create] is set, and
     * authorizes [publicKey]. A user that is not root gets passwordless sudo and, with
     * [dockerGroup], the docker group.
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
     * reloads sshd. The reload does not change open sessions, such as the master connection of the
     * orchestrator.
     *
     * The script reads the effective settings with `sshd -T -C` for each of [users] and for a
     * connection from [clientAddr]. Thus it finds a `Match` block that overrides the settings for
     * those users or for this address. A password login that must fail is not a good check,
     * because a login blocker counts it against the address of the orchestrator.
     *
     * [denyUsers] can no longer log in over SSH. With [softRootLogin], root login is a wish, not a
     * requirement: when it is off already, or another setting keeps it on, the script prints a
     * `NOTE` line and goes on. If root login is the only change, a problem with the drop-in is a
     * note too.
     */
    fun hardenSsh(
        disableRootLogin: Boolean,
        disablePasswordLogin: Boolean,
        users: List<String> = listOf("root"),
        clientAddr: String? = null,
        denyUsers: List<String> = emptyList(),
        softRootLogin: Boolean = false,
    ): String {
        val denied = denyUsers.filter { ConfigLoader.UNIX_USER.matches(it) && it != "root" }.distinct()
        val required = buildList {
            if (disableRootLogin && !softRootLogin) add("PermitRootLogin no")
            if (disablePasswordLogin) {
                add("PasswordAuthentication no")
                add("KbdInteractiveAuthentication no")
            }
            if (denied.isNotEmpty()) add("DenyUsers ${denied.joinToString(" ")}")
        }
        val soft = if (disableRootLogin && softRootLogin) listOf("PermitRootLogin no") else emptyList()
        val settings = required + soft
        val expected = required.map { it.lowercase() }
        val wished = soft.map { it.lowercase() }
        val address = clientAddr?.takeIf { IP_ADDRESS.matches(it) } ?: "127.0.0.1"
        val checkedUsers = users.filter { ConfigLoader.UNIX_USER.matches(it) }.distinct().ifEmpty { listOf("root") }
        val rootOff = if (wished.isEmpty()) {
            ""
        } else {
            $$"""sshd -T -C "user=root,host=$(hostname),addr=$$address" 2>/dev/null | tr 'A-Z' 'a-z' | grep -qxF 'permitrootlogin no' && echo "NOTE root login was already off""""
        }
        return $$"""
            |CONF=$$SSHD_DROP_IN
            |# A problem that touches only the wished-for root login is a note, not a failure.
            |fail() { if $${required.isEmpty()}; then echo "NOTE root login stays as it is: $1"; exit 0; fi; echo "$1" >&2; exit 1; }
            |$$rootOff
            |grep -Eiq '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' /etc/ssh/sshd_config || fail "sshd_config does not include sshd_config.d; harden it manually"
            |rm -f "$CONF.homegap-prev"
            |[ -f "$CONF" ] && cp -p "$CONF" "$CONF.homegap-prev"
            |cat > "$CONF.tmp" <<'HOMEGAP_SSHD'
            |# Managed by HomeGAP (add-node wizard). This file sorts first, so these settings win.
            |$${settings.joinToString("\n")}
            |HOMEGAP_SSHD
            |chmod 0644 "$CONF.tmp"
            |mv -f "$CONF.tmp" "$CONF"
            |if ! sshd -t; then $$RESTORE_SSHD; fail "sshd rejected the new settings; restored"; fi
            |for who in $${checkedUsers.joinToString(" ")}; do
            |  EFFECTIVE=$(sshd -T -C "user=$who,host=$(hostname),addr=$$address" 2>/dev/null | tr 'A-Z' 'a-z')
            |  for want in $${expected.joinToString(" ") { shQuote(it) }}; do
            |    printf '%s\n' "$EFFECTIVE" | grep -qxF "$want" || { $$RESTORE_SSHD; echo "another sshd setting overrides for $who: $want" >&2; exit 1; }
            |  done
            |  for want in $${wished.joinToString(" ") { shQuote(it) }}; do
            |    printf '%s\n' "$EFFECTIVE" | grep -qxF "$want" || echo "NOTE root login stays possible: another sshd setting keeps it on for $who"
            |  done
            |done
            |$${arm("ssh", "$RESTORE_SSHD; $RELOAD_SSHD")}
            |$$RELOAD_SSHD
            |echo "CHANGED sshd: $${settings.joinToString(", ")}"
        """.trimMargin()
    }

    /**
     * Exempts [ip] (the orchestrator, as the server sees it) from the login blockers on the server:
     * fail2ban, sshguard and CrowdSec. Each one bans an address that opens many SSH logins in a
     * short time, and setup and provisioning do that. The script also removes a current ban on
     * [ip]. A blocker that the script cannot configure is not an error: the script reports it. If
     * the address of the orchestrator changes later, the orchestrator only loses the exemption.
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

    /**
     * Reads what the server holds against [ip] after a login from it got no answer, for the last
     * [seconds] seconds. The script prints `BANNED=<blocker>` for each ban and removes the ban. It
     * prints `REFUSED=yes` if sshd logged a refusal. It prints `OPEN=<n>`, the number of SSH
     * connections from [ip] that the server holds.
     */
    fun blockReport(ip: String, seconds: Long): String {
        require(IP_ADDRESS.matches(ip)) { "not an IP address: $ip" }
        return $$"""
            |set +e
            |IP=$$ip
            |if command -v fail2ban-client >/dev/null 2>&1; then
            |  for jail in $(fail2ban-client status 2>/dev/null | sed -n 's/.*Jail list:[[:space:]]*//p' | tr ',' ' '); do
            |    if fail2ban-client status "$jail" 2>/dev/null | grep -qF "$IP"; then
            |      fail2ban-client set "$jail" unbanip "$IP" >/dev/null 2>&1
            |      echo "BANNED=fail2ban ($jail)"
            |    fi
            |  done
            |fi
            |if command -v cscli >/dev/null 2>&1 && cscli decisions list --ip "$IP" 2>/dev/null | grep -qF "$IP"; then
            |  cscli decisions delete --ip "$IP" >/dev/null 2>&1
            |  echo "BANNED=CrowdSec"
            |fi
            |if journalctl -u ssh -u sshd --since "-$${seconds}s" --no-pager 2>/dev/null | grep -F "$IP" | grep -qiE 'penalt|drop connection|refus'; then
            |  echo "REFUSED=yes"
            |fi
            |echo "OPEN=$(ss -Htn state established 2>/dev/null | grep -cF "$IP")"
            |exit 0
        """.trimMargin()
    }

    /** Applies the firewall [specs] and enables `ufw`, with a revert timer that disables it again. */
    fun firewall(specs: List<String>): String = $$"""
        |$${FirewallRules.script(specs, enable = false)}
        |$${arm("ufw", "ufw --force disable >/dev/null")}
        |ufw status | grep -q '^Status: active' || { ufw --force enable >/dev/null && echo "CHANGED ufw enabled"; }
    """.trimMargin()

    /** Restores the sshd drop-in from before [hardenSsh] and reloads sshd. Setup uses it when a check fails. */
    val REVERT_SSH = "CONF=$SSHD_DROP_IN; $RESTORE_SSHD; $RELOAD_SSHD; rm -f ${marker("ssh")}"

    /** Disables the firewall again. Setup uses it when a check fails. */
    val REVERT_UFW = "ufw --force disable >/dev/null; rm -f ${marker("ufw")}"

    /** Cancels the revert timer for [name] after a check proves that access works. */
    fun disarm(name: String): String = "rm -f ${marker(name)}"

    /**
     * Cancels the revert timers for [names] after the last check. The script fails if a timer
     * fired already. Then the timer reverted its change during the checks, and the server is not
     * in the state that setup reports.
     */
    fun confirm(names: List<String>): String {
        val checks = names.joinToString("\n") { name ->
            val reverted = "the $name change was already reverted by its safety timer; run setup again"
            "[ -f ${marker(name)} ] || { echo \"$reverted\" >&2; exit 1; }"
        }
        val removals = names.joinToString("\n") { "rm -f ${marker(it)}" }
        return "$checks\n$removals"
    }

    private const val RESTORE_SSHD =
        $$"""if [ -f "$CONF.homegap-prev" ]; then mv -f "$CONF.homegap-prev" "$CONF"; else rm -f "$CONF"; fi"""

    /**
     * Reloads sshd with the service manager of the server, or with a signal. sshd also reads its
     * config again for each new connection.
     */
    private const val RELOAD_SSHD =
        """if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet ssh 2>/dev/null; then systemctl reload ssh; """ +
            """elif command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet sshd 2>/dev/null; then systemctl reload sshd; """ +
            """elif [ -s /run/sshd.pid ]; then kill -HUP "$(cat /run/sshd.pid)"; fi"""

    /** True if [value] is a plain IPv4 or IPv6 address. */
    fun isIpAddress(value: String) = IP_ADDRESS.matches(value)

    /** An IPv4 or IPv6 address (no CIDR). A script can hold it without quotes. */
    private val IP_ADDRESS = Regex("""^(\d{1,3}(\.\d{1,3}){3}|[0-9a-fA-F:]*:[0-9a-fA-F:.]*)$""")

    private fun marker(name: String) = "/run/homegap-setup-$name.armed"

    /** Starts a detached timer that runs [revert], unless the marker is gone before the timer ends. */
    private fun arm(name: String, revert: String): String {
        val mark = marker(name)
        val job = "sleep $REVERT_AFTER_SECONDS; if [ -f $mark ]; then CONF=$SSHD_DROP_IN; $revert; rm -f $mark; fi"
        return "touch $mark; nohup setsid sh -c ${shQuote(job)} >/dev/null 2>&1 < /dev/null &"
    }
}
