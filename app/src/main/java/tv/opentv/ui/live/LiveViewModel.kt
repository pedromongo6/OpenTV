// language: Kotlin, file: ui/live/LiveViewModel.kt, target: Android 14
// *Live TV state: categories, channel list, guide for the focused channel, search.*

package tv.opentv.ui.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.opentv.data.Repository
import tv.opentv.domain.Channel
import tv.opentv.domain.EpgProgramme
import tv.opentv.domain.FailureReason
import tv.opentv.domain.LiveCategory
import tv.opentv.domain.LoadResult

data class LiveUiState(
    val loading: Boolean = true,
    val categories: List<LiveCategory> = emptyList(),
    val selectedCategory: String? = null,
    val allChannels: List<Channel> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val query: String = "",
    val guide: List<EpgProgramme> = emptyList(),
    val guideChannelId: String? = null,
    val error: String? = null,
)

class LiveViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository.get(app)
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private var guideJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            when (val cats = repo.liveCategories()) {
                is LoadResult.Err -> {
                    _state.update { it.copy(loading = false, error = describe(cats.reason)) }
                    return@launch
                }
                is LoadResult.Ok -> _state.update { it.copy(categories = cats.value) }
            }

            when (val streams = repo.liveStreams(null)) {
                is LoadResult.Err ->
                    _state.update { it.copy(loading = false, error = describe(streams.reason)) }

                is LoadResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        allChannels = streams.value,
                        channels = applyFilter(streams.value, it.selectedCategory, it.query),
                        error = null,
                    )
                }
            }
        }
    }

    fun selectCategory(id: String?) {
        _state.update {
            it.copy(
                selectedCategory = id,
                channels = applyFilter(it.allChannels, id, it.query),
            )
        }
    }

    /**
     * Search runs over name, category and EPG id so "globo" matches whether the
     * panel put the word in the title or only in the guide mapping.
     */
    fun search(query: String) {
        _state.update {
            it.copy(query = query, channels = applyFilter(it.allChannels, it.selectedCategory, query))
        }
    }

    private fun applyFilter(
        all: List<Channel>,
        categoryId: String?,
        query: String,
    ): List<Channel> {
        var out = all
        if (categoryId != null) out = out.filter { it.categoryId == categoryId }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            out = out.filter {
                it.name.lowercase().contains(q) ||
                    it.epgChannelId?.lowercase()?.contains(q) == true
            }
        }
        return out
    }

    /** Guide for the currently focused channel, refreshed when it changes. */
    fun onChannelFocused(channel: Channel) {
        if (_state.value.guideChannelId == channel.id) return
        _state.update { it.copy(guideChannelId = channel.id, guide = emptyList()) }

        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            delay(180) // debounce while the user scrolls the grid
            when (val r = repo.epgFor(channel.id, limit = 24)) {
                is LoadResult.Ok -> _state.update {
                    // Guard against a late reply for a channel we already left.
                    if (it.guideChannelId == channel.id) it.copy(guide = r.value) else it
                }
                is LoadResult.Err -> Unit
            }
        }
    }

    private fun describe(reason: FailureReason): String = when (reason) {
        FailureReason.Network -> "Network error — check the connection"
        FailureReason.Timeout -> "The server timed out"
        FailureReason.Unauthorized -> "Login rejected by the provider"
        FailureReason.Expired -> "Account expired"
        FailureReason.DeviceLimit -> "Device limit reached"
        FailureReason.RegionBlocked -> "Blocked in this region"
        FailureReason.ParseError -> "Could not read the provider's reply"
        FailureReason.NotFound -> "Nothing found"
        FailureReason.Unknown -> "Unexpected error"
    }
}
