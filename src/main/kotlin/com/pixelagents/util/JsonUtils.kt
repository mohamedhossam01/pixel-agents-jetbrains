package com.pixelagents.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

object JsonUtils {
    val gson: Gson = GsonBuilder().create()
    val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun toJson(obj: Any?): String = gson.toJson(obj)

    fun toPrettyJson(obj: Any?): String = prettyGson.toJson(obj)

    fun parseElement(json: String): JsonElement = JsonParser.parseString(json)

    inline fun <reified T> fromJson(json: String): T =
        gson.fromJson(json, object : TypeToken<T>() {}.type)

    fun toMap(json: String): Map<String, Any?> =
        gson.fromJson(json, object : TypeToken<Map<String, Any?>>() {}.type)
}
