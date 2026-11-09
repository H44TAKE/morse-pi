package br.pi.morse

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Ligação Bluetooth LE com a Raspberry Pi ("MorsePi"), usada pelos dois apps (controle e rosto).
 * Protocolo: linhas UTF-8 terminadas em \n (ver README).
 */
@SuppressLint("MissingPermission")
class PiLink(private val ctx: Context, private val ui: Handler, private val cb: Callbacks) {

    interface Callbacks {
        /** Thread principal. */
        fun onState(connected: Boolean, text: String)
        /** Qualquer thread: Bluetooth pronto para uso. */
        fun onReady()
        /** Qualquer thread: uma linha completa vinda da Pi. */
        fun onLine(line: String)
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // celular -> Pi
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Pi -> celular
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var scanning = false
    private val buffer = ByteArrayOutputStream()
    private val outQ = java.util.ArrayDeque<Pair<String, Boolean>>()
    private var writing = false

    val isReady: Boolean get() = gatt != null && rx != null
    val isBusy: Boolean get() = gatt != null || scanning

    fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions() = neededPermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun adapter(): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** Procura a Pi e conecta. Precisa das permissões já concedidas. */
    fun connect() {
        val ad = adapter()
        if (ad == null || !ad.isEnabled) { cb.onState(false, "Ligue o Bluetooth"); return }
        cb.onState(false, "Procurando a Pi…")
        scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        ad.bluetoothLeScanner.startScan(listOf(filter), settings, scanCb)
        ui.postDelayed({
            if (scanning) { stopScan(); cb.onState(false, "Pi não encontrada") }
        }, 15000)
    }

    fun disconnect() {
        if (scanning) { stopScan(); cb.onState(false, "Desconectado") }
        gatt?.disconnect()
    }

    fun close() {
        stopScan()
        gatt?.close(); gatt = null; rx = null
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            stopScan()
            ui.post { cb.onState(false, "Conectando…") }
            gatt = r.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) { ui.post { cb.onState(false, "Falha na busca ($errorCode)") } }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(185)
            } else {
                g.close(); gatt = null; rx = null
                synchronized(outQ) { outQ.clear(); writing = false }
                ui.post { cb.onState(false, "Desconectado") }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val svc = g.getService(SERVICE)
            val tx = svc?.getCharacteristic(TX)
            rx = svc?.getCharacteristic(RX)
            if (tx == null || rx == null) { ui.post { cb.onState(false, "Serviço não encontrado") }; return }
            g.setCharacteristicNotification(tx, true)
            val d = tx.getDescriptor(CCCD)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") g.writeDescriptor(d)
            }
            ui.post { cb.onState(true, "Conectado na Pi") }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { cb.onReady() }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(outQ) { writing = false }
            pump()
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onBytes(value)
        }

        // Android 12 ou menos
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value
            if (v != null) onBytes(v)
        }
    }

    /** Junta os pedaços (BLE manda ~20 bytes por vez) até achar o fim da linha. */
    private fun onBytes(b: ByteArray) {
        for (x in b) {
            if (x == '\n'.code.toByte()) {
                val line = buffer.toString(Charsets.UTF_8.name()); buffer.reset()
                if (line.isNotEmpty()) cb.onLine(line)
            } else buffer.write(x.toInt())
        }
    }

    /** Manda um comando à Pi. `fast` = sem confirmação (para a tecla manual). */
    fun send(s: String, fast: Boolean = false) {
        synchronized(outQ) { outQ.add(s to fast) }
        pump()
    }

    private fun pump() {
        val item: Pair<String, Boolean>
        synchronized(outQ) {
            if (writing) return
            item = outQ.poll() ?: return
            writing = true
        }
        val g = gatt; val c = rx
        if (g == null || c == null) { synchronized(outQ) { writing = false }; return }
        val bytes = item.first.toByteArray(Charsets.UTF_8)
        val type = if (item.second) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                   else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") c.writeType = type
            @Suppress("DEPRECATION") c.value = bytes
            @Suppress("DEPRECATION") g.writeCharacteristic(c)
        }
        if (!ok) { synchronized(outQ) { writing = false }; pump() }
    }
}
