// language: Kotlin, file: domain/Models.kt, target: Android 14
// *Provider-neutral domain model. Xtream and M3U both normalise into these.*

package tv.opentv.domain

import kotlinx.serialization.Serializable

/** Where a playlist comes from. Drives which repository implementation is used. */
@Serializable
sealed interface SourceKind {
    @Serializable
    data class Xtream(
        val host: String,
        val username: String,
        val password: String,
        /** Some panels need an output container forced, e.g. "m3u8" or "ts". */
        val preferredOutput: String? = null,
    ) : SourceKind

    @Serializable
    data class M3u(
        val url: String,
        val name: String = "Playlist",
        val epgUrl: String? = null,
    ) : SourceKind

    @Serializable
    data class Single(val url: String, val name: String = "Stream") : SourceKind
}

/** A saved provider entry shown on the sources screen. */
@Serializable
data class Source(
    val id: Long = 0,
    val name: String,
    val kind: SourceKind,
    val addedAt: Long = System.currentTimeMillis(),
)

// ---------------------------------------------------------------- live TV

data class LiveCategory(
    val id: String,
    val name: String,
)

data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val categoryId: String? = null,
    val epgChannelId: String? = null,
    /** Extra playable alternates for failover, best first. */
    val alternates: List<String> = emptyList(),
    val tvArchive: Boolean = false,
    val tvArchiveDuration: Int = 0,
)

data class EpgProgramme(
    val channelId: String,
    val title: String,
    val description: String? = null,
    val startUtc: Long,
    val endUtc: Long,
) {
    val durationMs: Long get() = (endUtc - startUtc).coerceAtLeast(0)
    fun progressAt(nowUtc: Long): Float =
        if (durationMs <= 0) 0f
        else ((nowUtc - startUtc).toFloat() / durationMs).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------- VOD

data class VodCategory(val id: String, val name: String)

/** One playable item. Movies and series episodes share this shape. */
data class VodItem(
    val id: String,
    val title: String,
    val streamUrl: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val categoryId: String? = null,
    val rating: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val durationSecs: Int? = null,
    val trailerUrl: String? = null,
    val isSeries: Boolean = false,
    /** For series: the panel's series id, used to fetch seasons. */
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val containerExtension: String? = null,
)

data class Season(val number: Int, val name: String, val episodeCount: Int)

data class SeriesInfo(
    val seriesId: String,
    val title: String,
    val plot: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: String? = null,
    val seasons: List<Season> = emptyList(),
)

/** Account state as the panel reports it. */
data class AccountInfo(
    val username: String,
    val isTrial: Boolean,
    val active: Boolean,
    val expiresAtUtc: Long?,
    val maxConnections: Int,
    val activeConnections: Int,
    val allowedOutputFormats: List<String> = emptyList(),
    val serverUrl: String? = null,
    val port: String? = null,
) {
    val daysRemaining: Long?
        get() = expiresAtUtc?.let { ((it - System.currentTimeMillis() / 1000) / 86_400).coerceAtLeast(0) }
}

// ---------------------------------------------------------------- results

/** Errors as values so the UI can render a specific message per failure. */
sealed interface LoadResult<out T> {
    data class Ok<T>(val value: T) : LoadResult<T>
    data class Err(val reason: FailureReason, val detail: String? = null) : LoadResult<Nothing>
}

enum class FailureReason {
    Network,
    Timeout,
    Unauthorized,
    Expired,
    DeviceLimit,
    RegionBlocked,
    ParseError,
    NotFound,
    Unknown,
}
