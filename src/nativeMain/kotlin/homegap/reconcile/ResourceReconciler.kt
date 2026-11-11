package homegap.reconcile

import homegap.provision.ExitNodes
import homegap.provision.TunnelDns
import com.charleskorn.kaml.*
import homegap.clients.PangolinClient
import homegap.clients.PangolinResource
import homegap.clients.SshSession
import homegap.clients.SshSession.Companion.shQuote
import homegap.config.SecretRef
import homegap.discovery.AuthMode
import homegap.discovery.HealthCheck
import homegap.model.*
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.provision.MaintenancePage
import homegap.provision.NodeTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

/**
 * Resource domain: Pangolin public/private resources on each node via declarative blueprints
 * (keyed by deterministic niceIds), deletion of managed resources no longer desired, and the
 * node's hot-reloaded Traefik routing file (SNI passthrough, health, management API).
 */
class ResourceReconciler(private val ctx: ReconcileContext) {
    private val domain = "resources"

    suspend fun reconcile(ready: ReadyNode, desired: NodeDesired, pausedServiceKeys: Set<String>): List<PangolinResource> {
        val node = ready.node
        val org = ctx.config.pangolin.orgId
        val api = ready.api
        val paused = ctx.nodePaused(node.id)

        applyBaseBlueprint(api, node.id, org, paused)

        val active = desired.publicResources.filter { !it.paused }
        val activePrivate = desired.privateResources.filter { !it.paused }

        var actual = api.listResources(org)
        val byNice = actual.associateBy { it.niceId }
        val st = ctx.state.node(node.id)
        var applied = 0

        // One blueprint per resource: a resource Pangolin rejects (e.g. a wildcard without an EE
        // license) must not block the others. Each is re-applied when missing, drifted, or changed.
        for (d in active) {
            val a = byNice[d.niceId]
            val bp = buildBlueprint(listOf(d), emptyList(), ctx.config.siteNiceId(node.id))
            val h = Crypto.fnv64(bp.toString())
            val drifted = !matches(d, a)
            val changed = st.facts["bp:${d.niceId}"] != h
            val action = when { a == null -> "create"; drifted || changed -> "update"; else -> "none" }
            ctx.diff.add(domain, node.id, d.niceId, action, describe(d), a?.let { describe(it) })
            if (action == "none") continue
            if (paused) {
                ctx.log.info(domain, "paused: ${d.niceId} differs from desired, not applying", node.id)
                continue
            }
            try {
                api.applyBlueprint(org, bp)
                applied++
                ctx.state.updateNode(node.id) { it.copy(facts = it.facts + ("bp:${d.niceId}" to h)) }
                ctx.log.change(domain, "${if (a == null) "created" else if (drifted) "repaired" else "updated"} ${d.niceId} (${describe(d)})", node.id)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                ctx.state.updateNode(node.id) { it.copy(facts = it.facts - "bp:${d.niceId}") }
                ctx.log.error(domain, "${d.niceId}: ${e.message}", node.id)
            }
        }
        // Listed once per pass and shared with reconcilePrivate.
        val siteResources = runCatching {
            api.listSiteResources(org)
        }.onFailure {
            if (it is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }
        val privateActual = siteResources.getOrNull()?.map { it.niceId }?.toSet()
        for (p in activePrivate) {
            val bp = buildBlueprint(emptyList(), listOf(p), ctx.config.siteNiceId(node.id))
            val h = Crypto.fnv64(bp.toString())
            if (st.facts["bp:private:${p.niceId}"] == h && (privateActual == null || p.niceId in privateActual)) {
                ctx.diff.add(domain, node.id, "private:${p.niceId}", "none")
                continue
            }
            ctx.diff.add(domain, node.id, "private:${p.niceId}", "ensure", "${p.alias} -> ${p.destination}")
            if (paused) continue
            try {
                api.applyBlueprint(org, bp)
                ctx.state.updateNode(node.id) { it.copy(facts = it.facts + ("bp:private:${p.niceId}" to h)) }
                ctx.log.change(domain, "ensured private resource ${p.niceId} (${p.alias})", node.id)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                ctx.log.error(domain, "private ${p.niceId}: ${e.message}", node.id)
            }
        }
        if (applied > 0) actual = api.listResources(org)

        // Delete managed resources that are no longer desired (never touch unmanaged ones).
        val keep = desired.publicResources.map { it.niceId }.toSet()
        for (a in actual) {
            if (!a.niceId.startsWith(Planner.NICE_PREFIX) || a.niceId in keep) continue
            if (pausedServiceKeys.any { a.niceId.startsWith(Planner.NICE_PREFIX + it) }) continue
            ctx.diff.add(domain, node.id, a.niceId, "delete", null, describe(a))
            if (paused) continue
            api.deleteResource(a.resourceId)
            ctx.state.updateNode(node.id) { it.copy(facts = it.facts - "bp:${a.niceId}") }
            ctx.log.change(domain, "deleted resource ${a.niceId}", node.id)
        }
        if (!paused) actual = actual.filter { it.niceId in keep || !it.niceId.startsWith(Planner.NICE_PREFIX) || pausedServiceKeys.any { k -> it.niceId.startsWith(Planner.NICE_PREFIX + k) } }

        reconcilePrivate(api, node.id, desired, pausedServiceKeys, paused, siteResources)

        if (!paused) writeRoutes(ready, desired, actual)
        return actual
    }

    /**
     * A cluster's resources, applied once per pass through one node ([via]): one resource per
     * purpose, with a target on every serving node's site (each node only routes its own), and one
     * private resource listing every serving node's site. [sites] maps the nodes that take part to
     * their site; a node without one yet (or not on the cluster stack) is left out until it has one.
     */
    suspend fun reconcileCluster(
        via: ReadyNode,
        plan: Map<String, NodeDesired>,
        sites: Map<String, String>,
        pausedServiceKeys: Set<String>,
        exits: Map<PrivateResource, List<String>> = emptyMap(),
    ): List<PangolinResource> {
        val org = ctx.config.pangolin.orgId
        val api = via.api
        val paused = ctx.nodePaused(via.node.id)
        applyBaseBlueprint(api, CLUSTER_SCOPE, org, paused)

        val serving = plan.filterKeys { it in sites }
        val public = clusterResources(serving.mapValues { (_, n) -> n.publicResources }, sites) { it.niceId }
        val private = clusterResources(serving.mapValues { (_, n) -> n.privateResources }, sites) { it.niceId }
        val known = ctx.state.snapshot().clusterPangolin?.blueprints.orEmpty()
        val hashes = known.toMutableMap()

        var actual = api.listResources(org)
        val byNice = actual.associateBy { it.niceId }
        var applied = 0
        for ((d, onSites) in public) {
            if (d.paused) continue
            val a = byNice[d.niceId]
            val bp = buildBlueprint(mapOf(d to onSites), emptyMap())
            val h = Crypto.fnv64(bp.toString())
            val drifted = !matches(d, a, onSites)
            val action = when { a == null -> "create"; drifted || known[d.niceId] != h -> "update"; else -> "none" }
            ctx.diff.add(domain, CLUSTER_SCOPE, d.niceId, action, "${describe(d)} on ${onSites.joinToString()}", a?.let { describe(it) })
            if (action == "none" || paused) continue
            try {
                api.applyBlueprint(org, bp)
                applied++
                hashes[d.niceId] = h
                ctx.log.change(domain, "${if (a == null) "created" else if (drifted) "repaired" else "updated"} ${d.niceId} (${describe(d)}) on ${onSites.joinToString()}")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                hashes.remove(d.niceId)
                ctx.log.error(domain, "${d.niceId}: ${e.message}")
            }
        }
        val siteResources = runCatching { api.listSiteResources(org) }.onFailure {
            if (it is CancellationException) currentCoroutineContext().ensureActive()
        }
        val privateActual = siteResources.getOrNull()?.map { it.niceId }?.toSet()
        for ((p, onSites) in private + exits) {
            if (p.paused) continue
            val bp = buildBlueprint(emptyMap(), mapOf(p to onSites))
            val h = Crypto.fnv64(bp.toString())
            val key = "private:${p.niceId}"
            if (known[key] == h && (privateActual == null || p.niceId in privateActual)) continue
            ctx.diff.add(domain, CLUSTER_SCOPE, key, "ensure", "${p.label()} on ${onSites.joinToString()}")
            if (paused) continue
            try {
                api.applyBlueprint(org, bp)
                hashes[key] = h
                ctx.log.change(domain, "ensured private resource ${p.niceId} (${p.label()}) on ${onSites.joinToString()}")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                ctx.log.error(domain, "private ${p.niceId}: ${e.message}")
            }
        }
        if (applied > 0) actual = api.listResources(org)

        // Every node's plan counts, even one without a site yet: never delete what a node still wants.
        val keep = plan.values.flatMap { n -> n.publicResources.map { it.niceId } }.toSet()
        for (a in actual) {
            if (!a.niceId.startsWith(Planner.NICE_PREFIX) || a.niceId in keep) continue
            if (pausedServiceKeys.any { a.niceId.startsWith(Planner.NICE_PREFIX + it) }) continue
            ctx.diff.add(domain, CLUSTER_SCOPE, a.niceId, "delete", null, describe(a))
            if (paused) continue
            api.deleteResource(a.resourceId)
            hashes.remove(a.niceId)
            ctx.log.change(domain, "deleted resource ${a.niceId}")
        }
        if (!paused) actual = actual.filter { it.niceId in keep || !it.niceId.startsWith(Planner.NICE_PREFIX) || pausedServiceKeys.any { k -> it.niceId.startsWith(Planner.NICE_PREFIX + k) } }

        // Exits and DNS resolvers the config wants are kept even while their sites are missing.
        val keepPrivate = plan.values.flatMap { n -> n.privateResources.map { it.niceId } }.toSet() +
            ExitNodes.wanted(ctx.config) + TunnelDns.wanted(ctx.config)
        siteResources.getOrNull()?.forEach { a ->
            if (!a.niceId.startsWith(Planner.NICE_PREFIX) || a.niceId in keepPrivate) return@forEach
            if (pausedServiceKeys.any { a.niceId.startsWith(Planner.NICE_PREFIX + it) }) return@forEach
            ctx.diff.add(domain, CLUSTER_SCOPE, "private:${a.niceId}", "delete")
            if (paused) return@forEach
            api.deleteSiteResource(a.siteResourceId)
            hashes.remove("private:${a.niceId}")
            ctx.log.change(domain, "deleted private resource ${a.niceId}")
        }
        if (hashes != known) ctx.state.update { st -> st.copy(clusterPangolin = st.clusterPangolin?.copy(blueprints = hashes)) }
        return actual
    }

    private suspend fun reconcilePrivate(
        api: PangolinClient, nodeId: String, desired: NodeDesired, pausedKeys: Set<String>, paused: Boolean,
        listed: Result<List<homegap.clients.PangolinSiteResource>>,
    ) {
        val keep = desired.privateResources.map { it.niceId }.toSet()
        // Applying a private blueprint above may have added entries; they are kept anyway.
        val actual = listed.getOrElse {
            ctx.log.warn(domain, "could not list private resources: ${it.message}", nodeId)
            return
        }
        for (a in actual) {
            if (!a.niceId.startsWith(Planner.NICE_PREFIX) || a.niceId in keep) continue
            if (pausedKeys.any { a.niceId.startsWith(Planner.NICE_PREFIX + it) }) continue
            ctx.diff.add(domain, nodeId, "private:${a.niceId}", "delete")
            if (paused) continue
            api.deleteSiteResource(a.siteResourceId)
            ctx.state.updateNode(nodeId) { it.copy(facts = it.facts - "bp:private:${a.niceId}") }
            ctx.log.change(domain, "deleted private resource ${a.niceId}", nodeId)
        }
    }

    private suspend fun applyBaseBlueprint(api: PangolinClient, nodeId: String, org: String, paused: Boolean) {
        val path = ctx.config.pangolin.baseBlueprint ?: return
        val text = Files.readTextOrNull(path) ?: run {
            ctx.log.warn(domain, "base blueprint $path not found", nodeId)
            return
        }
        val hash = Crypto.fnv64(text)
        val cluster = nodeId == CLUSTER_SCOPE
        val applied = if (cluster) ctx.state.snapshot().clusterPangolin?.blueprints?.get(BASE_KEY) else ctx.state.node(nodeId).facts["baseBlueprintHash"]
        if (applied == hash || paused) return
        val json = if (text.trimStart().startsWith("{")) Json.parseToJsonElement(text).jsonObject else yamlToJson(text).jsonObject
        api.applyBlueprint(org, json)
        if (cluster) ctx.state.update { st -> st.copy(clusterPangolin = st.clusterPangolin?.let { it.copy(blueprints = it.blueprints + (BASE_KEY to hash)) }) }
        else ctx.state.updateNode(nodeId) { it.copy(facts = it.facts + ("baseBlueprintHash" to hash)) }
        ctx.log.change(domain, "applied base blueprint $path", nodeId.takeIf { !cluster })
    }

    suspend fun writeRoutes(ready: ReadyNode, desired: NodeDesired, actual: List<PangolinResource>) {
        val ids = actual.associate { it.niceId to it.resourceId }
        val content = NodeTemplates.dynamicRoutes(desired, ids, ctx.config.cluster != null)
        val maintenance = MaintenancePage.routes(desired, ids)
        val probes = MaintenancePage.probes(desired, ids)
        val hash = Crypto.fnv64(content + maintenance + probes)
        val st = ctx.state.node(ready.node.id)
        val dueCheck = st.facts["routesHash"] != hash || st.drift.isNotEmpty() || (st.lastCheckAt ?: 0) > (st.facts["routesCheckedAt"]?.toLongOrNull() ?: 0)
        if (!dueCheck) return
        val ssh = ctx.ssh.session(ready.node)
        val path = "${ctx.config.pangolin.installDir}/${NodeTemplates.DYNAMIC_ROUTES}"
        val q = shQuote(path)
        val b64 = Base64.encode(content.encodeToByteArray())
        val out = ssh.runScriptOrThrow("routes", """
            |install -d -m 0755 "$(dirname $q)"
            |printf '%s' '$b64' | base64 -d > $q.homegap-new
            |if ! cmp -s $q.homegap-new $q 2>/dev/null; then chmod 0644 $q.homegap-new; mv -f $q.homegap-new $q; echo CHANGED; else rm -f $q.homegap-new; fi
            |hg_own $q
            |${SshSession.writeFileSnippet("${ctx.config.pangolin.installDir}/${MaintenancePage.ROUTES}", maintenance)}
            |${SshSession.writeFileSnippet("${ctx.config.pangolin.installDir}/${MaintenancePage.PROBES}", probes)}
            |hg_own ${shQuote("${ctx.config.pangolin.installDir}/${MaintenancePage.DIR}")}
        """.trimMargin(), 60)
        if (out.contains("CHANGED")) {
            ctx.log.change(domain, "updated Traefik routes (${desired.sniRoutes.size} SNI passthrough, health=${desired.healthResourceNiceId != null})", ready.node.id)
        }
        ctx.state.updateNode(ready.node.id) { it.copy(facts = it.facts + ("routesHash" to hash) + ("routesCheckedAt" to (st.lastCheckAt ?: 0).toString())) }
    }

    private fun matches(d: PublicResource, a: PangolinResource?, sites: List<String>? = null): Boolean {
        if (a == null) return false
        if (a.mode != d.mode.wire) return false
        if (d.mode == ResourceMode.HTTP && a.fullDomain != d.fullDomain) return false
        if (d.mode != ResourceMode.HTTP && a.proxyPort != d.proxyPort) return false
        if (a.enabled == false) return false
        val t = a.targets
        if (sites != null) {
            // One target per serving node's site, each pointing at the service.
            if (t.mapNotNull { it.siteNiceId }.sorted() != sites.sorted()) return false
            return t.all { it.ip == d.target.host && it.port == d.target.port && it.enabled != false }
        }
        if (t.size != 1 || t[0].ip != d.target.host || t[0].port != d.target.port || t[0].enabled == false) return false
        return true
    }

    private fun PrivateResource.label() = if (mode == "exit-node" || alias.isEmpty()) "$name ($destination)".removeSuffix(" ()") else "$alias -> $destination"

    private fun describe(d: PublicResource) = "${d.mode.wire} ${d.fullDomain ?: ":${d.proxyPort}"} -> ${d.target.host}:${d.target.port}"
    private fun describe(a: PangolinResource) = "${a.mode} ${a.fullDomain ?: ":${a.proxyPort}"} -> ${a.targets.joinToString { "${it.ip}:${it.port}" }}"

    fun buildBlueprint(publicRes: List<PublicResource>, privateRes: List<PrivateResource>, site: String = ctx.config.pangolin.siteName): JsonObject =
        buildBlueprint(publicRes.associateWith { listOf(site) }, privateRes.associateWith { listOf(it.siteNiceId) })

    /** [publicRes] and [privateRes] map each resource to the sites that serve it. */
    fun buildBlueprint(publicRes: Map<PublicResource, List<String>>, privateRes: Map<PrivateResource, List<String>>): JsonObject {
        return buildJsonObject {
            putJsonObject("public-resources") {
                for ((r, sites) in publicRes) putJsonObject(r.niceId) { publicResource(r, sites) }
            }
            if (privateRes.isNotEmpty()) {
                putJsonObject("private-resources") {
                    for ((r, sites) in privateRes) putJsonObject(r.niceId) {
                        put("name", r.name)
                        put("mode", r.mode)
                        putJsonArray("sites") { sites.forEach { add(it) } }
                        if (r.mode != "exit-node") {
                            // An exit node has no destination or ports: all traffic, all ports.
                            put("destination", r.destination)
                            // Optional: a cidr resource (a DNS resolver) is reached by its address.
                            if (r.alias.isNotEmpty()) put("alias", r.alias)
                            if (r.tcpPorts.isNotEmpty()) put("tcp-ports", r.tcpPorts)
                            if (r.udpPorts.isNotEmpty()) put("udp-ports", r.udpPorts)
                        }
                        // Pangolin refuses Admin here (it can reach everything anyway).
                        putJsonArray("roles") { r.roles.filter { it != "Admin" }.forEach { add(it) } }
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.publicResource(r: PublicResource, sites: List<String>) {
        put("name", r.niceId)
        put("mode", r.mode.wire)
        put("enabled", true)
        if (r.mode == ResourceMode.HTTP) {
            put("full-domain", r.fullDomain!!)
            put("ssl", r.ssl)
            r.hostHeader?.let { put("host-header", it) }
            r.tlsServerName?.let { put("tls-server-name", it) }
            putJsonObject("auth") {
                when (r.auth) {
                    AuthMode.NONE -> put("sso-enabled", false)
                    AuthMode.SSO -> {
                        put("sso-enabled", true)
                        putJsonArray("sso-roles") { r.roles.filter { it != "Admin" }.forEach { add(it) } }
                    }
                    AuthMode.PIN -> {
                        put("sso-enabled", false)
                        val pin = r.pinEnv?.let { SecretRef("pin:${r.serviceKey}", it, null).resolve() }?.toIntOrNull()
                        if (pin != null && pin in 100000..999999) put("pincode", pin)
                        else ctx.log.error(domain, "PIN for ${r.serviceKey} missing or not 6 digits (env ${r.pinEnv})")
                    }
                }
            }
        } else {
            put("proxy-port", r.proxyPort!!)
            if (r.proxyProtocol && r.mode == ResourceMode.TCP) {
                put("proxy-protocol", true)
                put("proxy-protocol-version", NodeTemplates.PROXY_PROTOCOL_VERSION)
            }
        }
        putJsonArray("targets") {
            for (site in sites) addJsonObject {
                put("site", site)
                put("hostname", r.target.host)
                put("port", r.target.port)
                r.target.method?.let { put("method", it) }
                r.healthcheck?.let { hc ->
                    putJsonObject("healthcheck") {
                        put("hostname", r.target.host)
                        put("port", r.target.port)
                        put("enabled", true)
                        when {
                            hc is HealthCheck.Http && r.mode == ResourceMode.HTTP -> {
                                put("mode", "http"); put("path", hc.path); put("scheme", r.target.method ?: "http")
                            }
                            else -> put("mode", "tcp")
                        }
                        put("interval", 30)
                        put("timeout", 5)
                    }
                }
            }
        }
    }

    companion object {
        /** Diff and base-blueprint scope for the cluster's shared resources. */
        const val CLUSTER_SCOPE = "cluster"
        private const val BASE_KEY = "base"

        /**
         * Groups each node's resources by niceId: the resource (identical on every node that
         * serves it) and the sites of the nodes that do, in node order.
         */
        fun <R> clusterResources(byNode: Map<String, List<R>>, sites: Map<String, String>, niceId: (R) -> String): Map<R, List<String>> {
            val out = linkedMapOf<String, Pair<R, MutableList<String>>>()
            for ((node, resources) in byNode) {
                val site = sites[node] ?: continue
                for (r in resources) out.getOrPut(niceId(r)) { r to mutableListOf() }.second += site
            }
            return out.values.associate { it.first to it.second.toList() }
        }

        private val INT = Regex("^-?(0|[1-9]\\d*)$")
        private val FLOAT = Regex("^-?\\d+\\.\\d+$")

        fun yamlToJson(text: String): JsonElement = toJson(Yaml.default.parseToYamlNode(text))

        private fun toJson(n: YamlNode): JsonElement = when (n) {
            is YamlMap -> JsonObject(n.entries.entries.associate { (k, v) -> k.content to toJson(v) })
            is YamlList -> JsonArray(n.items.map { toJson(it) })
            is YamlNull -> JsonNull
            is YamlTaggedNode -> toJson(n.innerNode)
            is YamlScalar -> {
                val c = n.content
                when {
                    INT.matches(c) -> JsonPrimitive(c.toLong())
                    FLOAT.matches(c) -> JsonPrimitive(c.toDouble())
                    c == "true" -> JsonPrimitive(true)
                    c == "false" -> JsonPrimitive(false)
                    else -> JsonPrimitive(c)
                }
            }
        }
    }
}
