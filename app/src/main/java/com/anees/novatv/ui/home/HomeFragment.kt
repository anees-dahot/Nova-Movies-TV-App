package com.anees.novatv.ui.home

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.anees.novatv.MainActivity
import com.anees.novatv.R
import com.anees.novatv.data.local.LocalStore
import com.anees.novatv.data.model.LangTags
import com.anees.novatv.data.model.MediaCard
import com.anees.novatv.data.model.MediaType
import com.anees.novatv.data.repository.HomeData
import com.anees.novatv.ui.common.QuickActions
import com.anees.novatv.ui.common.TabPage
import com.anees.novatv.ui.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeFragment : Fragment(), RowCallbacks, TabPage {

    private val viewModel: HomeViewModel by activityViewModels()
    private var tabIndex = 0

    private lateinit var backdrop: ImageView
    private lateinit var rows: RecyclerView
    private val adapter = RowsAdapter(this)
    private var heroView: View? = null

    private var featured: List<MediaCard> = emptyList()
    private var heroCard: MediaCard? = null
    private var backdropUrl: String? = null
    private var backdropJob: Job? = null
    private var rotateJob: Job? = null
    private var lastCardFocus = 0L
    private var inRows = false
    private var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private lateinit var infoPanel: View
    private var pinTop = 0
    private val repo = com.anees.novatv.data.repository.MediaRepository()
    private var infoJob: Job? = null
    private var topPrefetched = false

    companion object {
        fun newInstance(tabIndex: Int) = HomeFragment().apply { arguments = Bundle().apply { putInt("tab", tabIndex) } }
        private val DIM = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.62f, 0.62f, 0.62f, 1f) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabIndex = arguments?.getInt("tab") ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        backdrop = view.findViewById(R.id.backdrop)
        backdrop.colorFilter = DIM
        rows = view.findViewById(R.id.rows)
        infoPanel = view.findViewById(R.id.info_panel)
        // Focused row sits ~46% down the screen; the space above shows the focused title's details
        pinTop = (resources.displayMetrics.heightPixels * 0.46f).toInt()
        rows.layoutManager = PinnedRowsLayoutManager(requireContext(), pinTop)
        rows.adapter = adapter
        rows.itemAnimator = null
        rows.setItemViewCacheSize(4)

        // Skeleton until data (or the disk cache) arrives
        adapter.submitList(listOf(HomeItem.Hero) + (1..3).map { HomeItem.Skeleton("sk$it", wide = it == 1 && tabIndex == 0) })

        viewModel.homeData.observe(viewLifecycleOwner) { render() }
        viewModel.genreRows.observe(viewLifecycleOwner) { if (tabIndex == 1 || tabIndex == 2) render() }
        viewModel.trending.observe(viewLifecycleOwner) { if (tabIndex == 0) render() }
        viewModel.load()
        if (tabIndex == 1) viewModel.loadGenres(MediaType.MOVIE)
        if (tabIndex == 2) viewModel.loadGenres(MediaType.SERIES)

        // Hide the tab bar and dim the backdrop while browsing rows; restore at the hero
        focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (isHidden || newFocus == null) return@OnGlobalFocusChangeListener
            val item = rows.findContainingItemView(newFocus) ?: return@OnGlobalFocusChangeListener
            val nowInRows = rows.getChildAdapterPosition(item) > 0
            if (nowInRows != inRows) setInRows(nowInRows)
        }
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    }

    override fun onDestroyView() {
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusListener)
        heroView = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) { render(); startRotation() } else rotateJob?.cancel()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) { render(); startRotation() }   // Continue Watching may have changed
    }

    override fun onPause() {
        super.onPause()
        rotateJob?.cancel()
    }

    override fun scrollToTop(): Boolean {
        val moved = inRows
        setInRows(false)
        rows.scrollToPosition(0)
        return moved
    }

    /** Rows mode: hide tab bar, show the details panel, clip rows scrolled up under it. */
    private fun setInRows(on: Boolean) {
        inRows = on
        (activity as? MainActivity)?.setTabBarShown(!on)
        backdrop.animate().alpha(if (on) 0.7f else 1f).setDuration(220).start()
        rows.setPadding(0, if (on) pinTop - (6 * resources.displayMetrics.density).toInt() else 0, 0, rows.paddingBottom)
        if (on) { infoPanel.visibility = View.VISIBLE; infoPanel.animate().alpha(1f).setDuration(200).start() }
        else infoPanel.animate().alpha(0f).setDuration(150).withEndAction { if (!inRows) infoPanel.visibility = View.INVISIBLE }.start()
    }

    private fun setInfo(card: MediaCard) {
        val v = view ?: return
        v.findViewById<TextView>(R.id.info_kind).text = kindLabel(card)
        v.findViewById<TextView>(R.id.info_title).text = card.title
        v.findViewById<TextView>(R.id.info_overview).text = card.overview
        val meta = v.findViewById<TextView>(R.id.info_meta)
        meta.text = metaLine(card, null)
        infoJob?.cancel()
        infoJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(450)   // only fetch runtime / seasons once focus rests
            repo.extraInfo(card)?.let { meta.text = metaLine(card, it) }
        }
    }

    private fun kindLabel(card: MediaCard) = when (card.type) { MediaType.MOVIE -> "MOVIE"; MediaType.SERIES -> "SERIES"; MediaType.ANIME -> "ANIME" }

    private fun metaLine(card: MediaCard, extra: String?) = listOfNotNull(
        card.year.ifEmpty { null },
        card.rating.takeIf { it > 0 }?.let { "★ ${"%.1f".format(it)}" },
        extra,
        card.genres.ifEmpty { null },
        LangTags.label(card, LocalStore.audioLangs("${card.type}:${card.id}"))?.let { "🔊 $it" }
    ).joinToString("  ·  ")

    // ── Rows ──────────────────────────────────────────────────────────────────

    private fun sections(d: HomeData): List<Pair<String, List<MediaCard>>> {
        val genres = viewModel.genreRows.value ?: emptyMap()
        return when (tabIndex) {
            1 -> listOf("Trending Movies" to d.trendingMovies, "Bollywood" to d.hindiMovies, "In Theaters" to d.nowPlaying, "Top Rated" to d.topRatedMovies) +
                    (genres[MediaType.MOVIE] ?: emptyList())
            2 -> listOf("Trending Series" to d.trendingSeries, "Popular Series" to d.popularSeries,
                        "Hindi Series" to d.hindiSeries, "Pakistani Dramas" to d.pakDramas, "Top Rated" to d.topRatedSeries) +
                    (genres[MediaType.SERIES] ?: emptyList())
            3 -> listOf("Trending Anime" to d.trendingAnime, "Most Popular" to d.popularAnime, "Top Rated" to d.topAnime)
            else -> listOf(
                "Trending Movies" to d.trendingMovies, "Popular Series" to d.popularSeries,
                "Bollywood" to d.hindiMovies, "Trending Series" to d.trendingSeries,
                "Trending Anime" to d.trendingAnime, "Hindi Series" to d.hindiSeries,
                "Top Rated Movies" to d.topRatedMovies, "Top Rated Series" to d.topRatedSeries, "Top Anime" to d.topAnime
            )
        }.filter { it.second.isNotEmpty() }
    }

    /** Builds the row list and lets DiffUtil touch only what changed (e.g. Continue Watching). */
    private fun render() {
        val data = viewModel.homeData.value ?: return
        if (view == null) return
        val content = sections(data)
        if (content.isEmpty()) return
        featured = content.first().second.filter { it.backdropUrl != null }.take(6)

        val items = mutableListOf<HomeItem>(HomeItem.Hero)
        if (tabIndex == 0) {
            val history = LocalStore.history()
            if (history.isNotEmpty()) items += HomeItem.Row("cw", "Continue Watching", history.map { it.card }, wide = true,
                progress = history.map { it.progress },
                subtitles = history.map { h -> if (h.card.type == MediaType.MOVIE) remaining(h.durationMs - h.positionMs) else "S${h.season} · E${h.episode}" })
            val list = LocalStore.watchlist()
            if (list.isNotEmpty()) items += HomeItem.Row("mylist", "My List", list)
            viewModel.trending.value?.take(10)?.takeIf { it.size >= 5 }?.let {
                items += HomeItem.Row("top10", "Top 10 Today", it, ranked = true)
            }
        }
        content.forEach { (title, cards) -> items += HomeItem.Row(title, title, cards) }

        // Data can arrive while the user is browsing (fresh rows, Top 10): keep focus on the same card
        val focusedItem = view?.findFocus()?.let { rows.findContainingItemView(it) }
        val focusedRowKey = focusedItem?.let { (adapter.currentList.getOrNull(rows.getChildAdapterPosition(it)) as? HomeItem.Row)?.key }
        val focusedCard = focusedItem?.findViewById<RecyclerView>(R.id.row_list)?.let { list ->
            list.findFocus()?.let { list.findContainingItemView(it) }?.let { list.getChildAdapterPosition(it) }
        }
        adapter.submitList(items) {
            if (focusedRowKey == null || focusedCard == null || focusedCard < 0) return@submitList
            val pos = adapter.currentList.indexOfFirst { it.key == focusedRowKey }
            if (pos < 0) return@submitList
            val stillThere = view?.findFocus()?.let { rows.findContainingItemView(it) }?.let { rows.getChildAdapterPosition(it) } == pos
            if (!stillThere) rows.post {
                rows.findViewHolderForAdapterPosition(pos)?.itemView?.findViewById<RecyclerView>(R.id.row_list)
                    ?.findViewHolderForAdapterPosition(focusedCard)?.itemView?.requestFocus()
            }
        }
        if (heroCard == null) featured.firstOrNull()?.let { setHero(it) }
        // The most likely next press: resume the top Continue Watching item. Ready it after Home settles.
        if (tabIndex == 0 && !topPrefetched) LocalStore.history().firstOrNull()?.let { h ->
            topPrefetched = true
            com.anees.novatv.ui.player.Prefetcher.request(requireContext(), h.card, h.season, h.episode, delayMs = 5000)
        }
    }

    private fun remaining(ms: Long) = "${(ms / 60_000).coerceAtLeast(1)} min left"

    override fun onCardClick(row: HomeItem.Row, index: Int, card: MediaCard) {
        if (row.key == "cw") {
            val h = LocalStore.history().getOrNull(index) ?: return
            PlayerActivity.launch(requireContext(), h.card, h.season, h.episode)
        } else (activity as? MainActivity)?.openDetail(card)
    }

    override fun onCardLongClick(row: HomeItem.Row, index: Int, card: MediaCard) =
        QuickActions.show(requireContext(), card) { render() }

    // ── Hero / backdrop (Apple TV: background follows focus) ──────────────────

    override fun bindHero(view: View) {
        heroView = view
        view.findViewById<Button>(R.id.btn_play).setOnClickListener {
            heroCard?.let { c ->
                val h = LocalStore.historyFor(c)
                if (h != null) PlayerActivity.launch(requireContext(), c, h.season, h.episode)
                else if (c.type == MediaType.MOVIE) PlayerActivity.launch(requireContext(), c, 0, 0)
                else (activity as? MainActivity)?.openDetail(c)
            }
        }
        view.findViewById<Button>(R.id.btn_info).setOnClickListener { heroCard?.let { (activity as? MainActivity)?.openDetail(it) } }
        view.findViewById<Button>(R.id.btn_watchlist).setOnClickListener {
            heroCard?.let { LocalStore.toggleWatchlist(it); updateHeroText(it, animate = false); render() }
        }
        heroCard?.let { updateHeroText(it, animate = false) }
    }

    override fun onCardFocused(card: MediaCard) {
        lastCardFocus = System.currentTimeMillis()
        setInfo(card)
        // Continue Watching card resting in focus ~1s: get its stream ready
        LocalStore.historyFor(card)?.takeIf { tabIndex == 0 }?.let { h ->
            com.anees.novatv.ui.player.Prefetcher.request(requireContext(), h.card, h.season, h.episode, delayMs = 1000)
        }
        backdropJob?.cancel()
        backdropJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(350) // only when focus rests: fast D-pad scrolling never decodes images
            setHero(card)
        }
    }

    private fun setHero(card: MediaCard) {
        heroCard = card
        val url = card.backdropUrl ?: card.posterUrl
        if (url != backdropUrl) {
            backdropUrl = url
            backdrop.load(url) { crossfade(400) }
        }
        updateHeroText(card, animate = true)
    }

    private fun updateHeroText(card: MediaCard, animate: Boolean) {
        val v = heroView ?: return
        v.findViewById<TextView>(R.id.hero_kind).text = kindLabel(card)
        v.findViewById<TextView>(R.id.hero_title).text = card.title
        v.findViewById<TextView>(R.id.hero_meta).text = metaLine(card, null)
        v.findViewById<TextView>(R.id.hero_overview).text = card.overview
        val h = LocalStore.historyFor(card)
        v.findViewById<Button>(R.id.btn_play).text = if (h != null) "▶  Resume" else "▶  Play"
        v.findViewById<Button>(R.id.btn_watchlist).text = if (LocalStore.inWatchlist(card)) "✓" else "+"
        if (animate && !inRows) {
            val text = v.findViewById<View>(R.id.hero_title).parent as View
            text.alpha = 0.3f
            text.animate().alpha(1f).setDuration(220).start()
        }
    }

    /** Rotate the featured hero every 8s while the user isn't browsing. */
    private fun startRotation() {
        rotateJob?.cancel()
        if (featured.size < 2) return
        rotateJob = viewLifecycleOwner.lifecycleScope.launch {
            var i = featured.indexOf(heroCard).coerceAtLeast(0)
            while (isActive) {
                delay(8000)
                if (inRows || System.currentTimeMillis() - lastCardFocus < 15_000) continue
                i = (i + 1) % featured.size
                setHero(featured[i])
            }
        }
    }
}
