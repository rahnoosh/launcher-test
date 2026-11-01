package com.launcher.tv.data.sky

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Locale

class SkyDataTest {
    @Test
    fun bingPhotoOfTheDay() {
        val json = """{"images":[{"startdate":"20261031","url":"/th?id=OHR.LarchesDolomites_EN-US1234_1920x1080.jpg&rf=LaDigue_1920x1080.jpg&pid=hp",
            "urlbase":"/th?id=OHR.LarchesDolomites_EN-US1234","copyright":"Larch trees in the Dolomites, Italy (© Jane Doe/Getty Images)",
            "title":"Autumn's gold","hsh":"x"}],"tooltips":{}}"""
        val (url, credit) = DailyPhoto.parse(Json.parseToJsonElement(json))!!
        assertEquals("https://www.bing.com/th?id=OHR.LarchesDolomites_EN-US1234_1920x1080.jpg", url)
        assertEquals("Larch trees in the Dolomites, Italy (© Jane Doe/Getty Images)", credit)
        assertNull(DailyPhoto.parse(Json.parseToJsonElement("""{"images":[]}""")))
    }

    @Test
    fun openMeteoForecast() {
        val json = """{"latitude":33.2,"longitude":-87.6,"utc_offset_seconds":-18000,"timezone":"America/Chicago",
            "current":{"time":"2026-10-31T20:45","interval":900,"temperature_2m":18.3,"weather_code":2,"is_day":0},
            "daily":{"time":["2026-10-31"],"sunrise":["2026-10-31T06:36"],"sunset":["2026-10-31T17:46"]}}"""
        val w = Weather.parseForecast(Json.parseToJsonElement(json), "Tuscaloosa", 1L)!!
        assertEquals(18.3, w.tempC, 0.001)
        assertEquals(2, w.code)
        assertFalse(w.isDay)
        assertEquals("Tuscaloosa", w.city)
        // Local times plus the UTC offset: 06:36 at UTC-5 is 11:36 UTC.
        assertEquals(Instant.parse("2026-10-31T11:36:00Z").toEpochMilli(), w.sunrise)
        assertEquals(Instant.parse("2026-10-31T22:46:00Z").toEpochMilli(), w.sunset)
        assertEquals("65°", w.display(Locale.US))
        assertEquals("18°", w.display(Locale.UK))
        assertEquals("⛅", w.symbol)
        assertNull(Weather.parseForecast(Json.parseToJsonElement("{}"), null, 1L))
    }
}
