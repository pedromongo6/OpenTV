// language: Kotlin, file: ui/live/LiveFragment.kt, target: Android 14
// *Live TV screen: category rail, channel list, guide panel, search.*

package tv.opentv.ui.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import tv.opentv.R
import tv.opentv.data.Repository
import tv.opentv.databinding.FragmentLiveBinding
import tv.opentv.domain.Channel
import tv.opentv.ui.PlayerActivity
import tv.opentv.ui.common.CategoryAdapter
import tv.opentv.ui.common.ChannelAdapter
import tv.opentv.ui.common.EpgAdapter

class LiveFragment : Fragment() {

    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!

    private val vm: LiveViewModel by viewModels()

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var epgAdapter: EpgAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        categoryAdapter = CategoryAdapter(
            onSelected = { vm.selectCategory(it) },
            allLabel = getString(R.string.all_channels),
        )
        binding.categoryList.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryAdapter
        }

        channelAdapter = ChannelAdapter(
            onClick = { play(it) },
            onFocused = { vm.onChannelFocused(it) },
            onLongClick = { toggleFavourite(it) },
        )
        binding.channelList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = channelAdapter
            setHasFixedSize(true)
        }

        epgAdapter = EpgAdapter()
        binding.guideList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = epgAdapter
        }

        binding.swipe.setOnRefreshListener { vm.load() }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                vm.search(binding.searchInput.text?.toString().orEmpty())
                true
            } else false
        }

        observe()
        binding.channelList.requestFocus()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.state.collect { s ->
                        binding.swipe.isRefreshing = false
                        binding.progress.isVisible = s.loading && s.channels.isEmpty()
                        binding.empty.isVisible = !s.loading && s.channels.isEmpty()

                        s.error?.let { binding.errorText.text = it }
                        binding.errorText.isVisible = s.error != null && s.channels.isEmpty()

                        categoryAdapter.submit(s.categories, s.selectedCategory)
                        channelAdapter.submit(s.channels)
                        epgAdapter.submit(s.guide)

                        binding.channelCount.text =
                            resources.getQuantityString(
                                R.plurals.channel_count,
                                s.channels.size,
                                s.channels.size,
                            )
                    }
                }
            }
        }
    }

    private fun play(channel: Channel) {
        startActivity(
            PlayerActivity.intentForChannel(
                requireContext(),
                channel,
                channelAdapter.currentList.map { it.streamUrl },
            ),
        )
    }

    private fun toggleFavourite(channel: Channel) {
        val repo = Repository.get(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = repo.db().favourites()
            if (dao.isFavourite(channel.id, "live")) {
                dao.delete(channel.id, "live")
            } else {
                dao.insert(
                    tv.opentv.data.db.FavouriteEntity(
                        itemId = channel.id,
                        kind = "live",
                        title = channel.name,
                        url = channel.streamUrl,
                        logo = channel.logoUrl,
                        sourceId = repo.favouriteId(),
                    ),
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.channelList.adapter = null
        binding.categoryList.adapter = null
        binding.guideList.adapter = null
        _binding = null
    }
}
