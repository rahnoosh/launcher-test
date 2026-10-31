package com.launcher.tv.data.iptv

import com.launcher.tv.player.TitleParser
import java.text.Normalizer

internal object Names {
    private val diacritics = Regex("""\p{Mn}+""")
    private val nonAlnum = Regex("""[^A-Z0-9]+""")

    /** "US: ESPN 2 FHD" -> [US, ESPN, 2, FHD] */
    fun tokens(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(diacritics, "")
            .uppercase().replace("&", " AND ").replace("+", " PLUS ")
            .split(nonAlnum).filter { it.isNotEmpty() }

    /** Title key for matching catalogue names: "|EN| The Bear (2022) [4K]" -> "bear". */
    fun titleKey(s: String): String {
        val name = TitleParser.parse(s, null).name.ifBlank { s }
        val t = tokens(name).toMutableList()
        if (t.firstOrNull() == "THE" && t.size > 1) t.removeAt(0)
        return t.joinToString(" ").lowercase()
    }

    /** Year in a catalogue name like "The Bear (2022)". */
    fun yearOf(s: String): Int? = TitleParser.parse(s, null).year

    /** Finds [needle] as a contiguous token run (ignoring spaces inside it, so "ESPN2" matches "ESPN 2"). */
    fun findRun(haystack: List<String>, needle: List<String>): IntRange? {
        val squashed = needle.joinToString("")
        if (squashed.isEmpty()) return null
        for (i in haystack.indices) {
            val sb = StringBuilder()
            for (j in i until haystack.size) {
                sb.append(haystack[j])
                if (sb.length > squashed.length) break
                if (sb.toString() == squashed) return i..j
                if (!squashed.startsWith(sb)) break
            }
        }
        return null
    }

    fun containsWord(haystack: List<String>, word: String): Boolean = findRun(haystack, tokens(word)) != null
}
