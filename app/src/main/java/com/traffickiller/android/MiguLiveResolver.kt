package com.traffickiller.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Pure helpers for parsing Migu HLS playlists so they can be unit tested
 * without any network access.
 */
object MiguHlsParser {
    fun isMasterPlaylist(text: String): Boolean =
        text.lineSequence().any { it.trim().startsWith("#EXT-X-STREAM-INF") }

    /** First non-comment line, i.e. the variant playlist path of a master playlist. */
    fun firstVariantUri(text: String): String? =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    /** All non-comment lines, i.e. the media segment paths of a media playlist. */
    fun segmentUris(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

    fun resolveAgainst(baseUrl: String, relative: String): String {
        val trimmed = relative.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return URI(baseUrl).resolve(trimmed).toString()
    }
}

data class MiguLiveResolution(
    val playlistUrl: String,
    val segmentUrls: List<String>,
    val resolvedAtMs: Long
)

/**
 * Resolves the rotating Migu live entry (`migu.php?id=...`) all the way down to
 * concrete `.ts` segment URLs, because the entry itself only returns a playlist.
 */
class MiguLiveResolver(private val client: OkHttpClient) {

    suspend fun resolve(providerUrl: String): MiguLiveResolution = withContext(Dispatchers.IO) {
        val provider = fetch(providerUrl)
        var playlistUrl = provider.first
        var text = provider.second

        if (MiguHlsParser.isMasterPlaylist(text)) {
            val variant = MiguHlsParser.firstVariantUri(text)
                ?: throw IOException("主索引中没有可用的清晰度")
            val variantUrl = MiguHlsParser.resolveAgainst(playlistUrl, variant)
            val variantResp = fetch(variantUrl)
            playlistUrl = variantResp.first
            text = variantResp.second
        }

        val segments = MiguHlsParser.segmentUris(text)
            .map { MiguHlsParser.resolveAgainst(playlistUrl, it) }
            .take(SEGMENT_CACHE_LIMIT)
        if (segments.isEmpty()) {
            throw IOException("未解析到 ts 分片")
        }

        MiguLiveResolution(
            playlistUrl = playlistUrl,
            segmentUrls = segments,
            resolvedAtMs = System.currentTimeMillis()
        )
    }

    private fun fetch(url: String): Pair<String, String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", PresetNodes.MIGU_VIDEO_USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-store")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("空响应体")
            val text = body.string()
            if (text.isBlank()) {
                throw IOException("播放列表为空")
            }
            // `request.url` is the final URL after any redirects.
            return response.request.url.toString() to text
        }
    }

    companion object {
        /** Only the first few segments are cached and reused until they stop working. */
        const val SEGMENT_CACHE_LIMIT = 5

        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Persists the last successfully resolved live address so a run can start
 * instantly on a cached playlist while a fresh one is being fetched.
 */
class MiguLinkCache(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): MiguLiveResolution? {
        val playlistUrl = prefs.getString(KEY_PLAYLIST, null) ?: return null
        val segmentsRaw = prefs.getString(KEY_SEGMENTS, null) ?: return null
        val resolvedAt = prefs.getLong(KEY_RESOLVED_AT, 0L)
        val segments = segmentsRaw.split('\n').filter { it.isNotBlank() }
        if (segments.isEmpty() || resolvedAt <= 0L) return null
        return MiguLiveResolution(playlistUrl, segments, resolvedAt)
    }

    fun loadFor(providerUrl: String): MiguLiveResolution? {
        val cachedProvider = prefs.getString(KEY_PROVIDER, null)
        if (cachedProvider != providerUrl) return null
        return load()
    }

    fun save(providerUrl: String, resolution: MiguLiveResolution) {
        prefs.edit()
            .putString(KEY_PROVIDER, providerUrl)
            .putString(KEY_PLAYLIST, resolution.playlistUrl)
            .putString(KEY_SEGMENTS, resolution.segmentUrls.joinToString("\n"))
            .putLong(KEY_RESOLVED_AT, resolution.resolvedAtMs)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "migu_live_cache"
        const val KEY_PROVIDER = "provider"
        const val KEY_PLAYLIST = "playlist"
        const val KEY_SEGMENTS = "segments"
        const val KEY_RESOLVED_AT = "resolved_at"
    }
}