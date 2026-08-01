package nl.redlabs.epsonreset.usb

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import nl.redlabs.epsonreset.Diag
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.protocol.EscpRemote
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
 * The spooler's RAW datatype writes to the printer's **print-data** service, not the 1284.4 control
 * socket that libusb claims. So this follows the [SnmpTransport][nl.redlabs.epsonreset.net.SnmpTransport]
 * pattern, not [LibUsbTransport]: the 1284.4 channel-open/credit packets are dropped (they would be
 * stray bytes in a print stream), and each data packet is sent as just its ESC/P factory command,
 * unwrapped from its D4 frame. The win over libusb is that it needs no driver rebind (Zadig): the
 * printer's own Windows driver is the pipe, and it stays usable for printing.
 *
 * Whether a given printer answers factory commands on this service is a firmware question that only
 * real hardware settles — see the note in [docs/usb-connection.md].
 */
class WinspoolTransport internal constructor(
    private val channel: RawPrinterChannel,
    private val drainTimeoutMs: Int = DRAIN_TIMEOUT_MS,
) : Transport {

    private var closed = false

    override fun send(packet: ByteArray): Boolean {
        // No 1284.4 channel to open and no credit to grant on the print-data service — drop those,
        // reporting success because nothing failed (same as SnmpTransport).
        if (EscpRemote.isChannelPacket(packet)) return true
        val command = EscpRemote.remoteCommandOf(packet) ?: return false
        return channel.write(command) == command.size
    }

    /**
     * Reads the reply. `ReadPrinter` blocks until the printer answers or the USB port's own timeout
     * (seconds) elapses, so this stops the instant a whole reply is in hand — Epson factory replies
     * end in `;` — rather than paying another blocking read for data that will never come.
     */
    override fun drain(): ByteArray {
        val collected = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        val deadline = System.currentTimeMillis() + drainTimeoutMs

        while (System.currentTimeMillis() < deadline) {
            val n = channel.read(buffer)
            if (n <= 0) break
            collected.write(buffer, 0, n)
            if (collected.toByteArray().contains(REPLY_TERMINATOR)) break
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
        private const val DRAIN_TIMEOUT_MS = 6000
        private const val READ_CHUNK = 512

        /** Epson factory replies (`…EE:001C19;`, `||:42:OK;`) end here; a whole one has arrived. */
        private const val REPLY_TERMINATOR = ';'.code.toByte()

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
        val ok = spool.WritePrinter(handle, data, data.size, written)
        Diag.log {
            "[DBG] WritePrinter ok=$ok wrote=${written.value}/${data.size} err=${Native.getLastError()}"
        }
        return if (ok) written.value else -1
    }

    override fun read(buffer: ByteArray): Int {
        val read = IntByReference()
        val ok = spool.ReadPrinter(handle, buffer, buffer.size, read)
        Diag.log { "[DBG] ReadPrinter ok=$ok read=${read.value} err=${Native.getLastError()}" }
        return if (ok) read.value else -1
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
            Diag.log { "[DBG] OpenPrinter \"$queueName\"…" }
            val handleRef = PointerByReference()
            if (!spool.OpenPrinterW(WString(queueName), handleRef, null) || handleRef.value == null) {
                Diag.log { "[DBG] OpenPrinter failed err=${Native.getLastError()}" }
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
            Diag.log { "[DBG] StartDocPrinter(RAW) job=$job err=${Native.getLastError()}" }
            if (job == 0) {
                spool.ClosePrinter(handle)
                return Result.Failed(
                    "The printer \"$queueName\" would not accept a job.",
                    "Make sure it is switched on and not paused in Settings → Bluetooth & devices → " +
                        "Printers, then rescan.",
                )
            }

            val page = spool.StartPagePrinter(handle)
            Diag.log { "[DBG] StartPagePrinter ok=$page err=${Native.getLastError()}" }
            if (!page) {
                spool.EndDocPrinter(handle)
                spool.ClosePrinter(handle)
                return Result.Failed("The printer \"$queueName\" would not start the job.", null)
            }

            return Result.Ok(WinspoolChannel(spool, handle))
        }
    }
}
