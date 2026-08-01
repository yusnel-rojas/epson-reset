package nl.redlabs.epsonreset.usb

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Minimal JNA bindings for talking to a USB printer's real endpoints on Windows with nothing
 * installed.
 *
 * `usbprint.sys` — the driver Windows itself binds to every USB printer — registers a device
 * interface (`\\?\usb#vid_04b8&pid_…#…#{28d78fad-…}`) that opens with plain `CreateFile`, and
 * `WriteFile`/`ReadFile` on that handle are raw bulk transfers on the wire. Unlike a spooler RAW
 * job, nothing routes these bytes into the print-data service: the full 1284.4 handshake and the
 * control socket work, the same reach libusb has with Zadig — minus libusb and minus Zadig. This
 * is the channel Epson's own Adjustment Program uses.
 *
 * Two libraries are needed: `cfgmgr32` to list the registered interface paths, and `kernel32` to
 * open one and move bytes. Reads on the handle block until the printer talks, so all I/O is
 * OVERLAPPED with an explicit wait — see [UsbPrintTransport].
 */
object UsbPrint {

    /** The `cfgmgr32` calls that enumerate device interface paths. */
    @Suppress("FunctionName")
    interface CfgMgr32 : StdCallLibrary {
        fun CM_Get_Device_Interface_List_SizeW(
            len: IntByReference,
            interfaceClassGuid: Guid,
            deviceId: WString?,
            flags: Int,
        ): Int

        fun CM_Get_Device_Interface_ListW(
            interfaceClassGuid: Guid,
            deviceId: WString?,
            buffer: Pointer,
            bufferLen: Int,
            flags: Int,
        ): Int
    }

    /** The `kernel32` calls for overlapped I/O on a device handle. */
    @Suppress("FunctionName")
    interface Kernel32Io : StdCallLibrary {
        fun CreateFileW(
            name: WString,
            access: Int,
            shareMode: Int,
            security: Pointer?,
            disposition: Int,
            flags: Int,
            template: Pointer?,
        ): Pointer

        fun CloseHandle(handle: Pointer): Boolean

        // Buffers are native [Memory], never a Java array: the kernel fills an overlapped buffer
        // after the call returns, and JNA copies a Java array back only at return time.
        fun WriteFile(
            handle: Pointer,
            buffer: Pointer,
            count: Int,
            written: IntByReference,
            overlapped: Pointer,
        ): Boolean
        fun ReadFile(handle: Pointer, buffer: Pointer, count: Int, read: IntByReference, overlapped: Pointer): Boolean

        fun GetOverlappedResult(
            handle: Pointer,
            overlapped: Pointer,
            transferred: IntByReference,
            wait: Boolean,
        ): Boolean
        fun CancelIo(handle: Pointer): Boolean

        fun CreateEventW(security: Pointer?, manualReset: Boolean, initialState: Boolean, name: WString?): Pointer?
        fun WaitForSingleObject(handle: Pointer, timeoutMs: Int): Int
    }

    // JNA maps struct fields by name/order onto the Win32 layout; the names are the API's.
    @Suppress("ktlint:standard:property-naming")
    @Structure.FieldOrder("Data1", "Data2", "Data3", "Data4")
    class Guid : Structure() {
        @JvmField var Data1: Int = 0

        @JvmField var Data2: Short = 0

        @JvmField var Data3: Short = 0

        @JvmField var Data4: ByteArray = ByteArray(8)

        companion object {
            /** `GUID_DEVINTERFACE_USBPRINT`, {28D78FAD-5A12-11D1-AE5B-0000F803A8C2}. */
            fun usbPrint() = Guid().apply {
                Data1 = 0x28D78FAD
                Data2 = 0x5A12
                Data3 = 0x11D1
                Data4 = byteArrayOf(
                    0xAE.toByte(),
                    0x5B,
                    0x00,
                    0x00,
                    0xF8.toByte(),
                    0x03,
                    0xA8.toByte(),
                    0xC2.toByte(),
                )
            }
        }
    }

    sealed interface PathsResult {
        data class Ok(val paths: List<String>) : PathsResult
        data class Failed(val message: String) : PathsResult
    }

    /** Every USB-printer interface path currently present, `usbprint.sys`'s registrations. */
    fun printerInterfacePaths(): PathsResult {
        val cfg = cfgMgr32
            ?: return PathsResult.Failed(loadError ?: "cfgmgr32 could not be loaded")

        val guid = Guid.usbPrint()
        val lenRef = IntByReference()
        val sized = cfg.CM_Get_Device_Interface_List_SizeW(lenRef, guid, null, CM_GET_DEVICE_INTERFACE_LIST_PRESENT)
        if (sized != CR_SUCCESS) {
            return PathsResult.Failed("CM_Get_Device_Interface_List_Size failed (CONFIGRET $sized)")
        }

        // The length is in WCHARs and includes the list's double-NUL; 1 means an empty list.
        val chars = lenRef.value
        if (chars <= 1) return PathsResult.Ok(emptyList())

        val buffer = Memory(chars.toLong() * 2)
        val listed = cfg.CM_Get_Device_Interface_ListW(guid, null, buffer, chars, CM_GET_DEVICE_INTERFACE_LIST_PRESENT)
        if (listed != CR_SUCCESS) return PathsResult.Failed("CM_Get_Device_Interface_List failed (CONFIGRET $listed)")

        // A REG_MULTI_SZ: NUL-terminated wide strings back to back, ended by an empty one.
        val paths = mutableListOf<String>()
        var offset = 0L
        while (offset < chars.toLong() * 2) {
            val path = buffer.getWideString(offset)
            if (path.isEmpty()) break
            paths += path
            offset += (path.length + 1) * 2L
        }
        return PathsResult.Ok(paths)
    }

    const val CR_SUCCESS = 0
    const val CM_GET_DEVICE_INTERFACE_LIST_PRESENT = 0

    const val GENERIC_READ = 0x80000000.toInt()
    const val GENERIC_WRITE = 0x40000000
    const val OPEN_EXISTING = 3
    const val FILE_FLAG_OVERLAPPED = 0x40000000

    const val ERROR_FILE_NOT_FOUND = 2
    const val ERROR_ACCESS_DENIED = 5
    const val ERROR_SHARING_VIOLATION = 32
    const val ERROR_IO_PENDING = 997

    const val WAIT_OBJECT_0 = 0

    /** `INVALID_HANDLE_VALUE`, what [Kernel32Io.CreateFileW] returns on failure. */
    fun isInvalidHandle(handle: Pointer?): Boolean = handle == null || Pointer.nativeValue(handle) == -1L

    /**
     * OVERLAPPED is built by hand as raw memory rather than a JNA [Structure]: the kernel writes
     * into it asynchronously, and JNA's automatic struct syncing around each call would race it.
     * Only `hEvent` is ever set; the layout is two pointer-sized status words, the 8-byte offset
     * union, then `hEvent`.
     */
    val OVERLAPPED_HEVENT_OFFSET = 2L * Native.POINTER_SIZE + 8
    val OVERLAPPED_SIZE = OVERLAPPED_HEVENT_OFFSET + Native.POINTER_SIZE

    /** Null off Windows; every caller degrades instead. */
    val cfgMgr32: CfgMgr32? by lazy { tryLoad("cfgmgr32", CfgMgr32::class.java) }
    val kernel32: Kernel32Io? by lazy { tryLoad("kernel32", Kernel32Io::class.java) }

    /** Why a load failed, for diagnostics. */
    var loadError: String? = null
        private set

    private fun <T : StdCallLibrary> tryLoad(name: String, type: Class<T>): T? = try {
        Native.load(name, type, W32APIOptions.UNICODE_OPTIONS)
    } catch (e: UnsatisfiedLinkError) {
        loadError = e.message ?: "$name not found"
        null
    } catch (e: NoClassDefFoundError) {
        // JNA's win32 helpers aren't on non-Windows classpaths for every JNA packaging.
        loadError = e.message ?: "$name unavailable on this platform"
        null
    }
}
