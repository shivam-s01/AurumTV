package com.aurum.musictv.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.lifecycleScope
import com.aurum.musictv.R
import com.aurum.musictv.data.AurumApi
import com.aurum.musictv.data.Song
import com.aurum.musictv.ui.player.PlayerActivity
import com.aurum.musictv.ui.search.SearchActivity
import kotlinx.coroutines.launch

/**
 * TV equivalent of home_screen.dart, deliberately minimal: a couple of
 * horizontal rows, no hero carousel, no ambient-glow animation layer —
 * those are phone-specific polish that costs RAM/CPU we don't have to
 * spare on a 1GB TV box. Leanback's BrowseSupportFragment gives us
 * D-pad row/card navigation, title, and search-affordance for free.
 */
class HomeBrowseFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false
        adapter = rowsAdapter

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

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

        loadRows()
    }

    private fun loadRows() {
        lifecycleScope.launch {
            val sections = AurumApi.homeSections()
            sections.forEach { (title, songs) ->
                val listRowAdapter = ArrayObjectAdapter(SongCardPresenter())
                songs.forEach { listRowAdapter.add(it) }
                val header = HeaderItem(rowsAdapter.size().toLong(), title)
                rowsAdapter.add(ListRow(header, listRowAdapter))
            }
        }
    }
}
