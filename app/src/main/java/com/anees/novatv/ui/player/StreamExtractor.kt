package com.anees.novatv.ui.player

import com.anees.novatv.data.api.RetrofitClient
import com.anees.novatv.data.model.MediaCard
import com.anees.novatv.data.model.MediaType
import com.anees.novatv.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Builds the ordered list of embed pages for a title. Order comes from a headless test
 * (Nov 2026): vidsrc.to and vidlink returned real streams; the rest are fallbacks.
 */
object StreamExtractor {

    /** [timeoutMs]: slow TV boxes need ~20-25s for vidsrc's page chain on a cold WebView. */
    data class Provider(val name: String, val movie: (Int) -> String, val tv: (Int, Int, Int) -> String,
                        val timeoutMs: Long = 15_000, val multiAudio: Boolean = false)

    /** What to ask providers for: always a TMDB id, movie or tv episode. */
    data class Target(val tmdbId: Int, val isMovie: Boolean, val season: Int, val episode: Int, val malId: Int? = null)

    val PROVIDERS = listOf(
        // Nov 2026 test: one HLS stream with many dubs (e.g. Inception: English, Hindi, Telugu, Arabic…), found in ~3s
        Provider("VidLux", { "https://vidlux.xyz/embed/movie/$it" }, { id, s, e -> "https://vidlux.xyz/embed/tv/$id/$s/$e" }, 20_000, multiAudio = true),
        Provider("VidSrc", { "https://vidsrc.to/embed/movie/$it" }, { id, s, e -> "https://vidsrc.to/embed/tv/$id/$s/$e" }, 35_000),
        Provider("VidLink", { "https://vidlink.pro/movie/$it?autoplay=true" }, { id, s, e -> "https://vidlink.pro/tv/$id/$s/$e?autoplay=true" }),
        Provider("VidSrc CC", { "https://vidsrc.cc/v2/embed/movie/$it?autoPlay=true" }, { id, s, e -> "https://vidsrc.cc/v2/embed/tv/$id/$s/$e?autoPlay=true" }),
        Provider("Videasy", { "https://player.videasy.net/movie/$it" }, { id, s, e -> "https://player.videasy.net/tv/$id/$s/$e" }),
        Provider("VidFast", { "https://vidfast.pro/movie/$it?autoPlay=true" }, { id, s, e -> "https://vidfast.pro/tv/$id/$s/$e?autoPlay=true" }),
    )

    /** Providers that worked recently go first; ones that keep failing sink to the end. */
    fun ordered(): List<Provider> {
        // Wanting a dub (not "Original") puts multi-audio sources first, whatever their score
        val wantsDub = com.anees.novatv.data.local.LocalStore.settings().audioLang != "orig"
        return PROVIDERS.sortedWith(
            compareByDescending<Provider> { wantsDub && it.multiAudio }
                .thenByDescending { com.anees.novatv.data.local.LocalStore.providerScore(it.name) }
        )
    }

    /** Sniffed streams stay valid for hours; reuse them for retries, resume and source switches. */
    private val cache = HashMap<String, Pair<Long, WebStreamSniffer.Sniffed>>()
    fun cached(p: Provider, t: Target): WebStreamSniffer.Sniffed? =
        cache[embedUrl(p, t)]?.takeIf { System.currentTimeMillis() - it.first < 60 * 60_000 }?.second
    fun remember(p: Provider, t: Target, s: WebStreamSniffer.Sniffed) { cache[embedUrl(p, t)] = System.currentTimeMillis() to s }
    fun forget(p: Provider, t: Target) { cache.remove(embedUrl(p, t)) }

    /**
     * Some providers wrap every playlist/segment in a slow proxy (`…/api/robot-proxy?url=<real>`).
     * VidLux's proxy managed ~28 KB/s vs ~490 KB/s from the origin (Nov 2026), so go direct.
     */
    fun unwrapProxy(uri: android.net.Uri): android.net.Uri {
        if (uri.path?.contains("robot-proxy") != true) return uri
        return uri.getQueryParameter("url")?.let { android.net.Uri.parse(it) } ?: uri
    }

    fun embedUrl(p: Provider, t: Target) = if (t.isMovie) p.movie(t.tmdbId) else p.tv(t.tmdbId, t.season, t.episode)

    /** Movies/series map 1:1; anime goes AniList → TMDB via ani.zip (season/episode from TVDB numbering). */
    suspend fun resolveTarget(card: MediaCard, season: Int, episode: Int, repo: MediaRepository): Target? = when (card.type) {
        MediaType.MOVIE -> Target(card.tmdbId ?: card.id, true, 0, 0)
        MediaType.SERIES -> Target(card.tmdbId ?: card.id, false, season.coerceAtLeast(1), episode.coerceAtLeast(1))
        MediaType.ANIME -> {
            val zip = repo.animeMapping(card.aniListId ?: card.id)
            val tmdb = zip?.mappings?.tmdbId?.toIntOrNull()
            if (tmdb == null) null
            else if (zip.mappings.type == "MOVIE") Target(tmdb, true, 0, 0, zip.mappings.malId)
            else {
                val ep = zip.episodes?.get(episode.toString())
                Target(tmdb, false, ep?.seasonNumber ?: 1, ep?.episodeNumber ?: episode, zip.mappings.malId)
            }
        }
    }

    /** AniSkip opening interval in ms for an anime episode, or null. */
    suspend fun animeIntro(malId: Int, episode: Int): LongRange? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode?types[]=op&episodeLength=0"
            RetrofitClient.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val results = JSONObject(r.body?.string() ?: return@use null).optJSONArray("results") ?: return@use null
                if (results.length() == 0) return@use null
                val iv = results.getJSONObject(0).getJSONObject("interval")
                (iv.getDouble("startTime") * 1000).toLong()..(iv.getDouble("endTime") * 1000).toLong()
            }
        }.getOrNull()
    }
}
