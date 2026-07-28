package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.backup.Capture
import nl.redlabs.epsonreset.backup.EepromBackup
import nl.redlabs.epsonreset.backup.UnitChoice
import nl.redlabs.epsonreset.backup.UnitSelector
import nl.redlabs.epsonreset.db.PadGroup
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.device.MatchedPrinter
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val model = PrinterModel(
    name = "TEST",
    readKey = 1,
    writeKey = "Zvubnpsj",
    padGroups = listOf(
        PadGroup("Platen", "platen", listOf(58, 59), listOf(0, 0)),
        PadGroup("Main", "main", listOf(87), listOf(0x94)),
    ),
)

private fun reading(address: Int, value: Int?) = CounterReader.Reading(address, value, 0, "Waste")

class WritePacketTargetTest {

    /**
     * The backup is built from these pairs, so this is the test that decides whether the right
     * bytes get saved.
     */
    @Test
    fun `write packet yields the address and value it will commit`() {
        val packet = SequenceGenerator.writePacket(1, 58, 0x94, "Zvubnpsj")

        assertEquals(58 to 0x94, Executor.writePacketTarget(packet))
    }

    @Test
    fun `a two byte address survives the little endian split`() {
        val packet = SequenceGenerator.writePacket(1, 0x0123, 0xFF, "Zvubnpsj")

        assertEquals(0x0123 to 0xFF, Executor.writePacketTarget(packet))
    }

    /** A read packet must never be mistaken for a write — it has no value byte to report. */
    @Test
    fun `read and malformed packets yield nothing`() {
        assertNull(Executor.writePacketTarget(SequenceGenerator.readPacket(1, 58)))
        assertNull(Executor.writePacketTarget(ByteArray(4)))
        assertNull(Executor.writePacketTarget(ByteArray(0)))
    }

    /**
     * The refactor that introduced `generateWrites` must not have moved a single byte of the reset
     * sequence — `generate` is now a call into it.
     */
    @Test
    fun `generate is generateWrites over the pad groups`() {
        val viaPadGroups = SequenceGenerator.generateWrites(
            model,
            listOf(58 to 0, 59 to 0, 87 to 0x94),
        )

        val generated = SequenceGenerator.generate(model)

        assertEquals(generated.size, viaPadGroups.size)
        generated.zip(viaPadGroups).forEach { (a, b) -> assertContentEquals(a, b) }
    }
}

class BackupCaptureTest {

    private val sequence = SequenceGenerator.generate(model)
    private val fullRead = listOf(reading(58, 0x19), reading(59, 0x0F), reading(87, 0x3C))

    @Test
    fun `captures exactly the addresses the sequence will write`() {
        val capture = EepromBackup.capture("TEST", sequence, fullRead)

        val backup = assertIs<Capture.Ready>(capture).backup
        assertEquals(listOf(58, 59, 87), backup.entries.map { it.address })
        assertEquals(listOf(0x19, 0x0F, 0x3C), backup.entries.map { it.value })
    }

    /** The reset values ride along, so a restore can report what it is undoing. */
    @Test
    fun `records the value the reset intended to write`() {
        val backup = assertIs<Capture.Ready>(EepromBackup.capture("TEST", sequence, fullRead)).backup

        assertEquals(listOf(0, 0, 0x94), backup.entries.map { it.resetValue })
        assertEquals(3, backup.changedByReset)
    }

    /** A byte already at its reset value would not change, and must not be counted as one. */
    @Test
    fun `changedByReset ignores addresses already at the reset value`() {
        val alreadyReset = listOf(reading(58, 0), reading(59, 0), reading(87, 0x3C))
        val backup = assertIs<Capture.Ready>(EepromBackup.capture("TEST", sequence, alreadyReset)).backup

        assertEquals(1, backup.changedByReset)
    }

    /** The load-bearing safety test. */
    @Test
    fun `refuses when an address the run will write did not answer`() {
        val holed = listOf(reading(58, 0x19), reading(59, null), reading(87, 0x3C))

        val incomplete = assertIs<Capture.Incomplete>(EepromBackup.capture("TEST", sequence, holed))
        assertEquals(listOf(59), incomplete.missing)
    }

    @Test
    fun `refuses when an address the run will write was never sampled`() {
        val short = listOf(reading(58, 0x19), reading(59, 0x0F))

        val incomplete = assertIs<Capture.Incomplete>(EepromBackup.capture("TEST", sequence, short))
        assertEquals(listOf(87), incomplete.missing)
    }

    /** Extra readings are normal — `readAll` samples counter-spec addresses the reset never writes. */
    @Test
    fun `readings beyond the write set are ignored`() {
        val extra = fullRead + listOf(reading(252, 0xB4), reading(253, 0x00))

        val backup = assertIs<Capture.Ready>(EepromBackup.capture("TEST", sequence, extra)).backup
        assertEquals(listOf(58, 59, 87), backup.entries.map { it.address })
    }

    /** One address, one pre-write byte — however many times the sequence writes over it. */
    @Test
    fun `an address written twice is recorded once`() {
        val twice = SequenceGenerator.generateWrites(model, listOf(58 to 0, 58 to 0x94))

        val backup = assertIs<Capture.Ready>(EepromBackup.capture("TEST", twice, fullRead)).backup
        assertEquals(listOf(58), backup.entries.map { it.address })
        assertEquals(0x19, backup.entries.single().value)
    }

    @Test
    fun `a sequence with no writes is not a backup`() {
        val handshakeOnly = SequenceGenerator.generateWrites(model, emptyList())

        assertIs<Capture.NothingToWrite>(EepromBackup.capture("TEST", handshakeOnly, fullRead))
    }
}

/** Reading a saved snapshot back, which is the one read in this app that needs no printer. */
class SnapshotReadBackTest {

    private val backup = EepromBackup(
        model = "TEST",
        createdAt = "20260727T004500Z",
        printerSerial = "UNIT-A",
        entries = listOf(
            EepromBackup.Entry(58, 0x19, 0),
            EepromBackup.Entry(59, 0x0F, 0),
            EepromBackup.Entry(87, 0x94, 0x94),
        ),
    )

    @Test
    fun `every saved byte comes back as a reading that answered`() {
        val readings = backup.readings()

        assertEquals(listOf(58, 59, 87), readings.map { it.address })
        assertEquals(listOf(0x19, 0x0F, 0x94), readings.map { it.value })
        assertEquals(3, CounterReader.Report(backup.model, readings).answered)
    }

    /** The reset value rides along, so the saved bytes can be compared against it with no device. */
    @Test
    fun `a byte already at its reset value reads back as such`() {
        val readings = backup.readings().associateBy { it.address }

        assertEquals(false, readings.getValue(58).isAtResetValue)
        assertEquals(true, readings.getValue(87).isAtResetValue)
    }

    /** The file carries no group names; the model supplies them when it is still known. */
    @Test
    fun `group names come from the model when one is supplied`() {
        assertEquals(listOf("Platen", "Platen", "Main"), backup.readings(model).map { it.groupDescription })
        assertTrue(backup.readings().all { it.groupDescription == "saved snapshot" })
    }

    @Test
    fun `the timestamp is rendered for people, and a bad one is left alone`() {
        assertEquals("2026-07-27 00:45 UTC", backup.takenAt)
        assertEquals("whenever", backup.copy(createdAt = "whenever").takenAt)
    }
}

class BackupFileTest {

    private val backup = EepromBackup(
        model = "ET-2820",
        createdAt = "20260727T004500Z",
        printerSerial = null,
        entries = listOf(
            EepromBackup.Entry(58, 0x19, 0),
            EepromBackup.Entry(59, 0x0F, 0),
        ),
    )

    @Test
    fun `survives a json round trip`() {
        val parsed = assertNotNull(EepromBackup.parse(backup.toJson()))

        assertEquals(backup, parsed)
    }

    @Test
    fun `saves under a name carrying the model and timestamp`() {
        val dir = createTempDirectory("backup-test").toFile()

        val file = backup.save(dir)

        assertTrue(file.isFile)
        assertEquals("ET-2820-20260727T004500Z.json", file.name)
        assertEquals(backup, assertNotNull(EepromBackup.load(file)))
    }

    /** The temp file must not survive as something `list` would offer. */
    @Test
    fun `leaves no partial file behind`() {
        val dir = createTempDirectory("backup-test").toFile()
        backup.save(dir)

        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertEquals(1, EepromBackup.list(dir).size)
    }

    /**
     * These values go back out as EEPROM writes, so a corrupted or hand-edited file has to fail
     * here rather than at the printer.
     */
    @Test
    fun `rejects a byte outside the unsigned range`() {
        val tampered = backup.toJson().replace("\"value\": 25", "\"value\": 300")

        assertNull(EepromBackup.parse(tampered))
    }

    @Test
    fun `rejects a negative address`() {
        val tampered = backup.toJson().replace("\"addr\": 58", "\"addr\": -1")

        assertNull(EepromBackup.parse(tampered))
    }

    @Test
    fun `rejects text that is not a backup`() {
        assertNull(EepromBackup.parse("{}"))
        assertNull(EepromBackup.parse("not json at all"))
        assertNull(EepromBackup.parse("""{"model":"X","entries":[]}"""))
    }

    @Test
    fun `list returns newest first`() {
        val dir = createTempDirectory("backup-test").toFile()
        backup.save(dir)
        val older = backup.copy(createdAt = "20260101T000000Z").save(dir)
        older.setLastModified(1_000L)

        assertEquals(
            listOf("ET-2820-20260727T004500Z.json", "ET-2820-20260101T000000Z.json"),
            EepromBackup.list(dir).map { it.name },
        )
    }
}

class UnitSelectorTest {

    private fun backup(model: String = "ET-2820", serial: String? = "XADA1") = EepromBackup(
        model = model,
        createdAt = "20260727T004500Z",
        printerSerial = serial,
        entries = listOf(EepromBackup.Entry(58, 0x19, 0)),
    )

    private fun unit(modelName: String?, serial: String?, address: Int = 4) = MatchedPrinter(
        device = DetectedPrinter(
            link = Link.Usb(
                busNumber = 1,
                deviceAddress = address,
                interfaceNumber = 1,
                endpointIn = 0x81.toByte(),
                endpointOut = 0x02,
                isPrinterClass = true,
            ),
            manufacturer = "EPSON",
            product = modelName,
            serial = serial,
            productId = 0x1169,
        ),
        model = modelName?.let { PrinterModel(name = it) },
        confidence = MatchedPrinter.Confidence.EXACT,
    )

    @Test
    fun `writes to the unit whose serial matches`() {
        val choice = UnitSelector.choose(
            backup(),
            listOf(unit("ET-2820", "OTHER", 4), unit("ET-2820", "XADA1", 5)),
        )

        val write = assertIs<UnitChoice.Write>(choice)
        assertEquals("XADA1", write.device.serial)
        assertNull(write.unconfirmed)
    }

    @Test
    fun `refuses when nothing on the bus is that model`() {
        val choice = UnitSelector.choose(backup(), listOf(unit("XP-245", "XADA1")))

        assertIs<UnitChoice.NoSuchModel>(choice)
    }

    @Test
    fun `refuses when an unmatched device is all that is connected`() {
        val choice = UnitSelector.choose(backup(), listOf(unit(null, "XADA1")))

        assertIs<UnitChoice.NoSuchModel>(choice)
    }

    /** Right model, wrong unit — the write key would have matched, so this is the only guard. */
    @Test
    fun `refuses the right model with the wrong serial`() {
        val choice = UnitSelector.choose(backup(), listOf(unit("ET-2820", "DIFFERENT")))

        val wrong = assertIs<UnitChoice.WrongUnit>(choice)
        assertEquals("XADA1", wrong.wanted)
        assertEquals(listOf("DIFFERENT"), wrong.connected)
    }

    /**
     * Two of the same model and no way to tell them apart is the case the whole rule exists for:
     * the key matches either one, so guessing would write to a coin flip.
     */
    @Test
    fun `refuses two identical models that cannot be told apart`() {
        val choice = UnitSelector.choose(
            backup(serial = null),
            listOf(unit("ET-2820", null, 4), unit("ET-2820", null, 5)),
        )

        assertEquals(2, assertIs<UnitChoice.Ambiguous>(choice).count)
    }

    @Test
    fun `refuses two identical models when neither reports a serial to match`() {
        val choice = UnitSelector.choose(
            backup(serial = "XADA1"),
            listOf(unit("ET-2820", null, 4), unit("ET-2820", null, 5)),
        )

        assertIs<UnitChoice.Ambiguous>(choice)
    }

    /**
     * Allowed, but flagged. Refusing here would strand every backup taken before the serial was
     * recorded, and with one candidate there is nothing to confuse it with.
     */
    @Test
    fun `allows a lone unit when the backup has no serial, but says so`() {
        val choice = UnitSelector.choose(backup(serial = null), listOf(unit("ET-2820", "XADA1")))

        assertNotNull(assertIs<UnitChoice.Write>(choice).unconfirmed)
    }

    @Test
    fun `allows a lone unit whose serial could not be read, but says so`() {
        val choice = UnitSelector.choose(backup(serial = "XADA1"), listOf(unit("ET-2820", null)))

        val write = assertIs<UnitChoice.Write>(choice)
        assertNotNull(write.unconfirmed)
        assertTrue(write.unconfirmed!!.contains("XADA1"))
    }

    @Test
    fun `model matching ignores case`() {
        val choice = UnitSelector.choose(backup(model = "et-2820"), listOf(unit("ET-2820", "XADA1")))

        assertIs<UnitChoice.Write>(choice)
    }
}

class RestoreSequenceTest {

    /**
     * A restore is the reset sequence with the saved bytes in place of the reset values — the whole
     * point being that it reuses the write path the golden packet tests already cover.
     */
    @Test
    fun `restore writes the backed up bytes rather than the reset values`() {
        val backup = EepromBackup(
            model = "TEST",
            createdAt = "20260727T004500Z",
            printerSerial = null,
            entries = listOf(EepromBackup.Entry(58, 0x19, 0), EepromBackup.Entry(59, 0x0F, 0)),
        )

        val targets = SequenceGenerator.generateWrites(model, backup.writes)
            .mapNotNull { Executor.writePacketTarget(it) }

        assertEquals(listOf(58 to 0x19, 59 to 0x0F), targets)
    }

    /** Round trip: what a capture saves is what a restore would put back. */
    @Test
    fun `capture then restore returns the original bytes`() {
        val original = listOf(reading(58, 0x19), reading(59, 0x0F), reading(87, 0x3C))
        val captured = assertIs<Capture.Ready>(
            EepromBackup.capture("TEST", SequenceGenerator.generate(model), original, now = Instant.EPOCH),
        ).backup

        val restored = SequenceGenerator.generateWrites(model, captured.writes)
            .mapNotNull { Executor.writePacketTarget(it) }
            .toMap()

        assertEquals(mapOf(58 to 0x19, 59 to 0x0F, 87 to 0x3C), restored)
    }
}
