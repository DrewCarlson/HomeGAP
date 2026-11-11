package homegap.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The JSON both ends of `/api` use. Every field is sent, defaults and nulls included, so scripts
 * that read the API see the whole shape; the dashboard would fill defaults in either way.
 */
val ApiJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** [snapshot] as a JSON object without `generatedAt`, the form [ServerEvent.Patch] compares. */
fun snapshotFields(snapshot: StatusSnapshot): JsonObject {
    val fields = ApiJson.encodeToJsonElement(StatusSnapshot.serializer(), snapshot).jsonObject
    return JsonObject(fields - GENERATED_AT)
}

/** The fields of [current] that differ from [previous], as a patch generated at [at]. */
fun snapshotPatch(
    previous: JsonObject,
    current: JsonObject,
    at: Long,
): ServerEvent.Patch? {
    val set = current.filter { (key, value) -> previous[key] != value }
    val unset = previous.keys - current.keys
    if (set.isEmpty() && unset.isEmpty()) return null
    return ServerEvent.Patch(
        at = at,
        set = JsonObject(set),
        unset = unset.toList(),
    )
}

/** These snapshot fields (from [snapshotFields]) with [patch] applied. */
fun JsonObject.patched(patch: ServerEvent.Patch): JsonObject {
    val fields = toMutableMap()
    fields.putAll(patch.set)
    patch.unset.forEach { fields.remove(it) }
    return JsonObject(fields)
}

/** Reads [fields] back into a snapshot generated at [generatedAt]. */
fun snapshotOf(fields: JsonObject, generatedAt: Long): StatusSnapshot {
    val withTime = JsonObject(fields + (GENERATED_AT to JsonPrimitive(generatedAt)))
    return ApiJson.decodeFromJsonElement(StatusSnapshot.serializer(), withTime)
}

private const val GENERATED_AT = "generatedAt"
