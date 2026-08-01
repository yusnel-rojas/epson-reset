package nl.redlabs.epsonreset.usb

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Minimal JNA binding for the Windows print spooler — just the calls the reset flow needs to talk
 * to a printer through the driver Windows already installed, with no libusb and no Zadig.
 *
 * A RAW job written with [WritePrinter] reaches the printer's print-data service, and its
 * back-channel comes back through [ReadPrinter]. See [WinspoolTransport] for what that means for
 * framing (ESC/P commands, not raw 1284.4 packets).
 */
// JNA maps struct fields by name/order onto the Win32 layout, so the field names and their order are
// the API's and not ours to rename or camel-case; renaming compiles and then reads wrong offsets.
@Suppress("FunctionName", "ktlint:standard:property-naming")
interface Winspool : StdCallLibrary {

    fun OpenPrinterW(printerName: WString, handle: PointerByReference, defaults: Pointer?): Boolean
    fun ClosePrinter(handle: Pointer): Boolean

    /** Returns a non-zero job id on success, 0 on failure. */
    fun StartDocPrinterW(handle: Pointer, level: Int, docInfo: DocInfo1): Int
    fun EndDocPrinter(handle: Pointer): Boolean
    fun StartPagePrinter(handle: Pointer): Boolean
    fun EndPagePrinter(handle: Pointer): Boolean

    fun WritePrinter(handle: Pointer, buffer: ByteArray, count: Int, written: IntByReference): Boolean
    fun ReadPrinter(handle: Pointer, buffer: ByteArray, count: Int, read: IntByReference): Boolean

    fun EnumPrintersW(
        flags: Int,
        name: Pointer?,
        level: Int,
        printerEnum: Pointer?,
        cbBuf: Int,
        needed: IntByReference,
        returned: IntByReference,
    ): Boolean

    @Structure.FieldOrder("pDocName", "pOutputFile", "pDatatype")
    class DocInfo1 : Structure() {
        @JvmField var pDocName: WString? = null

        @JvmField var pOutputFile: WString? = null

        @JvmField var pDatatype: WString? = null
    }

    /**
     * `PRINTER_INFO_2`. Only the name/port/driver strings are read; the rest is here to make the
     * struct the right size so `toArray` strides correctly over the enumeration buffer.
     */
    @Structure.FieldOrder(
        "pServerName", "pPrinterName", "pShareName", "pPortName", "pDriverName", "pComment",
        "pLocation", "pDevMode", "pSepFile", "pPrintProcessor", "pDatatype", "pParameters",
        "pSecurityDescriptor", "Attributes", "Priority", "DefaultPriority", "StartTime",
        "UntilTime", "Status", "cJobs", "AveragePPM",
    )
    class PrinterInfo2 : Structure {
        @JvmField var pServerName: Pointer? = null

        @JvmField var pPrinterName: Pointer? = null

        @JvmField var pShareName: Pointer? = null

        @JvmField var pPortName: Pointer? = null

        @JvmField var pDriverName: Pointer? = null

        @JvmField var pComment: Pointer? = null

        @JvmField var pLocation: Pointer? = null

        @JvmField var pDevMode: Pointer? = null

        @JvmField var pSepFile: Pointer? = null

        @JvmField var pPrintProcessor: Pointer? = null

        @JvmField var pDatatype: Pointer? = null

        @JvmField var pParameters: Pointer? = null

        @JvmField var pSecurityDescriptor: Pointer? = null

        @JvmField var Attributes: Int = 0

        @JvmField var Priority: Int = 0

        @JvmField var DefaultPriority: Int = 0

        @JvmField var StartTime: Int = 0

        @JvmField var UntilTime: Int = 0

        @JvmField var Status: Int = 0

        @JvmField var cJobs: Int = 0

        @JvmField var AveragePPM: Int = 0

        constructor() : super()
        constructor(p: Pointer) : super(p) {
            read()
        }

        private fun wide(p: Pointer?): String? = p?.getWideString(0)?.trim()?.takeIf { it.isNotEmpty() }

        val printerName: String? get() = wide(pPrinterName)
        val portName: String? get() = wide(pPortName)
        val driverName: String? get() = wide(pDriverName)
    }

    companion object {
        /** Levels and flags used by [EnumPrintersW]. */
        const val LEVEL_2 = 2
        const val PRINTER_ENUM_LOCAL = 0x00000002
        const val PRINTER_ENUM_CONNECTIONS = 0x00000004

        /** Null off Windows, where winspool.drv does not exist; every caller degrades instead. */
        val instance: Winspool? by lazy { tryLoad() }

        /** Why the load failed, for diagnostics. Null once [instance] is non-null. */
        var loadError: String? = null
            private set

        private fun tryLoad(): Winspool? = try {
            Native.load("winspool.drv", Winspool::class.java, W32APIOptions.UNICODE_OPTIONS)
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message ?: "winspool.drv not found"
            null
        } catch (e: NoClassDefFoundError) {
            // JNA's win32 helpers aren't on non-Windows classpaths for every JNA packaging.
            loadError = e.message ?: "winspool.drv unavailable on this platform"
            null
        }
    }
}
