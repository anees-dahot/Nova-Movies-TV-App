package com.anees.novatv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.anees.novatv.data.model.MediaCard
import com.anees.novatv.ui.common.TabPage
import com.anees.novatv.ui.detail.DetailFragment
import com.anees.novatv.ui.home.HomeFragment
import com.anees.novatv.ui.search.SearchFragment
import com.anees.novatv.ui.settings.SettingsFragment
import com.anees.novatv.ui.watchlist.WatchlistFragment

class MainActivity : AppCompatActivity() {

    private data class Tab(val label: String?, val icon: Int?, val create: () -> Fragment)

    // Search first: it's used constantly. Icons for Search/Settings, words for the rest (Apple TV style).
    private val tabs = listOf(
        Tab(null, R.drawable.ic_search) { SearchFragment() },
        Tab("Home", null) { HomeFragment.newInstance(0) },
        Tab("Movies", null) { HomeFragment.newInstance(1) },
        Tab("Series", null) { HomeFragment.newInstance(2) },
        Tab("Anime", null) { HomeFragment.newInstance(3) },
        Tab("My List", null) { WatchlistFragment() },
        Tab(null, R.drawable.ic_settings) { SettingsFragment() },
    )
    private val HOME = 1

    private val fragments = arrayOfNulls<Fragment>(tabs.size)
    private val tabViews = mutableListOf<TextView>()
    private var current = -1
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSwitch: Runnable? = null
    private lateinit var tabBar: View
    private lateinit var mainContent: View
    private var tabBarShown = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tabBar = findViewById(R.id.tab_bar)
        mainContent = findViewById(R.id.main_content)

        val bar = findViewById<LinearLayout>(R.id.tab_bar)
        val dp = resources.displayMetrics.density
        tabs.forEachIndexed { i, t ->
            val tv = TextView(this).apply {
                text = t.label ?: ""
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(getColorStateList(R.color.tab_text))
                setBackgroundResource(R.drawable.tab_bg)
                val hp = if (t.label == null) 12 else 18
                setPadding((hp * dp).toInt(), 0, (hp * dp).toInt(), 0)
                t.icon?.let {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(it, 0, 0, 0)
                    compoundDrawableTintList = getColorStateList(R.color.tab_text)
                }
                isFocusable = true
                setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(140).start()
                    if (has) scheduleSwitch(i)
                }
                setOnClickListener { switchTab(i) }
                if (i == 0) setOnLongClickListener { openVoiceSearch(); true }   // hold OK on 🔍 = voice
            }
            bar.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (32 * dp).toInt()).apply {
                marginStart = (2 * dp).toInt(); marginEnd = (2 * dp).toInt()
            })
            tabViews += tv
        }

        // Restore tabs the FragmentManager kept after process death
        tabs.indices.forEach { i -> fragments[i] = supportFragmentManager.findFragmentByTag("tab_$i") }
        val visible = fragments.indexOfFirst { it != null && !it.isHidden }
        switchTab(if (visible >= 0) visible else HOME)

        supportFragmentManager.addOnBackStackChangedListener {
            val detailOpen = supportFragmentManager.backStackEntryCount > 0
            mainContent.visibility = if (detailOpen) View.INVISIBLE else View.VISIBLE
            tabBar.visibility = if (detailOpen || !tabBarShown) View.INVISIBLE else View.VISIBLE
        }
    }

    /** Sliding focus across the bar shouldn't build every tab it passes: wait until it rests. */
    private fun scheduleSwitch(i: Int) {
        pendingSwitch?.let { handler.removeCallbacks(it) }
        pendingSwitch = Runnable { switchTab(i) }.also { handler.postDelayed(it, 250) }
    }

    fun switchTab(index: Int) {
        if (index == current) return
        val tx = supportFragmentManager.beginTransaction().setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        fragments.getOrNull(current)?.let { tx.hide(it) }
        // Tabs are created the first time they're opened, not all at startup
        val target = fragments[index] ?: tabs[index].create().also {
            fragments[index] = it
            tx.add(R.id.fragment_container, it, "tab_$index")
        }
        tx.show(target).commit()
        tabViews.getOrNull(current)?.isSelected = false
        tabViews[index].isSelected = true
        current = index
        setTabBarShown(true)
    }

    /** Apple TV hides the tab bar while you browse rows and brings it back at the top. */
    fun setTabBarShown(show: Boolean) {
        if (show == tabBarShown) return
        tabBarShown = show
        if (show) {
            tabBar.visibility = View.VISIBLE
            tabBar.animate().translationY(0f).alpha(1f).setDuration(200).withEndAction(null).start()
        } else {
            tabBar.animate().translationY(-tabBar.height * 1.6f).alpha(0f).setDuration(200)
                .withEndAction { if (!tabBarShown) tabBar.visibility = View.INVISIBLE }.start()
        }
    }

    fun focusTabBar() {
        setTabBarShown(true)
        tabViews[current].requestFocus()
    }

    /** Remote mic / search key, or holding OK on 🔍: jump to Search and start listening. */
    fun openVoiceSearch() {
        if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        pendingSwitch?.let { handler.removeCallbacks(it) }
        switchTab(0)
        supportFragmentManager.executePendingTransactions()
        (fragments[0] as? SearchFragment)?.startVoice()
    }

    override fun onSearchRequested(): Boolean { openVoiceSearch(); return true }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST -> { openVoiceSearch(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    /** Opens a detail screen above the tabs; Back returns to the same tab, row and card. */
    fun openDetail(card: MediaCard) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.detail_in, android.R.anim.fade_out, android.R.anim.fade_in, R.anim.detail_out)
            .add(R.id.detail_container, DetailFragment.newInstance(card))
            .addToBackStack(null)
            .commit()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
            // Apple TV: Back first returns to the top / tab bar, only then leaves the app
            tabViews.none { it.hasFocus() } -> {
                (fragments[current] as? TabPage)?.scrollToTop()
                focusTabBar()
            }
            current != HOME -> { switchTab(HOME); focusTabBar() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}
