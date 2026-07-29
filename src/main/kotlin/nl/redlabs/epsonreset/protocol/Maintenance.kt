package nl.redlabs.epsonreset.protocol

/**
 * The printer's own maintenance operations — a nozzle check, a head cleaning — in the two forms a
 * printer will take them, only one of which does anything.
 *
 * ## Two channels, and what each answered
 *
 * [run] sends the command on the 1284.4 **control channel**, the one the status query uses. That
 * form is settled and it does not work: an ET-2825 answers a nozzle check with `nc:;` — its own
 * name with an empty data field — and stays idle. Not a refusal; the same firmware says `:41:NA;`
 * to a factory read on the same connection, so it knows how to decline and did not. The command was
 * parsed and not acted on, because that channel answers questions rather than performing actions.
 *
 * [runInRemoteMode] sends the [RemoteMode] form instead, as print data, which is where the action
 * form of these commands lives. That is the path with a chance of working, and the parameter bytes
 * are the part still to be established per model — hence [Confidence], and the probe's ability to
 * override them.
 *
 * ## Why this file is careful
 *
 * These commands cost ink, and ink ends up in the pad whose counter this app exists to read. A
 * cleaning cycle therefore *raises* the very number a reset lowers — see [Operation.raisesWasteCounter],
 * which the UI must say out loud before running one. Nothing here writes EEPROM, and
 * [assertNoEepromWrite] enforces that rather than documenting it.
 *
 * And nothing is sent to a printer that has not first answered a status query. A command sent into
 * a channel that is not up is not ignored, it is *printed* — an ET-2820 asked that way produced the
 * literal text `ststncst` and then stalled. Silence is the only warning that precedes it.
 */
object Maintenance {

    /** How much ink an operation spends, and so how much it adds to the waste pad. */
    enum class InkCost(val label: String) {
        /** Nothing measurable. */
        NONE("none"),

        /** A test pattern's worth. */
        SMALL("a little"),

        /** A normal cleaning cycle. */
        MODERATE("a noticeable amount"),

        /** A deep or power cleaning — the expensive one, and not for routine use. */
        HEAVY("a lot"),
    }

    /** How well established the command bytes are for a given operation. */
    enum class Confidence {
        /** The exact bytes have answered on hardware in this project. */
        PROVEN,

        /** The operation started on hardware, but its complete job lifecycle is not established. */
        PARTIAL,

        /** Structurally sound and taken from the documented remote set, but untried here. */
        INFERRED,
    }

    /**
     * A maintenance operation and everything the UI needs to describe it honestly before running
     * it. [command] and [payload] are internal because nothing outside this object should be
     * assembling packets by hand.
     */
    enum class Operation(
        val label: String,
        val summary: String,
        internal val command: String,
        internal val payload: List<Int>,
        /**
         * The parameters this command takes in [RemoteMode]. Least certain part of the whole
         * feature, which is why the probe can override it.
         */
        internal val remoteParameters: List<Int>,
        /**
         * Commands the captured reference stream sends *before* the operative one, inside the same
         * remote session. escputil's nozzle check leads with `VI` and a first `NC 00 10`; its
         * cleaning leads with nothing. The asymmetry is the reference's own.
         */
        internal val remotePrelude: List<Pair<String, List<Int>>> = emptyList(),
        val printsPage: Boolean,
        val inkCost: InkCost,
        val confidence: Confidence,
    ) {
        /**
         * Prints the test pattern. The cheapest way to find out whether a cleaning cycle is even
         * warranted, which matters here more than usual: every cleaning that turns out to have been
         * unnecessary is pad life spent for nothing.
         */
        NOZZLE_CHECK(
            label = "Nozzle check",
            summary = "Prints the test pattern, so you can see which nozzles are actually blocked " +
                "before spending a cleaning cycle on them.",
            command = "nc",
            payload = listOf(0x00),
            remoteParameters = listOf(0x00, 0x00),
            remotePrelude = listOf("VI" to listOf(0x00, 0x00), "NC" to listOf(0x00, 0x10)),
            printsPage = true,
            inkCost = InkCost.SMALL,
            confidence = Confidence.PARTIAL,
        ),

        /** The ordinary cleaning cycle. */
        HEAD_CLEANING(
            label = "Head cleaning",
            summary = "Runs one ordinary cleaning cycle on all colours. The ink it flushes goes " +
                "into the waste pad.",
            command = "ch",
            payload = listOf(0x00),
            remoteParameters = listOf(0x00, 0x00),
            printsPage = false,
            inkCost = InkCost.MODERATE,
            confidence = Confidence.INFERRED,
        ),

        /**
         * The deep cycle. Kept distinct from [HEAD_CLEANING] rather than offered as a parameter,
         * because the cost difference is the whole point and a parameter hides it.
         *
         * `00 10` is not a guess: Ircama's `epson_escp2` builds a cleaning parameter as
         * `group | 0x10`, where the low bits pick the nozzle group and `0x10` is the power flag,
         * citing x900-otsakupuhastajat for it. That also settles what `00 02` would have been —
         * nozzle group 2, not a deep clean. Still [Confidence.INFERRED] because no printer here has
         * run it, but the byte itself now has two independent sources behind it.
         */
        POWER_CLEANING(
            label = "Power cleaning",
            summary = "A deep cleaning cycle. It spends several ordinary cleanings' worth of ink " +
                "in one go and fills the pad accordingly — a last resort, not a routine.",
            command = "ch",
            payload = listOf(0x10),
            remoteParameters = listOf(0x00, 0x10),
            printsPage = false,
            inkCost = InkCost.HEAVY,
            confidence = Confidence.INFERRED,
        ),
        ;

        /**
         * True when running this adds ink to the waste pad — which is to say, when it moves the
         * counter this app otherwise exists to bring down.
         */
        val raisesWasteCounter: Boolean get() = inkCost != InkCost.NONE
    }

    interface Listener {
        fun onTrace(line: String) {}
        fun onNote(text: String) {}
    }

    /**
     * A source of fresh transports to the same printer.
     *
     * [runInRemoteMode] needs this rather than one transport because the two framings may not share
     * a connection: a stream that has negotiated 1284.4 will render ESC/P2 as text, which is the
     * mistake that printed `ststncst`. Each phase therefore gets its own connection, and the
     * negotiation only ever happens on the phases that want it.
     */
    fun interface Connection {
        /** A transport that has had nothing sent on it yet, or null when the printer is unreachable. */
        fun open(): Transport?
    }

    /**
     * What happened. [accepted] is deliberately conservative: unlike an EEPROM write there is no
     * `:42:OK;` to check against, so it is only true when the printer's *own* reported state moved
     * to something that means it took the job — see [stateShowsWork].
     */
    data class Result(
        val operation: Operation,
        val accepted: Boolean,
        val stateBefore: Int?,
        val stateAfter: Int?,
        val reply: ByteArray,
        val error: String? = null,
        /**
         * The data field of the printer's echo — `nc:;` gives `""`, `nc:03;` gives `"03"` — or null
         * when the reply carried no echo. See [echoOf] for why the distinction matters.
         */
        val echo: String? = null,
    ) {
        /** True when the printer echoed the command name back, empty payload or not. */
        val parsed: Boolean get() = echo != null

        /**
         * True when the command went out and nothing contradicted it, but the printer's state never
         * visibly moved. A short cycle that finished before the state was sampled looks exactly like
         * a command that was ignored, and the two must not be reported as the same thing.
         */
        val inconclusive: Boolean get() = error == null && !accepted

        override fun equals(other: Any?) = other is Result &&
            operation == other.operation &&
            accepted == other.accepted &&
            stateBefore == other.stateBefore &&
            stateAfter == other.stateAfter &&
            reply.contentEquals(other.reply) &&
            error == other.error &&
            echo == other.echo

        override fun hashCode(): Int {
            var h = operation.hashCode()
            h = 31 * h + accepted.hashCode()
            h = 31 * h + (stateBefore ?: -1)
            h = 31 * h + (stateAfter ?: -1)
            h = 31 * h + reply.contentHashCode()
            h = 31 * h + (error?.hashCode() ?: 0)
            h = 31 * h + (echo?.hashCode() ?: 0)
            return h
        }
    }

    /** The control-channel packet for one operation — parsed by the printer, but not acted on. */
    fun packetFor(operation: Operation): ByteArray =
        SequenceGenerator.controlCommand(operation.command, operation.payload.map { it.toByte() })
            .also { assertNoEepromWrite(it) }

    /**
     * The remote-mode sequence for one operation — the form that performs the action. [parameters]
     * defaults to the operation's own; overriding it is how variants get tried.
     */
    fun remoteSequenceFor(operation: Operation, parameters: List<Int> = operation.remoteParameters): ByteArray =
        RemoteMode.sequenceOf(operation.remotePrelude + (operation.command to parameters))
            .also { assertNoEepromWrite(it) }

    /** The parameter bytes an operation carries in remote mode, for callers that want to show them. */
    fun remoteParametersOf(operation: Operation): List<Int> = operation.remoteParameters

    /**
     * Why the printer's own account of itself argues against starting maintenance now, or null when
     * it doesn't. A printer already cleaning is the interesting case: asking again is at best
     * ignored and at worst another cycle's worth of ink.
     *
     * A null [status] is *not* answered here. Missing status means the channel never answered, which
     * is a transport question rather than a question about what the printer is doing — [run] refuses
     * on it before it gets this far.
     */
    fun blockedReason(status: Status.Report?): String? {
        val busy = status?.busyReason ?: return null
        return "$busy Wait for it to finish, then try again."
    }

    /**
     * Sends one operation and reports what the printer did about it.
     *
     * The channel handshake is included because this may be the first thing on it. Where a counter
     * read has already opened the channel the extra handshake is harmless — it is the same three
     * packets [CounterReader.readAll] sends.
     */
    fun run(transport: Transport, operation: Operation, listener: Listener? = null): Result {
        val trace = object : CounterReader.Listener {
            override fun onTrace(line: String) = listener?.onTrace(line) ?: Unit
        }

        for (packet in SequenceGenerator.handshake()) {
            if (!transport.send(packet)) {
                return failed(operation, "Transport failure during channel handshake.")
            }
            listener?.onTrace("[OUT] handshake (${packet.size} bytes)\n${Executor.hexDump(packet)}")
            transport.drain()
        }

        val before = CounterReader.readStatus(transport, trace)

        // The channel must be proven live before anything else goes down it. A control command is
        // two ASCII letters; if the 1284.4 channel is not up, the printer does not ignore them — it
        // renders them, and an ET-2820 asked this way printed the literal text "ststncst" and then
        // sat waiting for the rest of a job that was never coming. Silence here is not "no news",
        // it is the one signal that says do not send. Same principle as SnmpTransport.readProven.
        if (before == null) {
            return failed(
                operation,
                "The printer is not answering on the control channel, so nothing was sent. " +
                    "Commands sent into a channel that is not up are printed as text rather than " +
                    "obeyed. Over USB this usually means the print subsystem still holds the " +
                    "device — release it and try again.",
            )
        }

        blockedReason(before)?.let { return failed(operation, it, stateBefore = before.state) }

        val packet = packetFor(operation)
        listener?.onTrace("[OUT] ${operation.label} (${packet.size} bytes)\n${Executor.hexDump(packet)}")

        val collected = java.io.ByteArrayOutputStream()

        // Credit first, the same way a read does it: without an allowance the printer may not
        // transmit, and a refusal would look identical to silence.
        for (credit in SequenceGenerator.creditPair()) {
            if (!transport.send(credit)) {
                return failed(operation, "Transport failure while granting credit.", before?.state)
            }
            collected.write(transport.drain())
        }

        if (!transport.send(packet)) {
            return failed(operation, "The printer did not accept the ${operation.label} command.", before?.state)
        }
        collected.write(transport.drain())

        val reply = collected.toByteArray()
        listener?.onTrace("[IN]  reply (${reply.size} bytes)\n${Executor.hexDump(reply)}")

        // A `:NA;` naming some other command byte is still a refusal, and must not be reported as
        // silence — FactoryReply only phrases the read and write cases, so the rest get their own.
        if (FactoryReply.isRefused(reply)) {
            val explanation = FactoryReply.explain(reply)
                ?: "The printer refused the ${operation.label} command (:NA;). Its firmware does " +
                "not accept it over this connection."
            // stateAfter stays null: a refusal ends the run before anything is sampled, and
            // repeating the earlier reading here would claim a measurement never taken.
            return Result(operation, false, before?.state, null, reply, explanation)
        }

        val echo = echoOf(reply, operation)

        // The acceptance signal. There is no acknowledgement to read, so the question "did it take
        // the job" is answered by asking the printer what it is now doing.
        val after = CounterReader.readStatus(transport, trace)
        val accepted = stateShowsWork(after?.state)

        listener?.onNote(
            when {
                accepted -> "${operation.label} started — the printer reports it is working."

                // Parsed, but nothing to say and nothing done. The channel understood the name and
                // declined to act on it, which is a different answer from being ignored.
                echo != null && echo.isEmpty() ->
                    "The printer echoed '${operation.command}:;' — it parsed the command but did " +
                        "not act on it, and its state did not move."

                echo != null ->
                    "The printer answered '${operation.command}:$echo;' but its state did not move."

                else ->
                    "${operation.label} was sent and nothing refused it, but the printer said " +
                        "nothing and its state did not move."
            },
        )

        return Result(operation, accepted, before?.state, after?.state, reply, echo = echo)
    }

    /**
     * The data field of the printer's echo of its own command, or null when the reply carries none.
     *
     * An ET-2825 answers `nc` with `nc:;` — the command name, a colon, nothing, a semicolon. That
     * is the general Epson reply grammar with an **empty** data field, and it separates three
     * things that would otherwise all look like failure:
     *
     * - no echo at all — the command never reached a parser,
     * - an echo with data — the command was understood and answered,
     * - an echo with nothing in it — the command was *parsed* and had nothing to say, which is what
     *   a query channel does with a command that isn't a query.
     *
     * The third is the observed case, and it is why a null echo and an empty one must not collapse
     * into the same report.
     */
    internal fun echoOf(reply: ByteArray, operation: Operation): String? = Regex("${operation.command}:([^;]*);")
        .find(String(reply, Charsets.ISO_8859_1))
        ?.groupValues
        ?.get(1)

    /**
     * Runs one operation as [RemoteMode] print data — the form that actually performs the action,
     * as against the control channel's form, which is only parsed.
     *
     * ## Why only the status check before the command is safe
     *
     * Reading the status negotiates 1284.4, and the printer stays in that mode after the USB handle
     * is closed. [RemoteMode.sequenceFor] starts with Epson's explicit Exit Packet Mode EJL sequence,
     * so checking for a busy or errored printer immediately before the operation is safe.
     *
     * The inverse is not safe: a successful USB write only means the bytes reached the device. It
     * does not mean a printed page has been ejected or that the printer has returned to its control
     * parser. On an ET-2820, negotiating 1284.4 2.5 seconds after `NC` made the still-active print
     * stream render the status command as the literal text `st` and retain the sheet. There is no
     * reliable completion signal on this stream, so this method deliberately performs no automatic
     * status read after sending.
     *
     * [precheck] remains switchable for protocol experiments, but defaults on: a busy printer is
     * refused and [Result.stateBefore] is populated before ink-spending work is requested.
     *
     * [parameters] defaults to the operation's own, and exists so the probe can try variants
     * without a recompile — the parameter bytes are the least established part of this.
     */
    fun runInRemoteMode(
        connection: Connection,
        operation: Operation,
        parameters: List<Int> = operation.remoteParameters,
        listener: Listener? = null,
        precheck: Boolean = true,
    ): Result {
        val trace = object : CounterReader.Listener {
            override fun onTrace(line: String) = listener?.onTrace(line) ?: Unit
        }

        var before: Status.Report? = null
        if (precheck) {
            before = negotiatedStatus(connection, trace)
                ?: return failed(
                    operation,
                    "The printer is not answering on the control channel, so nothing was sent. A " +
                        "printer that cannot be asked what it is doing cannot be told to do anything.",
                )

            blockedReason(before)?.let { return failed(operation, it, stateBefore = before?.state) }

            listener?.onNote(
                "Status checked; the remote stream will explicitly exit 1284.4 packet mode before sending ESC/P2.",
            )
        }

        // The send gets its own handle. The printer may still be in 1284.4 from the precheck, so the
        // sequence itself begins by explicitly exiting packet mode.
        val sequence = remoteSequenceFor(operation, parameters)
        check(RemoteMode.isRemoteSequence(sequence)) {
            "Remote sequence lost its envelope — refusing to send raw bytes to the print stream."
        }

        listener?.onTrace("[OUT] ${operation.label} remote (${sequence.size} bytes)\n${Executor.hexDump(sequence)}")

        val sent = connection.open()?.use { it.send(sequence) }
        if (sent != true) {
            return failed(operation, "Could not send the ${operation.label} sequence.", before?.state)
        }

        listener?.onNote(
            "Sent as remote-mode print data. No status poll will be made while the printer may still " +
                "be processing it; verify the result at the printer.",
        )

        return Result(operation, false, before?.state, null, ByteArray(0))
    }

    /** Opens, negotiates 1284.4, and reads the status block. Null when any of that fails. */
    private fun negotiatedStatus(connection: Connection, trace: CounterReader.Listener): Status.Report? =
        connection.open()?.use { transport ->
            for (packet in SequenceGenerator.handshake()) {
                if (!transport.send(packet)) return@use null
                transport.drain()
            }
            CounterReader.readStatus(transport, trace)
        }

    /** States that mean the printer picked the job up. */
    internal fun stateShowsWork(state: Int?): Boolean = state != null && state in WORKING_STATES

    private val WORKING_STATES = setOf(
        Status.STATE_SELF_TEST,
        Status.STATE_BUSY,
        Status.STATE_CLEANING,
    )

    /** [Result.stateAfter] is left null throughout: a run that failed never sampled one. */
    private fun failed(operation: Operation, error: String, stateBefore: Int? = null) =
        Result(operation, false, stateBefore, null, ByteArray(0), error)

    /**
     * The guarantee, enforced rather than documented: nothing this object emits is an EEPROM write.
     * Maintenance costs ink, which is recoverable; a stray write to the wrong address is not.
     */
    private fun assertNoEepromWrite(packet: ByteArray) {
        check(!Executor.isWritePacket(packet)) {
            "Maintenance built an EEPROM write packet — refusing to send it."
        }
    }
}
