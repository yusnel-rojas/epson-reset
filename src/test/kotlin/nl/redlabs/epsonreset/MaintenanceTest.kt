package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.Maintenance
import nl.redlabs.epsonreset.protocol.RemoteMode
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.protocol.Transport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaintenanceCommandTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * The whole safety argument for this feature is that these are the *same* envelope as `st`,
     * which a real ET-2825 answers. If that stops being true the argument is gone, so it is pinned
     * byte for byte against the golden status packet in `StatusCommandTest`.
     */
    @Test
    fun `a maintenance command is framed exactly like the proven status command`() {
        assertEquals(
            "02 02 00 0B 00 00 6E 63 01 00 00",
            hex(Maintenance.packetFor(Maintenance.Operation.NOZZLE_CHECK)),
        )
        assertEquals(
            "02 02 00 0B 00 00 63 68 01 00 00",
            hex(Maintenance.packetFor(Maintenance.Operation.HEAD_CLEANING)),
        )
    }

    @Test
    fun `power cleaning is the cleaning command with the power flag`() {
        val ordinary = Maintenance.packetFor(Maintenance.Operation.HEAD_CLEANING)
        val power = Maintenance.packetFor(Maintenance.Operation.POWER_CLEANING)

        // Same command characters and the same length — only the payload byte separates them, which
        // is why they are two operations rather than one with a parameter the UI could hide.
        assertEquals(ordinary.size, power.size)
        assertContentEqualsAt(ordinary, power, 0..9)
        assertEquals(0x00, ordinary[10].toInt() and 0xFF)
        assertEquals(0x10, power[10].toInt() and 0xFF)
    }

    private fun assertContentEqualsAt(a: ByteArray, b: ByteArray, range: IntRange) {
        for (i in range) assertEquals(a[i], b[i], "byte $i differs")
    }

    /** The enforced guarantee, tested the way the inspector's read-only promise is. */
    @Test
    fun `no maintenance operation can produce an eeprom write`() {
        for (operation in Maintenance.Operation.entries) {
            val packet = Maintenance.packetFor(operation)
            assertFalse(Executor.isWritePacket(packet), "${operation.label} built a write packet")
            assertFalse(Executor.isReadPacket(packet), "${operation.label} built a read packet")
        }
    }

    /**
     * The point of the feature. Every operation offered here spends ink, and spent ink lands in the
     * pad whose counter the rest of the app exists to read — so nothing may quietly claim otherwise.
     */
    @Test
    fun `every operation admits that it fills the waste pad`() {
        for (operation in Maintenance.Operation.entries) {
            assertTrue(operation.raisesWasteCounter, "${operation.label} claims it costs no ink")
        }

        assertEquals(Maintenance.InkCost.HEAVY, Maintenance.Operation.POWER_CLEANING.inkCost)
        assertEquals(Maintenance.InkCost.SMALL, Maintenance.Operation.NOZZLE_CHECK.inkCost)
    }
}

class RemoteModeTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * The envelope, byte for byte, against `escputil --nozzle-check` captured from gutenprint 5.3.3
     * (`escp2-et2750`, the closest EcoTank in its model list to the ET-2820 here).
     *
     * Everything before the command is what the reference sends; everything after it is the ending
     * that finally ejected the sheet. Two earlier endings are pinned *out* by this: a `JE` command in
     * its own remote session, which escputil never sends at all, and a form feed applied only to a
     * nozzle check.
     */
    @Test
    fun `the remote envelope matches the escputil reference byte for byte`() {
        assertEquals(
            "00 00 00 1B 01 40 45 4A 4C 20 31 32 38 34 2E 34 0A 40 45 4A 4C 20 20 20 20 20 0A " +
                "1B 40 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 4E 43 02 00 00 00 1B 00 00 00 " +
                "1B 00 0C 1B 00 1B 00",
            hex(RemoteMode.sequenceFor("nc", listOf(0x00, 0x00))),
        )
    }

    /** No `JE` anywhere: the reference emits none, for either operation. */
    @Test
    fun `no sequence carries a JE command`() {
        for (operation in Maintenance.Operation.entries) {
            assertFalse(
                hex(Maintenance.remoteSequenceFor(operation)).contains("4A 45"),
                "${operation.label} still sends JE, which escputil never does",
            )
        }
    }

    @Test
    fun `the command name is upper case and its length little endian`() {
        assertEquals("43 48 02 00 00 00", hex(RemoteMode.command("ch", listOf(0x00, 0x00))))
        assertEquals("4E 43 02 00 00 00", hex(RemoteMode.command("nc", listOf(0x00, 0x00))))
    }

    /**
     * Every operation gets the same ending, including the `0C` form feed. escputil's nozzle-check and
     * clean-head streams have byte-identical tails, so there is no per-operation ending to get right —
     * and the form feed is not optional for cleaning just because cleaning prints nothing.
     */
    @Test
    fun `every operation ends with the same captured tail`() {
        for (operation in Maintenance.Operation.entries) {
            assertTrue(
                hex(Maintenance.remoteSequenceFor(operation)).endsWith("1B 00 00 00 1B 00 0C 1B 00 1B 00"),
                "${operation.label} does not end with the reference tail",
            )
        }
    }

    @Test
    fun `a remote sequence is recognisable and is never a d4 packet`() {
        for (operation in Maintenance.Operation.entries) {
            val sequence = Maintenance.remoteSequenceFor(operation)
            assertTrue(RemoteMode.isRemoteSequence(sequence))
            assertFalse(Executor.isWritePacket(sequence))
            assertTrue(
                String(sequence, Charsets.ISO_8859_1).contains("@EJL 1284.4\n@EJL     \n"),
                "${operation.label} does not explicitly exit packet mode",
            )
        }
    }

    @Test
    fun `overriding the parameters changes only the parameters`() {
        val standard = Maintenance.remoteSequenceFor(Maintenance.Operation.NOZZLE_CHECK)
        val varied = Maintenance.remoteSequenceFor(Maintenance.Operation.NOZZLE_CHECK, listOf(0x00, 0x10))

        assertEquals(standard.size, varied.size)
        assertTrue(hex(standard).contains("4E 43 02 00 00 00"))
        assertTrue(hex(varied).contains("4E 43 02 00 00 10"))
    }

    @Test
    fun `power cleaning sets the power flag rather than the colour-head selector`() {
        val ordinary = hex(Maintenance.remoteSequenceFor(Maintenance.Operation.HEAD_CLEANING))
        val power = hex(Maintenance.remoteSequenceFor(Maintenance.Operation.POWER_CLEANING))

        assertTrue(ordinary.contains("43 48 02 00 00 00"))
        assertTrue(power.contains("43 48 02 00 00 10"))
        assertFalse(power.contains("43 48 02 00 00 02"))
    }

    /**
     * Cleaning once sent a `TI` clock prelude. escputil sends none, and unreferenced bytes in this
     * stream have a track record, so it was removed — pinned here so it does not drift back.
     */
    @Test
    fun `no sequence carries a TI clock prelude`() {
        for (operation in Maintenance.Operation.entries) {
            assertFalse(
                hex(Maintenance.remoteSequenceFor(operation)).contains("54 49"),
                "${operation.label} sends TI, which the reference does not",
            )
        }
    }

    /**
     * The full nozzle-check stream, against the captured `escputil --nozzle-check` bytes. The
     * reference leads the operative `NC 00 00` with `VI 02 00 00 00` and a first `NC 02 00 00 10`,
     * all inside the one remote session — so this pins the prelude as well as the ending.
     */
    @Test
    fun `the nozzle check stream is escputils stream exactly`() {
        assertEquals(
            "00 00 00 1B 01 40 45 4A 4C 20 31 32 38 34 2E 34 0A 40 45 4A 4C 20 20 20 20 20 0A " +
                "1B 40 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 " +
                "56 49 02 00 00 00 4E 43 02 00 00 10 4E 43 02 00 00 00 " +
                "1B 00 00 00 1B 00 0C 1B 00 1B 00",
            hex(Maintenance.remoteSequenceFor(Maintenance.Operation.NOZZLE_CHECK)),
        )
    }

    /** And the cleaning stream, which the reference sends with no prelude at all. */
    @Test
    fun `the cleaning stream is escputils stream exactly`() {
        assertEquals(
            "00 00 00 1B 01 40 45 4A 4C 20 31 32 38 34 2E 34 0A 40 45 4A 4C 20 20 20 20 20 0A " +
                "1B 40 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 43 48 02 00 00 00 " +
                "1B 00 00 00 1B 00 0C 1B 00 1B 00",
            hex(Maintenance.remoteSequenceFor(Maintenance.Operation.HEAD_CLEANING)),
        )
    }
}

class MaintenanceRunTest {

    /** Builds a `@BDC ST2` block reporting one state, which is all these tests read out of it. */
    private fun st2(state: Int): ByteArray {
        val fields = byteArrayOf(Status.TYPE_STATE.toByte(), 0x01, state.toByte())
        return "@BDC ST2\r\n".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf((fields.size and 0xFF).toByte(), ((fields.size shr 8) and 0xFF).toByte()) +
            fields
    }

    /**
     * A printer that answers status queries from a script and records the commands it was asked
     * for. Channel and credit packets are accepted in silence, as a real one accepts them.
     */
    private inner class ScriptedPrinter(vararg states: Int) : Transport {
        private val queue = ArrayDeque(states.toList())
        private var lastState: Int? = null
        private var pending = ByteArray(0)

        /** Two-letter control commands, in the order they were sent. */
        val commands = mutableListOf<String>()

        /** When set, anything that isn't a status query gets this reply instead. */
        var refusal: String? = null

        /** True once an ESC/P2 remote sequence has been written to this connection. */
        var sawRemoteSequence = false
            private set

        /** How many times 1284.4 was negotiated on this connection. */
        var handshakes = 0
            private set

        override fun send(packet: ByteArray): Boolean {
            pending = ByteArray(0)

            if (RemoteMode.isRemoteSequence(packet)) {
                sawRemoteSequence = true
                return true
            }

            // The EJL line is the negotiation itself, as against the credit traffic that follows it.
            if (String(packet, Charsets.ISO_8859_1).contains("@EJL 1284.4")) handshakes++

            // Channel setup and credit — not a control command, nothing to say back.
            if (packet.size < 8 || packet[0] != 0x02.toByte() || packet[1] != 0x02.toByte()) return true

            val command = String(packet, 6, 2, Charsets.ISO_8859_1)
            commands += command

            pending = when {
                command == "st" -> {
                    val state = if (queue.isEmpty()) lastState else queue.removeFirst()
                    lastState = state
                    state?.let { st2(it) } ?: ByteArray(0)
                }

                refusal != null -> refusal!!.toByteArray(Charsets.ISO_8859_1)
                else -> ByteArray(0)
            }
            return true
        }

        override fun drain(): ByteArray = pending.also { pending = ByteArray(0) }
    }

    @Test
    fun `a cleaning that moves the printer into the cleaning state is accepted`() {
        val printer = ScriptedPrinter(Status.STATE_IDLE, Status.STATE_CLEANING)

        val result = Maintenance.run(printer, Maintenance.Operation.HEAD_CLEANING)

        assertTrue(result.accepted)
        assertNull(result.error)
        assertEquals(Status.STATE_IDLE, result.stateBefore)
        assertEquals(Status.STATE_CLEANING, result.stateAfter)
        assertContains(printer.commands, "ch")
    }

    @Test
    fun `a nozzle check is accepted on the self-test state`() {
        val printer = ScriptedPrinter(Status.STATE_IDLE, Status.STATE_SELF_TEST)

        val result = Maintenance.run(printer, Maintenance.Operation.NOZZLE_CHECK)

        assertTrue(result.accepted)
        assertContains(printer.commands, "nc")
    }

    /**
     * The case that must not be dressed up as success. A short cycle that finished before the state
     * was sampled is indistinguishable from a command the firmware ignored, and reporting either as
     * "done" would teach someone to trust a button that may do nothing.
     */
    @Test
    fun `a state that never moves is inconclusive rather than successful`() {
        val printer = ScriptedPrinter(Status.STATE_IDLE, Status.STATE_IDLE)

        val result = Maintenance.run(printer, Maintenance.Operation.HEAD_CLEANING)

        assertFalse(result.accepted)
        assertTrue(result.inconclusive)
        assertNull(result.error)
    }

    @Test
    fun `a busy printer is left alone and the command is never sent`() {
        val printer = ScriptedPrinter(Status.STATE_CLEANING)

        val result = Maintenance.run(printer, Maintenance.Operation.POWER_CLEANING)

        assertFalse(result.accepted)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("cleaning the print head"))
        // Status was asked; the cleaning command never went out.
        assertEquals(listOf("st"), printer.commands.distinct())
    }

    @Test
    fun `a refusal is reported as a refusal and not as silence`() {
        val printer = ScriptedPrinter(Status.STATE_IDLE, Status.STATE_IDLE)
        printer.refusal = "||:41:NA;"

        val result = Maintenance.run(printer, Maintenance.Operation.HEAD_CLEANING)

        assertFalse(result.accepted)
        assertFalse(result.inconclusive)
        assertNotNull(result.error)
    }

    /** A `:NA;` for a command byte FactoryReply has no wording for still has to say "refused". */
    @Test
    fun `an unrecognised refusal code still produces an explanation`() {
        val printer = ScriptedPrinter(Status.STATE_IDLE, Status.STATE_IDLE)
        printer.refusal = "||:55:NA;"

        val result = Maintenance.run(printer, Maintenance.Operation.NOZZLE_CHECK)

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("refused"))
    }

    /**
     * Captured from an ET-2825 on firmware 05.24 over SNMP, 2026-07-29. The command was parsed and
     * answered with an empty data field — neither a refusal nor silence, and the run must say so.
     */
    @Test
    fun `an empty echo is reported as parsed rather than as silence`() {
        val printer = ScriptedPrinter(Status.STATE_IDLE, Status.STATE_IDLE)
        printer.refusal = "nc:;"

        val result = Maintenance.run(printer, Maintenance.Operation.NOZZLE_CHECK)

        assertEquals("", result.echo)
        assertTrue(result.parsed)
        assertFalse(result.accepted)
        assertTrue(result.inconclusive)
        assertNull(result.error)
    }

    @Test
    fun `no echo at all is distinct from an empty one`() {
        val printer = ScriptedPrinter(Status.STATE_IDLE, Status.STATE_IDLE)

        val result = Maintenance.run(printer, Maintenance.Operation.NOZZLE_CHECK)

        assertNull(result.echo)
        assertFalse(result.parsed)
        assertTrue(result.inconclusive)
    }

    @Test
    fun `an echo carrying data is kept`() {
        val printer = ScriptedPrinter(Status.STATE_IDLE, Status.STATE_IDLE)
        printer.refusal = "ch:03;"

        val result = Maintenance.run(printer, Maintenance.Operation.HEAD_CLEANING)

        assertEquals("03", result.echo)
        assertTrue(result.parsed)
    }

    @Test
    fun `an idle printer blocks nothing`() {
        assertNull(Maintenance.blockedReason(Status.parse(st2(Status.STATE_IDLE))))
        assertNotNull(Maintenance.blockedReason(Status.parse(st2(Status.STATE_CLEANING))))
    }

    /**
     * The one that was learned the expensive way, on an ET-2820 over USB on 2026-07-29.
     *
     * The status read came back empty — the 1284.4 channel was never up — and the run sent the
     * command anyway. The printer did not ignore it: it rendered every command's two ASCII letters
     * as text, printed the literal string `ststncst`, and then sat waiting for the rest of a job
     * that was never coming. That is the same failure the port-9100 attempt produced, and silence
     * is its only warning.
     *
     * So a silent channel now refuses the run, and this test is what keeps it refusing.
     */
    @Test
    fun `a channel that never answers is refused before anything is sent`() {
        val printer = ScriptedPrinter() // answers no status at all

        val result = Maintenance.run(printer, Maintenance.Operation.NOZZLE_CHECK)

        assertFalse(result.accepted)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("not answering"))
        // The critical assertion: the command never went out. Only status was ever asked for.
        assertFalse(printer.commands.contains("nc"), "a maintenance command reached a dead channel")
    }

    /** [Maintenance.blockedReason] stays a question about the printer, not about the transport. */
    @Test
    fun `a silent status is not itself a busy printer`() {
        assertNull(Maintenance.blockedReason(null))
    }

    // ---- remote mode, where the two framings must never meet on one connection ----

    /**
     * Hands out a fresh [ScriptedPrinter] per phase and remembers them all, so a test can ask what
     * each connection was actually asked to carry.
     */
    private inner class Connections(private vararg val states: Int) : Maintenance.Connection {
        val opened = mutableListOf<ScriptedPrinter>()

        override fun open(): Transport = ScriptedPrinter(*states).also { opened += it }
    }

    /**
     * The rule the whole remote path rests on. A connection that has negotiated 1284.4 renders
     * ESC/P2 as text — that is what printed `ststncst` — so the sequence must go out on a
     * connection that has had no handshake on it at all.
     */
    @Test
    fun `the remote sequence never shares a connection with the handshake`() {
        val connections = Connections(Status.STATE_IDLE)

        Maintenance.runInRemoteMode(connections, Maintenance.Operation.NOZZLE_CHECK)

        val carriedRemote = connections.opened.filter { it.sawRemoteSequence }
        assertEquals(1, carriedRemote.size, "the remote sequence went out on the wrong number of connections")
        assertTrue(
            carriedRemote.single().handshakes == 0,
            "the remote sequence went out on a connection that had negotiated 1284.4",
        )
    }

    /**
     * The ordering bug, pinned so it cannot come back.
     *
     * Reading status leaves the printer in 1284.4 mode even after the USB handle closes. The remote
     * stream now explicitly exits packet mode, so it is safe to restore the busy check before an
     * ink-spending operation.
     */
    @Test
    fun `the default run checks status before sending on a separate connection`() {
        val connections = Connections(Status.STATE_IDLE)

        Maintenance.runInRemoteMode(connections, Maintenance.Operation.NOZZLE_CHECK)

        assertEquals(2, connections.opened.size, "traffic continued after the remote command")
        assertTrue(connections.opened.first().handshakes > 0, "status was not checked first")
        assertTrue(connections.opened[1].sawRemoteSequence, "the second connection did not carry the command")
    }

    /**
     * Proven on an ET-2820: a status poll while the nozzle page was still active printed the
     * literal text `st` and retained the sheet. USB send completion is not printer job completion,
     * so the safe result after a successful write remains observationally inconclusive.
     */
    @Test
    fun `a remote run never polls status after sending`() {
        val connections = Connections(Status.STATE_IDLE, Status.STATE_CLEANING)

        val result = Maintenance.runInRemoteMode(connections, Maintenance.Operation.HEAD_CLEANING)

        assertFalse(result.accepted)
        assertTrue(result.inconclusive)
        assertEquals(Status.STATE_IDLE, result.stateBefore)
        assertNull(result.stateAfter)
        assertEquals(2, connections.opened.size)
        assertTrue(connections.opened.last().sawRemoteSequence)
    }

    @Test
    fun `the default precheck refuses a channel that will not answer`() {
        val connections = Connections() // no status at all

        val result = Maintenance.runInRemoteMode(connections, Maintenance.Operation.POWER_CLEANING)

        assertNotNull(result.error)
        assertFalse(result.accepted)
        assertTrue(connections.opened.none { it.sawRemoteSequence }, "sent into a dead channel")
    }

    @Test
    fun `the precheck refuses a busy printer`() {
        val connections = Connections(Status.STATE_CLEANING)

        val result = Maintenance.runInRemoteMode(
            connections,
            Maintenance.Operation.HEAD_CLEANING,
            precheck = true,
        )

        assertNotNull(result.error)
        assertTrue(connections.opened.none { it.sawRemoteSequence })
    }

    /** The protocol override remains available for experiments, and says nothing about before. */
    @Test
    fun `disabling the precheck reports no before state`() {
        val connections = Connections(Status.STATE_IDLE)

        val result = Maintenance.runInRemoteMode(
            connections,
            Maintenance.Operation.NOZZLE_CHECK,
            precheck = false,
        )

        assertNull(result.stateBefore)
        assertNull(result.stateAfter)
        assertEquals(1, connections.opened.size)
        assertTrue(connections.opened.single().sawRemoteSequence)
    }
}
