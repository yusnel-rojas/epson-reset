package nl.redlabs.epsonreset.usb

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.protocol.Transport

/**
 * A raw bidirectional byte pipe to one printer. The winspool implementation talks through the
 * Windows print spooler; tests supply a fake. Kept behind an interface so the transport's framing
 * and drain logic can be exercised with no native library present.
 */
interface RawPrinterChannel : AutoCloseable {
    /** Bytes written, or -1 on failure. */
    fun write(data: ByteArray): Int

    /** Bytes read into [buffer]; 0 when nothing is waiting yet, -1 on failure. */
    fun read(buffer: ByteArray): Int

    override fun close()
}

/**
 * [Transport] over the Windows print spooler's raw channel.
 *
 * Windows' `usbprint.sys` forwards a RAW job to the same USB bulk endpoints libusb would use and
 * returns the printer's back-channel through `ReadPrinter`, so this passes the D4 packet stream
 * through unchanged — exactly like [LibUsbTransport], and unlike the SNMP passthrough which has to
 * unwrap each packet to a bare ESC/P command. The win over libusb is that it needs no driver
 * rebind (Zadig): the printer's own Windows driver is the pipe, and it stays usable for printing.
 */
class WinspoolTransport internal constructor(
    private val channel: RawPrinterChannel,
    private val drainTimeoutMs: Int = DRAIN_TIMEOUT_MS,
) : Transport {

    private var closed = false

    override fun send(packet: ByteArray): Boolean = channel.write(packet) == packet.size

    /** Polls the back-channel until the reply arrives and then stops, or the window elapses empty. */
    override fun drain(): ByteArray {
        val collected = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        val deadline = System.currentTimeMillis() + drainTimeoutMs
        var sawData = false

        while (System.currentTimeMillis() < deadline) {
            val n = channel.read(buffer)
            when {
                n > 0 -> {
                    collected.write(buffer, 0, n)
                    sawData = true
                }
                // A gap after real data means the reply is complete; a gap before it means the
                // printer hasn't answered yet, so keep waiting.
                sawData -> break
                else -> Thread.sleep(POLL_INTERVAL_MS)
            }
        }

        return collected.toByteArray()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { channel.close() }
    }

    sealed interface OpenResult {
        data class Ok(val transport: WinspoolTransport) : OpenResult
        data class Failed(val message: String, val remedy: String?) : OpenResult
    }

    companion object {
        private const val DRAIN_TIMEOUT_MS = 1500
        private const val POLL_INTERVAL_MS = 25L
        private const val READ_CHUNK = 512

        fun open(link: Link.WindowsPrinter, drainTimeoutMs: Int = DRAIN_TIMEOUT_MS): OpenResult {
            val spool = Winspool.instance
                ?: return OpenResult.Failed(
                    "The Windows print spooler is not available.",
                    Winspool.loadError,
                )

            return when (val channel = WinspoolChannel.open(spool, link.queueName)) {
                is WinspoolChannel.Result.Ok ->
                    OpenResult.Ok(WinspoolTransport(channel.channel, drainTimeoutMs))
                is WinspoolChannel.Result.Failed -> OpenResult.Failed(channel.message, channel.remedy)
            }
        }
    }
}

/** The real spooler pipe: OpenPrinter → StartDocPrinter(RAW) → StartPagePrinter, then read/write. */
private class WinspoolChannel private constructor(private val spool: Winspool, private val handle: Pointer) :
    RawPrinterChannel {

    override fun write(data: ByteArray): Int {
        val written = IntByReference()
        return if (spool.WritePrinter(handle, data, data.size, written)) written.value else -1
    }

    override fun read(buffer: ByteArray): Int {
        val read = IntByReference()
        return if (spool.ReadPrinter(handle, buffer, buffer.size, read)) read.value else -1
    }

    override fun close() {
        runCatching { spool.EndPagePrinter(handle) }
        runCatching { spool.EndDocPrinter(handle) }
        runCatching { spool.ClosePrinter(handle) }
    }

    sealed interface Result {
        data class Ok(val channel: RawPrinterChannel) : Result
        data class Failed(val message: String, val remedy: String?) : Result
    }

    companion object {
        fun open(spool: Winspool, queueName: String): Result {
            val handleRef = PointerByReference()
            if (!spool.OpenPrinterW(WString(queueName), handleRef, null) || handleRef.value == null) {
                return Result.Failed(
                    "Could not open the printer \"$queueName\".",
                    "Plug the printer in and let Windows finish installing its driver, then rescan.",
                )
            }
            val handle = handleRef.value

            val docInfo = Winspool.DocInfo1().apply {
                pDocName = WString("Epson Reset")
                pDatatype = WString("RAW")
            }
            val job = spool.StartDocPrinterW(handle, 1, docInfo)
            if (job == 0) {
                spool.ClosePrinter(handle)
                return Result.Failed(
                    "The printer \"$queueName\" would not accept a job.",
                    "Make sure it is switched on and not paused in Settings → Bluetooth & devices → " +
                        "Printers, then rescan.",
                )
            }

            if (!spool.StartPagePrinter(handle)) {
                spool.EndDocPrinter(handle)
                spool.ClosePrinter(handle)
                return Result.Failed("The printer \"$queueName\" would not start the job.", null)
            }

            return Result.Ok(WinspoolChannel(spool, handle))
        }
    }
}
