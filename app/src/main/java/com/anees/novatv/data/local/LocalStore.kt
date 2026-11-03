package com.anees.novatv.data.local

import android.content.Context
import android.content.SharedPreferences
import com.anees.novatv.data.model.HistoryEntry
import com.anees.novatv.data.model.MediaCard
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Tiny SharedPreferences-backed store: continue-watching history + watchlist. */
/** User preferences (Settings tab). */
data class Settings(
    val audioLang: String = "hi",          // hi, en, ur, or "orig"
    val subtitlesOn: Boolean = false,
    val subtitleSize: Int = 1,             // 0 small, 1 medium, 2 large
    val autoplayNext: Boolean = true,
    val lowCpuMode: Boolean = false,       // tunneled playback
    val matchFrameRate: Boolean = false,
    val volumeBoost: Int = 0               // 0 off, 1 +6dB, 2 +10dB, 3 +15dB
)

object LocalStore {
    private const val MAX_HISTORY = 30
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("nova", Context.MODE_PRIVATE)
    }

    // ── History ───────────────────────────────────────────────────────────────

    fun history(): List<HistoryEntry> = read("history", object : TypeToken<List<HistoryEntry>>() {})

    fun historyFor(card: MediaCard): HistoryEntry? = history().firstOrNull { it.key == "${card.type}:${card.id}" }

    fun saveProgress(entry: HistoryEntry) {
        val list = history().filterNot { it.key == entry.key }.toMutableList()
        // Treat >95% watched movies as finished
        val finished = entry.durationMs > 0 && entry.progress > 0.95f && entry.card.type.name == "MOVIE"
        if (!finished) list.add(0, entry)
        write("history", list.take(MAX_HISTORY))
    }

    fun removeHistory(key: String) = write("history", history().filterNot { it.key == key })

    // ── Watchlist ─────────────────────────────────────────────────────────────

    fun watchlist(): List<MediaCard> = read("watchlist", object : TypeToken<List<MediaCard>>() {})

    fun inWatchlist(card: MediaCard) = watchlist().any { it.id == card.id && it.type == card.type }

    /** Toggles and returns the new state. */
    fun toggleWatchlist(card: MediaCard): Boolean {
        val list = watchlist().toMutableList()
        val existing = list.indexOfFirst { it.id == card.id && it.type == card.type }
        return if (existing >= 0) { list.removeAt(existing); write("watchlist", list); false }
        else { list.add(0, card); write("watchlist", list); true }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun settings(): Settings = prefs.getString("settings", null)
        ?.let { runCatching { gson.fromJson(it, Settings::class.java) }.getOrNull() } ?: Settings()

    fun saveSettings(s: Settings) = prefs.edit().putString("settings", gson.toJson(s)).apply()

    fun clearHistory() = prefs.edit().remove("history").apply()

    // ── Per-title audio language (picked in the player, reused next episode) ─

    fun audioFor(key: String): String? = prefs.getString("audio_$key", null)
    fun setAudioFor(key: String, lang: String) = prefs.edit().putString("audio_$key", lang).apply()

    /** Audio languages actually found in a title's stream (learned when it plays). */
    fun audioLangs(key: String): List<String>? = prefs.getString("langs_$key", null)?.split(",")?.filter { it.isNotBlank() }
    fun setAudioLangs(key: String, langs: List<String>) = prefs.edit().putString("langs_$key", langs.joinToString(",")).apply()

    /** Remembers "no source had a Hindi dub for this title", so the dub search isn't repeated. */
    fun noDub(key: String, lang: String) = prefs.getBoolean("nodub_${key}_$lang", false)
    fun setNoDub(key: String, lang: String) = prefs.edit().putBoolean("nodub_${key}_$lang", true).apply()

    // ── Recent searches ───────────────────────────────────────────────────────

    fun recentSearches(): List<String> = read("recent_q", object : TypeToken<List<String>>() {})

    fun addRecentSearch(q: String) {
        val t = q.trim().takeIf { it.length >= 2 } ?: return
        write("recent_q", (listOf(t) + recentSearches().filterNot { it.equals(t, true) }).take(10))
    }

    // ── Provider health ───────────────────────────────────────────────────────

    /** +3 per success, -1 per failure, clamped; VidSrc starts ahead because it's the most reliable. */
    fun providerScore(name: String): Int = prefs.getInt("prov_$name", if (name == "VidSrc") 10 else 0)

    fun reportProvider(name: String, ok: Boolean) =
        prefs.edit().putInt("prov_$name", (providerScore(name) + if (ok) 3 else -1).coerceIn(-10, 20)).apply()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun <T> read(key: String, type: TypeToken<List<T>>): List<T> =
        prefs.getString(key, null)?.let { runCatching { gson.fromJson<List<T>>(it, type.type) }.getOrNull() } ?: emptyList()

    private fun write(key: String, value: Any) = prefs.edit().putString(key, gson.toJson(value)).apply()
}
