package homegap.api

import kotlinx.serialization.Serializable

/*
 * Add-node wizard: take a fresh VPS (address + initial credentials) to a provisioned node.
 * Passwords travel only in requests, are held in memory for the duration of the work and are
 * never logged, stored or returned.
 */

/** How the orchestrator logs in for setup work. Exactly one of [password] or [keyFile] is used. */
@Serializable
data class SshLoginDto(
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    val password: String? = null,
    /** A private key already on the orchestrator (for VPSes provisioned with a key). */
    val keyFile: String? = null,
    /** Forget a previously recorded host key for this address first (the server was rebuilt). */
    val resetHostKey: Boolean = false,
    /**
     * The host key fingerprint that the operator confirmed. A password login to a server with no
     * recorded host key needs it: the password goes only to a confirmed key.
     */
    val hostKeyFingerprint: String? = null,
)

/** Result of the wizard's "Test connection". */
@Serializable
data class ConnectionTestDto(
    val ok: Boolean,
    val error: String? = null,
    /** The server presented a different host key than the one recorded for this address. */
    val hostKeyChanged: Boolean = false,
    /** SHA256 fingerprint of the server's host key, to compare with the provider's console. */
    val hostKeyFingerprint: String? = null,
    /**
     * The server is new to HomeGAP, and no login was tried. The operator must compare
     * [hostKeyFingerprint] and send the test again with it in [SshLoginDto.hostKeyFingerprint].
     */
    val hostKeyUnconfirmed: Boolean = false,
    val os: String? = null,
    val osVersion: String? = null,
    val arch: String? = null,
    /** The OS and architecture are ones HomeGAP provisions. */
    val supported: Boolean = false,
    /** `root`, `nopasswd`, `password` (sudo asks for the login password) or `none`. */
    val privilege: String = "none",
    val dockerInstalled: Boolean = false,
    /** The orchestrator's address as the server sees it (default for restricting SSH). */
    val clientIp: String? = null,
)

/** Create, import or point at the SSH key the orchestrator will use for this node. */
@Serializable
data class SshKeyRequestDto(
    val nodeId: String,
    /** `generate`, `import` (paste a private key) or `existing` (a file on the orchestrator). */
    val mode: String,
    val privateKey: String? = null,
    val path: String? = null,
)

@Serializable
data class SshKeyDto(
    /** Private key file on the orchestrator. */
    val path: String,
    /** `authorized_keys` line. */
    val publicKey: String,
    val fingerprint: String,
)

/** Everything the wizard collected; drives one setup job. */
@Serializable
data class NodeSetupRequestDto(
    val node: NodeConfigDto,
    val login: SshLoginDto,
    /** Key the node will be managed with afterwards (from the keys endpoint). */
    val keyFile: String,
    /** Create this user (sudo + docker groups, passwordless sudo, key login); null keeps [SshLoginDto.user]. */
    val createUser: String? = null,
    val dockerGroup: Boolean = true,
    val disableRootLogin: Boolean = false,
    val disablePasswordLogin: Boolean = false,
    /**
     * Ends the SSH login of the initial login user ([SshLoginDto.user]), when it is not root and
     * not the management user. The provider's console still works.
     */
    val disableLoginUser: Boolean = false,
    val configureFirewall: Boolean = true,
    /** Allowed SSH sources (IPs/CIDRs); empty allows any source. */
    val sshAllowFrom: List<String> = emptyList(),
    /**
     * A failed job this one retries: steps whose result stayed on the server (the management
     * user) are not run again.
     */
    val retryOf: String? = null,
)

@Serializable
data class SetupStepDto(
    val id: String,
    val title: String,
    /** `pending`, `running`, `ok`, `failed` or `skipped`. */
    val status: String = "pending",
    val detail: String? = null,
)

@Serializable
data class SetupJobDto(
    val id: String,
    val nodeId: String,
    /** `running`, `succeeded` or `failed`. */
    val state: String,
    val steps: List<SetupStepDto>,
    val error: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** After a failure by a block of new connections: the time before which a retry fails the same way. */
    val retryAt: Long? = null,
)
