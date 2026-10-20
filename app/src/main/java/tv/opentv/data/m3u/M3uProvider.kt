// language: Kotlin, file: data/m3u/M3uProvider.kt, target: Android 14
// *Provider backed by a plain M3U/M3U8 URL plus an optional XMLTV guide.*

package tv.opentv.data.m3u

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class M3uProvider(private val spec: SourceKind.M3u) : Provider {

    override val displayName: String get() = spec.name

    /** Playlist is parsed once and cached; several screens read from it. */
    @Volatile
    private var cached: Playlist? = null

    @Volatile
    private var cachedEpg: Map<String, List<EpgProgramme>>? = null

    private suspend fun playlist(): LoadResult<Playlist> {
        cached?.let { return LoadResult.Ok(it) }
        return when (val r = fetchText(spec.url)) {
            is LoadResult.Err -> r
            is LoadResult.Ok -> {
                if (!r.value.contains("#EXTM3U") && !r.value.contains("#EXTINF")) {
                    LoadResult.Err(FailureReason.ParseError, "Not an M3U playlist")
                } else {
                    val p = withContext(Dispatchers.Default) { M3uParser.parse(r.value, spec.epgUrl) }
                    cached = p
                    LoadResult.Ok(p)
                }
            }
        }
    }

    private suspend fun guide(p: Playlist): Map<String, List<EpgProgramme>> {
        cachedEpg?.let { return it }
        val url = p.epgUrl ?: spec.epgUrl ?: return emptyMap()
        return when (val r = fetchText(url)) {
            is LoadResult.Err -> emptyMap()
            is LoadResult.Ok -> {
                val parsed = withContext(Dispatchers.Default) { XmltvParser.parse(r.value) }
                cachedEpg = parsed
                parsed
            }
        }
    }

    override suspend fun accountInfo(): LoadResult<AccountInfo> = when (val p = playlist()) {
        is LoadResult.Err -> p
        is LoadResult.Ok -> LoadResult.Ok(
            AccountInfo(
                username = spec.name,
                isTrial = false,
                active = true,
                expiresAtUtc = null,
                maxConnections = 1,
                activeConnections = 1,
                allowedOutputFormats = listOf("m3u8", "ts"),
            ),
        )
    }

    override suspend fun liveCategories(): LoadResult<List<LiveCategory>> = when (val p = playlist()) {
        is LoadResult.Err -> p
        is LoadResult.Ok -> LoadResult.Ok(p.value.liveCategories)
    }

    override suspend fun liveStreams(categoryId: String?): LoadResult<List<Channel>> = when (val p = playlist()) {
        is LoadResult.Err -> p
        is LoadResult.Ok -> LoadResult.Ok(
            if (categoryId == null) p.value.live
            else p.value.live.filter { it.categoryId == categoryId },
        )
    }

    override suspend fun epgForChannel(channelId: String, limit: Int): LoadResult<List<EpgProgramme>> =
        when (val p = playlist()) {
            is LoadResult.Err -> p
            is LoadResult.Ok -> {
                val all = guide(p.value)
                val now = System.currentTimeMillis() / 1000
                val list = all[channelId].orEmpty()
                    .filter { it.endUtc >= now - 3600 }
                    .take(limit)
                LoadResult.Ok(list)
            }
        }

    override suspend fun fullEpg(): LoadResult<Map<String, List<EpgProgramme>>> =
        when (val p = playlist()) {
            is LoadResult.Err -> p
            is LoadResult.Ok -> LoadResult.Ok(guide(p.value))
        }

    override suspend fun vodCategories(): LoadResult<List<VodCategory>> = when (val p = playlist()) {
        is LoadResult.Err -> p
        is LoadResult.Ok -> LoadResult.Ok(p.value.vodCategories)
    }

    override suspend fun vodStreams(categoryId: String?): LoadResult<List<VodItem>> = when (val p = playlist()) {
        is LoadResult.Err -> p
        is LoadResult.Ok -> LoadResult.Ok(
            if (categoryId == null) p.value.movies
            else p.value.movies.filter { it.categoryId == categoryId },
        )
    }

    override suspend fun seriesCategories(): LoadResult<List<VodCategory>> = when (val p = playlist()) {
        is LoadResult.Err -> p
        is LoadResult.Ok -> LoadResult.Ok(p.value.seriesCategories)
    }

    override suspend fun seriesList(categoryId: String?): LoadResult<List<VodItem>> = when (val p = playlist()) {
        is LoadResult.Err -> p
        is LoadResult.Ok -> {
            val items = if (categoryId == null) p.value.series
            else p.value.series.filter { it.categoryId == categoryId }
            LoadResult.Ok(items)
        }
    }

    /** In an M3U the "series id" is the hashed show name, so group by it. */
    override suspend fun seriesInfo(seriesId: String): LoadResult<SeriesInfo> = when (val p = playlist()) {
        is LoadResult.Err -> p
        is LoadResult.Ok -> {
            val eps = p.value.series.filter { it.seriesId == seriesId }
            val first = eps.firstOrNull()
                ?: return LoadResult.Err(FailureReason.NotFound, "Unknown series")
            LoadResult.Ok(
                SeriesInfo(
                    seriesId = seriesId,
                    title = first.title,
                    posterUrl = first.posterUrl,
                    seasons = eps.groupBy { it.seasonNumber ?: 0 }
                        .map { (n, list) -> Season(n, "Season $n", list.size) }
                        .sortedBy { it.number },
                ),
            )
        }
    }

    override suspend fun seriesEpisodes(seriesId: String, season: Int): LoadResult<List<VodItem>> =
        when (val p = playlist()) {
            is LoadResult.Err -> p
            is LoadResult.Ok -> LoadResult.Ok(
                p.value.series
                    .filter { it.seriesId == seriesId && (it.seasonNumber ?: 0) == season }
                    .sortedBy { it.episodeNumber ?: 0 },
            )
        }

    override suspend fun seasons(seriesId: String): LoadResult<List<Season>> = when (val i = seriesInfo(seriesId)) {
        is LoadResult.Err -> i
        is LoadResult.Ok -> LoadResult.Ok(i.value.seasons)
    }

    /** Force a re-download; wired to pull-to-refresh. */
    fun invalidate() {
        cached = null
        cachedEpg = null
    }

    private suspend fun fetchText(url: String): LoadResult<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext LoadResult.Err(mapHttp(resp.code), "HTTP ${resp.code}")
                }
                val body = resp.body?.string()
                    ?: return@withContext LoadResult.Err(FailureReason.ParseError, "Empty body")
                LoadResult.Ok(body)
            }
        } catch (e: SocketTimeoutException) {
            LoadResult.Err(FailureReason.Timeout, e.message)
        } catch (e: IOException) {
            LoadResult.Err(FailureReason.Network, e.message)
        } catch (e: Exception) {
            LoadResult.Err(FailureReason.Unknown, e.message)
        }
    }

    private fun mapHttp(code: Int): FailureReason = when (code) {
        401, 403 -> FailureReason.Unauthorized
        404 -> FailureReason.NotFound
        408 -> FailureReason.Timeout
        else -> FailureReason.Network
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) OpenTV/1.0"
    }
}
