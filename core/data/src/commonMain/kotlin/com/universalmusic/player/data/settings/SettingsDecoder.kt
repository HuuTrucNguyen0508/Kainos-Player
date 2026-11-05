package com.universalmusic.player.data.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

private val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun decodeAppSettings(text: String): AppSettings {
    val fields = settingsJson.parseToJsonElement(text).jsonObject.toMutableMap()
    if ("tint" !in fields) {
        val legacy = (fields["colorScheme"] as? JsonPrimitive)?.contentOrNull
        fields["tint"] = JsonPrimitive(FolioTint.entries.find { it.name == legacy }?.name ?: FolioTint.FOLIO.name)
    }
    fun defaultUnknown(key: String, names: List<String>, default: String) {
        if (key in fields && (fields[key] as? JsonPrimitive)?.contentOrNull !in names) {
            fields[key] = JsonPrimitive(default)
        }
    }
    defaultUnknown("skin", SkinChoice.entries.map { it.name }, SkinChoice.PLATFORM_DEFAULT.name)
    defaultUnknown("tint", FolioTint.entries.map { it.name }, FolioTint.FOLIO.name)
    return settingsJson.decodeFromJsonElement<AppSettings>(JsonObject(fields)).withoutLegacyHomeLanPairing()
}
