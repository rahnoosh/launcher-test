package com.launcher.tv.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val time = DateTimeFormatter.ofPattern("h:mm a")
private val dayTime = DateTimeFormatter.ofPattern("EEE h:mm a")
private val dateTime = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")

/** "Today 8:15 PM", "Tomorrow 1:00 PM", "Sun 4:25 PM" (this week), "Sat, Nov 15 · 10:00 PM". */
fun friendlyTime(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(millis).atZone(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), dt.toLocalDate())
    return when {
        days == 0L -> "Today ${time.format(dt)}"
        days == -1L -> "Yesterday ${time.format(dt)}"
        days == 1L -> "Tomorrow ${time.format(dt)}"
        days in 2..6 -> dayTime.format(dt)
        else -> dateTime.format(dt)
    }
}

fun clockText(): String = DateTimeFormatter.ofPattern("h:mm").format(java.time.LocalTime.now())
