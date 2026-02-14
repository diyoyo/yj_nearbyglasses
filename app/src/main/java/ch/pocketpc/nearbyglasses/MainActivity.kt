package ch.pocketpc.nearbyglasses

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ch.pocketpc.nearbyglasses.databinding.ActivityMainBinding
import ch.pocketpc.nearbyglasses.model.DetectionEvent
import ch.pocketpc.nearbyglasses.util.PreferencesManager
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ClipData
import android.content.ClipboardManager
import android.view.MotionEvent
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferencesManager: PreferencesManager
    
    private val detectionLog = mutableListOf<DetectionEvent>()
    //private val logTextBuffer = StringBuilder()
    private val logLines = ArrayDeque<String>()   // holds single lines WITHOUT trailing \n

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    private var scanService: BluetoothScanService? = null
    private var serviceBound = false
    private var startScanAfterBind = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothScanService.LocalBinder
            scanService = binder.getService()
            serviceBound = true
            
            scanService?.addDetectionListener(detectionListener)
            if (preferencesManager.debugEnabled) {
                scanService?.addDebugListener(debugListener)
            }
            if (startScanAfterBind) {
                startScanAfterBind = false
                scanService?.startScanning()
            }
            updateUI()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            scanService?.removeDetectionListener(detectionListener)
            if (preferencesManager.debugEnabled) {
                scanService?.removeDebugListener(debugListener)
            }
            scanService = null
            serviceBound = false

            updateUI()
        }
    }
    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    private val detectionListener: (DetectionEvent) -> Unit = { event ->
        runOnUiThread {
            addLogEntry(event)
        }
    }
    
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBluetoothEnabled()
        } else {
            showPermissionDeniedDialog()
        }
    }
    
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startScanningProcess()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled", Toast.LENGTH_SHORT).show()
        }
    }
    private fun appendLine(line: String) {
        // line should NOT contain "\n"
        if (line.isBlank()) return

        val maxLines = maxOf(
            50,
            if (preferencesManager.debugEnabled) preferencesManager.debugMaxLines else 100
        )

        logLines.addLast(line)
        while (logLines.size > maxLines) {
            logLines.removeFirst()
        }
    }

    private fun buildLogText(): String {
        // Build the display text only when needed
        return logLines.joinToString(separator = "\n", postfix = if (logLines.isNotEmpty()) "\n" else "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferencesManager = PreferencesManager(this)
        preferencesManager.registerListener(this)
        
        setupToolbar()
        setupUI()
        updateLogDisplay()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }
    
    private fun setupUI() {
        // Make log TextView scrollable
        binding.textLog.movementMethod = ScrollingMovementMethod()
        // Tap a line to copy it (long-press copies full log)
        binding.textLog.setOnTouchListener(object : android.view.View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L

        override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                }

                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    val dt = System.currentTimeMillis() - downTime

                    // treat as tap if small movement + short duration
                    val isTap = dx < 20 && dy < 20 && dt < 300
                    if (isTap) {
                        val tv = binding.textLog
                        val layout = tv.layout ?: return false

                        val x = (event.x - tv.totalPaddingLeft + tv.scrollX).coerceAtLeast(0f)
                        val y = (event.y - tv.totalPaddingTop + tv.scrollY).coerceAtLeast(0f)

                        val line = layout.getLineForVertical(y.toInt())
                        val start = layout.getLineStart(line)
                        val end = layout.getLineEnd(line)

                        val fullText = tv.text?.toString().orEmpty()
                        val tappedLine = fullText.substring(start, end).trim()

                        if (tappedLine.isNotEmpty()) {
                            copyToClipboard("NearbyGlassesLogLine", tappedLine)
                            return true // handled tap
                        }
                    }
                }
            }
            return false // allow scrolling
        }
        })

        // Optional: long-press copies entire log
        binding.textLog.setOnLongClickListener {
            val text = binding.textLog.text?.toString().orEmpty()
            if (text.isNotBlank()) copyToClipboard("NearbyGlassesLog", text)
            true
        }

        // Start/Stop button
        binding.buttonToggleScan.setOnClickListener {
            if (serviceBound && scanService?.isScanning() == true) {
                stopScanning()
            } else {
                requestPermissionsAndScan()
            }
        }
        
        // Export button in menu
    }
    
    private fun addLogEntry(event: DetectionEvent) {
        // Add to log list
        detectionLog.add(event)
        
        // Update text log if logging is enabled
        if (!preferencesManager.loggingEnabled) return

        val timestamp = dateFormat.format(Date(event.timestamp))
        val line = "[$timestamp] ${event.deviceName ?: "Unknown"} (${event.rssi} dBm) - ${event.detectionReason}"
        appendLine(line)
        updateLogDisplay()

        /*
        if (preferencesManager.loggingEnabled) {
            val timestamp = dateFormat.format(Date(event.timestamp))
            val logLine = "[$timestamp] ${event.deviceName ?: "Unknown"} (${event.rssi} dBm) - ${event.detectionReason}\n"
            
            logTextBuffer.append(logLine)

            // Keep only last 100 entries in text buffer
            val lines = logTextBuffer.lines()
            if (lines.size > 100) {
                logTextBuffer.clear()
                logTextBuffer.append(lines.takeLast(100).joinToString("\n"))
            }
            
            updateLogDisplay()
        }*/
    }
    private val debugListener: (String) -> Unit = { msg ->
        runOnUiThread {
            if (!preferencesManager.debugEnabled) return@runOnUiThread
            if (preferencesManager.debugAdvOnly && !msg.startsWith("ADV ")) {
                return@runOnUiThread
            }

            val timestamp = dateFormat.format(Date())
            appendLine("[$timestamp] DEBUG: $msg")
            /*
            val line = "[$timestamp] DEBUG: $msg\n"
            //logTextBuffer.append(line)


            val lines = logTextBuffer.lines()
            val maxLines = preferencesManager.debugMaxLines
            if (lines.size > maxLines) { // default: 200 keep a bit more for debug
                logTextBuffer.clear()
                logTextBuffer.append(lines.takeLast(maxLines).joinToString("\n"))
            }
            */
            updateLogDisplay()
        }
    }

    private fun updateLogDisplay() {
        val show = preferencesManager.loggingEnabled || preferencesManager.debugEnabled
        if (show) {
            binding.textLog.isEnabled = true
            //binding.textLog.text = logTextBuffer.toString()
            binding.textLog.text = buildLogText()

            // Auto-scroll to bottom
            binding.scrollView.post {
                binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        } else {
            binding.textLog.isEnabled = false
            binding.textLog.text = getString(R.string.notLogging)
        }
    }
    
    private fun updateUI() {
        val isScanning = serviceBound && scanService?.isScanning() == true
        
        binding.buttonToggleScan.text = if (isScanning) {
            getString(R.string.stopScanning)
        } else {
            getString(R.string.startScanning)
        }
        
        binding.statusText.text = if (isScanning) {
            getString(R.string.textScanning)
        } else {
            getString(R.string.notScanning)
        }
    }
    
    private fun requestPermissionsAndScan() {
        val requiredPermissions = getRequiredPermissions()
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            checkBluetoothEnabled()
        } else {
            bluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            // REQUIRED because FGS type is "location"
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            // or Manifest.permission.ACCESS_COARSE_LOCATION
        //} else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        return permissions
    }
    
    private fun checkBluetoothEnabled() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show()
            return
        }
        
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            startScanningProcess()
        }
    }
    
    private fun startScanningProcess() {
        val useForegroundService = preferencesManager.foregroundServiceEnabled
        
        if (useForegroundService) {
            startForegroundService()
        } else {
            startScanAfterBind = true
            startInAppScanning()
        }
    }
    
    private fun startForegroundService() {
        val intent = Intent(this, BluetoothScanService::class.java).apply {
            action = BluetoothScanService.ACTION_START_SCAN
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        bindService(
            Intent(this, BluetoothScanService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        
        updateUI()
    }
    
    private fun startInAppScanning() {
        val intent = Intent(this, BluetoothScanService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun stopScanning() {
        if (serviceBound) {
            scanService?.let { service ->
                if (preferencesManager.foregroundServiceEnabled) {
                    val intent = Intent(this, BluetoothScanService::class.java).apply {
                        action = BluetoothScanService.ACTION_STOP_SCAN
                    }
                    startService(intent)
                } else {
                    service.stopScanning()
                }
            }
            
            unbindService(serviceConnection)
            serviceBound = false
        }
        
        updateUI()
    }
    
    private fun clearLog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Log")
            .setMessage("Are you sure you want to clear all detection logs?")
            .setPositiveButton("Clear") { _, _ ->
                detectionLog.clear()
                //logTextBuffer.clear()
                logLines.clear()
                updateLogDisplay()
                Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun exportLog() {
        val hasDetections = detectionLog.isNotEmpty()
        //val hasLogText = logTextBuffer.isNotEmpty()
        val hasLogText = logLines.isNotEmpty()

        if (!hasDetections && !hasLogText) {
            Toast.makeText(this, getString(R.string.nothing_to_export), Toast.LENGTH_SHORT).show()
            return
        }


        try {
            val fileName = "nearby_glasses_detected_${System.currentTimeMillis()}.txt"
            val file = File(getExternalFilesDir(null), fileName)
            //FileWriter(file).use { it.write(logTextBuffer.toString()) }
            FileWriter(file).use { it.write(buildLogText()) }

            shareFile(file, "text/plain")

            /*val fileName = "nearby_glasses_detected_${System.currentTimeMillis()}.json"
            val file = File(getExternalFilesDir(null), fileName)

            val json = buildString {
                append("{\n")
                append("  \"export_timestamp\": ${System.currentTimeMillis()},\n")
                append("  \"total_detections\": ${detectionLog.size},\n")
                append("  \"detections\": [\n")
                detectionLog.forEachIndexed { index, event ->
                    append("    ")
                    append(event.toJson().replace("\n", "\n    "))
                    if (index < detectionLog.size - 1) {
                        append(",")
                    }
                    append("\n")
                }
                append("  ]\n")
                append("}")
            }
            
            FileWriter(file).use { writer ->
                writer.write(json)
            }
            
            shareFile(file)*/
            
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting log", e)
            Toast.makeText(this, "Error exporting log: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun shareFile(file: File, mimeType: String = "text/plain") {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            //type = "application/json"
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        startActivity(Intent.createChooser(intent, "Export Detection Log"))
    }
    
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app requires Bluetooth and Location permissions to scan for devices. Please grant permissions in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "logging_enabled" -> updateLogDisplay()
            "debug_enabled" -> {
                updateLogDisplay()
                if (serviceBound) {
                    if (preferencesManager.debugEnabled) {
                        scanService?.addDebugListener(debugListener)
                    } else {
                        scanService?.removeDebugListener(debugListener)
                    }
                }
            }
            "debug_advonly" -> updateLogDisplay()
            "debug_max_lines" -> updateLogDisplay()
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_clear_log -> {
                clearLog()
                true
            }
            R.id.action_export -> {
                exportLog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        preferencesManager.unregisterListener(this)
        if (serviceBound) {
            scanService?.removeDetectionListener(detectionListener)
            scanService?.removeDebugListener(debugListener)
            unbindService(serviceConnection)
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
    }
}
