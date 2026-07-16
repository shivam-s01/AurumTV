package com.aurum.musictv.ui.search

import android.content.Intent
import android.os.Bundle
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.aurum.musictv.data.AurumApi
import com.aurum.musictv.data.Song
import com.aurum.musictv.ui.home.SongCardPresenter
import com.aurum.musictv.ui.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Uses Leanback's SearchSupportFragment, which gives D-pad text entry AND
 * built-in mic/voice search on TV remotes that support it — no custom
 * search bar UI needed (vs. search_screen.dart's hand-built search field).
 */
class SongSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Song) {
                val intent = Intent(requireContext(), PlayerActivity::class.java)
                intent.putExtra(PlayerActivity.EXTRA_SONG_ID, item.id)
                intent.putExtra(PlayerActivity.EXTRA_SONG_TITLE, item.title)
                intent.putExtra(PlayerActivity.EXTRA_SONG_ARTIST, item.artist)
                intent.putExtra(PlayerActivity.EXTRA_SONG_ART, item.albumArtUrl)
                intent.putExtra(PlayerActivity.EXTRA_SONG_SOURCE, item.source.name)
                startActivity(intent)
            }
        }
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String?): Boolean {
        debouncedSearch(newQuery)
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        debouncedSearch(query)
        return true
    }

    private fun debouncedSearch(query: String?) {
        searchJob?.cancel()
        if (query.isNullOrBlank() || query.length < 2) {
            rowsAdapter.clear()
            return
        }
        searchJob = lifecycleScope.launch {
            delay(350) // debounce, matches phone app's search-as-you-type guard
            val results = AurumApi.search(query, limit = 25)
            rowsAdapter.clear()
            if (results.isNotEmpty()) {
                val listRowAdapter = ArrayObjectAdapter(SongCardPresenter())
                results.forEach { listRowAdapter.add(it) }
                val header = HeaderItem(0, "Results")
                rowsAdapter.add(ListRow(header, listRowAdapter))
            }
        }
    }
}
