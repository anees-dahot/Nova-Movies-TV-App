package com.anees.novatv.ui.search

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anees.novatv.MainActivity
import com.anees.novatv.R
import com.anees.novatv.data.local.LocalStore
import com.anees.novatv.data.model.MediaCard
import com.anees.novatv.data.repository.MediaRepository
import com.anees.novatv.ui.common.QuickActions
import com.anees.novatv.ui.common.TabPage
import com.anees.novatv.ui.home.HomeViewModel
import com.anees.novatv.ui.home.MediaCardAdapter
import kotlinx.coroutines.*

/**
 * Instant search: built-in keyboard, results update as you type (250ms), repeat queries come
 * from memory, and the empty screen already shows Trending + recent searches.
 */
class SearchFragment : Fragment(), TabPage {

    private val repo = MediaRepository()
    private val home: HomeViewModel by activityViewModels()
    private var searchJob: Job? = null
    private var query = ""
    private lateinit var queryView: TextView
    private lateinit var results: RecyclerView
    private lateinit var title: TextView
    private lateinit var recent: LinearLayout
    private var firstKey: View? = null

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) setQuery(text, debounce = 0)
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_search, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        queryView = view.findViewById(R.id.query)
        results = view.findViewById(R.id.search_results)
        title = view.findViewById(R.id.results_title)
        recent = view.findViewById(R.id.recent)
        val dm = resources.displayMetrics
        val cols = ((dm.widthPixels / dm.density - 300 - 64) / 148f).toInt().coerceAtLeast(3)
        results.layoutManager = GridLayoutManager(requireContext(), cols)
        results.itemAnimator = null
        results.setHasFixedSize(true)

        buildKeyboard(view.findViewById(R.id.keyboard))
        home.trending.observe(viewLifecycleOwner) { if (query.isEmpty()) showCards("Trending Now", it) }
        renderRecent()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) renderRecent()
    }

    override fun scrollToTop(): Boolean { results.scrollToPosition(0); return false }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    private fun buildKeyboard(grid: GridLayout) {
        val dp = resources.displayMetrics.density
        val keys = ('a'..'z').map { it.toString() } + ('1'..'9').map { it.toString() } + "0"
        fun key(label: String, span: Int = 1, onClick: () -> Unit): TextView = TextView(requireContext()).apply {
            text = label
            textSize = if (label.length == 1) 17f else 13f
            gravity = Gravity.CENTER
            setTextColor(requireContext().getColorStateList(R.color.tab_text))
            setBackgroundResource(R.drawable.key_bg)
            isFocusable = true
            setOnClickListener { onClick() }
            grid.addView(this, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, span)
            ).apply { width = ((38 * span + 4 * (span - 1)) * dp).toInt(); height = (38 * dp).toInt(); setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt()) })
        }
        firstKey = key("🎤  Voice Search", 6) { startVoice() }
        keys.forEach { k -> key(k) { setQuery(query + k) } }
        key("space", 2) { if (query.isNotEmpty() && !query.endsWith(" ")) setQuery("$query ") }
        key("⌫", 2) { if (query.isNotEmpty()) setQuery(query.dropLast(1)) }.setOnLongClickListener { setQuery(""); true }
        key("clear", 2) { setQuery("") }
    }

    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie, series or anime")
        runCatching { voice.launch(intent) }
            .onFailure { Toast.makeText(requireContext(), "Voice search isn't available on this box", Toast.LENGTH_SHORT).show() }
    }

    // ── Searching ─────────────────────────────────────────────────────────────

    private fun setQuery(q: String, debounce: Long = 250) {
        query = q
        queryView.text = q
        searchJob?.cancel()
        val t = q.trim()
        if (t.length < 2) {
            showCards("Trending Now", home.trending.value ?: emptyList())
            return
        }
        cache[t.lowercase()]?.let { showCards("Results", it); return }
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(debounce)
            title.text = "Searching…"
            val cards = repo.search(t)
            cache[t.lowercase()] = cards
            showCards(if (cards.isEmpty()) "No results for “$t”" else "Results", cards)
        }
    }

    private fun showCards(header: String, cards: List<MediaCard>) {
        title.text = header
        results.adapter = MediaCardAdapter(cards,
            onLongClick = { _, c -> QuickActions.show(requireContext(), c) },
            onClick = { _, c ->
                if (query.isNotBlank()) { LocalStore.addRecentSearch(query); renderRecent() }
                (activity as? MainActivity)?.openDetail(c)
            })
    }

    private fun renderRecent() {
        if (!::recent.isInitialized) return
        recent.removeAllViews()
        val dp = resources.displayMetrics.density
        LocalStore.recentSearches().forEach { q ->
            recent.addView(TextView(requireContext()).apply {
                text = q
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(requireContext().getColorStateList(R.color.tab_text))
                setBackgroundResource(R.drawable.chip_bg)
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                isFocusable = true
                setOnClickListener { setQuery(q, debounce = 0) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (30 * dp).toInt()).apply { marginStart = (8 * dp).toInt() })
        }
    }

    companion object {
        /** Survives tab switches: repeat searches are instant. */
        private val cache = object : LinkedHashMap<String, List<MediaCard>>(32, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<MediaCard>>?) = size > 40
        }
    }
}
