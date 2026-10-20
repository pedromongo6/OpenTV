// language: Kotlin, file: data/Repository.kt, target: Android 14
// *Single access point for content. Caches the active provider and normalises errors.*

package tv.opentv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tv.opentv.data.db.AppDatabase
import tv.opentv.data.m3u.M3uProvider
import tv.opentv.data.xtream.XtreamProvider
import tv.opentv.domain.AccountInfo
import tv.opentv.domain.Channel
import tv.opentv.domain.EpgProgramme
import tv.opentv.domain.FailureReason
import tv.opentv.domain.LiveCategory
import tv.opentv.domain.LoadResult
import tv.opentv.domain.Season
import tv.opentv.domain.SeriesInfo
import tv.opentv.domain.Source
import tv.opentv.domain.SourceKind
import tv.opentv.domain.VodCategory
import tv.opentv.domain.VodItem

class Repository private constructor(private val context: Context) {

    private val prefs = Prefs.get(context)
    private val db = AppDatabase.get(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val buildMutex = Mutex()

    @Volatile
    private var provider: Provider? = null

    @Volatile
    private var providerSourceId: Long = -1

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    private val _activeSource = MutableStateFlow<Source?>(null)
    val activeSource: StateFlow<Source?> = _activeSource.asStateFlow()

    private val _account = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    // ------------------------------------------------------------ sources

    suspend fun loadSources() {
        val list = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<List<Source>>(prefs.sourcesJson) }.getOrDefault(emptyList())
        }
        _sources.value = list
        val activeId = prefs.activeSourceId
        val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
        if (active != null) {
            _activeSource.value = active
            prefs.lastSourceId = active.id
        }
    }

    suspend fun addSource(name: String, kind: SourceKind): LoadResult<Source> {
        val existing = _sources.value
        val nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1
        val source = Source(id = nextId, name = name, kind = kind)

        // Verify before persisting so a typo doesn't leave a dead entry.
        val probe = build(source)
        val check = probe.accountInfo()
        if (check is LoadResult.Err && kind !is SourceKind.M3u) {
            return LoadResult.Err(check.reason, check.detail)
        }

        val updated = existing + source
        withContext(Dispatchers.IO) {
            prefs.sourcesJson = json.encodeToString(updated)
            prefs.activeSourceId = nextId
        }
        _sources.value = updated
        _activeSource.value = source
        provider = probe
        providerSourceId = nextId
        if (check is LoadResult.Ok) _account.value = check.value
        return LoadResult.Ok(source)
    }

    suspend fun removeSource(id: Long) {
        val updated = _sources.value.filterNot { it.id == id }
        withContext(Dispatchers.IO) {
            prefs.sourcesJson = json.encodeToString(updated)
            if (prefs.activeSourceId == id) prefs.activeSourceId = updated.firstOrNull()?.id ?: -1L
        }
        _sources.value = updated
        if (providerSourceId == id) {
            provider = null
            providerSourceId = -1
            _account.value = null
        }
        _activeSource.value = updated.firstOrNull { it.id == prefs.activeSourceId } ?: updated.firstOrNull()
    }

    suspend fun selectSource(id: Long) {
        val s = _sources.value.firstOrNull { it.id == id } ?: return
        withContext(Dispatchers.IO) { prefs.activeSourceId = id }
        _activeSource.value = s
        provider = null
        providerSourceId = -1
    }

    // ------------------------------------------------------------ provider

    private fun build(source: Source): Provider = when (val k = source.kind) {
        is SourceKind.Xtream -> XtreamProvider(k)
        is SourceKind.M3u -> M3uProvider(k)
        is SourceKind.Single -> M3uProvider(SourceKind.M3u(url = k.url, name = k.name))
    }

    suspend fun provider(): LoadResult<Provider> {
        provider?.let { return LoadResult.Ok(it) }
        val source = _activeSource.value
            ?: return LoadResult.Err(FailureReason.NotFound, "No source configured")
        return buildMutex.withLock {
            provider?.let { return@withLock LoadResult.Ok(it) }
            val p = build(source)
            provider = p
            providerSourceId = source.id
            LoadResult.Ok(p)
        }
    }

    suspend fun refreshAccount() {
        when (val r = provider()) {
            is LoadResult.Ok -> {
                val info = r.value.accountInfo()
                if (info is LoadResult.Ok) _account.value = info.value
            }
            is LoadResult.Err -> Unit
        }
    }

    // ------------------------------------------------------------ passthrough

    suspend fun liveCategories(): LoadResult<List<LiveCategory>> = delegate { it.liveCategories() }
    suspend fun liveStreams(cat: String? = null): LoadResult<List<Channel>> = delegate { it.liveStreams(cat) }
    suspend fun epgFor(id: String, limit: Int = 24): LoadResult<List<EpgProgramme>> =
        delegate { it.epgForChannel(id, limit) }

    suspend fun fullEpg(): LoadResult<Map<String, List<EpgProgramme>>> = delegate { it.fullEpg() }

    suspend fun vodCategories(): LoadResult<List<VodCategory>> = delegate { it.vodCategories() }
    suspend fun vodStreams(cat: String? = null): LoadResult<List<VodItem>> = delegate { it.vodStreams(cat) }
    suspend fun seriesCategories(): LoadResult<List<VodCategory>> = delegate { it.seriesCategories() }
    suspend fun seriesList(cat: String? = null): LoadResult<List<VodItem>> = delegate { it.seriesList(cat) }
    suspend fun seriesInfo(id: String): LoadResult<SeriesInfo> = delegate { it.seriesInfo(id) }
    suspend fun seriesEpisodes(id: String, season: Int): LoadResult<List<VodItem>> =
        delegate { it.seriesEpisodes(id, season) }

    suspend fun seasons(id: String): LoadResult<List<Season>> = delegate { it.seasons(id) }
    suspend fun resolve(item: VodItem): LoadResult<String> = delegate { it.resolveStream(item) }

    private suspend fun <T> delegate(block: suspend (Provider) -> LoadResult<T>): LoadResult<T> =
        when (val p = provider()) {
            is LoadResult.Err -> p
            is LoadResult.Ok -> block(p.value)
        }

    // ------------------------------------------------------------ favourites

    fun favouriteId(): Long = _activeSource.value?.id ?: 0L

    fun db(): AppDatabase = db

    companion object {
        @Volatile
        private var instance: Repository? = null

        fun get(context: Context): Repository = instance ?: synchronized(this) {
            instance ?: Repository(context.applicationContext).also { instance = it }
        }
    }
}
