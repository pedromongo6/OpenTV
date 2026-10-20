// language: Kotlin, file: data/m3u/M3uParser.kt, target: Android 14
// *Extended M3U/M3U8 playlist parser. Handles #EXTINF attributes, #EXTGRP, #KODIPROP,*
// *#EXTVLCOPT and the common #EXTINF tvg-* metadata used by IPTV providers.*

package tv.opentv.data.m3u

import tv.opentv.domain.Channel
import tv.opentv.domain.EpgProgramme
import tv.opentv.domain.LiveCategory
import tv.opentv.domain.VodCategory
import tv.opentv.domain.VodItem

/** A parsed playlist: live entries plus, when present, movie/series entries. */
data class Playlist(
    val live: List<Channel>,
    val movies: List<VodItem>,
    val series: List<VodItem>,
    val liveCategories: List<LiveCategory>,
    val vodCategories: List<VodCategory>,
    val seriesCategories: List<VodCategory>,
    /** channel id -> display name, for XMLTV matching. */
    val epgNames: Map<String, String>,
    /** Direct XMLTV URL when the playlist header advertised one. */
    val epgUrl: String?,
)

object M3uParser {

    private val ATTR_RE = Regex("""([A-Za-z0-9_.-]+)="([^"]*)"""")

    /** Group names that providers use for VOD rather than live. */
    private val MOVIE_HINTS = setOf("movie", "movies", "filme", "filmes", "vod", "film")
    private val SERIES_HINTS = setOf("series", "serie", "séries", "shows", "tv shows", "novelas")

    fun parse(body: String, epgUrlFromSource: String? = null): Playlist {
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }

        val live = ArrayList<Channel>(4096)
        val movies = ArrayList<VodItem>(512)
        val series = ArrayList<VodItem>(512)
        val liveCats = LinkedHashMap<String, LiveCategory>()
        val vodCats = LinkedHashMap<String, VodCategory>()
        val seriesCats = LinkedHashMap<String, VodCategory>()
        val epgNames = HashMap<String, String>(4096)

        var pendingHeader: String? = null
        var pendingGroup: String? = null
        var pendingLogo: String? = null
        var pendingTvgId: String? = null
        var pendingKind: EntryKind = EntryKind.UNKNOWN
        var headerEpg: String? = null

        fun flush(url: String) {
            val title = pendingHeader?.ifBlank { null } ?: url.substringAfterLast('/').ifBlank { "Unknown" }
            val group = pendingGroup
            val kind = if (pendingKind != EntryKind.UNKNOWN) pendingKind else classify(group, title)

            when (kind) {
                EntryKind.MOVIE -> {
                    val cid = group?.let { slug(it) }
                    if (group != null) vodCats.getOrPut(cid!!) { VodCategory(cid, group) }
                    movies += VodItem(
                        id = "m:${url.hashCode()}",
                        title = title,
                        streamUrl = url,
                        posterUrl = pendingLogo,
                        categoryId = cid,
                        isSeries = false,
                    )
                }

                EntryKind.SERIES -> {
                    val cid = group?.let { slug(it) }
                    if (group != null) seriesCats.getOrPut(cid!!) { VodCategory(cid, group) }
                    val (show, season, episode) = splitEpisode(title)
                    series += VodItem(
                        id = "s:${show.hashCode()}:${season ?: 0}:${episode ?: 0}:${url.hashCode()}",
                        title = show,
                        streamUrl = url,
                        posterUrl = pendingLogo,
                        categoryId = cid,
                        isSeries = true,
                        seriesId = show.hashCode().toString(),
                        seasonNumber = season,
                        episodeNumber = episode,
                    )
                }

                else -> {
                    val cid = group?.let { slug(it) } ?: "uncategorised"
                    liveCats.getOrPut(cid) {
                        LiveCategory(cid, group ?: "Uncategorised")
                    }
                    val id = pendingTvgId?.ifBlank { null } ?: url
                    live += Channel(
                        id = id,
                        name = title,
                        streamUrl = url,
                        logoUrl = pendingLogo,
                        categoryId = cid,
                        epgChannelId = pendingTvgId,
                    )
                    epgNames[id] = title
                }
            }
            pendingHeader = null
            pendingGroup = null
            pendingLogo = null
            pendingTvgId = null
            pendingKind = EntryKind.UNKNOWN
        }

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U") -> {
                    // url-tvg / x-tvg-url carry the guide location
                    val attrs = ATTR_RE.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    headerEpg = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                }

                line.startsWith("#EXTINF") -> {
                    val attrs = ATTR_RE.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    // Text after the final comma is the display name.
                    val afterComma = line.substringAfter(',', "").trim()
                    pendingHeader = afterComma.ifBlank { attrs["tvg-name"] }
                    // #EXTGRP can precede #EXTINF; only overwrite when the entry
                    // actually carries a group-title, otherwise the earlier value wins.
                    attrs["group-title"]?.ifBlank { null }?.let { pendingGroup = it }
                    pendingLogo = attrs["tvg-logo"]?.ifBlank { null }
                    pendingTvgId = attrs["tvg-id"]?.ifBlank { null }
                    pendingKind = classify(pendingGroup, afterComma)
                }

                line.startsWith("#EXTGRP:") -> {
                    pendingGroup = line.removePrefix("#EXTGRP:").trim().ifBlank { null }
                    if (pendingKind == EntryKind.UNKNOWN) pendingKind = classify(pendingGroup, pendingHeader)
                }

                // VLC/Kodi hints. Recognised and skipped; the player handles codecs itself.
                line.startsWith("#KODIPROP:") || line.startsWith("#EXTVLCOPT:") -> Unit

                line.startsWith("#EXT-X-") -> Unit // HLS manifest tags inside a playlist body

                line.startsWith("#") -> Unit // any other directive

                else -> flush(line)
            }
        }

        return Playlist(
            live = live,
            movies = movies,
            series = series,
            liveCategories = liveCats.values.toList(),
            vodCategories = vodCats.values.toList(),
            seriesCategories = seriesCats.values.toList(),
            epgNames = epgNames,
            epgUrl = headerEpg ?: epgUrlFromSource,
        )
    }

    private enum class EntryKind { LIVE, MOVIE, SERIES, UNKNOWN }

    private fun classify(group: String?, title: String?): EntryKind {
        val g = group?.lowercase()?.trim().orEmpty()
        if (g.isNotEmpty()) {
            if (MOVIE_HINTS.any { g == it || g.contains(it) }) return EntryKind.MOVIE
            if (SERIES_HINTS.any { g == it || g.contains(it) }) return EntryKind.SERIES
        }
        val t = title?.lowercase().orEmpty()
        // "Show Name S01 E04", "Show Name 1x04", "Show Name Season 1 Episode 4"
        if (EPISODE_RE.containsMatchIn(t)) return EntryKind.SERIES
        return when {
            g.isEmpty() && t.isEmpty() -> EntryKind.UNKNOWN
            else -> EntryKind.LIVE
        }
    }

    private val EPISODE_RE = Regex("""\bs\d{1,2}\s*[ex]\d{1,3}\b|\b\d{1,2}x\d{1,3}\b|\bseason\s*\d+.*episode\s*\d+""")

    /** Pull show name, season and episode out of a combined title. */
    fun splitEpisode(title: String): Triple<String, Int?, Int?> {
        val patterns = listOf(
            Regex("""^(.*?)\s*[Ss](\d{1,2})\s*[Ee](\d{1,3})\s*[-–:]?\s*(.*)$"""),
            Regex("""^(.*?)\s*(\d{1,2})[xX](\d{1,3})\s*[-–:]?\s*(.*)$"""),
            Regex("""^(.*?)\s*[Ss]eason\s*(\d{1,2}).*?[Ee]pisode\s*(\d{1,3})\s*[-–:]?\s*(.*)$"""),
        )
        for (p in patterns) {
            val m = p.find(title) ?: continue
            val show = m.groupValues[1].trim().trimEnd('-', '–', ':').trim()
            val season = m.groupValues[2].toIntOrNull()
            val ep = m.groupValues[3].toIntOrNull()
            if (show.isNotEmpty()) return Triple(show, season, ep)
        }
        return Triple(title, null, null)
    }

    private fun slug(s: String): String =
        s.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').ifEmpty { "group" }
}

/** Minimal XMLTV reader. Only the fields the UI renders. */
object XmltvParser {

    private val PROGRAMME_RE = Regex(
        """<programme\s+([^>]*?)>(.*?)</programme>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val ATTR_RE = Regex("""([A-Za-z0-9_:-]+)="([^"]*)"""")
    private val TITLE_RE = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
    private val DESC_RE = Regex("""<desc[^>]*>(.*?)</desc>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(body: String): Map<String, List<EpgProgramme>> {
        val out = HashMap<String, MutableList<EpgProgramme>>()
        for (m in PROGRAMME_RE.findAll(body)) {
            val attrs = ATTR_RE.findAll(m.groupValues[1])
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val channelId = attrs["channel"] ?: continue
            val start = parseXmltvTime(attrs["start"]) ?: continue
            val stop = parseXmltvTime(attrs["stop"]) ?: (start + 3600)
            val bodyInner = m.groupValues[2]
            val title = TITLE_RE.find(bodyInner)?.groupValues?.get(1)?.unescape()?.trim().orEmpty()
            val desc = DESC_RE.find(bodyInner)?.groupValues?.get(1)?.unescape()?.trim()
            out.getOrPut(channelId) { mutableListOf() } += EpgProgramme(
                channelId = channelId,
                title = title.ifEmpty { "No title" },
                description = desc?.takeIf { it.isNotEmpty() },
                startUtc = start,
                endUtc = stop,
            )
        }
        out.values.forEach { it.sortBy { p -> p.startUtc } }
        return out
    }

    /** XMLTV timestamps look like 20261020060000 +0000. */
    fun parseXmltvTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return try {
            fmt.parse(cleaned)?.time?.div(1000)
        } catch (_: Exception) {
            try {
                java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(cleaned.substring(0, 14))?.time?.div(1000)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun String.unescape(): String = this
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}
