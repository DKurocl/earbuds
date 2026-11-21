package ua.olex.sonyanc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Case lid" test: listens to Bluetooth LE for 30 seconds and writes to Diagnostics everything
 * Sony earbuds announce (Sony's manufacturer data, Google Fast Pair, a WF-1000XM5 name). The owner
 * opens and closes the case with the earbuds inside; if an announcement comes with the lid, a
 * filtered background scan can bring the connected sheet up on opening the case, as AirPods do.
 * Each announcement is logged once per distinct payload, so the log stays readable.
 */
object LidTest {
    private const val SONY = 0x012D
    private val FAST_PAIR by lazy { ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB") }
    const val SECONDS = 30

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private fun hex(b: ByteArray?) = b?.joinToString(" ") { "%02X".format(it) } ?: ""

    /** Pure: what in one advertisement is about Sony earbuds, or null. Unit-tested. */
    fun describe(name: String?, sony: ByteArray?, fastPair: ByteArray?): String? {
        val parts = buildList {
            if (name != null && (name.contains("WF-", true) || name.contains("Sony", true))) add("name \"$name\"")
            if (sony != null) add("Sony ${hex(sony)}")
            if (fastPair != null) add("Fast Pair ${hex(fastPair)}")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    @SuppressLint("MissingPermission") // checked by the caller (BLUETOOTH_SCAN)
    suspend fun run(ctx: Context) {
        if (_running.value) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return Headphones.addLog("✗ Case lid test: Bluetooth is off")
        _running.value = true
        val start = System.currentTimeMillis()
        val seen = HashSet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val line = describe(record.deviceName ?: runCatching { result.device.name }.getOrNull(),
                    record.getManufacturerSpecificData(SONY), record.getServiceData(FAST_PAIR)) ?: return
                if (seen.add(result.device.address + line)) {
                    val t = (System.currentTimeMillis() - start) / 100 / 10f
                    Headphones.addLog("· ${t}s ${result.device.address} ${result.rssi} dBm · $line")
                }
            }
            override fun onScanFailed(errorCode: Int) { Headphones.addLog("✗ Case lid test: scan failed ($errorCode)") }
        }
        Headphones.addLog("· Case lid test: listening for $SECONDS s. Open and close the case a few times, earbuds inside.")
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            delay(SECONDS * 1000L)
        } finally {
            runCatching { scanner.stopScan(callback) }
            _running.value = false
            Headphones.addLog("· Case lid test: done, ${seen.size} announcement${if (seen.size == 1) "" else "s"} from Sony earbuds")
        }
    }
}
