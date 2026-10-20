// language: Kotlin, file: ui/movies/MoviesFragment.kt, target: Android 14
package tv.opentv.ui.movies

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.opentv.R
import tv.opentv.data.Repository
import tv.opentv.databinding.FragmentGridBinding
import tv.opentv.domain.FailureReason
import tv.opentv.domain.LoadResult
import tv.opentv.domain.VodCategory
import tv.opentv.domain.VodItem
import tv.opentv.ui.PlayerActivity
import tv.opentv.ui.common.CategoryAdapter
import tv.opentv.ui.common.VodAdapter

data class GridUiState(
    val loading: Boolean = true,
    val categories: List<VodCategory> = emptyList(),
    val selectedCategory: String? = null,
    val allItems: List<VodItem> = emptyList(),
    val items: List<VodItem> = emptyList(),
    val query: String = "",
    val error: String? = null,
)

class MoviesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository.get(app)
    private val _state = MutableStateFlow(GridUiState())
    val state: StateFlow<GridUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val c = repo.vodCategories()) {
                is LoadResult.Ok -> _state.update { it.copy(categories = c.value) }
                is LoadResult.Err -> Unit // a flat playlist may have no categories
            }
            when (val r = repo.vodStreams(null)) {
                is LoadResult.Err -> _state.update {
                    it.copy(loading = false, error = describe(r.reason))
                }
                is LoadResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        allItems = r.value,
                        items = filter(r.value, it.selectedCategory, it.query),
                    )
                }
            }
        }
    }

    fun selectCategory(id: String?) = _state.update {
        it.copy(selectedCategory = id, items = filter(it.allItems, id, it.query))
    }

    fun search(q: String) = _state.update {
        it.copy(query = q, items = filter(it.allItems, it.selectedCategory, q))
    }

    private fun filter(all: List<VodItem>, cat: String?, q: String): List<VodItem> {
        var out = all
        if (cat != null) out = out.filter { it.categoryId == cat }
        if (q.isNotBlank()) {
            val needle = q.trim().lowercase()
            out = out.filter {
                it.title.lowercase().contains(needle) ||
                    it.genre?.lowercase()?.contains(needle) == true
            }
        }
        return out
    }

    internal fun describe(reason: FailureReason): String = describeFailure(reason)
}

/** Shared by every screen so error wording is identical everywhere. */
fun describeFailure(reason: FailureReason): String = when (reason) {
    FailureReason.Network -> "Network error"
    FailureReason.Timeout -> "Server timed out"
    FailureReason.Unauthorized -> "Login rejected"
    FailureReason.Expired -> "Account expired"
    FailureReason.DeviceLimit -> "Device limit reached"
    FailureReason.RegionBlocked -> "Blocked in this region"
    FailureReason.ParseError -> "Unreadable reply from provider"
    FailureReason.NotFound -> "Nothing found"
    FailureReason.Unknown -> "Unexpected error"
}

class MoviesFragment : Fragment() {

    private var _b: FragmentGridBinding? = null
    private val b get() = _b!!
    private val vm: MoviesViewModel by viewModels()
    private lateinit var cats: CategoryAdapter
    private lateinit var grid: VodAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentGridBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cats = CategoryAdapter({ vm.selectCategory(it) }, getString(R.string.all_categories))
        b.categoryList.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false,
            )
            adapter = cats
        }

        grid = VodAdapter(
            onClick = { play(it) },
            onLongClick = { addToWatchLater(it) },
        )
        b.itemGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), columnsForScreen())
            adapter = grid
        }

        b.swipe.setOnRefreshListener { vm.load() }
        b.searchInput.addTextChangedListener(
            afterTextChanged = { vm.search(it) },
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s ->
                    b.swipe.isRefreshing = false
                    b.progress.isVisible = s.loading && s.items.isEmpty()
                    b.empty.isVisible = !s.loading && s.items.isEmpty()
                    b.errorText.isVisible = s.error != null && s.items.isEmpty()
                    s.error?.let { b.errorText.text = it }
                    cats.submitVod(s.categories, s.selectedCategory)
                    grid.submitList(s.items)
                }
            }
        }
    }

    private fun columnsForScreen(): Int {
        val w = resources.configuration.screenWidthDp
        return when {
            w >= 1280 -> 6
            w >= 960 -> 5
            w >= 600 -> 4
            else -> 3
        }
    }

    private fun play(item: VodItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val r = Repository.get(requireContext()).resolve(item)) {
                is LoadResult.Ok -> startActivity(
                    PlayerActivity.intentForVod(requireContext(), item, r.value),
                )
                is LoadResult.Err -> Unit
            }
        }
    }

    private fun addToWatchLater(item: VodItem) {
        val repo = Repository.get(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = repo.db().watchLater()
            if (dao.contains(item.id)) dao.delete(item.id)
            else dao.insert(
                tv.opentv.data.db.WatchLaterEntity(
                    itemId = item.id,
                    title = item.title,
                    url = item.streamUrl,
                    poster = item.posterUrl,
                    isSeries = item.isSeries,
                    sourceId = repo.favouriteId(),
                ),
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView(); b.itemGrid.adapter = null; b.categoryList.adapter = null; _b = null
    }
}

/** Small helper so we do not pull in the full core-ktx TextWatcher boilerplate everywhere. */
fun android.widget.EditText.addTextChangedListener(afterTextChanged: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = afterTextChanged(s?.toString().orEmpty())
    })
}
