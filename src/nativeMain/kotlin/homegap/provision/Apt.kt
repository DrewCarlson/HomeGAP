package homegap.provision

/**
 * `hg_apt`: `apt-get` that waits for another apt to finish and does not fail. On a new VPS,
 * cloud-init or unattended-upgrades can run while the mesh setup and the first provisioning
 * (Docker, ufw) install packages. `DPkg::Lock::Timeout` covers only the dpkg lock, not the lists
 * lock of `apt-get update`. Thus the function tries again after a lock failure, for about 10
 * minutes in all. It reports other failures at once. It prints output only after a failure.
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
