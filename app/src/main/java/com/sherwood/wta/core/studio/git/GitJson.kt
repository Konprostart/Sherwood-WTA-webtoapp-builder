package com.sherwood.wta.core.studio.git

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Small, null-tolerant readers for Gson trees.
 *
 * Both HTTP drivers parse third-party JSON, where a field can be missing, null or of the
 * wrong type at any time. These helpers never throw on a shape mismatch: a missing value
 * simply comes back as null, and the caller decides whether that is fatal. They live here
 * rather than in one driver's companion so a second driver can share them without
 * depending on the first one.
 */

internal fun JsonElement.asObjectOrNull(): JsonObject? =
    takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonElement.asArrayOrNull(): JsonArray? =
    takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

internal fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asInt

internal fun JsonObject.long(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asLong

internal fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean

internal fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

/** An id that the host may encode as a number or as a string. */
internal fun JsonObject.idString(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { primitive ->
        runCatching { primitive.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }
