package homegap.provision

/**
 * `hg_apt`: `apt-get` that waits for another apt to finish instead of failing. On a fresh VPS,
 * cloud-init or unattended-upgrades may be running, and the mesh and the first provisioning
 * (Docker, ufw) install packages at the same time. `DPkg::Lock::Timeout` only covers the dpkg
 * lock, not `apt-get update`'s lists lock, so lock failures are retried here (about 10 minutes
 * in all). Other failures are reported at once. Output is printed only on failure.
 */
object Apt {
    val FUNCTION: String = $$"""
        |hg_apt() {
        |  local i out
        |  for i in $(seq 1 120); do
        |    if out=$(DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 "$@" 2>&1); then return 0; fi
        |    case "$out" in
        |      *"Could not get lock"*|*"Unable to lock"*|*"Unable to acquire the dpkg frontend lock"*) sleep "${HG_APT_RETRY_DELAY:-5}" ;;
        |      *) printf '%s\n' "$out" >&2; return 1 ;;
        |    esac
        |  done
        |  printf '%s\n' "$out" >&2
        |  return 1
        |}
    """.trimMargin()
}
