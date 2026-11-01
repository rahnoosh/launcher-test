package com.launcher.tv.data

import android.content.Context
import android.content.SharedPreferences
import com.launcher.tv.data.history.WatchHistory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/** One person in the house. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** Index into the avatar colours. */
    val color: Int = 0,
    /** Kids profiles need the PIN for grown-up things (settings, accounts, teams, switching). */
    val kids: Boolean = false,
)

/**
 * Profiles: everyone keeps their own Trakt and Simkl, teams, settings, Continue Watching and home rows.
 * The box's providers, API keys, phone page and sleep timer are shared.
 *
 * The main profile keeps the original storage (so nothing moves when profiles arrive); others get
 * their own copies: preferences named "<name>__<id>" and files under profiles/<id>/.
 */
object Profiles {
    const val MAIN = "main"

    /** [ChangeBus] topic: the active profile changed. */
    const val CHANGED = "profile"

    /** Preferences that belong to a person rather than the box. */
    val PERSONAL_PREFS = listOf("trakt", "simkl", "follows", "spoilers", "ui")

    const val MAX = 6

    private val serializer = ListSerializer(Profile.serializer())

    private fun box(c: Context) = c.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun all(c: Context): List<Profile> =
        box(c).getString("list", null)
            ?.let { runCatching { Http.json.decodeFromString(serializer, it) }.getOrNull() }
            ?.takeIf { list -> list.any { it.id == MAIN } }
            ?: listOf(Profile(MAIN, "You"))

    fun activeId(c: Context): String = box(c).getString("active", MAIN) ?: MAIN

    fun active(c: Context): Profile {
        val id = activeId(c)
        val list = all(c)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    /** Null when there are already [MAX] profiles. */
    fun add(c: Context, name: String, kids: Boolean): Profile? {
        val list = all(c)
        if (list.size >= MAX) return null
        val p = Profile(
            id = UUID.randomUUID().toString().take(8),
            name = name.trim().ifEmpty { if (kids) "Kids" else "Guest" }.take(20),
            color = (list.maxOfOrNull { it.color } ?: 0) + 1,
            kids = kids,
        )
        save(c, list + p)
        return p
    }

    fun update(c: Context, profile: Profile) = save(c, all(c).map { if (it.id == profile.id) profile.copy(name = profile.name.trim().take(20).ifEmpty { it.name }) else it })

    /** Removes a profile and everything it kept on the box. The main profile stays. */
    fun remove(c: Context, id: String) {
        if (id == MAIN) return
        if (activeId(c) == id) switchTo(c, MAIN)
        save(c, all(c).filter { it.id != id })
        PERSONAL_PREFS.forEach { c.deleteSharedPreferences(scoped(id, it)) }
        File(c.filesDir, "profiles/$id").deleteRecursively()
    }

    fun switchTo(c: Context, id: String) {
        if (id == activeId(c) || all(c).none { it.id == id }) return
        box(c).edit().putString("active", id).commit()
        WatchHistory.reset()
        ChangeBus.emit(CHANGED)
    }

    private fun save(c: Context, list: List<Profile>) {
        box(c).edit().putString("list", Http.json.encodeToString(serializer, list)).apply()
    }

    // ---------- storage for the active profile ----------

    fun scoped(id: String, name: String): String = if (id == MAIN) name else "${name}__$id"

    /** The active profile's copy of a personal preferences file. */
    fun prefs(c: Context, name: String): SharedPreferences = c.getSharedPreferences(scoped(activeId(c), name), Context.MODE_PRIVATE)

    /** The active profile's copy of a personal data file. */
    fun file(c: Context, name: String): File {
        val id = activeId(c)
        if (id == MAIN) return File(c.filesDir, name)
        return File(File(c.filesDir, "profiles/$id").apply { mkdirs() }, name)
    }

    // ---------- kids PIN ----------

    fun hasPin(c: Context): Boolean = box(c).getString("pin", null) != null

    fun setPin(c: Context, pin: String) = box(c).edit().putString("pin", pin).apply()

    fun checkPin(c: Context, pin: String): Boolean = box(c).getString("pin", null)?.let { it == pin.trim() } ?: true

    /** While a kids profile is active, grown-up things ask for the PIN (once per few minutes). */
    fun locked(c: Context): Boolean = active(c).kids && hasPin(c) && System.currentTimeMillis() > unlockedUntil

    fun unlock() {
        unlockedUntil = System.currentTimeMillis() + 5 * 60_000L
    }

    @Volatile private var unlockedUntil = 0L
}
