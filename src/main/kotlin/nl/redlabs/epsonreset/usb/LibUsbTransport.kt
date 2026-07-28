package nl.redlabs.epsonreset.usb

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.protocol.EpsonD4
import nl.redlabs.epsonreset.protocol.Transport

/** Bulk-transfer [Transport] over a claimed USB interface. */
class LibUsbTransport private constructor(
    private val usb: LibUsb,
    private val handle: Pointer,
    private val interfaceNumber: Int,
    private val endpointIn: Byte,
    private val endpointOut: Byte,
    private val detachedKernelDriver: Boolean,
    private val drainTimeoutMs: Int = DRAIN_TIMEOUT_MS,
) : Transport {

    private var closed = false

    override fun send(packet: ByteArray): Boolean {
        val transferred = IntByReference()
        val status = usb.libusb_bulk_transfer(
            handle,
            endpointOut,
            packet,
            packet.size,
            transferred,
            SEND_TIMEOUT_MS,
        )
        return status == LibUsb.SUCCESS && transferred.value == packet.size
    }

    /** Reads until the printer goes quiet or errors. */
    override fun drain(): ByteArray {
        val collected = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)

        while (true) {
            val received = IntByReference()
            val status = usb.libusb_bulk_transfer(
                handle,
                endpointIn,
                buffer,
                buffer.size,
                received,
                drainTimeoutMs,
            )
            if (received.value > 0) collected.write(buffer, 0, received.value)
            if (status != LibUsb.SUCCESS || received.value == 0) break
        }

        return collected.toByteArray()
    }

    override fun close() {
        if (closed) return
        closed = true

        runCatching {
            usb.libusb_release_interface(handle, interfaceNumber)
            if (detachedKernelDriver) usb.libusb_attach_kernel_driver(handle, interfaceNumber)
            usb.libusb_close(handle)
        }
        runCatching { usb.libusb_exit(null) }
    }

    sealed interface OpenResult {
        data class Ok(val transport: LibUsbTransport) : OpenResult
        data class Failed(val message: String, val remedy: String?) : OpenResult
    }

    companion object {
        private const val SEND_TIMEOUT_MS = 2000
        private const val DRAIN_TIMEOUT_MS = 250
        private const val READ_CHUNK = 256

        fun open(target: DetectedPrinter, drainTimeoutMs: Int = DRAIN_TIMEOUT_MS): OpenResult {
            val link = target.link as? Link.Usb
                ?: return OpenResult.Failed(
                    "${target.displayName} is not a USB device.",
                    null,
                )

            val usb = LibUsb.instance
                ?: return OpenResult.Failed(
                    "libusb-1.0 is not available.",
                    UsbPrinterScanner.installHint(),
                )

            if (usb.libusb_init(null) < 0) {
                return OpenResult.Failed("Failed to initialise libusb.", null)
            }

            val listRef = PointerByReference()
            val count = usb.libusb_get_device_list(null, listRef)
            if (count < 0 || listRef.value == null) {
                usb.libusb_exit(null)
                return OpenResult.Failed("Could not enumerate USB devices.", null)
            }
            val listHead = listRef.value

            var handle: Pointer? = null
            var detached = false

            try {
                val device = findDevice(usb, listHead, count, link)
                    ?: return OpenResult.Failed(
                        "Printer ${target.displayName} is no longer on the bus.",
                        "Reconnect it and rescan.",
                    ).also { usb.libusb_exit(null) }

                val handleRef = PointerByReference()
                val status = usb.libusb_open(device, handleRef)
                handle = handleRef.value

                if (status != LibUsb.SUCCESS || handle == null) {
                    usb.libusb_exit(null)
                    return OpenResult.Failed(
                        "Could not open the printer (${LibUsb.errorName(status)}).",
                        openRemedy(status),
                    )
                }

                // Linux: hand the interface over from the usblp/CUPS driver. Returns
                // NOT_SUPPORTED on macOS and Windows, which is fine to ignore.
                if (usb.libusb_kernel_driver_active(handle, link.interfaceNumber) == 1) {
                    detached = usb.libusb_detach_kernel_driver(handle, link.interfaceNumber) == LibUsb.SUCCESS
                }

                val claim = usb.libusb_claim_interface(handle, link.interfaceNumber)
                if (claim != LibUsb.SUCCESS) {
                    usb.libusb_close(handle)
                    usb.libusb_exit(null)
                    return OpenResult.Failed(
                        "Could not claim the printer interface (${LibUsb.errorName(claim)}).",
                        claimRemedy(claim),
                    )
                }

                return OpenResult.Ok(
                    LibUsbTransport(
                        usb = usb,
                        handle = handle,
                        interfaceNumber = link.interfaceNumber,
                        endpointIn = link.endpointIn,
                        endpointOut = link.endpointOut,
                        detachedKernelDriver = detached,
                        drainTimeoutMs = drainTimeoutMs,
                    ),
                )
            } finally {
                usb.libusb_free_device_list(listHead, 1)
            }
        }

        private fun findDevice(usb: LibUsb, listHead: Pointer, count: Int, target: Link.Usb): Pointer? =
            listHead.getPointerArray(0, count).firstOrNull { device ->
                device != null &&
                    (usb.libusb_get_bus_number(device).toInt() and 0xFF) == target.busNumber &&
                    (usb.libusb_get_device_address(device).toInt() and 0xFF) == target.deviceAddress
            }

        private fun openRemedy(status: Int): String? = when (status) {
            LibUsb.ERROR_ACCESS -> if (isLinux()) {
                "Run with sudo, or add a udev rule for vendor %04X.".format(EpsonD4.EPSON_VID)
            } else {
                "Another process is holding the printer. Close print queues and try again."
            }
            LibUsb.ERROR_NO_DEVICE -> "Reconnect the printer and rescan."
            else -> null
        }

        private fun claimRemedy(status: Int): String? = when {
            status == LibUsb.ERROR_BUSY || status == LibUsb.ERROR_ACCESS -> when {
                isMac() ->
                    "macOS's printer driver owns this device. Remove the printer in " +
                        "System Settings → Printers & Scanners, then rescan."
                isLinux() -> "Stop CUPS (sudo systemctl stop cups) or run with sudo, then retry."
                else -> "Bind the device to a libusb driver with Zadig, then retry."
            }
            else -> null
        }

        private fun isMac() = System.getProperty("os.name").lowercase().contains("mac")
        private fun isLinux() = System.getProperty("os.name").lowercase().let {
            !it.contains("mac") && !it.contains("win")
        }
    }
}
