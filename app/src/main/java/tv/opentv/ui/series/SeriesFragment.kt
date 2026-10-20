// language: Kotlin, file: ui/series/SeriesFragment.kt, target: Android 14
// *Series browser plus the season/episode detail sheet.*

package tv.opentv.ui.series

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.opentv.R
import tv.opentv.data.Repository
import tv.opentv.databinding.FragmentGridBinding
import tv.opentv.domain.LoadResult
import tv.opentv.domain.Season
import tv.opentv.domain.VodCategory
import tv.opentv.domain.VodItem
import tv.opentv.ui.PlayerActivity
import tv.opentv.ui.common.CategoryAdapter
import tv.opentv.ui.common.VodAdapter
import tv.opentv.ui.movies.addTextChangedListener
import tv.opentv.ui.movies.describeFailure

data class SeriesUiState(
    val loading: Boolean = true,
    val categories: List<VodCategory> = emptyList(),
    val selectedCategory: String? = null,
    val all: List<VodItem> = emptyList(),
    val items: List<VodItem> = emptyList(),
    val query: String = "",
    val seasons: List<Season> = emptyList(),
    val episodes: List<VodItem> = emptyList(),
    val openSeries: String? = null,
    val openTitle: String = "",
    val error: String? = null,
)

class SeriesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository.get(app)
    private val _state = MutableStateFlow(SeriesUiState())
    val state: StateFlow<SeriesUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val c = repo.seriesCategories()) {
                is LoadResult.Ok -> _state.update { it.copy(categories = c.value) }
                is LoadResult.Err -> Unit
            }
            when (val r = repo.seriesList(null)) {
                is LoadResult.Err -> _state.update {
                    it.copy(loading = false, error = describeFailure(r.reason))
                }
                is LoadResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        all = r.value.distinctBy { it.seriesId ?: it.id },
                        items = filter(r.value.distinctBy { it.seriesId ?: it.id }, it.selectedCategory, it.query),
                    )
                }
            }
        }
    }

    fun selectCategory(id: String?) = _state.update {
        it.copy(selectedCategory = id, items = filter(it.all, id, it.query))
    }

    fun search(q: String) = _state.update {
        it.copy(query = q, items = filter(it.all, it.selectedCategory, q))
    }

    private fun filter(all: List<VodItem>, cat: String?, q: String): List<VodItem> {
        var out = all
        if (cat != null) out = out.filter { it.categoryId == cat }
        if (q.isNotBlank()) {
            val n = q.trim().lowercase()
            out = out.filter { it.title.lowercase().contains(n) }
        }
        return out
    }

    fun open(item: VodItem) {
        val sid = item.seriesId ?: item.id
        viewModelScope.launch {
            _state.update { it.copy(openSeries = sid, openTitle = item.title, episodes = emptyList()) }
            when (val r = repo.seasons(sid)) {
                is LoadResult.Ok -> {
                    _state.update { it.copy(seasons = r.value) }
                    r.value.firstOrNull()?.let { openSeason(it.number) }
                }
                is LoadResult.Err -> _state.update { it.copy(error = describeFailure(r.reason)) }
            }
        }
    }

    fun openSeason(number: Int) {
        val sid = _state.value.openSeries ?: return
        viewModelScope.launch {
            when (val r = repo.seriesEpisodes(sid, number)) {
                is LoadResult.Ok -> _state.update { it.copy(episodes = r.value) }
                is LoadResult.Err -> _state.update { it.copy(error = describeFailure(r.reason)) }
            }
        }
    }

    fun close() = _state.update { it.copy(openSeries = null, episodes = emptyList(), seasons = emptyList()) }
}

class SeriesFragment : Fragment() {

    private var _b: FragmentGridBinding? = null
    private val b get() = _b!!
    private val vm: SeriesViewModel by viewModels()
    private lateinit var cats: CategoryAdapter
    private lateinit var grid: VodAdapter
    private lateinit var seasonAdapter: VodAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentGridBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cats = CategoryAdapter({ vm.selectCategory(it) }, getString(R.string.all_categories))
        b.categoryList.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = cats
        }

        grid = VodAdapter(onClick = { vm.open(it) })
        b.itemGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), columns())
            adapter = grid
        }

        seasonAdapter = VodAdapter(
            onClick = { ep -> play(ep) },
        )
        b.episodeList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = seasonAdapter
            isVisible = false
        }

        b.swipe.setOnRefreshListener { vm.load() }
        b.searchInput.addTextChangedListener { vm.search(it) }
        b.closeEpisodes.setOnClickListener { vm.close() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s ->
                    b.swipe.isRefreshing = false
                    b.progress.isVisible = s.loading && s.items.isEmpty()
                    b.empty.isVisible = !s.loading && s.items.isEmpty() && s.openSeries == null
                    b.errorText.isVisible = s.error != null

                    cats.submitVod(s.categories, s.selectedCategory)
                    grid.submitList(s.items)

                    val open = s.openSeries != null
                    b.episodeContainer.isVisible = open
                    b.itemGrid.isVisible = !open
                    b.categoryList.isVisible = !open
                    b.searchInput.isVisible = !open

                    if (open) {
                        b.episodeTitle.text = s.openTitle
                        seasonAdapter.submitList(s.episodes)
                        b.seasonChips.removeAllViews()
                        s.seasons.forEach { season ->
                            val chip = layoutInflater.inflate(
                                R.layout.item_category_chip, b.seasonChips, false,
                            ) as android.widget.TextView
                            chip.text = season.name
                            chip.setOnClickListener { vm.openSeason(season.number) }
                            b.seasonChips.addView(chip)
                        }
                    }
                }
            }
        }
    }

    private fun columns(): Int = when {
        resources.configuration.screenWidthDp >= 1280 -> 6
        resources.configuration.screenWidthDp >= 960 -> 5
        resources.configuration.screenWidthDp >= 600 -> 4
        else -> 3
    }

    private fun play(item: VodItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val r = Repository.get(requireContext()).resolve(item)) {
                is LoadResult.Ok ->
                    startActivity(PlayerActivity.intentForVod(requireContext(), item, r.value))
                is LoadResult.Err -> Unit
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        b.itemGrid.adapter = null; b.categoryList.adapter = null
        b.episodeList.adapter = null; _b = null
    }
}
