package homegap.reconcile

import com.charleskorn.kaml.EmptyYamlDocumentException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import homegap.acme.CertMaterial
import homegap.clients.PangolinClient
import homegap.clients.PangolinResource
import homegap.clients.PangolinSiteResource
import homegap.clients.PangolinTarget
import homegap.clients.SshSession
import homegap.clients.SshSession.Companion.shQuote
import homegap.config.SecretRef
import homegap.discovery.AuthMode
import homegap.discovery.HealthCheck
import homegap.model.NodeDesired
import homegap.model.Planner
import homegap.model.PrivateResource
import homegap.model.PublicResource
import homegap.model.ResourceMode
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.runCatchingCancellable
import homegap.provision.ExitNodes
import homegap.provision.MaintenancePage
import homegap.provision.NodeTemplates
import homegap.provision.TunnelDns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64

/**
 * Resource domain: Pangolin public/private resources on each node via declarative blueprints
 * (keyed by deterministic niceIds), deletion of managed resources no longer desired, and the
 * node's hot-reloaded Traefik routing file (SNI passthrough, health, management API).
 */
class ResourceReconciler(
    private val ctx: ReconcileContext,
    /** The certificate that each node serves for the names it terminates, or null if there is none. */
    private val certificate: CertMaterial? = null,
) {
    private val orgId: String get() = ctx.config.pangolin.orgId
    private val installDir: String get() = ctx.config.pangolin.installDir

    /** One pass over the resources of one node. [facts] are the facts of the node at the start. */
    private class NodePass(val ready: ReadyNode, val paused: Boolean, val facts: Map<String, String>) {
        val nodeId: String get() = ready.node.id
        val api: PangolinClient get() = ready.api
        var applied = 0
    }

    /** One pass over the shared resources of a cluster. [known] has the blueprint hashes at the start. */
    private class ClusterPass(val api: PangolinClient, val paused: Boolean, val known: Map<String, String>) {
        val hashes = known.toMutableMap()
        var applied = 0
    }

    /** The managed resources that a pass keeps: each niceId in [wanted], and each resource of a paused service. */
    private class Kept(private val wanted: Set<String>, private val pausedServiceKeys: Set<String>) {
        /** A resource that nothing keeps. The answer is never true for a resource that HomeGAP does not manage. */
        fun isStale(niceId: String): Boolean =
            niceId.startsWith(Planner.NICE_PREFIX) &&
                niceId !in wanted &&
                pausedServiceKeys.none { niceId.startsWith(Planner.NICE_PREFIX + it) }
    }

    // ------------------------------------------------------------------ one node

    suspend fun reconcile(
        ready: ReadyNode,
        desired: NodeDesired,
        pausedServiceKeys: Set<String>,
    ): List<PangolinResource> {
        val node = ready.node
        val api = ready.api
        val paused = ctx.nodePaused(node.id)

        applyBaseBlueprint(api, node.id, paused)

        var actual = api.listResources(orgId)
        val byNiceId = actual.associateBy { it.niceId }
        val pass = NodePass(ready, paused, ctx.state.node(node.id).facts)

        // One blueprint per resource: a resource Pangolin rejects (e.g. a wildcard without an EE
        // license) must not block the others. Each is re-applied when missing, drifted, or changed.
        for (resource in desired.publicResources.filter { !it.paused }) {
            applyPublic(pass, resource, byNiceId[resource.niceId])
        }
        // Listed once per pass and shared with deleteStalePrivate.
        val siteResources = runCatchingCancellable { api.listSiteResources(orgId) }
        val privateActual = siteResources.getOrNull()?.map { it.niceId }?.toSet()
        for (resource in desired.privateResources.filter { !it.paused }) {
            applyPrivate(pass, resource, privateActual)
        }
        if (pass.applied > 0) actual = api.listResources(orgId)

        // Delete managed resources that are no longer desired (never touch unmanaged ones).
        val kept = Kept(desired.publicResources.map { it.niceId }.toSet(), pausedServiceKeys)
        actual.filter { kept.isStale(it.niceId) }.forEach { deleteStale(pass, it) }
        if (!paused) actual = actual.filter { !kept.isStale(it.niceId) }

        val keptPrivate = Kept(desired.privateResources.map { it.niceId }.toSet(), pausedServiceKeys)
        deleteStalePrivate(pass, keptPrivate, siteResources)

        if (!paused) writeRoutes(ready, desired, actual)
        return actual
    }

    private suspend fun applyPublic(pass: NodePass, resource: PublicResource, existing: PangolinResource?) {
        val nodeId = pass.nodeId
        val niceId = resource.niceId
        val blueprint = buildBlueprint(listOf(resource), emptyList(), ctx.config.siteNiceId(nodeId))
        val hash = Crypto.fnv64(blueprint.toString())
        val fact = BLUEPRINT_FACT + niceId
        val drifted = !matches(resource, existing)
        val changed = pass.facts[fact] != hash
        val action = when {
            existing == null -> "create"
            drifted || changed -> "update"
            else -> "none"
        }
        ctx.diff.add(
            domain = DOMAIN,
            target = nodeId,
            key = niceId,
            action = action,
            desired = describe(resource),
            actual = existing?.let { describe(it) },
        )
        if (action == "none") return
        if (pass.paused) {
            ctx.log.info(DOMAIN, "paused: $niceId differs from desired, not applying", nodeId)
            return
        }
        val verb = appliedVerb(existing, drifted)
        runCatchingCancellable {
            pass.api.applyBlueprint(orgId, blueprint)
            pass.applied++
            ctx.state.updateNode(nodeId) { it.copy(facts = it.facts + (fact to hash)) }
            ctx.log.change(DOMAIN, "$verb $niceId (${describe(resource)})", nodeId)
        }.onFailure { e ->
            ctx.state.updateNode(nodeId) { it.copy(facts = it.facts - fact) }
            ctx.log.error(DOMAIN, "$niceId: ${e.message}", nodeId)
        }
    }

    private suspend fun applyPrivate(pass: NodePass, resource: PrivateResource, privateActual: Set<String>?) {
        val nodeId = pass.nodeId
        val niceId = resource.niceId
        val blueprint = buildBlueprint(emptyList(), listOf(resource), ctx.config.siteNiceId(nodeId))
        val hash = Crypto.fnv64(blueprint.toString())
        val fact = PRIVATE_BLUEPRINT_FACT + niceId
        if (pass.facts[fact] == hash && (privateActual == null || niceId in privateActual)) {
            ctx.diff.add(DOMAIN, nodeId, key = "private:$niceId", action = "none")
            return
        }
        ctx.diff.add(
            domain = DOMAIN,
            target = nodeId,
            key = "private:$niceId",
            action = "ensure",
            desired = "${resource.alias} -> ${resource.destination}",
        )
        if (pass.paused) return
        runCatchingCancellable {
            pass.api.applyBlueprint(orgId, blueprint)
            ctx.state.updateNode(nodeId) { it.copy(facts = it.facts + (fact to hash)) }
            ctx.log.change(DOMAIN, "ensured private resource $niceId (${resource.alias})", nodeId)
        }.onFailure { e ->
            ctx.log.error(DOMAIN, "private $niceId: ${e.message}", nodeId)
        }
    }

    private suspend fun deleteStale(pass: NodePass, resource: PangolinResource) {
        val nodeId = pass.nodeId
        ctx.diff.add(
            domain = DOMAIN,
            target = nodeId,
            key = resource.niceId,
            action = "delete",
            desired = null,
            actual = describe(resource),
        )
        if (pass.paused) return
        pass.api.deleteResource(resource.resourceId)
        ctx.state.updateNode(nodeId) { it.copy(facts = it.facts - (BLUEPRINT_FACT + resource.niceId)) }
        ctx.log.change(DOMAIN, "deleted resource ${resource.niceId}", nodeId)
    }

    private suspend fun deleteStalePrivate(pass: NodePass, kept: Kept, listed: Result<List<PangolinSiteResource>>) {
        val nodeId = pass.nodeId
        // A private blueprint of this pass can add entries after the list. The pass keeps them.
        val actual = listed.getOrElse {
            ctx.log.warn(DOMAIN, "could not list private resources: ${it.message}", nodeId)
            return
        }
        actual.filter { kept.isStale(it.niceId) }.forEach { deletePrivate(pass, it) }
    }

    private suspend fun deletePrivate(pass: NodePass, resource: PangolinSiteResource) {
        val nodeId = pass.nodeId
        val niceId = resource.niceId
        ctx.diff.add(DOMAIN, nodeId, key = "private:$niceId", action = "delete")
        if (pass.paused) return
        pass.api.deleteSiteResource(resource.siteResourceId)
        ctx.state.updateNode(nodeId) { it.copy(facts = it.facts - (PRIVATE_BLUEPRINT_FACT + niceId)) }
        ctx.log.change(DOMAIN, "deleted private resource $niceId", nodeId)
    }

    // ------------------------------------------------------------------ cluster

    /**
     * A cluster's resources, applied once per pass through one node ([via]): one resource per
     * purpose, with a target on every serving node's site (each node only routes its own), and one
     * private resource listing every serving node's site. [sites] maps the nodes that take part to
     * their site. A node without a site (or not on the cluster stack) is not in the result until it
     * has one.
     */
    suspend fun reconcileCluster(
        via: ReadyNode,
        plan: Map<String, NodeDesired>,
        sites: Map<String, String>,
        pausedServiceKeys: Set<String>,
        exits: Map<PrivateResource, List<String>> = emptyMap(),
    ): List<PangolinResource> {
        val api = via.api
        val paused = ctx.nodePaused(via.node.id)
        applyBaseBlueprint(api, CLUSTER_SCOPE, paused)

        val serving = plan.filterKeys { it in sites }
        val public = clusterResources(serving.mapValues { (_, node) -> node.publicResources }, sites) { it.niceId }
        val private = clusterResources(serving.mapValues { (_, node) -> node.privateResources }, sites) { it.niceId }
        val known = ctx.state.snapshot().clusterPangolin?.blueprints.orEmpty()
        val pass = ClusterPass(api, paused, known)

        var actual = api.listResources(orgId)
        val byNiceId = actual.associateBy { it.niceId }
        for ((resource, onSites) in public) {
            if (resource.paused) continue
            applyClusterPublic(pass, resource, onSites, byNiceId[resource.niceId])
        }
        val siteResources = runCatchingCancellable { api.listSiteResources(orgId) }
        val privateActual = siteResources.getOrNull()?.map { it.niceId }?.toSet()
        for ((resource, onSites) in private + exits) {
            if (resource.paused) continue
            applyClusterPrivate(pass, resource, onSites, privateActual)
        }
        if (pass.applied > 0) actual = api.listResources(orgId)

        // Every node's plan counts, even one without a site yet: never delete what a node still wants.
        val wantedPublic = plan.values.flatMap { node -> node.publicResources.map { it.niceId } }.toSet()
        val kept = Kept(wantedPublic, pausedServiceKeys)
        actual.filter { kept.isStale(it.niceId) }.forEach { deleteClusterStale(pass, it) }
        if (!paused) actual = actual.filter { !kept.isStale(it.niceId) }

        // Exits and DNS resolvers the config wants are kept even while their sites are missing.
        val wantedPrivate = buildSet {
            plan.values.forEach { node -> addAll(node.privateResources.map { it.niceId }) }
            addAll(ExitNodes.wanted(ctx.config))
            addAll(TunnelDns.wanted(ctx.config))
        }
        val keptPrivate = Kept(wantedPrivate, pausedServiceKeys)
        siteResources.getOrNull()
            ?.filter { keptPrivate.isStale(it.niceId) }
            ?.forEach { deleteClusterStalePrivate(pass, it) }

        if (pass.hashes != known) {
            ctx.state.update { state ->
                state.copy(clusterPangolin = state.clusterPangolin?.copy(blueprints = pass.hashes))
            }
        }
        return actual
    }

    private suspend fun applyClusterPublic(
        pass: ClusterPass,
        resource: PublicResource,
        onSites: List<String>,
        existing: PangolinResource?,
    ) {
        val niceId = resource.niceId
        val blueprint = buildBlueprint(mapOf(resource to onSites), emptyMap())
        val hash = Crypto.fnv64(blueprint.toString())
        val drifted = !matches(resource, existing, onSites)
        val action = when {
            existing == null -> "create"
            drifted || pass.known[niceId] != hash -> "update"
            else -> "none"
        }
        ctx.diff.add(
            domain = DOMAIN,
            target = CLUSTER_SCOPE,
            key = niceId,
            action = action,
            desired = "${describe(resource)} on ${onSites.joinToString()}",
            actual = existing?.let { describe(it) },
        )
        if (action == "none" || pass.paused) return
        val verb = appliedVerb(existing, drifted)
        runCatchingCancellable {
            pass.api.applyBlueprint(orgId, blueprint)
            pass.applied++
            pass.hashes[niceId] = hash
            ctx.log.change(DOMAIN, "$verb $niceId (${describe(resource)}) on ${onSites.joinToString()}")
        }.onFailure { e ->
            pass.hashes.remove(niceId)
            ctx.log.error(DOMAIN, "$niceId: ${e.message}")
        }
    }

    private suspend fun applyClusterPrivate(
        pass: ClusterPass,
        resource: PrivateResource,
        onSites: List<String>,
        privateActual: Set<String>?,
    ) {
        val niceId = resource.niceId
        val blueprint = buildBlueprint(emptyMap(), mapOf(resource to onSites))
        val hash = Crypto.fnv64(blueprint.toString())
        val key = "private:$niceId"
        if (pass.known[key] == hash && (privateActual == null || niceId in privateActual)) return
        val label = resource.label()
        val siteList = onSites.joinToString()
        ctx.diff.add(
            domain = DOMAIN,
            target = CLUSTER_SCOPE,
            key = key,
            action = "ensure",
            desired = "$label on $siteList",
        )
        if (pass.paused) return
        runCatchingCancellable {
            pass.api.applyBlueprint(orgId, blueprint)
            pass.hashes[key] = hash
            ctx.log.change(DOMAIN, "ensured private resource $niceId ($label) on $siteList")
        }.onFailure { e ->
            ctx.log.error(DOMAIN, "private $niceId: ${e.message}")
        }
    }

    private suspend fun deleteClusterStale(pass: ClusterPass, resource: PangolinResource) {
        ctx.diff.add(
            domain = DOMAIN,
            target = CLUSTER_SCOPE,
            key = resource.niceId,
            action = "delete",
            desired = null,
            actual = describe(resource),
        )
        if (pass.paused) return
        pass.api.deleteResource(resource.resourceId)
        pass.hashes.remove(resource.niceId)
        ctx.log.change(DOMAIN, "deleted resource ${resource.niceId}")
    }

    private suspend fun deleteClusterStalePrivate(pass: ClusterPass, resource: PangolinSiteResource) {
        val key = "private:${resource.niceId}"
        ctx.diff.add(DOMAIN, CLUSTER_SCOPE, key = key, action = "delete")
        if (pass.paused) return
        pass.api.deleteSiteResource(resource.siteResourceId)
        pass.hashes.remove(key)
        ctx.log.change(DOMAIN, "deleted private resource ${resource.niceId}")
    }

    // ------------------------------------------------------------------ base blueprint

    /** Applies the base blueprint of the config, if its text changed. [scope] is a node id or [CLUSTER_SCOPE]. */
    private suspend fun applyBaseBlueprint(api: PangolinClient, scope: String, paused: Boolean) {
        val path = ctx.config.pangolin.baseBlueprint ?: return
        val text = Files.readTextOrNull(path) ?: run {
            ctx.log.warn(DOMAIN, "base blueprint $path not found", scope)
            return
        }
        val hash = Crypto.fnv64(text)
        val cluster = scope == CLUSTER_SCOPE
        val applied = when {
            cluster -> ctx.state.snapshot().clusterPangolin?.blueprints?.get(BASE_KEY)
            else -> ctx.state.node(scope).facts[BASE_BLUEPRINT_FACT]
        }
        if (applied == hash || paused) return
        val blueprint = blueprintOf(text) ?: return
        api.applyBlueprint(orgId, blueprint)
        if (cluster) {
            ctx.state.update { state ->
                val pangolin = state.clusterPangolin?.let { it.copy(blueprints = it.blueprints + (BASE_KEY to hash)) }
                state.copy(clusterPangolin = pangolin)
            }
        } else {
            ctx.state.updateNode(scope) { it.copy(facts = it.facts + (BASE_BLUEPRINT_FACT to hash)) }
        }
        ctx.log.change(DOMAIN, "applied base blueprint $path", scope.takeIf { !cluster })
    }

    // ------------------------------------------------------------------ routes and certificate

    suspend fun writeRoutes(ready: ReadyNode, desired: NodeDesired, actual: List<PangolinResource>) {
        val nodeId = ready.node.id
        val ids = actual.associate { it.niceId to it.resourceId }
        val content = NodeTemplates.dynamicRoutes(desired, ids, ctx.config.cluster != null)
        val maintenance = MaintenancePage.routes(desired, ids)
        val probes = MaintenancePage.probes(desired, ids)
        val hash = Crypto.fnv64(content + maintenance + probes + certificate?.hash)
        val state = ctx.state.node(nodeId)
        val resend = state.facts[CERT_RESEND] != null && certificate != null
        val lastCheckAt = state.lastCheckAt ?: 0L
        val routesCheckedAt = state.facts[ROUTES_CHECKED_FACT]?.toLongOrNull() ?: 0L
        val due = state.facts[ROUTES_HASH_FACT] != hash || state.drift.isNotEmpty() || lastCheckAt > routesCheckedAt
        if (!due) return

        val script = routesScript(content, maintenance, probes, resend)
        val output = ctx.ssh.session(ready.node).runScriptOrThrow("routes", script, ROUTES_TIMEOUT_SECONDS)
        if (output.contains("CERTIFICATE installed")) {
            val names = certificate?.names?.size
            ctx.log.change(DOMAIN, "installed the certificate of the nodes ($names names)", nodeId)
        }
        if (output.contains("CERTIFICATE removed")) {
            ctx.log.change(DOMAIN, "removed the certificate from the node: HomeGAP has none to give", nodeId)
        }
        if (output.contains("ROUTES changed")) {
            val passthrough = "${desired.sniRoutes.size} SNI passthrough"
            val health = "health=${desired.healthResourceNiceId != null}"
            ctx.log.change(DOMAIN, "updated Traefik routes ($passthrough, $health)", nodeId)
        }
        val checked = mapOf(ROUTES_HASH_FACT to hash, ROUTES_CHECKED_FACT to lastCheckAt.toString())
        ctx.state.updateNode(nodeId) { it.copy(facts = it.facts - CERT_RESEND + checked) }
    }

    private fun routesScript(content: String, maintenance: String, probes: String, resend: Boolean): String {
        val file = shQuote("$installDir/${NodeTemplates.DYNAMIC_ROUTES}")
        val encoded = Base64.encode(content.encodeToByteArray())
        val writeMaintenance = SshSession.writeFileSnippet("$installDir/${MaintenancePage.ROUTES}", maintenance)
        val writeProbes = SshSession.writeFileSnippet("$installDir/${MaintenancePage.PROBES}", probes)
        val maintenanceDir = shQuote("$installDir/${MaintenancePage.DIR}")
        val writeCertificate = certificateScript(resend)
        return """
            |install -d -m 0755 "$(dirname $file)"
            |printf '%s' '$encoded' | base64 -d > $file.homegap-new
            |if ! cmp -s $file.homegap-new $file 2>/dev/null; then chmod 0644 $file.homegap-new; mv -f $file.homegap-new $file; echo 'ROUTES changed'; else rm -f $file.homegap-new; fi
            |hg_own $file
            |$writeMaintenance
            |$writeProbes
            |hg_own $maintenanceDir
            |$writeCertificate
        """.trimMargin()
    }

    /**
     * Writes the certificate and its key, then the file that points Traefik at them. Without a
     * certificate, it removes all three, so a key from an earlier certificate does not stay on the
     * node.
     */
    private fun certificateScript(resend: Boolean): String {
        val cert = shQuote("$installDir/${NodeTemplates.CERT}")
        val key = shQuote("$installDir/${NodeTemplates.KEY}")
        val tls = shQuote("$installDir/${NodeTemplates.DYNAMIC_TLS}")
        val removeFiles = "rm -f $tls $cert $key"
        val material = certificate
            ?: return "if [ -e $cert ] || [ -e $key ] || [ -e $tls ]; then $removeFiles; echo 'CERTIFICATE removed'; fi"
        // Send again: without the old files, the node writes each one anew and Traefik reloads them.
        val removeOld = if (resend) removeFiles else ""
        val writeKey = SshSession.writeFileSnippet("$installDir/${NodeTemplates.KEY}", material.keyPem, "0600")
        val writeCert = SshSession.writeFileSnippet("$installDir/${NodeTemplates.CERT}", material.certPem, "0600")
        val tlsConfig = NodeTemplates.tlsDynamic(material.hash)
        val writeTls = SshSession.writeFileSnippet("$installDir/${NodeTemplates.DYNAMIC_TLS}", tlsConfig)
        return """
            |$removeOld
            |install -d -m 0700 "$(dirname $cert)"
            |umask 077
            |$writeKey
            |$writeCert
            |umask 022
            |chmod 0700 "$(dirname $cert)"
            |$writeTls | sed 's/^CHANGED.*/CERTIFICATE installed/'
            |hg_own "$(dirname $cert)"
            |hg_own $tls
        """.trimMargin()
    }

    // ------------------------------------------------------------------ comparison and text

    private fun matches(desired: PublicResource, actual: PangolinResource?, sites: List<String>? = null): Boolean {
        if (actual == null) return false
        if (actual.mode != desired.mode.wire) return false
        if (desired.mode == ResourceMode.HTTP && actual.fullDomain != desired.fullDomain) return false
        if (desired.mode != ResourceMode.HTTP && actual.proxyPort != desired.proxyPort) return false
        if (actual.enabled == false) return false
        val targets = actual.targets
        if (sites != null) {
            // One target per serving node's site, each pointing at the service.
            val onEachSite = targets.mapNotNull { it.siteNiceId }.sorted() == sites.sorted()
            return onEachSite && targets.all { pointsAt(it, desired) }
        }
        return targets.size == 1 && pointsAt(targets[0], desired)
    }

    private fun pointsAt(target: PangolinTarget, desired: PublicResource): Boolean =
        target.ip == desired.target.host && target.port == desired.target.port && target.enabled != false

    private fun appliedVerb(existing: PangolinResource?, drifted: Boolean): String = when {
        existing == null -> "created"
        drifted -> "repaired"
        else -> "updated"
    }

    private fun PrivateResource.label(): String = when {
        mode == EXIT_NODE_MODE || alias.isEmpty() -> "$name ($destination)".removeSuffix(" ()")
        else -> "$alias -> $destination"
    }

    private fun describe(resource: PublicResource): String {
        val address = resource.fullDomain ?: ":${resource.proxyPort}"
        return "${resource.mode.wire} $address -> ${resource.target.host}:${resource.target.port}"
    }

    private fun describe(resource: PangolinResource): String {
        val address = resource.fullDomain ?: ":${resource.proxyPort}"
        return "${resource.mode} $address -> ${resource.targets.joinToString { "${it.ip}:${it.port}" }}"
    }

    // ------------------------------------------------------------------ blueprints

    fun buildBlueprint(
        publicRes: List<PublicResource>,
        privateRes: List<PrivateResource>,
        site: String = ctx.config.pangolin.siteName,
    ): JsonObject =
        buildBlueprint(publicRes.associateWith { listOf(site) }, privateRes.associateWith { listOf(it.siteNiceId) })

    /**
     * The blueprint for the dashboard's diff and the dry-run log. It is the same as
     * [buildBlueprint], but it does not resolve a PIN, so no secret leaves the reconciler.
     */
    fun displayBlueprint(publicRes: List<PublicResource>, privateRes: List<PrivateResource>, site: String): JsonObject =
        buildBlueprint(
            publicRes = publicRes.associateWith { listOf(site) },
            privateRes = privateRes.associateWith { listOf(it.siteNiceId) },
            display = true,
        )

    /** [publicRes] and [privateRes] map each resource to the sites that serve it. */
    fun buildBlueprint(
        publicRes: Map<PublicResource, List<String>>,
        privateRes: Map<PrivateResource, List<String>>,
        display: Boolean = false,
    ): JsonObject = buildJsonObject {
        putJsonObject("public-resources") {
            for ((resource, sites) in publicRes) {
                putJsonObject(resource.niceId) { publicResource(resource, sites, display) }
            }
        }
        if (privateRes.isNotEmpty()) {
            putJsonObject("private-resources") {
                for ((resource, sites) in privateRes) {
                    putJsonObject(resource.niceId) { privateResource(resource, sites) }
                }
            }
        }
    }

    private fun JsonObjectBuilder.privateResource(resource: PrivateResource, sites: List<String>) {
        put("name", resource.name)
        put("mode", resource.mode)
        putJsonArray("sites") { sites.forEach { add(it) } }
        if (resource.mode != EXIT_NODE_MODE) {
            // An exit node has no destination or ports: all traffic, all ports.
            put("destination", resource.destination)
            // Optional: a cidr resource (a DNS resolver) is reached by its address.
            if (resource.alias.isNotEmpty()) put("alias", resource.alias)
            if (resource.tcpPorts.isNotEmpty()) put("tcp-ports", resource.tcpPorts)
            if (resource.udpPorts.isNotEmpty()) put("udp-ports", resource.udpPorts)
        }
        // Pangolin refuses Admin here (it can reach everything anyway).
        putJsonArray("roles") { resource.roles.filter { it != ADMIN_ROLE }.forEach { add(it) } }
    }

    private fun JsonObjectBuilder.publicResource(resource: PublicResource, sites: List<String>, display: Boolean) {
        put("name", resource.niceId)
        put("mode", resource.mode.wire)
        put("enabled", true)
        if (resource.mode == ResourceMode.HTTP) {
            put("full-domain", resource.fullDomain!!)
            put("ssl", resource.ssl)
            resource.hostHeader?.let { put("host-header", it) }
            resource.tlsServerName?.let { put("tls-server-name", it) }
            putJsonObject("auth") { auth(resource, display) }
        } else {
            put("proxy-port", resource.proxyPort!!)
            if (resource.proxyProtocol && resource.mode == ResourceMode.TCP) {
                put("proxy-protocol", true)
                put("proxy-protocol-version", NodeTemplates.PROXY_PROTOCOL_VERSION)
            }
        }
        putJsonArray("targets") {
            for (site in sites) {
                addJsonObject {
                    put("site", site)
                    put("hostname", resource.target.host)
                    put("port", resource.target.port)
                    resource.target.method?.let { put("method", it) }
                    // Pangolin gives each router the same priority. A lower one for a wildcard
                    // lets the service of an exact name under it answer that name.
                    if (resource.fullDomain?.startsWith("*.") == true) {
                        put("priority", WILDCARD_PRIORITY)
                    }
                    resource.healthcheck?.let { check ->
                        putJsonObject("healthcheck") { healthcheck(resource, check) }
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.auth(resource: PublicResource, display: Boolean) {
        when (resource.auth) {
            AuthMode.NONE -> put("sso-enabled", false)
            AuthMode.SSO -> {
                put("sso-enabled", true)
                putJsonArray("sso-roles") { resource.roles.filter { it != ADMIN_ROLE }.forEach { add(it) } }
            }
            AuthMode.PIN -> {
                put("sso-enabled", false)
                pincode(resource, display)
            }
        }
    }

    /** For display, the blueprint has the name of the variable in place of the PIN. */
    private fun JsonObjectBuilder.pincode(resource: PublicResource, display: Boolean) {
        if (display) {
            put("pincode", "(from ${resource.pinEnv ?: "no variable"})")
            return
        }
        val pin = resource.pinEnv
            ?.let { SecretRef("pin:${resource.serviceKey}", it, null).resolve() }
            ?.toIntOrNull()
        if (pin != null && pin in PIN_RANGE) {
            put("pincode", pin)
        } else {
            ctx.log.error(DOMAIN, "PIN for ${resource.serviceKey} missing or not 6 digits (env ${resource.pinEnv})")
        }
    }

    private fun JsonObjectBuilder.healthcheck(resource: PublicResource, check: HealthCheck) {
        put("hostname", resource.target.host)
        put("port", resource.target.port)
        put("enabled", true)
        if (check is HealthCheck.Http && resource.mode == ResourceMode.HTTP) {
            put("mode", "http")
            put("path", check.path)
            put("scheme", resource.target.method ?: "http")
        } else {
            put("mode", "tcp")
        }
        put("interval", HEALTHCHECK_INTERVAL)
        put("timeout", HEALTHCHECK_TIMEOUT)
    }

    companion object {
        /** Below the default priority of a Pangolin target (100). */
        private const val WILDCARD_PRIORITY = 90

        /** Diff and base-blueprint scope for the cluster's shared resources. */
        const val CLUSTER_SCOPE = "cluster"
        private const val BASE_KEY = "base"

        /** A node fact: the dashboard asked to send the certificate to the node again. */
        const val CERT_RESEND = "certResend"

        private const val DOMAIN = "resources"
        private const val ROUTES_TIMEOUT_SECONDS = 60
        private const val HEALTHCHECK_INTERVAL = 30
        private const val HEALTHCHECK_TIMEOUT = 5
        private const val EXIT_NODE_MODE = "exit-node"
        private const val ADMIN_ROLE = "Admin"
        private val PIN_RANGE = 100000..999999

        // Node facts. A blueprint fact has the niceId of the resource after the prefix.
        private const val BLUEPRINT_FACT = "bp:"
        private const val PRIVATE_BLUEPRINT_FACT = "bp:private:"
        private const val BASE_BLUEPRINT_FACT = "baseBlueprintHash"
        private const val ROUTES_HASH_FACT = "routesHash"
        private const val ROUTES_CHECKED_FACT = "routesCheckedAt"

        /**
         * Groups each node's resources by niceId: the resource (identical on every node that
         * serves it) and the sites of the nodes that do, in node order.
         */
        fun <R> clusterResources(
            byNode: Map<String, List<R>>,
            sites: Map<String, String>,
            niceId: (R) -> String,
        ): Map<R, List<String>> {
            val grouped = linkedMapOf<String, Pair<R, MutableList<String>>>()
            for ((node, resources) in byNode) {
                val site = sites[node] ?: continue
                for (resource in resources) {
                    grouped.getOrPut(niceId(resource)) { resource to mutableListOf() }.second += site
                }
            }
            return grouped.values.associate { it.first to it.second.toList() }
        }

        private val INT = Regex("^-?(0|[1-9]\\d*)$")
        private val FLOAT = Regex("^-?\\d+\\.\\d+$")

        fun yamlToJson(text: String): JsonElement = toJson(Yaml.default.parseToYamlNode(text))

        /**
         * The blueprint in [text], which is JSON or YAML. Null if the YAML has no content, as the
         * example file with only comments.
         */
        fun blueprintOf(text: String): JsonObject? = when {
            text.trimStart().startsWith("{") -> Json.parseToJsonElement(text).jsonObject
            else -> try {
                yamlToJson(text).jsonObject
            } catch (_: EmptyYamlDocumentException) {
                null
            }
        }

        private fun toJson(node: YamlNode): JsonElement = when (node) {
            is YamlMap -> JsonObject(node.entries.entries.associate { (key, value) -> key.content to toJson(value) })
            is YamlList -> JsonArray(node.items.map { toJson(it) })
            is YamlNull -> JsonNull
            is YamlTaggedNode -> toJson(node.innerNode)
            is YamlScalar -> scalarToJson(node.content)
        }

        private fun scalarToJson(content: String): JsonPrimitive = when {
            INT.matches(content) -> JsonPrimitive(content.toLong())
            FLOAT.matches(content) -> JsonPrimitive(content.toDouble())
            content == "true" -> JsonPrimitive(true)
            content == "false" -> JsonPrimitive(false)
            else -> JsonPrimitive(content)
        }
    }
}
