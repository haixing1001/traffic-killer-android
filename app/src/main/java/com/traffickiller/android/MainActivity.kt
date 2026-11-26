package com.traffickiller.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.traffickiller.android.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var suppressUrlWatcher = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Start foreground service even if notification permission was denied;
            // Android still allows the service to run, only notification visibility is hidden.
            launchTrafficService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPresetDropdown()
        loadSavedSettings()
        setupListeners()
        observeEngineState()
    }

    override fun onResume() {
        super.onResume()
        if (!TrafficEngineStateHolder.state.value.isRunning) {
            binding.networkTypeText.text =
                "当前网络：${TrafficForegroundService.describeActiveNetwork(this)}"
        }
    }

    private fun setupPresetDropdown() {
        val labels = PresetNodes.items.map { it.title }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        binding.nodeDropdown.setAdapter(adapter)

        binding.nodeDropdown.setOnItemClickListener { _, _, position, _ ->
            val node = PresetNodes.items.getOrNull(position) ?: return@setOnItemClickListener
            if (node.url.isNotEmpty()) {
                suppressUrlWatcher = true
                binding.urlInput.setText(node.url)
                suppressUrlWatcher = false
            }
            saveSettings()
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_URL, PresetNodes.items[1].url).orEmpty()
        val savedThreads = prefs.getInt(KEY_THREADS, TrafficUnitFormatter.DEFAULT_THREADS)
        val savedLimitGb = prefs.getString(KEY_LIMIT_GB, "5").orEmpty()

        val matchedIndex = PresetNodes.items.indexOfFirst { it.url == savedUrl && it.url.isNotEmpty() }
        val displayNodeTitle = if (matchedIndex >= 0) {
            PresetNodes.items[matchedIndex].title
        } else {
            PresetNodes.items[0].title
        }
        binding.nodeDropdown.setText(displayNodeTitle, false)

        suppressUrlWatcher = true
        binding.urlInput.setText(savedUrl)
        suppressUrlWatcher = false

        binding.threadInput.setText(TrafficUnitFormatter.clampThreadCount(savedThreads).toString())
        binding.limitInput.setText(savedLimitGb)
        updateLimitSubtitle(TrafficUnitFormatter.parseLimitGbToBytes(savedLimitGb))
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_URL, binding.urlInput.text?.toString().orEmpty().trim())
            .putInt(
                KEY_THREADS,
                TrafficUnitFormatter.parseThreadCount(binding.threadInput.text?.toString().orEmpty())
            )
            .putString(KEY_LIMIT_GB, binding.limitInput.text?.toString().orEmpty().trim())
            .apply()
    }

    private fun setupListeners() {
        binding.urlInput.doAfterTextChanged {
            if (suppressUrlWatcher) return@doAfterTextChanged
            binding.nodeDropdown.setText(PresetNodes.items[0].title, false)
            saveSettings()
        }

        binding.threadMinusBtn.setOnClickListener {
            val current = TrafficUnitFormatter.parseThreadCount(binding.threadInput.text?.toString().orEmpty())
            val next = TrafficUnitFormatter.clampThreadCount(current - 1)
            binding.threadInput.setText(next.toString())
            saveSettings()
        }

        binding.threadPlusBtn.setOnClickListener {
            val current = TrafficUnitFormatter.parseThreadCount(binding.threadInput.text?.toString().orEmpty())
            val next = TrafficUnitFormatter.clampThreadCount(current + 1)
            binding.threadInput.setText(next.toString())
            saveSettings()
        }

        binding.threadInput.doAfterTextChanged {
            saveSettings()
        }

        binding.limitInput.doAfterTextChanged { editable ->
            val limitBytes = TrafficUnitFormatter.parseLimitGbToBytes(editable?.toString().orEmpty())
            updateLimitSubtitle(limitBytes)
            saveSettings()
        }

        binding.chipLimit1.setOnClickListener { binding.limitInput.setText("1") }
        binding.chipLimit5.setOnClickListener { binding.limitInput.setText("5") }
        binding.chipLimit10.setOnClickListener { binding.limitInput.setText("10") }
        binding.chipLimitUnlimited.setOnClickListener { binding.limitInput.setText("0") }

        binding.toggleButton.setOnClickListener {
            if (TrafficEngineStateHolder.state.value.isRunning) {
                TrafficForegroundService.stop(this)
            } else {
                startWithPermissionCheck()
            }
        }

        binding.resetTotalButton.setOnClickListener {
            if (TrafficEngineStateHolder.state.value.isRunning) {
                TrafficForegroundService.resetTotal(this)
            } else {
                TrafficEngineStateHolder.resetTotalWhileStopped()
            }
        }
    }

    private fun startWithPermissionCheck() {
        val url = binding.urlInput.text?.toString().orEmpty().trim()
        if (!TrafficUnitFormatter.isValidHttpUrl(url)) {
            showBanner("请输入有效的 http:// 或 https:// 地址", isError = true)
            return
        }

        val normalizedThreads =
            TrafficUnitFormatter.parseThreadCount(binding.threadInput.text?.toString().orEmpty())
        binding.threadInput.setText(normalizedThreads.toString())
        saveSettings()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        launchTrafficService()
    }

    private fun launchTrafficService() {
        val url = binding.urlInput.text?.toString().orEmpty().trim()
        val threads =
            TrafficUnitFormatter.parseThreadCount(binding.threadInput.text?.toString().orEmpty())
        val limitBytes =
            TrafficUnitFormatter.parseLimitGbToBytes(binding.limitInput.text?.toString().orEmpty())

        TrafficForegroundService.start(
            context = this,
            url = url,
            threads = threads,
            limitBytes = limitBytes
        )
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrafficEngineStateHolder.state.collect { snapshot ->
                    renderSnapshot(snapshot)
                }
            }
        }
    }

    private fun renderSnapshot(snapshot: TrafficSnapshot) {
        val running = snapshot.isRunning
        binding.toggleButton.text =
            getString(if (running) R.string.action_stop else R.string.action_start)

        // Lock configuration inputs while running
        binding.nodeDropdown.isEnabled = !running
        binding.urlInput.isEnabled = !running
        binding.threadInput.isEnabled = !running
        binding.threadMinusBtn.isEnabled = !running
        binding.threadPlusBtn.isEnabled = !running
        binding.limitInput.isEnabled = !running
        binding.chipLimit1.isEnabled = !running
        binding.chipLimit5.isEnabled = !running
        binding.chipLimit10.isEnabled = !running
        binding.chipLimitUnlimited.isEnabled = !running

        binding.totalTrafficText.text = if (snapshot.totalBytes > 0L || running) {
            TrafficUnitFormatter.formatBytes(snapshot.totalBytes)
        } else {
            "-"
        }

        val activeLimit = if (running) {
            snapshot.limitBytes
        } else {
            TrafficUnitFormatter.parseLimitGbToBytes(binding.limitInput.text?.toString().orEmpty())
        }
        updateLimitSubtitle(activeLimit)

        if (activeLimit > 0L && (running || snapshot.totalBytes > 0L)) {
            val progress = ((snapshot.totalBytes.toDouble() / activeLimit.toDouble()) * 1000.0)
                .toInt()
                .coerceIn(0, 1000)
            binding.limitProgressBar.visibility = View.VISIBLE
            binding.limitProgressBar.progress = progress
        } else {
            binding.limitProgressBar.visibility = View.GONE
        }

        binding.speedLabelText.text = getString(
            if (snapshot.speedIsAverage) R.string.label_avg_speed else R.string.label_realtime_speed
        )

        if (running || snapshot.speedIsAverage) {
            binding.speedValueText.text = TrafficUnitFormatter.formatSpeed(snapshot.speedBytesPerSec)
            binding.bandwidthValueText.text =
                TrafficUnitFormatter.formatBitsPerSec(snapshot.speedBytesPerSec * 8.0)
        } else {
            binding.speedValueText.text = "-"
            binding.bandwidthValueText.text = "-"
        }

        binding.networkTypeText.text = "当前网络：${
            if (running) snapshot.activeNetworkLabel else TrafficForegroundService.describeActiveNetwork(this)
        }"

        if (snapshot.statusMessage.isNotEmpty()) {
            showBanner(snapshot.statusMessage, snapshot.isError)
        } else {
            binding.statusBanner.visibility = View.GONE
        }
    }

    private fun updateLimitSubtitle(limitBytes: Long) {
        binding.limitSubtitleText.text = if (limitBytes > 0L) {
            "上限: ${TrafficUnitFormatter.formatBytes(limitBytes)}"
        } else {
            "上限: 无限制"
        }
    }

    private fun showBanner(message: String, isError: Boolean) {
        binding.statusBanner.visibility = View.VISIBLE
        binding.statusBanner.text = message
        binding.statusBanner.setBackgroundColor(
            ContextCompat.getColor(this, if (isError) R.color.error_bg else R.color.info_bg)
        )
        binding.statusBanner.setTextColor(
            ContextCompat.getColor(this, if (isError) R.color.error_text else R.color.info_text)
        )
    }

    companion object {
        private const val PREFS_NAME = "traffic_killer_prefs"
        private const val KEY_URL = "url"
        private const val KEY_THREADS = "threads"
        private const val KEY_LIMIT_GB = "limit_gb"
    }
}
