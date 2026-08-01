package nl.redlabs.epsonreset.usb

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** Minimal JNA binding for libusb-1.0 — just the calls the reset flow needs. */
// JNA maps struct fields by name, so `MaxPower` and `extra_length` are the names libusb's headers
// use and not ours to camel-case: renaming them compiles and then reads the wrong offsets at run
// time. Hence the naming rules are off for this file rather than for the codebase.
@Suppress("FunctionName", "TooManyFunctions", "ktlint:standard:property-naming")
interface LibUsb : Library {

    fun libusb_init(ctx: PointerByReference?): Int
    fun libusb_exit(ctx: Pointer?)
    fun libusb_error_name(code: Int): String?

    fun libusb_get_device_list(ctx: Pointer?, list: PointerByReference): Int
    fun libusb_free_device_list(list: Pointer, unrefDevices: Int)

    fun libusb_get_device_descriptor(device: Pointer, desc: DeviceDescriptor): Int
    fun libusb_get_bus_number(device: Pointer): Byte
    fun libusb_get_device_address(device: Pointer): Byte

    fun libusb_open(device: Pointer, handle: PointerByReference): Int
    fun libusb_close(handle: Pointer)

    fun libusb_get_active_config_descriptor(device: Pointer, config: PointerByReference): Int
    fun libusb_free_config_descriptor(config: Pointer)

    fun libusb_get_string_descriptor_ascii(handle: Pointer, descIndex: Byte, data: ByteArray, length: Int): Int

    fun libusb_kernel_driver_active(handle: Pointer, interfaceNumber: Int): Int
    fun libusb_detach_kernel_driver(handle: Pointer, interfaceNumber: Int): Int
    fun libusb_attach_kernel_driver(handle: Pointer, interfaceNumber: Int): Int
    fun libusb_set_auto_detach_kernel_driver(handle: Pointer, enable: Int): Int

    fun libusb_claim_interface(handle: Pointer, interfaceNumber: Int): Int
    fun libusb_release_interface(handle: Pointer, interfaceNumber: Int): Int

    fun libusb_bulk_transfer(
        handle: Pointer,
        endpoint: Byte,
        data: ByteArray,
        length: Int,
        transferred: IntByReference,
        timeout: Int,
    ): Int

    @Structure.FieldOrder(
        "bLength", "bDescriptorType", "bcdUSB", "bDeviceClass", "bDeviceSubClass",
        "bDeviceProtocol", "bMaxPacketSize0", "idVendor", "idProduct", "bcdDevice",
        "iManufacturer", "iProduct", "iSerialNumber", "bNumConfigurations",
    )
    class DeviceDescriptor : Structure() {
        @JvmField var bLength: Byte = 0

        @JvmField var bDescriptorType: Byte = 0

        @JvmField var bcdUSB: Short = 0

        @JvmField var bDeviceClass: Byte = 0

        @JvmField var bDeviceSubClass: Byte = 0

        @JvmField var bDeviceProtocol: Byte = 0

        @JvmField var bMaxPacketSize0: Byte = 0

        @JvmField var idVendor: Short = 0

        @JvmField var idProduct: Short = 0

        @JvmField var bcdDevice: Short = 0

        @JvmField var iManufacturer: Byte = 0

        @JvmField var iProduct: Byte = 0

        @JvmField var iSerialNumber: Byte = 0

        @JvmField var bNumConfigurations: Byte = 0
    }

    @Structure.FieldOrder(
        "bLength", "bDescriptorType", "wTotalLength", "bNumInterfaces", "bConfigurationValue",
        "iConfiguration", "bmAttributes", "MaxPower", "iface", "extra", "extra_length",
    )
    class ConfigDescriptor(p: Pointer) : Structure(p) {
        @JvmField var bLength: Byte = 0

        @JvmField var bDescriptorType: Byte = 0

        @JvmField var wTotalLength: Short = 0

        @JvmField var bNumInterfaces: Byte = 0

        @JvmField var bConfigurationValue: Byte = 0

        @JvmField var iConfiguration: Byte = 0

        @JvmField var bmAttributes: Byte = 0

        @JvmField var MaxPower: Byte = 0

        @JvmField var iface: Pointer? = null

        @JvmField var extra: Pointer? = null

        @JvmField var extra_length: Int = 0

        init {
            read()
        }

        /** `struct libusb_interface*` array, one per interface. */
        fun interfaces(): List<Interface> {
            val base = iface ?: return emptyList()
            val count = bNumInterfaces.toInt() and 0xFF
            if (count == 0) return emptyList()
            return Interface(base).toArray(count).map { it as Interface }
        }
    }

    @Structure.FieldOrder("altsetting", "num_altsetting")
    class Interface(p: Pointer) : Structure(p) {
        @JvmField var altsetting: Pointer? = null

        @JvmField var num_altsetting: Int = 0

        init {
            read()
        }

        /** Alt-setting 0 is the one the reset path uses. */
        fun firstAltSetting(): InterfaceDescriptor? {
            val base = altsetting ?: return null
            if (num_altsetting <= 0) return null
            return InterfaceDescriptor(base)
        }
    }

    @Structure.FieldOrder(
        "bLength", "bDescriptorType", "bInterfaceNumber", "bAlternateSetting", "bNumEndpoints",
        "bInterfaceClass", "bInterfaceSubClass", "bInterfaceProtocol", "iInterface",
        "endpoint", "extra", "extra_length",
    )
    class InterfaceDescriptor(p: Pointer) : Structure(p) {
        @JvmField var bLength: Byte = 0

        @JvmField var bDescriptorType: Byte = 0

        @JvmField var bInterfaceNumber: Byte = 0

        @JvmField var bAlternateSetting: Byte = 0

        @JvmField var bNumEndpoints: Byte = 0

        @JvmField var bInterfaceClass: Byte = 0

        @JvmField var bInterfaceSubClass: Byte = 0

        @JvmField var bInterfaceProtocol: Byte = 0

        @JvmField var iInterface: Byte = 0

        @JvmField var endpoint: Pointer? = null

        @JvmField var extra: Pointer? = null

        @JvmField var extra_length: Int = 0

        init {
            read()
        }

        fun endpoints(): List<EndpointDescriptor> {
            val base = endpoint ?: return emptyList()
            val count = bNumEndpoints.toInt() and 0xFF
            if (count == 0) return emptyList()
            return EndpointDescriptor(base).toArray(count).map { it as EndpointDescriptor }
        }
    }

    @Structure.FieldOrder(
        "bLength", "bDescriptorType", "bEndpointAddress", "bmAttributes", "wMaxPacketSize",
        "bInterval", "bRefresh", "bSynchAddress", "extra", "extra_length",
    )
    class EndpointDescriptor(p: Pointer) : Structure(p) {
        @JvmField var bLength: Byte = 0

        @JvmField var bDescriptorType: Byte = 0

        @JvmField var bEndpointAddress: Byte = 0

        @JvmField var bmAttributes: Byte = 0

        @JvmField var wMaxPacketSize: Short = 0

        @JvmField var bInterval: Byte = 0

        @JvmField var bRefresh: Byte = 0

        @JvmField var bSynchAddress: Byte = 0

        @JvmField var extra: Pointer? = null

        @JvmField var extra_length: Int = 0

        init {
            read()
        }
    }

    class Context : PointerType()

    companion object {
        const val CLASS_PRINTER = 0x07
        const val CLASS_VENDOR_SPEC = 0xFF
        const val TRANSFER_TYPE_MASK = 0x03
        const val TRANSFER_TYPE_BULK = 0x02
        const val ENDPOINT_IN = 0x80

        const val SUCCESS = 0
        const val ERROR_ACCESS = -3
        const val ERROR_NO_DEVICE = -4
        const val ERROR_BUSY = -6
        const val ERROR_TIMEOUT = -7
        const val ERROR_NOT_SUPPORTED = -12

        /** Null when libusb isn't installed; every caller degrades instead of crashing. */
        val instance: LibUsb? by lazy { tryLoad() }

        /** Why the load failed, for the UI to show verbatim. Null once [instance] is non-null. */
        var loadError: String? = null
            private set

        private fun tryLoad(): LibUsb? {
            // Homebrew (both arch prefixes) and MacPorts land outside JNA's default search path.
            listOf("/opt/homebrew/lib", "/usr/local/lib", "/opt/local/lib", "/usr/lib")
                .filter { java.io.File(it).isDirectory }
                .forEach { NativeLibrary.addSearchPath("usb-1.0", it) }

            // Windows has no libusb package; the installer bundles usb-1.0.dll in the app image, which
            // isn't on the default search path when jpackage launches from its own runtime.
            if (System.getProperty("os.name").lowercase().contains("win")) {
                windowsBundleDirs().forEach { NativeLibrary.addSearchPath("usb-1.0", it) }
            }

            return try {
                Native.load("usb-1.0", LibUsb::class.java)
            } catch (e: UnsatisfiedLinkError) {
                loadError = e.message ?: "libusb-1.0 not found"
                null
            }
        }

        /** The jpackage app image dirs — `.../EpsonReset` and its `app/` — where the DLL is shipped. */
        private fun windowsBundleDirs(): List<String> {
            val root = System.getProperty("java.home")?.let { java.io.File(it).parentFile } ?: return emptyList()
            return listOf(root, java.io.File(root, "app"))
                .filter { it.isDirectory }
                .map { it.absolutePath }
        }

        fun errorName(code: Int): String =
            runCatching { instance?.libusb_error_name(code) }.getOrNull() ?: "error $code"
    }
}
