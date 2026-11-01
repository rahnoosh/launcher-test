package com.launcher.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(200)}")

/** Shared OkHttp client and JSON settings. All network calls run on the IO dispatcher. */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    private val JSON_TYPE = "application/json".toMediaType()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        execute(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build())

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        execute(
            Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody(JSON_TYPE))
                .build()
        )

    /** application/x-www-form-urlencoded POST (OAuth 2 token endpoints). */
    suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): String =
        execute(
            Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(okhttp3.FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())
                .build()
        )

    /** Raw bytes (images). */
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(get(url, headers))

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, text)
            text
        }
    }
}

// Small helpers for walking loosely-typed JSON (ESPN, TheSportsDB, Jolpica).
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
