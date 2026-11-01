package com.launcher.tv.data.sky

import android.content.Context
import com.launcher.tv.data.Http
import com.launcher.tv.data.arr
import com.launcher.tv.data.obj
import com.launcher.tv.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * The weather for the status pill and today's sunrise and sunset, which set the sky's light. Your
 * approximate location comes from your IP address (GeoJS); the forecast from Open-Meteo. Neither
 * needs an account or key.
 */
object Weather {
    @Serializable
    data class Now(
        val tempC: Double,
        /** WMO weather code. */
        val code: Int,
        val isDay: Boolean,
        val sunrise: Long? = null,
        val sunset: Long? = null,
        val city: String? = null,
        val fetchedAt: Long = 0,
    ) {
        /** °F where people use it, °C elsewhere. */
        fun display(locale: Locale = Locale.getDefault()): String =
            if (locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) "${Math.round(tempC * 9 / 5 + 32)}°" else "${Math.round(tempC)}°"

        /** A simple symbol for the status pill. */
        val symbol: String
            get() = when (code) {
                0, 1 -> if (isDay) "☀" else "☾"
                2 -> "⛅"
                3, 45, 48 -> "☁"
                in 51..67, in 80..82 -> "☂"
                in 71..77, 85, 86 -> "❄"
                in 95..99 -> "⚡"
                else -> "☁"
            }
    }

    private const val PREFS = "weather"
    private const val MAX_AGE_MS = 30 * 60_000L

    /** The cached reading if it's under half an hour old, else a fresh one (or the old one if offline). */
    suspend fun current(context: Context): Now? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString("now", null)?.let { runCatching { Http.json.decodeFromString(Now.serializer(), it) }.getOrNull() }
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < MAX_AGE_MS) return cached
        return try {
            val geo = Http.getJson("https://get.geojs.io/v1/ip/geo.json").obj()
            val lat = geo?.str("latitude") ?: return cached
            val lon = geo.str("longitude") ?: return cached
            val forecast = Http.getJson(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&daily=sunrise,sunset&timezone=auto&forecast_days=1"
            )
            val now = parseForecast(forecast, geo.str("city"), System.currentTimeMillis()) ?: return cached
            prefs.edit().putString("now", Http.json.encodeToString(Now.serializer(), now)).apply()
            now
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            cached
        }
    }

    /** Open-Meteo's answer. Its sunrise/sunset are local times; utc_offset_seconds turns them into instants. */
    fun parseForecast(root: JsonElement?, city: String?, now: Long): Now? {
        val o = root.obj() ?: return null
        val current = o.obj("current") ?: return null
        val temp = (current["temperature_2m"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val offset = o.str("utc_offset_seconds")?.toIntOrNull() ?: 0
        fun instant(local: String?): Long? = local?.let {
            runCatching { LocalDateTime.parse(it).toInstant(ZoneOffset.ofTotalSeconds(offset)).toEpochMilli() }.getOrNull()
        }
        val daily = o.obj("daily")
        return Now(
            tempC = temp,
            code = current.str("weather_code")?.toIntOrNull() ?: 0,
            isDay = current.str("is_day") == "1",
            sunrise = instant(daily?.arr("sunrise")?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }),
            sunset = instant(daily?.arr("sunset")?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }),
            city = city,
            fetchedAt = now,
        )
    }
}
