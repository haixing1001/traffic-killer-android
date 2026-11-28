package com.traffickiller.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TrafficForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracker = TrafficStatsTracker()
    private lateinit var notificationManager: NotificationManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var activeRunJob: Job? = null
    private var activeClient: OkHttpClient? = null
    private var roundStartMs: Long = 0L
    @Volatile
    private var lastSampleBytes: Long = 0L
    @Volatile
    private var lastSampleTime: Long = 0L
    private var currentLimitBytes: Long = 0L
    private var currentNetworkLabel: String = "默认网络"
    private val runGeneration = AtomicInteger(0)

    private lateinit var liveCache: MiguLinkCache
    private var liveResolver: MiguLiveResolver? = null
    private var dynamicLive: Boolean = false
    @Volatile
    private var liveSegments: List<String> = emptyList()
    @Volatile
    private var liveResolvedAtMs: Long = 0L
    private val segmentCursor = AtomicInteger(0)
    private val failedSegmentIndexes = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var lastLiveRefreshAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        liveCache = MiguLinkCache(this)
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRun(statusMessage = "已手动停止", isError = false)
                return START_NOT_STICKY
            }

            ACTION_RESET_TOTAL -> {
                if (activeRunJob?.isActive == true) {
                    TrafficEngineStateHolder.archivedTotalBytes = 0L
                    tracker.resetForNewRound()
                    val now = System.currentTimeMillis()
                    roundStartMs = now
                    lastSampleBytes = 0L
                    lastSampleTime = now
                    TrafficEngineStateHolder.update {
                        it.copy(totalBytes = 0L, roundBytes = 0L, speedBytesPerSec = 0.0)
                    }
                } else {
                    TrafficEngineStateHolder.resetTotalWhileStopped()
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
                val threads = TrafficUnitFormatter.clampThreadCount(
                    intent.getIntExtra(EXTRA_THREADS, TrafficUnitFormatter.DEFAULT_THREADS)
                )
                val limitBytes = intent.getLongExtra(EXTRA_LIMIT_BYTES, 0L).coerceAtLeast(0L)
                val isForegroundLaunch = intent.getBooleanExtra(EXTRA_FOREGROUND_LAUNCH, false)
                startRun(url, threads, limitBytes, isForegroundLaunch)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRun(
        url: String,
        threads: Int,
        limitBytes: Long,
        isForegroundLaunch: Boolean
    ) {
        if (activeRunJob?.isActive == true) return

        if (!TrafficUnitFormatter.isValidHttpUrl(url)) {
            abortBeforeStart("链接格式无效，请输入有效的 http/https 地址", isForegroundLaunch)
            return
        }

        if (TrafficEngineStateHolder.hasReachedLimit(limitBytes)) {
            abortBeforeStart("当前累计流量已达到设定的上限，请清零累计或调高上限后继续", isForegroundLaunch)
            return
        }

        val myGen = runGeneration.incrementAndGet()
        currentLimitBytes = limitBytes
        currentNetworkLabel = describeActiveNetwork(this)

        val initialNotification = buildNotification(
            consumedBytes = TrafficEngineStateHolder.archivedTotalBytes,
            limitBytes = limitBytes,
            speedBytesPerSec = 0.0,
            channelText = "当前网络：$currentNetworkLabel"
        )
        startForegroundCompat(initialNotification)
        acquireWakeLock()

        val client = buildHttpClient()
        activeClient = client
        tracker.resetForNewRound()
        val nowStart = System.currentTimeMillis()
        roundStartMs = nowStart
        lastSampleBytes = 0L
        lastSampleTime = nowStart

        dynamicLive = PresetNodes.isDynamicLiveUrl(url)
        liveSegments = emptyList()
        liveResolvedAtMs = 0L
        segmentCursor.set(0)
        failedSegmentIndexes.clear()
        lastLiveRefreshAtMs = 0L
        liveResolver = if (dynamicLive) MiguLiveResolver(client) else null

        TrafficEngineStateHolder.update {
            it.copy(
                isRunning = true,
                speedBytesPerSec = 0.0,
                speedIsAverage = false,
                limitBytes = limitBytes,
                activeNetworkLabel = currentNetworkLabel,
                statusMessage = if (dynamicLive) "正在获取咪咕直播地址..." else "正在运行（$threads 线程）",
                isError = false
            )
        }

        activeRunJob = serviceScope.launch {
            if (dynamicLive) {
                // Prefer the cached first-5 segments so a run starts instantly;
                // a fresh resolve only happens when the cache is missing or dead.
                val cached = liveCache.loadFor(url)
                if (cached != null) {
                    applyLiveResolution(url, cached, saveToCache = false)
                }
                if (liveSegments.isEmpty()) {
                    try {
                        applyLiveResolution(url, liveResolver!!.resolve(url))
                    } catch (e: Exception) {
                        stopRun("获取直播地址失败：${e.message ?: "网络错误"}", isError = true)
                        return@launch
                    }
                }
                if (runGeneration.get() != myGen) return@launch
                val cacheAgeSec = if (liveResolvedAtMs > 0L) {
                    ((System.currentTimeMillis() - liveResolvedAtMs) / 1000L).coerceAtLeast(0L)
                } else {
                    0L
                }
                TrafficEngineStateHolder.update {
                    it.copy(
                        statusMessage = "正在运行（$threads 线程 · ${liveSegments.size} 个分片，缓存 ${cacheAgeSec} 秒）",
                        isError = false
                    )
                }
            }

            val consecutiveErrors = AtomicInteger(0)
            val maxErrors = maxOf(6, threads * 3)

            // Launch concurrent worker coroutines on IO dispatcher
            val workers = List(threads) {
                launch(Dispatchers.IO) {
                    runWorkerLoop(
                        url = url,
                        client = client,
                        myGen = myGen,
                        consecutiveErrors = consecutiveErrors,
                        maxErrors = maxErrors,
                        dynamicLive = dynamicLive
                    )
                }
            }

            // Launch monitoring & speed-sampling ticker
            var lastNotifyTime = 0L
            var liveRefreshFailures = 0
            var deadRefreshes = 0
            var bytesAtFirstDeadRefresh = 0L

            while (isActive && runGeneration.get() == myGen) {
                delay(250L)
                val now = System.currentTimeMillis()

                if (dynamicLive && liveResolver != null && liveSegments.isNotEmpty() &&
                    failedSegmentIndexes.size >= liveSegments.size &&
                    now - lastLiveRefreshAtMs >= LIVE_REFRESH_MIN_INTERVAL_MS
                ) {
                    // All cached first-5 segments failed: fetch a fresh set.
                    lastLiveRefreshAtMs = now
                    try {
                        applyLiveResolution(url, liveResolver!!.resolve(url))
                        liveRefreshFailures = 0
                    } catch (e: Exception) {
                        liveRefreshFailures += 1
                        if (liveRefreshFailures >= LIVE_MAX_REFRESH_FAILURES) {
                            stopRun("直播地址持续刷新失败：${e.message ?: "网络错误"}", isError = true)
                            break
                        }
                    }

                    deadRefreshes += 1
                    if (deadRefreshes == 1) {
                        bytesAtFirstDeadRefresh = tracker.getRoundConsumedBytes()
                    }
                    if (deadRefreshes >= LIVE_MAX_DEAD_REFRESHES &&
                        tracker.getRoundConsumedBytes() <= bytesAtFirstDeadRefresh
                    ) {
                        stopRun("缓存分片已失效且刷新后仍无法下载，已停止", isError = true)
                        break
                    }
                }

                val roundBytes = tracker.getRoundConsumedBytes()
                val totalBytes = TrafficEngineStateHolder.archivedTotalBytes + roundBytes

                if (limitBytes > 0L && totalBytes >= limitBytes) {
                    stopRun("已达到设定的流量上限（${TrafficUnitFormatter.formatBytes(limitBytes)}），已自动停止", isError = false)
                    break
                }

                val dtMs = (now - lastSampleTime).coerceAtLeast(1L)
                val speed = if (dtMs >= 500L) {
                    val s = ((roundBytes - lastSampleBytes).coerceAtLeast(0L) * 1000.0) / dtMs
                    lastSampleBytes = roundBytes
                    lastSampleTime = now
                    s
                } else {
                    TrafficEngineStateHolder.state.value.speedBytesPerSec
                }

                TrafficEngineStateHolder.update {
                    it.copy(
                        totalBytes = totalBytes,
                        roundBytes = roundBytes,
                        speedBytesPerSec = speed,
                        speedIsAverage = false
                    )
                }

                if (now - lastNotifyTime >= 1_000L) {
                    lastNotifyTime = now
                    currentNetworkLabel = describeActiveNetwork(this@TrafficForegroundService)
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildNotification(totalBytes, limitBytes, speed, "当前网络：$currentNetworkLabel")
                    )
                }
            }

            workers.forEach { it.cancel() }
        }
    }

    private fun applyLiveResolution(
        providerUrl: String,
        resolution: MiguLiveResolution,
        saveToCache: Boolean = true
    ) {
        liveSegments = resolution.segmentUrls
        liveResolvedAtMs = resolution.resolvedAtMs
        segmentCursor.set(0)
        failedSegmentIndexes.clear()
        if (saveToCache) {
            liveCache.save(providerUrl, resolution)
        }
    }

    private suspend fun runWorkerLoop(
        url: String,
        client: OkHttpClient,
        myGen: Int,
        consecutiveErrors: AtomicInteger,
        maxErrors: Int,
        dynamicLive: Boolean
    ) {
        val buffer = ByteArray(32 * 1024)
        val requestUserAgent = PresetNodes.resolveUserAgent(url, USER_AGENT)

        while (runGeneration.get() == myGen) {
            val totalNow = TrafficEngineStateHolder.archivedTotalBytes + tracker.getRoundConsumedBytes()
            if (currentLimitBytes > 0L && totalNow >= currentLimitBytes) {
                break
            }

            var segmentIndex = -1
            val targetUrl: String
            val targetUserAgent: String
            if (dynamicLive) {
                val segments = liveSegments
                if (segments.isEmpty()) {
                    delay(500L)
                    continue
                }
                val index = Math.floorMod(segmentCursor.getAndIncrement(), segments.size)
                segmentIndex = index
                targetUrl = segments[index]
                targetUserAgent = PresetNodes.MIGU_VIDEO_USER_AGENT
            } else {
                targetUrl = url
                targetUserAgent = requestUserAgent
            }

            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", targetUserAgent)
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-store")
                .build()

            val call = client.newCall(request)
            var retrySoon = false
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        if (dynamicLive) {
                            // Live segments rotate away; remember the dead one so the
                            // monitor loop can refresh the cached set once all are dead.
                            if (segmentIndex >= 0) {
                                failedSegmentIndexes.add(segmentIndex)
                            }
                            retrySoon = true
                            return@use
                        }
                        serviceScope.launch {
                            if (runGeneration.get() == myGen) {
                                stopRun("目标节点返回 HTTP ${response.code}，已停止", isError = true)
                            }
                        }
                        return
                    }

                    val body = response.body ?: throw IOException("空响应体")
                    consecutiveErrors.set(0)
                    body.byteStream().use { stream ->
                        while (runGeneration.get() == myGen) {
                            val read = stream.read(buffer)
                            if (read == -1) break
                            tracker.recordAppRead(read)

                            val consumed = TrafficEngineStateHolder.archivedTotalBytes + tracker.getRoundConsumedBytes()
                            if (currentLimitBytes > 0L && consumed >= currentLimitBytes) {
                                serviceScope.launch {
                                    if (runGeneration.get() == myGen) {
                                        stopRun(
                                            "已达到设定的流量上限（${TrafficUnitFormatter.formatBytes(currentLimitBytes)}），已自动停止",
                                            isError = false
                                        )
                                    }
                                }
                                return
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (runGeneration.get() != myGen) return
                if (dynamicLive) {
                    // Playlist may already be rotated; just wait for the next refresh.
                    retrySoon = true
                } else {
                    val errCount = consecutiveErrors.incrementAndGet()
                    if (errCount >= maxErrors) {
                        serviceScope.launch {
                            if (runGeneration.get() == myGen) {
                                stopRun("连接连续失败：${e.message ?: "网络错误"}", isError = true)
                            }
                        }
                        return
                    }
                    delay((1_000L + errCount * 400L).coerceAtMost(5_000L))
                }
            }

            if (retrySoon) {
                delay(150L)
            }
        }
    }

    private fun stopRun(statusMessage: String, isError: Boolean) {
        val currentState = TrafficEngineStateHolder.state.value
        if (!currentState.isRunning && activeRunJob == null) {
            stopSelf()
            return
        }

        runGeneration.incrementAndGet()
        activeRunJob?.cancel()
        activeRunJob = null

        activeClient?.dispatcher?.cancelAll()
        activeClient = null
        releaseWakeLock()

        dynamicLive = false
        liveSegments = emptyList()
        liveResolvedAtMs = 0L
        liveResolver = null

        val roundBytes = if (roundStartMs > 0L) tracker.getRoundConsumedBytes() else 0L
        val totalBytes = TrafficEngineStateHolder.archivedTotalBytes + roundBytes
        TrafficEngineStateHolder.archivedTotalBytes = totalBytes

        val elapsedMs = if (roundStartMs > 0L) (System.currentTimeMillis() - roundStartMs).coerceAtLeast(1L) else 1L
        val avgSpeed = (roundBytes * 1000.0) / elapsedMs
        roundStartMs = 0L

        TrafficEngineStateHolder.update {
            it.copy(
                isRunning = false,
                totalBytes = totalBytes,
                roundBytes = roundBytes,
                speedBytesPerSec = avgSpeed,
                speedIsAverage = true,
                activeNetworkLabel = describeActiveNetwork(this),
                statusMessage = statusMessage,
                isError = isError
            )
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun abortBeforeStart(message: String, isForegroundLaunch: Boolean) {
        if (isForegroundLaunch) {
            val notification = buildNotification(
                consumedBytes = TrafficEngineStateHolder.archivedTotalBytes,
                limitBytes = 0L,
                speedBytesPerSec = 0.0,
                channelText = message
            )
            startForegroundCompat(notification)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        TrafficEngineStateHolder.update {
            it.copy(isRunning = false, statusMessage = message, isError = true)
        }
        stopSelf()
    }

    private fun buildHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        consumedBytes: Long,
        limitBytes: Long,
        speedBytesPerSec: Double,
        channelText: String
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TrafficForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val consumedStr = TrafficUnitFormatter.formatBytes(consumedBytes)
        val limitStr = if (limitBytes > 0L) " / ${TrafficUnitFormatter.formatBytes(limitBytes)}" else ""
        val speedStr = TrafficUnitFormatter.formatSpeed(speedBytesPerSec)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_traffic)
            .setContentTitle("已消耗 $consumedStr$limitStr · $speedStr")
            .setContentText(channelText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)

        if (limitBytes > 0L) {
            val progress = ((consumedBytes.toDouble() / limitBytes.toDouble()) * 1000.0)
                .toInt()
                .coerceIn(0, 1000)
            builder.setProgress(1000, progress, false)
        }

        return builder.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrafficKiller::RunnerLock").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        runGeneration.incrementAndGet()
        activeRunJob?.cancel()
        activeClient?.dispatcher?.cancelAll()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.traffickiller.android.action.START"
        const val ACTION_STOP = "com.traffickiller.android.action.STOP"
        const val ACTION_RESET_TOTAL = "com.traffickiller.android.action.RESET_TOTAL"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_THREADS = "extra_threads"
        const val EXTRA_LIMIT_BYTES = "extra_limit_bytes"
        const val EXTRA_FOREGROUND_LAUNCH = "extra_foreground_launch"

        private const val CHANNEL_ID = "traffic_runner"
        private const val NOTIFICATION_ID = 1001
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Minimum gap between two re-resolves after the cached segments all died. */
        private const val LIVE_REFRESH_MIN_INTERVAL_MS = 1_000L

        /** Consecutive resolve errors before giving up. */
        private const val LIVE_MAX_REFRESH_FAILURES = 3

        /** Refresh attempt count after which a still-unusable set aborts the run. */
        private const val LIVE_MAX_DEAD_REFRESHES = 10

        fun describeActiveNetwork(context: Context): String {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "无网络连接"
            val caps = cm.getNetworkCapabilities(network) ?: return "未知网络"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
                else -> "其他网络"
            }
        }

        fun start(
            context: Context,
            url: String,
            threads: Int,
            limitBytes: Long
        ) {
            val useForeground = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            val intent = Intent(context, TrafficForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_THREADS, threads)
                putExtra(EXTRA_LIMIT_BYTES, limitBytes)
                putExtra(EXTRA_FOREGROUND_LAUNCH, useForeground)
            }
            if (useForeground) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrafficForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun resetTotal(context: Context) {
            val intent = Intent(context, TrafficForegroundService::class.java).apply {
                action = ACTION_RESET_TOTAL
            }
            context.startService(intent)
        }
    }
}
