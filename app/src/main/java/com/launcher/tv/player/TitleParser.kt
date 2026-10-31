package com.launcher.tv.player

/**
 * Turns the messy titles IPTV providers use into something Trakt can search for.
 *
 *   "EN - The Bear S03 E05"              -> episode "The Bear" 3x5
 *   "|EN| Dune: Part Two (2024) [4K]"    -> movie "Dune: Part Two" (2024)
 *   "Breaking.Bad.S01E05.720p.mkv"        -> episode "Breaking Bad" 1x5
 *   "UK: Sky Sports Main Event FHD"       -> live (not scrobbled)
 *
 * The stream URL helps too: Xtream Codes providers use /movie/, /series/ and /live/ paths.
 */
object TitleParser {

    enum class Kind { MOVIE, EPISODE, LIVE, UNKNOWN }

    data class Parsed(
        val kind: Kind,
        val name: String,
        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
    ) {
        val canScrobble: Boolean
            get() = name.isNotBlank() && (kind == Kind.MOVIE || (kind == Kind.EPISODE && season != null && episode != null))
    }

    private val prefixTag = Regex("""^\s*(?:\|[^|]{1,15}\|\s*|\[[^\]]{1,15}]\s*|\([A-Z]{2,4}\)\s*|[A-Z]{2,4}(?:\s*[-:|]\s*|\s+-\s+))""")
    private val episodeSxE = Regex("""(?i)\bS(\d{1,2})\s*[-.]?\s*E(\d{1,3})\b""")
    private val episodeNxN = Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""")
    private val year = Regex("""[(\[]?\b((?:19|20)\d{2})\b[)\]]?""")
    private val qualityTags = Regex(
        """(?i)\b(4K|UHD|FHD|HD|SD|HEVC|H\.?26[45]|x26[45]|HDR10\+?|HDR|DV|DoVi|2160p|1080p|720p|480p|MULTI|MULTISUB|SUBS?|DUAL|AUDIO|WEB-?DL|WEBRip|BluRay|BRRip|REMUX|10bit|AAC|AC3|EAC3|DDP?5\.1|ATMOS)\b"""
    )
    private val bracketed = Regex("""[\[{][^\]}]*[\]}]""")
    private val extension = Regex("""(?i)\.(mkv|mp4|avi|ts|m3u8|mov|wmv|flv)$""")

    fun parse(rawTitle: String?, url: String?): Parsed {
        val path = url?.substringBefore('?')?.lowercase().orEmpty()
        val urlKind = when {
            "/live/" in path -> Kind.LIVE
            "/movie/" in path -> Kind.MOVIE
            "/series/" in path -> Kind.EPISODE
            else -> null
        }
        // Fall back to the file name in the URL when the player wasn't given a title.
        val fromUrl = rawTitle.isNullOrBlank()
        var text = rawTitle?.trim().takeUnless { it.isNullOrBlank() }
            ?: url?.substringBefore('?')?.substringAfterLast('/')?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: return Parsed(urlKind ?: Kind.UNKNOWN, "")

        text = extension.replace(text, "")
        // Xtream file names are just ids ("991.mp4"): nothing to identify.
        if (fromUrl && text.all { it.isDigit() }) return Parsed(urlKind ?: Kind.UNKNOWN, "")
        if (' ' !in text) text = text.replace('.', ' ').replace('_', ' ')

        // Strip "EN - ", "|UK| ", "[FR] ", repeatedly ("|EN| UK: ...").
        while (true) {
            val m = prefixTag.find(text) ?: break
            if (m.range.first != 0 || m.value.length >= text.length) break
            text = text.substring(m.range.last + 1)
        }

        episodeSxE.find(text)?.let { m ->
            return Parsed(Kind.EPISODE, clean(text.substring(0, m.range.first)), extractYear(text.substring(0, m.range.first)),
                m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        if (urlKind != Kind.MOVIE) {
            episodeNxN.find(text)?.let { m ->
                return Parsed(Kind.EPISODE, clean(text.substring(0, m.range.first)), null,
                    m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
        }
        if (urlKind == Kind.LIVE) return Parsed(Kind.LIVE, clean(text))

        // Movie: a year after the name ("Oppenheimer (2023)"); a leading number is part of the name ("1917").
        val yearMatch = year.findAll(text).lastOrNull { it.range.first > 0 && clean(text.substring(0, it.range.first)).isNotEmpty() }
        if (yearMatch != null && urlKind != Kind.EPISODE) {
            return Parsed(Kind.MOVIE, clean(text.substring(0, yearMatch.range.first)), yearMatch.groupValues[1].toInt())
        }
        return Parsed(urlKind ?: Kind.UNKNOWN, clean(text))
    }

    private fun extractYear(s: String): Int? = year.findAll(s).lastOrNull { it.range.first > 0 }?.groupValues?.get(1)?.toInt()

    private fun clean(s: String): String {
        var t = bracketed.replace(s, " ")
        t = qualityTags.replace(t, " ")
        t = year.findAll(t).lastOrNull { it.range.first > 0 && it.range.last >= t.trimEnd().length - 1 }
            ?.let { t.substring(0, it.range.first) } ?: t
        return t.replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '|', '.', '–', '(', ')')
    }
}
