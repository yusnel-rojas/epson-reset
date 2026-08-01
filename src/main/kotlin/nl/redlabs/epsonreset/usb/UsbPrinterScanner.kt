package nl.redlabs.epsonreset.usb

import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.protocol.EpsonD4

/** Enumerates every Epson on the USB bus. */
object UsbPrinterScanner {

    sealed interface ScanResult {
        data class Ok(val printers: List<DetectedPrinter>) : ScanResult

        /** libusb isn't installed. [hint] is a platform-specific install line. */
        data class LibraryMissing(val detail: String, val hint: String) : ScanResult

        data class Failed(val message: String) : ScanResult
    }

    fun scan(): ScanResult {
        val usb = LibUsb.instance
            ?: return ScanResult.LibraryMissing(
                detail = LibUsb.loadError ?: "libusb-1.0 could not be loaded",
                hint = installHint(),
            )

        if (usb.libusb_init(null) < 0) return ScanResult.Failed("Failed to initialise libusb.")

        return try {
            ScanResult.Ok(enumerate(usb))
        } catch (e: Exception) {
            ScanResult.Failed(e.message ?: e.toString())
        } finally {
            usb.libusb_exit(null)
        }
    }

    private fun enumerate(usb: LibUsb): List<DetectedPrinter> {
        val listRef = PointerByReference()
        val count = usb.libusb_get_device_list(null, listRef)
        if (count < 0) return emptyList()

        val listHead = listRef.value ?: return emptyList()
        val found = mutableListOf<DetectedPrinter>()

        try {
            val devices = listHead.getPointerArray(0, count)
            for (device in devices) {
                if (device == null) continue
                inspect(usb, device)?.let { found += it }
            }
        } finally {
            usb.libusb_free_device_list(listHead, 1)
        }

        return found
    }

    private fun inspect(usb: LibUsb, device: Pointer): DetectedPrinter? {
        val desc = LibUsb.DeviceDescriptor()
        if (usb.libusb_get_device_descriptor(device, desc) < 0) return null
        if ((desc.idVendor.toInt() and 0xFFFF) != EpsonD4.EPSON_VID) return null

        val endpoints = findBulkInterface(usb, device) ?: return null

        // Descriptor strings need the device open. On macOS the OS printer driver often holds it,
        // so a failure here is informational, not fatal — the device is still listed.
        var manufacturer: String? = null
        var product: String? = null
        var serial: String? = null
        var accessNote: String? = null

        val handleRef = PointerByReference()
        val openStatus = usb.libusb_open(device, handleRef)
        val handle = handleRef.value

        if (openStatus == LibUsb.SUCCESS && handle != null) {
            manufacturer = readString(usb, handle, desc.iManufacturer)
            product = readString(usb, handle, desc.iProduct)
            serial = readString(usb, handle, desc.iSerialNumber)
            usb.libusb_close(handle)
        } else {
            accessNote = when (openStatus) {
                LibUsb.ERROR_ACCESS -> "Permission denied reading device details."
                LibUsb.ERROR_NO_DEVICE -> "Device disconnected during scan."
                else -> "Could not read device details (${LibUsb.errorName(openStatus)})."
            }
        }

        return DetectedPrinter(
            link = Link.Usb(
                busNumber = usb.libusb_get_bus_number(device).toInt() and 0xFF,
                deviceAddress = usb.libusb_get_device_address(device).toInt() and 0xFF,
                interfaceNumber = endpoints.interfaceNumber,
                endpointIn = endpoints.endpointIn,
                endpointOut = endpoints.endpointOut,
                isPrinterClass = endpoints.isPrinterClass,
            ),
            manufacturer = manufacturer,
            product = product,
            serial = serial,
            productId = desc.idProduct.toInt() and 0xFFFF,
            accessNote = accessNote,
        )
    }

    internal data class BulkInterface(
        val interfaceNumber: Int,
        val endpointIn: Byte,
        val endpointOut: Byte,
        val isPrinterClass: Boolean,
    )

    /**
     * Picks the interface to talk over: a printer-class one if present, else the first vendor-
     * specific interface with both bulk directions.
     */
    private fun findBulkInterface(usb: LibUsb, device: Pointer): BulkInterface? {
        val configRef = PointerByReference()
        if (usb.libusb_get_active_config_descriptor(device, configRef) != LibUsb.SUCCESS) return null
        val configPtr = configRef.value ?: return null

        return try {
            val config = LibUsb.ConfigDescriptor(configPtr)
            var fallback: BulkInterface? = null

            for ((index, iface) in config.interfaces().withIndex()) {
                val alt = iface.firstAltSetting() ?: continue
                val cls = alt.bInterfaceClass.toInt() and 0xFF
                if (cls != LibUsb.CLASS_PRINTER && cls != LibUsb.CLASS_VENDOR_SPEC) continue

                var epIn: Byte = 0
                var epOut: Byte = 0
                for (ep in alt.endpoints()) {
                    val attrs = ep.bmAttributes.toInt() and LibUsb.TRANSFER_TYPE_MASK
                    if (attrs != LibUsb.TRANSFER_TYPE_BULK) continue

                    if ((ep.bEndpointAddress.toInt() and LibUsb.ENDPOINT_IN) != 0) {
                        epIn = ep.bEndpointAddress
                    } else {
                        epOut = ep.bEndpointAddress
                    }
                }
                if (epIn.toInt() == 0 || epOut.toInt() == 0) continue

                // bInterfaceNumber is the number to claim; the array index is not always the same.
                val number = alt.bInterfaceNumber.toInt() and 0xFF
                val candidate = BulkInterface(
                    interfaceNumber = if (number != 0 || index == 0) number else index,
                    endpointIn = epIn,
                    endpointOut = epOut,
                    isPrinterClass = cls == LibUsb.CLASS_PRINTER,
                )

                if (cls == LibUsb.CLASS_PRINTER) return candidate
                if (fallback == null) fallback = candidate
            }
            fallback
        } finally {
            usb.libusb_free_config_descriptor(configPtr)
        }
    }

    private fun readString(usb: LibUsb, handle: Pointer, index: Byte): String? {
        if (index.toInt() == 0) return null
        val buffer = ByteArray(256)
        val length = usb.libusb_get_string_descriptor_ascii(handle, index, buffer, buffer.size)
        if (length <= 0) return null
        return String(buffer, 0, length, Charsets.ISO_8859_1).trim().takeIf { it.isNotEmpty() }
    }

    fun installHint(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> "brew install libusb"
            // The spooler path is the default on Windows; libusb is only reached when no Epson queue
            // was found, so the fix is normally to install the printer, not to reach for Zadig.
            os.contains("win") ->
                "Plug in the printer and let Windows install its driver, then rescan. " +
                    "(Advanced: bind it to a libusb driver with Zadig.)"
            else -> "sudo apt install libusb-1.0-0    # or your distro's equivalent"
        }
    }
}
