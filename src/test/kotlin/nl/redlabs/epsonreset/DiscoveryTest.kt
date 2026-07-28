package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.device.DeviceMatcher
import nl.redlabs.epsonreset.net.MdnsDiscovery
import nl.redlabs.epsonreset.protocol.DeviceId
import nl.redlabs.epsonreset.protocol.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds DNS messages the way a responder would, compression pointers included. */
private class Dns {
    private val bytes = mutableListOf<Byte>()
    private val offsets = mutableMapOf<String, Int>()

    fun u8(value: Int) = apply { bytes += (value and 0xFF).toByte() }
    fun u16(value: Int) = apply {
        u8(value shr 8)
        u8(value)
    }
    fun u32(value: Int) = apply {
        u16(value shr 16)
        u16(value)
    }

    fun raw(data: ByteArray) = apply { data.forEach { bytes += it } }

    /** Writes a name in full, remembering where it landed so it can be pointed at later. */
    fun name(value: String) = apply {
        offsets.putIfAbsent(value, bytes.size)
        value.split('.').forEach { label ->
            u8(label.length)
            raw(label.toByteArray(Charsets.UTF_8))
        }
        u8(0)
    }

    /** The 14-bit back-reference every real response is full of. */
    fun pointer(value: String) = apply { u16(0xC000 or offsets.getValue(value)) }

    fun rawPointer(offset: Int) = apply { u16(0xC000 or offset) }

    fun rdata(block: Dns.() -> Unit) = apply {
        val lengthAt = bytes.size
        u16(0)
        val start = bytes.size
        block()
        val length = bytes.size - start
        bytes[lengthAt] = ((length shr 8) and 0xFF).toByte()
        bytes[lengthAt + 1] = (length and 0xFF).toByte()
    }

    fun header(answers: Int) = apply {
        u16(0) // id
        u16(0x8400) // authoritative response
        u16(0) // no questions echoed back
        u16(answers)
        u16(0)
        u16(0)
    }

    fun record(owner: String, type: Int, block: Dns.() -> Unit) = apply {
        name(owner)
        u16(type)
        u16(1) // class IN
        u32(120) // ttl
        rdata(block)
    }

    fun text(vararg entries: String) = apply {
        entries.forEach { entry ->
            val encoded = entry.toByteArray(Charsets.UTF_8)
            u8(encoded.size)
            raw(encoded)
        }
    }

    fun build(): ByteArray = bytes.toByteArray()
}

private const val SERVICE = MdnsDiscovery.SERVICE
private const val INSTANCE = "EPSON ET-2820 Series.$SERVICE"

class MdnsWireTest {

    /**
     * A response shaped the way an Epson answers: SRV, then a PTR pointing back at the instance
     * name the SRV already wrote out, then TXT and the address.
     */
    private fun response(
        instance: String = INSTANCE,
        target: String = "EPSON123456.local",
        address: String = "192.168.1.50",
        vararg txt: String,
    ): ByteArray = Dns()
        .header(answers = 4)
        .record(instance, 33) {
            u16(0) // priority
            u16(0) // weight
            u16(9100)
            name(target)
        }
        .record(SERVICE, 12) { pointer(instance) }
        .record(instance, 16) { text(*txt) }
        .record(target, 1) {
            address.split('.').forEach { u8(it.toInt()) }
        }
        .build()

    @Test
    fun `resolves a service to a host port and its txt record`() {
        val packet = response(
            txt = arrayOf("txtvers=1", "usb_MFG=EPSON", "usb_MDL=ET-2820 Series", "note="),
        )

        val service = MdnsDiscovery.assemble(MdnsDiscovery.parse(packet), SERVICE).single()

        assertEquals("EPSON ET-2820 Series", service.instance)
        assertEquals("192.168.1.50", service.host)
        assertEquals(9100, service.port)
        assertEquals("ET-2820 Series", service.txt["usb_MDL"])
        assertEquals("", service.txt["note"])
    }

    /**
     * The whole reason discovery is worth having: `usb_MDL` is the USB model string republished, so
     * a printer found over the network resolves to the same database entry as one plugged in.
     */
    @Test
    fun `the advertised model resolves to a database entry`() {
        val packet = response(txt = arrayOf("usb_MFG=EPSON", "usb_MDL=ET-2820 Series"))
        val service = MdnsDiscovery.assemble(MdnsDiscovery.parse(packet), SERVICE).single()

        val resolved = DeviceMatcher.resolve(service.model, PrinterDatabase.load())

        assertEquals("ET-2820", assertNotNull(resolved.model).name)
    }

    @Test
    fun `ty stands in when usb_MDL is absent`() {
        val packet = response(txt = arrayOf("ty=EPSON XP-245 Series"))
        val service = MdnsDiscovery.assemble(MdnsDiscovery.parse(packet), SERVICE).single()

        assertEquals("EPSON XP-245 Series", service.model)
        assertTrue(service.isEpson)
    }

    /** Writing EEPROM keys to another maker's printer is not a mistake worth leaving available. */
    @Test
    fun `a printer that says nothing about Epson is not claimed`() {
        val packet = response(
            instance = "Brother HL-2270DW.$SERVICE",
            txt = arrayOf("usb_MFG=Brother", "usb_MDL=HL-2270DW"),
        )

        val service = MdnsDiscovery.assemble(MdnsDiscovery.parse(packet), SERVICE).single()
        assertFalse(service.isEpson)
    }

    /** Without the A record the SRV target is a name only mDNS can resolve, so it is a last resort. */
    @Test
    fun `a service without an address record falls back to the srv target`() {
        val packet = Dns()
            .header(answers = 2)
            .record(INSTANCE, 33) {
                u16(0)
                u16(0)
                u16(9100)
                name("EPSON123456.local")
            }
            .record(INSTANCE, 16) { text("usb_MDL=ET-2820 Series") }
            .build()

        val service = MdnsDiscovery.assemble(MdnsDiscovery.parse(packet), SERVICE).single()
        assertEquals("EPSON123456.local", service.host)
    }

    @Test
    fun `records for other services are ignored`() {
        val packet = Dns()
            .header(answers = 1)
            .record("HP LaserJet._ipp._tcp.local", 33) {
                u16(0)
                u16(0)
                u16(631)
                name("hp.local")
            }
            .build()

        assertTrue(MdnsDiscovery.assemble(MdnsDiscovery.parse(packet), SERVICE).isEmpty())
    }

    /** A malformed packet is a bad packet, not a hang: the pointer chain has to be bounded. */
    @Test
    fun `a pointer loop is refused rather than followed`() {
        val packet = Dns()
            .header(answers = 1)
            .rawPointer(12) // the record's own name, pointing at itself
            .u16(33).u16(1).u32(120)
            .rdata {
                u16(0)
                u16(0)
                u16(9100)
                name("x.local")
            }
            .build()

        assertTrue(MdnsDiscovery.parse(packet).isEmpty())
    }

    @Test
    fun `truncated input yields nothing rather than throwing`() {
        assertTrue(MdnsDiscovery.parse(ByteArray(0)).isEmpty())
        assertTrue(MdnsDiscovery.parse(byteArrayOf(0, 0, 0x84.toByte())).isEmpty())
        assertTrue(MdnsDiscovery.parse(response().copyOfRange(0, 20)).isEmpty())
    }

    @Test
    fun `the query asks for the raw printing service by ptr`() {
        val query = MdnsDiscovery.query(SERVICE)
        val text = String(query, Charsets.ISO_8859_1)

        assertEquals(1, (query[5].toInt() and 0xFF)) // one question
        assertTrue(text.contains("_pdl-datastream"))
        assertEquals(12, (query[query.size - 4].toInt() and 0xFF) shl 8 or (query[query.size - 3].toInt() and 0xFF))
        assertEquals(0x0001, (query[query.size - 2].toInt() and 0xFF) shl 8 or (query[query.size - 1].toInt() and 0xFF))
    }

    /** The unicast-reply bit, for the fallback socket that isn't a group member. */
    @Test
    fun `a unicast query sets the top bit of the class field`() {
        val query = MdnsDiscovery.query(SERVICE, unicastReply = true)

        assertEquals(0x80, query[query.size - 2].toInt() and 0xFF)
        assertEquals(0x01, query[query.size - 1].toInt() and 0xFF)
    }
}

class DeviceIdTest {

    private val reply =
        "@EJL ID\r\nMFG:EPSON;CMD:ESCPL2,BDC,D4,D4PX;MDL:ET-2820 Series;" +
            "CLS:PRINTER;DES:EPSON ET-2820 Series;SERN:X4KP0219;"

    @Test
    fun `pulls the model manufacturer and serial out of the id string`() {
        val id = assertNotNull(DeviceId.parse(reply.toByteArray(Charsets.ISO_8859_1)))

        assertEquals("EPSON", id.manufacturer)
        assertEquals("ET-2820 Series", id.model)
        assertEquals("X4KP0219", id.serial)
        assertTrue(id.isEpson)
        assertTrue(id.commandSets.contains("D4"))
    }

    /** The same string the USB descriptor would have given, so it matches the same way. */
    @Test
    fun `the reported model resolves to a database entry`() {
        val id = assertNotNull(DeviceId.parse(reply.toByteArray(Charsets.ISO_8859_1)))

        assertEquals("ET-2820", assertNotNull(DeviceMatcher.resolve(id.model, PrinterDatabase.load()).model).name)
    }

    /** Some printers put a two-byte length in front; the pairs are in the same place regardless. */
    @Test
    fun `a length prefix in front of the id does not confuse it`() {
        val prefixed = byteArrayOf(0, 0x40) + reply.toByteArray(Charsets.ISO_8859_1)

        assertEquals("ET-2820 Series", assertNotNull(DeviceId.parse(prefixed)).model)
    }

    @Test
    fun `a reply with no key value pairs is not an identity`() {
        assertNull(DeviceId.parse(ByteArray(0)))
        assertNull(DeviceId.parse("||status;".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `query sends the request and parses what comes back`() {
        val transport = object : Transport {
            var asked = false
            override fun send(packet: ByteArray): Boolean {
                asked = String(packet, Charsets.ISO_8859_1).contains("@EJL ID")
                return true
            }

            override fun drain(): ByteArray = if (asked) reply.toByteArray(Charsets.ISO_8859_1) else ByteArray(0)
        }

        assertEquals("ET-2820 Series", assertNotNull(DeviceId.query(transport)).model)
    }

    @Test
    fun `a transport that cannot send yields nothing`() {
        val dead = object : Transport {
            override fun send(packet: ByteArray) = false
            override fun drain() = ByteArray(0)
        }

        assertNull(DeviceId.query(dead))
    }
}
