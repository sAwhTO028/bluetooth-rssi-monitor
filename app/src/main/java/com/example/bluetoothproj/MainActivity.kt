package com.example.bluetoothproj

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.bluetoothproj.ui.theme.BluetoothProjTheme
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    // Core Bluetooth objects used for BLE scanning
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    // Track discovered devices as Compose state so the UI updates automatically
    private val scannedDevices = mutableStateListOf<ScannedDevice>()

    // In-memory RSSI history (prototype only): last 20 RSSI values per device (keyed by MAC)
    private val rssiHistoryByAddress =
        mutableStateMapOf<String, SnapshotStateList<Int>>()

    // Track whether we are currently scanning
    private var isScanning by mutableStateOf(false)

    // We keep a reference to the ScanCallback so we can stop scanning later
    private var scanCallback: ScanCallback? = null

    // Classic discovery support (prototype-only, no RSSI)
    private var classicFoundReceiver: BroadcastReceiver? = null
    private var isClassicReceiverRegistered: Boolean = false

    // Remember if the user pressed "Start Scan" so we can begin scanning
    // after permissions are granted.
    private var shouldStartScanAfterPermission = false

    // Launcher to request runtime Bluetooth/location permissions
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = result.values.all { it }
            if (allGranted && shouldStartScanAfterPermission) {
                startBleScan()
            }
            shouldStartScanAfterPermission = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Get BluetoothManager and BluetoothAdapter for BLE operations
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            BluetoothProjTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BluetoothSignalMonitorScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        devices = scannedDevices,
                        rssiHistoryByAddress = rssiHistoryByAddress,
                        isScanning = isScanning,
                        onStartScanClick = { onStartScanClicked() },
                        onStopScanClick = { onStopScanClicked() },
                        onClearClick = { onClearClicked() }
                    )
                }
            }
        }
    }

    private fun onStartScanClicked() {
        shouldStartScanAfterPermission = true
        requestBluetoothPermissions()
    }

    private fun onStopScanClicked() {
        stopBleScan()
        isScanning = false
    }

    private fun onClearClicked() {
        scannedDevices.clear()
        rssiHistoryByAddress.clear()
        stopBleScan()
        isScanning = false
    }

    // Decide which permissions to request based on Android version
    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        bluetoothPermissionLauncher.launch(permissions)
    }

    // Start a basic BLE scan using BluetoothLeScanner and ScanCallback
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        // Avoid starting multiple scans at once
        if (scanCallback != null) return

        // Clear old results when starting a new scan
        scannedDevices.clear()

        // Also show already-paired Classic (BR/EDR) devices in the same list.
        // They won't have RSSI via this simple prototype.
        addClassicPairedDevices()

        // Also try Classic Bluetooth discovery (best effort).
        // This discovers unpaired devices but does not provide RSSI.
        startClassicDiscovery()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let {
                    // Update Compose state from the UI thread for safety.
                    runOnUiThread { handleScanResult(it) }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { scanResult ->
                    // Keep updates simple; prototype behavior.
                    runOnUiThread { handleScanResult(scanResult) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                // For a simple prototype we just stop scanning on error
                runOnUiThread { isScanning = false }
            }
        }

        scanCallback = callback
        bluetoothLeScanner = scanner
        scanner.startScan(callback)
        isScanning = true
    }

    // Add already paired Classic Bluetooth devices to the current device list.
    // Prototype note: BluetoothAdapter.bondedDevices does not provide RSSI.
    private fun addClassicPairedDevices() {
        val adapter = bluetoothAdapter ?: return
        val bondedDevices = adapter.bondedDevices ?: return

        bondedDevices.forEach { device ->
            val name = device.name ?: "Unknown Device"
            val address = device.address ?: return@forEach

            val updatedDevice = ScannedDevice(
                name = name,
                address = address,
                rssi = RSSI_NOT_AVAILABLE
            )

            val existingIndex = scannedDevices.indexOfFirst { it.address == address }
            if (existingIndex >= 0) {
                scannedDevices[existingIndex] = updatedDevice
            } else {
                scannedDevices.add(updatedDevice)
            }
        }
    }

    // Start Classic Bluetooth discovery (best effort).
    // RSSI is not available in ACTION_FOUND, so we store RSSI_NOT_AVAILABLE.
    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery() {
        val adapter = bluetoothAdapter ?: return

        ensureClassicReceiverRegistered()

        try {
            // Avoid "already discovering" issues
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            adapter.startDiscovery()
        } catch (_: Exception) {
            // Prototype: ignore discovery errors
        }
    }

    private fun ensureClassicReceiverRegistered() {
        if (isClassicReceiverRegistered) return

        if (classicFoundReceiver == null) {
            classicFoundReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothDevice.ACTION_FOUND) return

                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val address = device?.address ?: return
                    val name = device.name ?: "Unknown Device"

                    val updatedDevice = ScannedDevice(
                        name = name,
                        address = address,
                        rssi = RSSI_NOT_AVAILABLE
                    )

                    val existingIndex = scannedDevices.indexOfFirst { it.address == address }
                    if (existingIndex >= 0) {
                        scannedDevices[existingIndex] = updatedDevice
                    } else {
                        scannedDevices.add(updatedDevice)
                    }
                }
            }
        }

        try {
            registerReceiver(
                classicFoundReceiver,
                IntentFilter(BluetoothDevice.ACTION_FOUND)
            )
            isClassicReceiverRegistered = true
        } catch (_: Exception) {
            // Prototype: ignore registration errors
        }
    }

    private fun stopClassicDiscovery() {
        val adapter = bluetoothAdapter
        try {
            if (adapter?.isDiscovering == true) {
                adapter.cancelDiscovery()
            }
        } catch (_: Exception) {
            // ignore
        }

        if (isClassicReceiverRegistered && classicFoundReceiver != null) {
            try {
                unregisterReceiver(classicFoundReceiver)
            } catch (_: Exception) {
                // ignore
            }
        }

        isClassicReceiverRegistered = false
    }

    // Stop an active BLE scan
    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        stopClassicDiscovery()

        val scanner = bluetoothLeScanner ?: bluetoothAdapter?.bluetoothLeScanner
        val callback = scanCallback

        if (scanner != null && callback != null) {
            scanner.stopScan(callback)
        }

        scanCallback = null
    }

    // Handle a single ScanResult: extract name, address, and RSSI and update the list
    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val name = device.name ?: "Unknown Device"
        val rssi = result.rssi

        val existingIndex = scannedDevices.indexOfFirst { it.address == address }
        val updatedDevice = ScannedDevice(
            name = name,
            address = address,
            rssi = rssi
        )

        if (existingIndex >= 0) {
            scannedDevices[existingIndex] = updatedDevice
        } else {
            scannedDevices.add(updatedDevice)
        }

        // Update RSSI history for the selected device details (last 20 values).
        val history = rssiHistoryByAddress.getOrPut(address) { mutableStateListOf() }
        history.add(rssi)
        if (history.size > 20) {
            history.removeAt(0)
        }
    }
}

@Composable
fun BluetoothSignalMonitorScreen(
    modifier: Modifier = Modifier,
    devices: List<ScannedDevice> = emptyList(),
    rssiHistoryByAddress: Map<String, List<Int>> = emptyMap(),
    isScanning: Boolean = false,
    onStartScanClick: () -> Unit = {},
    onStopScanClick: () -> Unit = {},
    onClearClick: () -> Unit = {}
) {
    // Selected device state (prototype-only, no navigation)
    var selectedAddress by remember { mutableStateOf<String?>(null) }

    val selectedDevice = devices.firstOrNull { it.address == selectedAddress }
    val recentRssi = selectedAddress?.let { rssiHistoryByAddress[it] } ?: emptyList()
    val averageRssiLast5 = recentRssi.takeLast(5).average().takeIf { !it.isNaN() }

    // Smooth displayed RSSI to reduce sudden jumps.
    var smoothedRssi by remember(selectedAddress) { mutableStateOf<Double?>(null) }
    LaunchedEffect(averageRssiLast5) {
        averageRssiLast5?.let { newValue ->
            smoothedRssi = if (smoothedRssi == null) {
                newValue
            } else {
                (smoothedRssi!! * 0.7) + (newValue * 0.3)
            }
        }
    }

    val displayRssi = if (selectedDevice?.let { isRssiAvailable(it.rssi) } == true) {
        smoothedRssi?.roundToInt() ?: selectedDevice?.rssi
    } else {
        null
    }

    val signalQuality = displayRssi?.let { classifySignalQuality(it) } ?: "Unknown"
    val distanceRange = displayRssi?.let { classifyDistanceRange(it) } ?: "Unknown"
    val approxDistanceMeters = averageRssiLast5?.let { estimateDistanceMeters(it) }

    // Filtering UI state (default disabled)
    var showStrongOnly by remember { mutableStateOf(false) }
    val filteredDevices = if (showStrongOnly) {
        devices.filter { it.rssi >= -70 }
    } else {
        devices
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Bluetooth Signal Monitor",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Selected device details (shown when a device is selected)
        if (selectedDevice != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = selectedDevice.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = selectedDevice.address,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (displayRssi != null) {
                            "RSSI: $displayRssi dBm"
                        } else {
                            "RSSI not available"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = if (displayRssi != null) "Signal: $signalQuality" else "Signal: N/A",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = if (displayRssi != null) {
                            "Approx distance: $distanceRange"
                        } else {
                            "Approx distance: N/A"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Note: Not accurate",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        text = "Recent RSSI (last 20):",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    if (recentRssi.isEmpty()) {
                        Text(
                            text = "No history yet.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        // Compact chip grid: 5 items per row, newest values first
                        RssiHistoryChipGrid(
                            values = recentRssi.asReversed()
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { onStartScanClick() },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Start Scan")
            }

            Button(
                onClick = { onStopScanClick() },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Stop Scan")
            }

            Button(
                onClick = {
                    selectedAddress = null
                    onClearClick()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Clear")
            }
        }

        Text(
            text = if (isScanning) "Status: Scanning..." else "Status: Idle",
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Nearby devices:",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Show Strong Only",
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = showStrongOnly,
                onCheckedChange = { showStrongOnly = it }
            )
        }

        if (filteredDevices.isEmpty()) {
            Text(
                text = "No devices found yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredDevices) { device ->
                    val isSelected = device.address == selectedAddress
                    BluetoothDeviceCard(
                        device = device,
                        isSelected = isSelected,
                        onClick = { selectedAddress = device.address }
                    )
                }
            }
        }
    }
}

@Composable
fun BluetoothDeviceCard(
    device: ScannedDevice,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = if (isRssiAvailable(device.rssi)) {
                        "RSSI: ${device.rssi} dBm"
                    } else {
                        "RSSI not available"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isRssiAvailable(device.rssi)) {
                SignalBars(
                    bars = rssiToBars(device.rssi),
                    modifier = Modifier
                        .height(24.dp)
                        .width(34.dp)
                )
            } else {
                Spacer(
                    modifier = Modifier
                        .height(24.dp)
                        .width(34.dp)
                )
            }
        }
    }
}

// Convert RSSI (dBm) into 1–5 signal bars (simple prototype mapping)
private fun rssiToBars(rssi: Int): Int {
    // If RSSI isn't available (Classic devices), treat as minimum.
    if (!isRssiAvailable(rssi)) return 1
    return when {
        rssi >= -55 -> 5
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
}

// Estimate distance in meters from averaged RSSI.
private fun estimateDistanceMeters(rssi: Double, txPower: Int = -59): Double {
    return 10.0.pow((txPower - rssi) / 20.0)
}

private fun classifySignalQuality(rssi: Int): String {
    return when {
        rssi >= -55 -> "Strong"
        rssi >= -65 -> "Good"
        rssi >= -75 -> "Medium"
        rssi >= -85 -> "Weak"
        else -> "Very Weak"
    }
}

private fun classifyDistanceRange(rssi: Int): String {
    return when {
        rssi >= -55 -> "Very Near (~1m)"
        rssi >= -65 -> "Near (1–3m)"
        rssi >= -75 -> "Medium (3–6m)"
        rssi >= -85 -> "Far (6m+)"
        else -> "Very Far / Unstable"
    }
}

private const val RSSI_NOT_AVAILABLE: Int = -9999

private fun isRssiAvailable(rssi: Int): Boolean = rssi != RSSI_NOT_AVAILABLE

@Composable
fun SignalBars(
    bars: Int,
    modifier: Modifier = Modifier
) {
    val clampedBars = bars.coerceIn(1, 5)
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Draw 5 vertical bars with increasing heights (no custom canvas)
        for (i in 1..5) {
            val height = (8 + i * 3).dp
            val color = if (i <= clampedBars) activeColor else inactiveColor

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun RssiHistoryChipGrid(values: List<Int>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        values.chunked(5).forEach { rowValues ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowValues.forEach { rssi ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Text(
                            text = "[$rssi]",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BluetoothSignalMonitorPreview() {
    BluetoothProjTheme {
        BluetoothSignalMonitorScreen(
            devices = listOf(
                ScannedDevice("Sample Device 1", "00:11:22:33:44:55", -45),
                ScannedDevice("Unknown Device", "AA:BB:CC:DD:EE:FF", -70)
            ),
            isScanning = true
        )
    }
}