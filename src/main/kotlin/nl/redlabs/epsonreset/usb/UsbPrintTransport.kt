package nl.redlabs.epsonreset.usb

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import nl.redlabs.epsonreset.Diag
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.protocol.EpsonD4
import nl.redlabs.epsonreset.protocol.Transport

/**
 * [Transport] over the `usbprint.sys` device interface — the driverless Windows USB path that
 * reaches the printer's real endpoints.
 *
 * Bytes written to this handle hit the wire as-is, so unlike [WinspoolTransport] this sends the
 * **full 1284.4 protocol** — handshake, channel open, credit, D4-framed data — exactly like
 * [LibUsbTransport], with no driver rebind and no library to install: the handle comes from the
 * driver Windows already bound. The printer stays a normal Windows printer.
 *
 * The handle is opened exclusively, so anything else holding the port — a job mid-print, EPSON
 * Status Monitor — surfaces as a sharing violation with the remedy attached.
 */
class UsbPrintTransport internal constructor(private val channel: RawPrinterChannel) : Transport {

    private var closed = false

    override fun send(packet: ByteArray): Boolean = channel.write(packet) == packet.size

    /** Reads until the printer goes quiet or errors, mirroring [LibUsbTransport]. */
    override fun drain(): ByteArray {
        val collected = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)

        while (true) {
            val n = channel.read(buffer)
            if (n <= 0) break
            collected.write(buffer, 0, n)
        }

        return collected.toByteArray()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { channel.close() }
    }

    sealed interface OpenResult {
        data class Ok(val transport: UsbPrintTransport) : OpenResult
        data class Failed(val message: String, val remedy: String?) : OpenResult
    }

    companion object {
        private const val DRAIN_TIMEOUT_MS = 250
        private const val READ_CHUNK = 256

        fun open(link: Link.WindowsPrinter, drainTimeoutMs: Int = DRAIN_TIMEOUT_MS): OpenResult {
            val io = UsbPrint.kernel32
                ?: return OpenResult.Failed("Windows I/O is not available.", UsbPrint.loadError)

            val paths = when (val listed = UsbPrint.printerInterfacePaths()) {
                is UsbPrint.PathsResult.Ok -> listed.paths
                is UsbPrint.PathsResult.Failed -> return OpenResult.Failed(
                    "Could not list USB printer interfaces.",
                    listed.message,
                )
            }
            Diag.log { "[DBG] usbprint interfaces: ${if (paths.isEmpty()) "(none)" else paths.joinToString()}" }

            // The interface list only holds *live* devices, so absence usually means the printer
            // is off or unplugged — or something has claimed it away from Windows.
            val path = pickEpsonInterface(paths)
                ?: return OpenResult.Failed(
                    "The printer is not answering on USB.",
                    "Check it is on and the cable is seated. If it is shared or forwarded to " +
                        "another machine, release it there, then rescan.",
                )
            Diag.log { "[DBG] usbprint open $path (pid=${pidOf(path) ?: "?"}) for \"${link.queueName}\"" }

            val handle = io.CreateFileW(
                WString(path),
                UsbPrint.GENERIC_READ or UsbPrint.GENERIC_WRITE,
                0, // exclusive — factory commands and a print job must not interleave
                null,
                UsbPrint.OPEN_EXISTING,
                UsbPrint.FILE_FLAG_OVERLAPPED,
                null,
            )
            if (UsbPrint.isInvalidHandle(handle)) {
                val err = Native.getLastError()
                Diag.log { "[DBG] usbprint CreateFile failed err=$err" }
                return OpenResult.Failed(
                    "Could not open the printer \"${link.queueName}\" (Windows error $err).",
                    when (err) {
                        UsbPrint.ERROR_SHARING_VIOLATION ->
                            "Another program is holding the printer. Close EPSON Status Monitor " +
                                "(system tray, bottom right), wait for any print job to finish, and " +
                                "release the printer wherever else it is shared, then retry."
                        UsbPrint.ERROR_ACCESS_DENIED ->
                            "Close other printer software, or run the app as administrator, then retry."
                        UsbPrint.ERROR_FILE_NOT_FOUND ->
                            "The printer dropped off the bus. Reconnect it and rescan."
                        else -> null
                    },
                )
            }

            return OpenResult.Ok(UsbPrintTransport(UsbPrintChannel(io, handle, drainTimeoutMs)))
        }

        /**
         * The Epson interface most likely to be the printer engine. On a composite device
         * (printer + scanner) the engine is the lowest `mi_` interface; a single-function
         * printer's path has no `mi_` at all and any match is the engine. With several printers
         * attached this picks one of them — the paths carry no queue name to pair against.
         */
        internal fun pickEpsonInterface(paths: List<String>): String? =
            paths.filter { it.contains(EPSON_VID_TOKEN, ignoreCase = true) }
                .minByOrNull { interfaceIndex(it) ?: 0 }

        /** The `mi_NN` interface number in a device path, null for a non-composite device. */
        internal fun interfaceIndex(path: String): Int? = Regex("mi_([0-9a-f]{2})", RegexOption.IGNORE_CASE)
            .find(path)?.groupValues?.get(1)?.toInt(16)

        /** The `pid_NNNN` hex token in a device path, for diagnostics. */
        internal fun pidOf(path: String): String? = Regex("pid_([0-9a-f]{4})", RegexOption.IGNORE_CASE)
            .find(path)?.groupValues?.get(1)?.lowercase()

        private val EPSON_VID_TOKEN = "vid_%04x".format(EpsonD4.EPSON_VID)
    }
}

/**
 * The real endpoint pipe: overlapped `ReadFile`/`WriteFile` on the `usbprint.sys` handle. Every
 * call starts the I/O, waits on its event with a deadline, and cancels on timeout — a synchronous
 * read on this handle would otherwise block until the printer decides to talk, which for a wrong
 * key or model is never.
 */
private class UsbPrintChannel(
    private val io: UsbPrint.Kernel32Io,
    private val handle: Pointer,
    private val readTimeoutMs: Int,
) : RawPrinterChannel {

    override fun write(data: ByteArray): Int {
        val buffer = Memory(data.size.toLong()).apply { write(0, data, 0, data.size) }
        val n = overlapped(WRITE_TIMEOUT_MS) { ov, transferred ->
            io.WriteFile(handle, buffer, data.size, transferred, ov)
        }
        Diag.log { "[DBG] usbprint write $n/${data.size}" }
        return n
    }

    override fun read(buffer: ByteArray): Int {
        val native = Memory(buffer.size.toLong())
        val n = overlapped(readTimeoutMs) { ov, transferred ->
            io.ReadFile(handle, native, buffer.size, transferred, ov)
        }
        if (n > 0) native.read(0, buffer, 0, n)
        Diag.log { "[DBG] usbprint read $n" }
        return n
    }

    override fun close() {
        runCatching { io.CancelIo(handle) }
        runCatching { io.CloseHandle(handle) }
    }

    /**
     * One overlapped operation: bytes transferred, 0 when the deadline passed with nothing, -1 on
     * failure. A timed-out request is cancelled and then waited out — the kernel owns the buffer
     * until the cancel completes — keeping whatever partial data beat the cancel.
     */
    private fun overlapped(timeoutMs: Int, start: (Pointer, IntByReference) -> Boolean): Int {
        val event = io.CreateEventW(null, true, false, null) ?: return -1
        val ov = Memory(UsbPrint.OVERLAPPED_SIZE).apply {
            clear()
            setPointer(UsbPrint.OVERLAPPED_HEVENT_OFFSET, event)
        }
        val transferred = IntByReference()

        try {
            if (start(ov, transferred)) return transferred.value

            val err = Native.getLastError()
            if (err != UsbPrint.ERROR_IO_PENDING) {
                Diag.log { "[DBG] usbprint I/O failed err=$err" }
                return -1
            }

            return if (io.WaitForSingleObject(event, timeoutMs) == UsbPrint.WAIT_OBJECT_0) {
                if (io.GetOverlappedResult(handle, ov, transferred, false)) transferred.value else -1
            } else {
                io.CancelIo(handle)
                io.GetOverlappedResult(handle, ov, transferred, true)
                transferred.value
            }
        } finally {
            io.CloseHandle(event)
        }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 5000
    }
}
