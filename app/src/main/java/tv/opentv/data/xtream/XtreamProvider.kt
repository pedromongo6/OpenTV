// language: Kotlin, file: data/xtream/XtreamProvider.kt, target: Android 14
// *Xtream Codes panel client: player_api.php endpoints, normalised into the domain model.*

package tv.opentv.data.xtream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import tv.opentv.data.Http
import tv.opentv.data.Provider
import tv.opentv.domain.AccountInfo
import tv.opentv.domain.Channel
import tv.opentv.domain.EpgProgramme
import tv.opentv.domain.FailureReason
import tv.opentv.domain.LiveCategory
import tv.opentv.domain.LoadResult
import tv.opentv.domain.Season
import tv.opentv.domain.SeriesInfo
import tv.opentv.domain.SourceKind
import tv.opentv.domain.VodCategory
import tv.opentv.domain.VodItem
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder

class XtreamProvider(private val spec: SourceKind.Xtream) : Provider {

    override val displayName: String get() = "${spec.username}@${hostOnly(spec.host)}"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val apiBase: String
        get() {
            val h = spec.host.trim().trimEnd('/')
            val withScheme = if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
            return "$withScheme/player_api.php"
        }

    /** The stream endpoint lives beside the API, not on it. */
    private val streamBase: String
        get() {
            val h = spec.host.trim().trimEnd('/')
            val withScheme = if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
            return withScheme
        }

    private fun creds() = "username=${enc(spec.username)}&password=${enc(spec.password)}"

    private fun apiUrl(action: String? = null, extra: String = ""): String = buildString {
        append(apiBase).append('?').append(creds())
        if (action != null) append("&action=").append(action)
        if (extra.isNotEmpty()) append('&').append(extra)
    }

    // ------------------------------------------------------------ transport

    private suspend fun get(url: String): LoadResult<JsonElement> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            Http.client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) {
                    return@withContext LoadResult.Err(mapHttp(resp.code), "HTTP ${resp.code}")
                }
                if (body.isNullOrBlank()) {
                    return@withContext LoadResult.Err(FailureReason.ParseError, "Empty body")
                }
                val trimmed = body.trimStart()
                // Panels answer with JSON, or with a bare array, or sometimes raw TS on a bad action.
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    LoadResult.Ok(json.parseToJsonElement(trimmed))
                } else {
                    LoadResult.Err(FailureReason.ParseError, "Non-JSON reply")
                }
            }
        } catch (e: SocketTimeoutException) {
            LoadResult.Err(FailureReason.Timeout, e.message)
        } catch (e: IOException) {
            LoadResult.Err(FailureReason.Network, e.message)
        } catch (e: Exception) {
            LoadResult.Err(FailureReason.Unknown, e.message)
        }
    }

    private suspend fun getArray(url: String): LoadResult<List<JsonObject>> = when (val r = get(url)) {
        is LoadResult.Err -> r
        is LoadResult.Ok -> {
            val el = r.value
            when {
                el is JsonArray -> LoadResult.Ok(el.mapNotNull { it as? JsonObject })
                el is JsonObject -> LoadResult.Ok(listOf(el))
                else -> LoadResult.Err(FailureReason.ParseError, "Expected array")
            }
        }
    }

    // ------------------------------------------------------------ account

    override suspend fun accountInfo(): LoadResult<AccountInfo> = when (val r = get(apiUrl())) {
        is LoadResult.Err -> r
        is LoadResult.Ok -> {
            val root = r.value as? JsonObject
                ?: return LoadResult.Err(FailureReason.ParseError, "Bad auth reply")
            val info = root["user_info"]?.asObject() ?: root
            val auth = root["auth"]?.jsonPrimitive?.intOrNull ?: 0
            if (auth == 0 && info["auth"]?.jsonPrimitive?.intOrNull == 0) {
                val status = info["status"]?.asString()
                return when (status) {
                    "Expired" -> LoadResult.Err(FailureReason.Expired)
                    "Disabled" -> LoadResult.Err(FailureReason.Unauthorized, "Account disabled")
                    "Banned" -> LoadResult.Err(FailureReason.Unauthorized, "Account banned")
                    else -> LoadResult.Err(FailureReason.Unauthorized, status)
                }
            }
            val serverInfo = root["server_info"]?.asObject()
            LoadResult.Ok(
                AccountInfo(
                    username = info["username"]?.asString() ?: spec.username,
                    isTrial = info["is_trial"].isTruthy(),
                    active = (info["status"]?.asString() ?: "Active") == "Active",
                    expiresAtUtc = info["exp_date"]?.asString()?.toLongOrNull(),
                    maxConnections = info["max_connections"]?.asString()?.toIntOrNull() ?: 1,
                    activeConnections = info["active_cons"]?.asString()?.toIntOrNull() ?: 0,
                    allowedOutputFormats = info["allowed_output_formats"]
                        ?.asArray()?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList(),
                    serverUrl = serverInfo?.get("url")?.asString(),
                    port = serverInfo?.get("port")?.asString(),
                ),
            )
        }
    }

    // ------------------------------------------------------------ live

    override suspend fun liveCategories(): LoadResult<List<LiveCategory>> =
        when (val r = getArray(apiUrl("get_live_categories"))) {
            is LoadResult.Err -> r
            is LoadResult.Ok -> LoadResult.Ok(
                r.value.map { LiveCategory(it.str("category_id"), it.str("category_name")) },
            )
        }

    override suspend fun liveStreams(categoryId: String?): LoadResult<List<Channel>> {
        val url = if (categoryId == null) apiUrl("get_live_streams")
        else apiUrl("get_live_streams", "category_id=${enc(categoryId)}")
        return when (val r = getArray(url)) {
            is LoadResult.Err -> r
            is LoadResult.Ok -> LoadResult.Ok(r.value.map { it.toChannel() })
        }
    }

    private fun JsonObject.toChannel(): Channel {
        val id = str("stream_id")
        val ext = str("container_extension").ifBlank { "ts" }
        val direct = str("stream_url").ifBlank { null }
        val built = buildStreamUrl(id, ext)
        return Channel(
            id = id,
            name = str("name"),
            streamUrl = direct ?: built,
            logoUrl = str("stream_icon").ifBlank { null },
            categoryId = str("category_id").ifBlank { null },
            epgChannelId = str("epg_channel_id").ifBlank { null },
            tvArchive = intOrZero("tv_archive") == 1,
            tvArchiveDuration = intOrZero("tv_archive_duration"),
        )
    }

    override suspend fun epgForChannel(channelId: String, limit: Int): LoadResult<List<EpgProgramme>> {
        val url = apiUrl("get_simple_data_table", "stream_id=${enc(channelId)}")
        return when (val r = get(url)) {
            is LoadResult.Err -> r
            is LoadResult.Ok -> {
                val listings = (r.value as? JsonObject)?.get("epg_listings")?.asArray().orEmpty()
                LoadResult.Ok(
                    listings.mapNotNull { obj ->
                        val o = obj as? JsonObject ?: return@mapNotNull null
                        val start = o["start_timestamp"]?.asString()?.toLongOrNull() ?: return@mapNotNull null
                        val stop = o["stop_timestamp"]?.asString()?.toLongOrNull() ?: (start + 3600)
                        EpgProgramme(
                            channelId = channelId,
                            title = o.str("title").decodeB64OrSelf(),
                            description = o.str("description").decodeB64OrSelf().ifBlank { null },
                            startUtc = start,
                            endUtc = stop,
                        )
                    },
                )
            }
        }
    }

    /** Xtream has no global EPG action; the guide is assembled per visible channel. */
    override suspend fun fullEpg(): LoadResult<Map<String, List<EpgProgramme>>> =
        LoadResult.Ok(emptyMap())

    // ------------------------------------------------------------ vod

    override suspend fun vodCategories(): LoadResult<List<VodCategory>> =
        when (val r = getArray(apiUrl("get_vod_categories"))) {
            is LoadResult.Err -> r
            is LoadResult.Ok -> LoadResult.Ok(
                r.value.map { VodCategory(it.str("category_id"), it.str("category_name")) },
            )
        }

    override suspend fun vodStreams(categoryId: String?): LoadResult<List<VodItem>> {
        val url = if (categoryId == null) apiUrl("get_vod_streams")
        else apiUrl("get_vod_streams", "category_id=${enc(categoryId)}")
        return when (val r = getArray(url)) {
            is LoadResult.Err -> r
            is LoadResult.Ok -> LoadResult.Ok(r.value.map { it.toVodItem(isSeries = false) })
        }
    }

    override suspend fun seriesCategories(): LoadResult<List<VodCategory>> =
        when (val r = getArray(apiUrl("get_series_categories"))) {
            is LoadResult.Err -> r
            is LoadResult.Ok -> LoadResult.Ok(
                r.value.map { VodCategory(it.str("category_id"), it.str("category_name")) },
            )
        }

    override suspend fun seriesList(categoryId: String?): LoadResult<List<VodItem>> {
        val url = if (categoryId == null) apiUrl("get_series")
        else apiUrl("get_series", "category_id=${enc(categoryId)}")
        return when (val r = getArray(url)) {
            is LoadResult.Err -> r
            is LoadResult.Ok -> LoadResult.Ok(r.value.map { it.toVodItem(isSeries = true) })
        }
    }

    private fun JsonObject.toVodItem(isSeries: Boolean): VodItem {
        val id = if (isSeries) str("series_id") else str("stream_id")
        val ext = str("container_extension").ifBlank { if (isSeries) "mp4" else "mp4" }
        return VodItem(
            id = id,
            title = str("name"),
            streamUrl = if (isSeries) "" else buildVodUrl(id, ext),
            posterUrl = str("stream_icon").ifBlank { str("cover").ifBlank { null } },
            backdropUrl = str("backdrop_path").ifBlank { null },
            categoryId = str("category_id").ifBlank { null },
            rating = str("rating").ifBlank { str("rating_5based").ifBlank { null } },
            plot = str("plot").ifBlank { null },
            genre = str("genre").ifBlank { null },
            releaseDate = str("releasedate").ifBlank { str("releaseDate").ifBlank { null } },
            durationSecs = str("episode_run_time").toIntOrNull(),
            trailerUrl = str("youtube_trailer").ifBlank { null }?.let { "https://www.youtube.com/watch?v=$it" },
            isSeries = isSeries,
            seriesId = if (isSeries) id else null,
            containerExtension = ext,
        )
    }

    override suspend fun seriesInfo(seriesId: String): LoadResult<SeriesInfo> {
        val url = apiUrl("get_series_info", "series_id=${enc(seriesId)}")
        return when (val r = get(url)) {
            is LoadResult.Err -> r
            is LoadResult.Ok -> {
                val root = r.value as? JsonObject
                    ?: return LoadResult.Err(FailureReason.ParseError, "Bad series reply")
                val info = root["info"]?.asObject() ?: JsonObject(emptyMap())
                val episodeMap = root["episodes"]?.asObject() ?: JsonObject(emptyMap())
                val seasons = episodeMap.keys.mapNotNull { k ->
                    val n = k.toIntOrNull() ?: return@mapNotNull null
                    Season(n, "Season $n", episodeMap[k]?.asArray()?.size ?: 0)
                }.sortedBy { it.number }
                LoadResult.Ok(
                    SeriesInfo(
                        seriesId = seriesId,
                        title = info.str("name"),
                        plot = info.str("plot").ifBlank { null },
                        posterUrl = info.str("cover").ifBlank { null },
                        backdropUrl = info.str("backdrop_path").ifBlank { null },
                        cast = info.str("cast").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        director = info.str("director").ifBlank { null },
                        genre = info.str("genre").ifBlank { null },
                        releaseDate = info.str("releaseDate").ifBlank { str("releasedate").ifBlank { null } },
                        rating = info.str("rating").ifBlank { null },
                        seasons = seasons,
                    ),
                )
            }
        }
    }

    override suspend fun seriesEpisodes(seriesId: String, season: Int): LoadResult<List<VodItem>> {
        val url = apiUrl("get_series_info", "series_id=${enc(seriesId)}")
        return when (val r = get(url)) {
            is LoadResult.Err -> r
            is LoadResult.Ok -> {
                val root = r.value as? JsonObject
                    ?: return LoadResult.Err(FailureReason.ParseError, "Bad series reply")
                val eps = root["episodes"]?.asObject()?.get(season.toString())?.asArray().orEmpty()
                LoadResult.Ok(
                    eps.mapNotNull { e ->
                        val o = e as? JsonObject ?: return@mapNotNull null
                        val id = o.str("id")
                        val ext = o.str("container_extension").ifBlank { "mp4" }
                        VodItem(
                            id = id,
                            title = o.str("title").ifBlank { "Episode ${o.str("episode_num")}" },
                            streamUrl = buildVodUrl(id, ext),
                            posterUrl = o.str("movie_image").ifBlank { null },
                            plot = o.str("plot").ifBlank { null },
                            durationSecs = o.str("duration_secs").toIntOrNull()
                                ?: o.str("duration").parseDuration(),
                            isSeries = true,
                            seriesId = seriesId,
                            seasonNumber = o.str("season").toIntOrNull() ?: season,
                            episodeNumber = o.str("episode_num").toIntOrNull(),
                            containerExtension = ext,
                        )
                    },
                )
            }
        }
    }

    override suspend fun seasons(seriesId: String): LoadResult<List<Season>> = when (val r = seriesInfo(seriesId)) {
        is LoadResult.Err -> r
        is LoadResult.Ok -> LoadResult.Ok(r.value.seasons)
    }

    // ------------------------------------------------------------ urls

    private fun buildStreamUrl(streamId: String, ext: String): String {
        val out = spec.preferredOutput ?: ext
        return "$streamBase/live/${spec.username}/${spec.password}/$streamId.$out"
    }

    private fun buildVodUrl(streamId: String, ext: String): String =
        "$streamBase/movie/${spec.username}/${spec.password}/$streamId.$ext"

    // ------------------------------------------------------------ helpers

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
    private fun JsonElement.asArray(): JsonArray? = this as? JsonArray
    private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.isTruthy(): Boolean =
        (this as? JsonPrimitive)?.booleanOrNull ?: (this.asString() == "1")

    private fun JsonObject.str(key: String): String {
        val v = this[key] ?: return ""
        if (v is JsonPrimitive) return v.contentOrNull.orEmpty()
        return ""
    }

    private fun JsonObject.intOrZero(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: this.str(key).toIntOrNull() ?: 0

    /** Panels historically base64 the EPG text and sometimes don't. Handle both. */
    private fun String.decodeB64OrSelf(): String {
        if (isBlank()) return this
        return try {
            val decoded = String(android.util.Base64.decode(this, android.util.Base64.DEFAULT))
            // Only accept the decode if it looks like text.
            if (decoded.any { it.isLetterOrDigit() }) decoded.trim() else this
        } catch (_: Exception) {
            this
        }
    }

    private fun String.parseDuration(): Int? {
        // "01:32:10" -> seconds
        val parts = split(':')
        return if (parts.size == 3) {
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val s = parts[2].toIntOrNull() ?: return null
            h * 3600 + m * 60 + s
        } else null
    }

    private fun mapHttp(code: Int): FailureReason = when (code) {
        401, 403 -> FailureReason.Unauthorized
        404 -> FailureReason.NotFound
        408, 504 -> FailureReason.Timeout
        else -> FailureReason.Network
    }

    private fun hostOnly(url: String): String =
        url.removePrefix("https://").removePrefix("http://").trimEnd('/').substringBefore('/')

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val UA = "IPTVSmarters/1.0 (Linux; Android 14) OpenTV/1.0"
    }
}
