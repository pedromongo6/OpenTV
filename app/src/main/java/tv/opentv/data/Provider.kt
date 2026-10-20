// language: Kotlin, file: data/Provider.kt, target: Android 14
// *Contract every playlist backend implements. Repository picks by SourceKind.*

package tv.opentv.data

import tv.opentv.domain.AccountInfo
import tv.opentv.domain.Channel
import tv.opentv.domain.EpgProgramme
import tv.opentv.domain.LiveCategory
import tv.opentv.domain.LoadResult
import tv.opentv.domain.Season
import tv.opentv.domain.SeriesInfo
import tv.opentv.domain.VodCategory
import tv.opentv.domain.VodItem

interface Provider {

    /** One-line description for the sources list. */
    val displayName: String

    suspend fun accountInfo(): LoadResult<AccountInfo>

    // ---- live ----
    suspend fun liveCategories(): LoadResult<List<LiveCategory>>
    suspend fun liveStreams(categoryId: String? = null): LoadResult<List<Channel>>
    suspend fun epgForChannel(channelId: String, limit: Int = 24): LoadResult<List<EpgProgramme>>

    /** Full guide when the panel can supply one; M3U sources use a remote XMLTV file. */
    suspend fun fullEpg(): LoadResult<Map<String, List<EpgProgramme>>>

    // ---- vod ----
    suspend fun vodCategories(): LoadResult<List<VodCategory>>
    suspend fun vodStreams(categoryId: String? = null): LoadResult<List<VodItem>>
    suspend fun seriesCategories(): LoadResult<List<VodCategory>> = vodCategories()
    suspend fun seriesList(categoryId: String? = null): LoadResult<List<VodItem>>
    suspend fun seriesInfo(seriesId: String): LoadResult<SeriesInfo>
    suspend fun seriesEpisodes(seriesId: String, season: Int): LoadResult<List<VodItem>>

    /** Resolve a fresh playable URL. Panels often expire tokens. */
    suspend fun resolveStream(item: VodItem): LoadResult<String> = LoadResult.Ok(item.streamUrl)

    suspend fun seasons(seriesId: String): LoadResult<List<Season>> = LoadResult.Ok(emptyList())
}
