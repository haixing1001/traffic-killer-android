package com.traffickiller.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

data class MiguDynamicTarget(
    val contId: String,
    val rateType: String = MiguPlayUrlSigner.DEFAULT_RATE_TYPE,
    val h265: Boolean = false
)

/**
 * Pure Kotlin helpers for Migu Video's Android plaintext `playurl/v1` gateway:
 * MD5 request signing, `ddCalcu` GSLB token folding, and target URL parsing.
 * Kept free of Android framework dependencies so it runs in fast JVM unit tests.
 */
object MiguPlayUrlSigner {
    const val PLAYURL_ENDPOINT = "https://play.miguvideo.com/playurl/v1/play/playurl"
    const val APP_VERSION = "2600034600"
    const val APP_VERSION_8 = "26000346"
    const val SIGN_SALT_SECRET = "2cac4f2c6c3346a5b34e085725ef7e33migu"
    const val CHANNEL_ID = "26000346-99000-201600010010028"
    const val APP_CODE = "miguvideo_android"
    const val TERMINAL_ID = "android"
    const val DEFAULT_RATE_TYPE = "3"

    private const val DDCALCU_KEYS = "cdabyzwxkl"
    private const val DDCALCU_WORD_0 = 'v'
    private const val DDCALCU_WORD_3 = 'a'
    private const val DDCALCU_THIRD_REPLACE_INDEX = 6

    private val detailPathRegex = Regex("^/(?:m|p)/detail/([0-9]+)(?:/.*)?$")
    private val puDataRegex = Regex("(?:\\?|&)puData=([^&]+)")
    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun parseDynamicTarget(rawUrl: String): MiguDynamicTarget? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        val path = uri.path.orEmpty()
        val query = parseQueryParams(uri.rawQuery)

        val rateType = query["rateType"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_RATE_TYPE
        val h265 = query["h265N"]?.equals("true", ignoreCase = true) == true ||
            query["h265N"] == "1"

        if (host == "play.miguvideo.com" && path.startsWith("/playurl/v1/play/playurl")) {
            val contId = query["contId"]?.trim().orEmpty()
            if (contId.isNotEmpty()) {
                return MiguDynamicTarget(contId = contId, rateType = rateType, h265 = h265)
            }
            return null
        }

        if (host == "m.miguvideo.com" || host == "www.miguvideo.com") {
            val match = detailPathRegex.matchEntire(path) ?: return null
            val contId = match.groupValues[1]
            return MiguDynamicTarget(contId = contId, rateType = rateType, h265 = h265)
        }

        return null
    }

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    fun generateSalt(randomValue: Int = ThreadLocalRandom.current().nextInt(0, 1_000_000)): String =
        String.format(Locale.US, "%06d25", randomValue.coerceIn(0, 999_999))

    fun computeSign(timestampMs: String, contId: String, salt: String): String {
        val inner = md5Hex(timestampMs + contId + APP_VERSION_8)
        return md5Hex(inner + SIGN_SALT_SECRET + salt.take(4))
    }

    fun buildPlayUrlRequestUrl(
        target: MiguDynamicTarget,
        timestampMs: Long = System.currentTimeMillis(),
        salt: String = generateSalt()
    ): String {
        val ts = timestampMs.toString()
        val sign = computeSign(ts, target.contId, salt)
        return buildString {
            append(PLAYURL_ENDPOINT)
            append("?sign=").append(sign)
            append("&rateType=").append(target.rateType)
            append("&contId=").append(target.contId)
            append("&timestamp=").append(ts)
            append("&salt=").append(salt)
            append("&flvEnable=true&super4k=true")
            if (target.h265) {
                append("&h265N=true")
            }
        }
    }

    fun extractPuData(gslbUrl: String): String? =
        puDataRegex.find(gslbUrl)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    fun currentShanghaiDateStr(): String =
        LocalDate.now(shanghaiZone).format(DateTimeFormatter.BASIC_ISO_DATE)

    /**
     * Anonymous Android `ddCalcu` fold over `puData`: folds head/tail pairs inward
     * and injects 4 characters at rounds 1..4 using numeric digit indices into [DDCALCU_KEYS].
     */
    fun computeDdCalcu(
        puData: String,
        programId: String,
        dateStr: String = currentShanghaiDateStr()
    ): String {
        require(puData.length >= 2) { "puData 长度无效" }
        require(dateStr.isNotEmpty() && dateStr[0].isDigit()) { "日期格式无效: $dateStr" }
        require(
            programId.length > DDCALCU_THIRD_REPLACE_INDEX &&
                programId[DDCALCU_THIRD_REPLACE_INDEX].isDigit()
        ) {
            "ProgramID 长度或格式无效: $programId"
        }

        val kDay = DDCALCU_KEYS[dateStr[0].digitToInt()]
        val kPid = DDCALCU_KEYS[programId[DDCALCU_THIRD_REPLACE_INDEX].digitToInt()]
        val n = puData.length / 2
        val sb = StringBuilder(puData.length + 4)
        for (i in 0 until n) {
            sb.append(puData[puData.length - 1 - i])
            sb.append(puData[i])
            when (i) {
                1 -> sb.append(DDCALCU_WORD_0)
                2 -> sb.append(kDay)
                3 -> sb.append(kPid)
                4 -> sb.append(DDCALCU_WORD_3)
            }
        }
        return sb.toString()
    }

    fun appendDdCalcu(rawGslbUrl: String, ddCalcu: String): String {
        val sep = if ('?' in rawGslbUrl) '&' else '?'
        return "$rawGslbUrl${sep}ddCalcu=$ddCalcu&sv=10004&ct=android"
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
            val key = URLDecoder.decode(rawKey, "UTF-8")
            val value = URLDecoder.decode(rawVal, "UTF-8")
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }
}

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
 * Resolves Migu dynamic targets (live TV pIDs or VOD ProgramIDs) directly through
 * `play.miguvideo.com/playurl/v1` + local `ddCalcu` down to concrete `.ts` segments.
 */
class MiguLiveResolver(private val client: OkHttpClient) {

    suspend fun resolve(providerUrl: String): MiguLiveResolution = withContext(Dispatchers.IO) {
        val target = MiguPlayUrlSigner.parseDynamicTarget(providerUrl)
        val landing = if (target != null) {
            fetchLandingPlaylistViaPlayUrl(target)
        } else {
            fetch(providerUrl)
        }

        var playlistUrl = landing.first
        var text = landing.second

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

    private fun fetchLandingPlaylistViaPlayUrl(target: MiguDynamicTarget): Pair<String, String> {
        val playUrl = MiguPlayUrlSigner.buildPlayUrlRequestUrl(target)
        val playUrlHeaders = Headers.Builder()
            .add("AppVersion", MiguPlayUrlSigner.APP_VERSION_8)
            .add("TerminalId", MiguPlayUrlSigner.TERMINAL_ID)
            .add("X-UP-CLIENT-CHANNEL-ID", MiguPlayUrlSigner.CHANNEL_ID)
            .add("appCode", MiguPlayUrlSigner.APP_CODE)
            .build()

        val (_, jsonBody) = fetch(playUrl, playUrlHeaders)
        val json = try {
            JSONObject(jsonBody)
        } catch (e: Exception) {
            throw IOException("playurl 响应不是有效 JSON", e)
        }

        val code = json.optString("code")
        val bodyObj = json.optJSONObject("body")
        val rawGslbUrl = bodyObj?.optJSONObject("urlInfo")?.optString("url").orEmpty()
        if (code != "200" || rawGslbUrl.isBlank()) {
            val rid = json.optString("rid")
            val message = json.optString("message")
            throw IOException(formatPlayUrlError(code, rid, message))
        }

        val ddProgramId = bodyObj?.optJSONObject("content")?.optString("contId")
            ?.takeIf { it.isNotBlank() }
            ?: target.contId
        val puData = MiguPlayUrlSigner.extractPuData(rawGslbUrl)
            ?: throw IOException("gslb 地址缺少 puData 参数")
        val ddCalcu = try {
            MiguPlayUrlSigner.computeDdCalcu(puData, ddProgramId)
        } catch (e: IllegalArgumentException) {
            throw IOException("ddCalcu 计算失败：${e.message}", e)
        }

        val signedGslbUrl = MiguPlayUrlSigner.appendDdCalcu(rawGslbUrl, ddCalcu)
        return fetch(signedGslbUrl)
    }

    private fun formatPlayUrlError(code: String, rid: String, message: String): String = when {
        rid == "WUDALIANSAI_FUFEI_VERSION_UPGRADE" ->
            "频道需付费会员权限（$rid），匿名接口不可用"
        rid == "PROGRAM_OFFLINE" ->
            "节目已下线或 ID 非 ProgramID（$rid）"
        rid == "CHECK_ENCRY_NOT_MATCH" || message.contains("异常") ->
            "咪咕接口频控或鉴权校验未通过（$code/$rid）：${message.ifBlank { "请稍后再试" }}"
        else ->
            "playurl 取址失败（code=$code, rid=$rid）：${message.ifBlank { "未知错误" }}"
    }

    private fun fetch(url: String, extraHeaders: Headers? = null): Pair<String, String> {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", PresetNodes.MIGU_VIDEO_USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-store")
        if (extraHeaders != null) {
            for (i in 0 until extraHeaders.size) {
                builder.header(extraHeaders.name(i), extraHeaders.value(i))
            }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 661) {
                    throw IOException("gslb 鉴权失败（HTTP 661，ddCalcu 或令牌无效）")
                }
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
