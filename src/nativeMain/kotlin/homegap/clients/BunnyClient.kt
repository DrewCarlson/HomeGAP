package homegap.clients

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class BunnyApiException(val status: Int, message: String) : Exception(message)

@Serializable
data class BunnyZone(
    @SerialName("Id") val id: Long,
    @SerialName("Domain") val domain: String,
    @SerialName("Records") val records: List<BunnyRecord> = emptyList(),
    @SerialName("Nameserver1") val nameserver1: String? = null,
    @SerialName("Nameserver2") val nameserver2: String? = null,
) {
    /** The zone's nameservers (Bunny's own unless the zone uses custom ones). */
    val nameservers: List<String>
        get() = listOfNotNull(nameserver1, nameserver2).filter { it.isNotBlank() }.ifEmpty { DEFAULT_NAMESERVERS }

    companion object {
        val DEFAULT_NAMESERVERS = listOf("kiki.bunny.net", "coco.bunny.net")
    }
}

@Serializable
data class BunnyZoneList(@SerialName("Items") val items: List<BunnyZone> = emptyList())

@Serializable
data class BunnyRecord(
    @SerialName("Id") val id: Long = 0,
    @SerialName("Type") val type: Int,
    @SerialName("Ttl") val ttl: Int = 300,
    @SerialName("Value") val value: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Weight") val weight: Int = 100,
    @SerialName("MonitorStatus") val monitorStatus: Int = 0,
    @SerialName("MonitorType") val monitorType: Int = 0,
    @SerialName("GeolocationLatitude") val latitude: Double = 0.0,
    @SerialName("GeolocationLongitude") val longitude: Double = 0.0,
    @SerialName("LatencyZone") val latencyZone: String? = null,
    @SerialName("SmartRoutingType") val smartRoutingType: Int = 0,
    @SerialName("Disabled") val disabled: Boolean = false,
    @SerialName("Comment") val comment: String? = null,
    /** CAA records only. */
    @SerialName("Flags") val flags: Int? = null,
    @SerialName("Tag") val tag: String? = null,
) {
    val typeName: String get() = RECORD_TYPES.getOrElse(type) { type.toString() }

    companion object {
        val RECORD_TYPES = listOf("A", "AAAA", "CNAME", "TXT", "MX", "RDR", "FLATTEN", "PZ", "SRV", "CAA", "PTR", "SCR", "NS", "SVCB", "HTTPS", "TLSA")
        fun typeCode(name: String): Int = RECORD_TYPES.indexOf(name).also { require(it >= 0) { "unknown record type $name" } }
        const val MONITOR_NONE = 0
        const val MONITOR_PING = 1
        const val MONITOR_HTTP = 2
        const val ROUTING_NONE = 0
        const val ROUTING_LATENCY = 1
        const val ROUTING_GEO = 2
        val MONITOR_STATUS = listOf("unknown", "online", "offline")
    }
}

/** Bunny DNS API (`AccessKey` header). */
open class BunnyClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private suspend fun call(method: HttpMethod, path: String, body: String? = null): HttpResponse {
        val resp = http.request("$baseUrl$path") {
            this.method = method
            header("AccessKey", apiKey)
            accept(ContentType.Application.Json)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        if (!resp.status.isSuccess()) {
            throw BunnyApiException(resp.status.value, "Bunny ${method.value} $path -> ${resp.status.value}: ${resp.bodyAsText().take(300)}")
        }
        return resp
    }

    open suspend fun findZone(domain: String): BunnyZone? {
        val list = json.decodeFromString<BunnyZoneList>(call(HttpMethod.Get, "/dnszone?search=$domain&perPage=1000").bodyAsText())
        return list.items.firstOrNull { it.domain.equals(domain, ignoreCase = true) }
    }

    open suspend fun getZone(id: Long): BunnyZone = json.decodeFromString(call(HttpMethod.Get, "/dnszone/$id").bodyAsText())

    /** Write payload: drops the read-only fields (Id on create, MonitorStatus). */
    private fun writeBody(r: BunnyRecord, includeId: Boolean): String {
        val obj = json.encodeToJsonElement(r).jsonObject
        return JsonObject(obj.filterKeys { it != "MonitorStatus" && (includeId || it != "Id") }).toString()
    }

    open suspend fun addRecord(zoneId: Long, r: BunnyRecord): BunnyRecord {
        val text = call(HttpMethod.Put, "/dnszone/$zoneId/records", writeBody(r, includeId = false)).bodyAsText()
        return runCatching { json.decodeFromString<BunnyRecord>(text) }.getOrDefault(r)
    }

    open suspend fun updateRecord(zoneId: Long, r: BunnyRecord) {
        call(HttpMethod.Post, "/dnszone/$zoneId/records/${r.id}", writeBody(r, includeId = true))
    }

    open suspend fun deleteRecord(zoneId: Long, id: Long) {
        call(HttpMethod.Delete, "/dnszone/$zoneId/records/$id")
    }
}
