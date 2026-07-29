package nl.redlabs.epsonreset.protocol

/**
 * ESC/P2 remote mode: the command form that lives in the **print data stream** rather than on the
 * 1284.4 control channel.
 *
 * ## Why there are two forms of the same command
 *
 * The control channel parses a nozzle check and does nothing with it — an ET-2825 answers `nc:;`,
 * its own name with an empty data field, and stays idle. That channel answers questions; it does
 * not perform actions. The action form is this one, wrapped in the escape sequences that make it
 * valid print data instead of a control packet.
 *
 * ## Why this is not the thing that damaged a printer
 *
 * It is worth being precise, because this project has already broken a printer on this exact
 * boundary. What did the damage was sending **1284.4 packets** to the print data stream: those are
 * not valid ESC/P2, so the printer rendered them as pages and then waited for a job that never
 * came. The same thing happened again over USB, printing the literal text `ststncst`.
 *
 * What is built here is the opposite: ESC/P2 remote-mode data that the parser recognises. The
 * ET-2820 executed `NC 00 00` and printed its full nozzle report.
 *
 * ## Captured, not derived
 *
 * Every byte here is taken from `escputil --nozzle-check` and `--clean-head` (gutenprint 5.3.3,
 * `escp2-et2750`), dumped to a file rather than a printer. That matters because the two endings
 * invented before the capture were both wrong: a `JE` command, which the reference never sends at
 * all, and a form feed applied only to a nozzle check. The ending that finally ejected the sheet is
 * the reference's, unchanged and identical for both operations — see [JOB_END].
 *
 * ## The one rule
 *
 * Remote mode and 1284.4 are mutually exclusive parser states, but a new USB handle does not reset
 * the state held by the printer. Every sequence therefore starts with [EXIT_PACKET_MODE], the EJL
 * preamble which explicitly returns the printer to its print-data parser before any ESC/P2 bytes.
 */
object RemoteMode {

    /**
     * Exit IEEE-1284.4 packet mode. The five spaces before LF are part of Epson's fixed EJL
     * sequence, not formatting. This must precede ESC/P2 even on a newly opened USB handle because
     * packet mode is printer state, not handle state.
     */
    private val EXIT_PACKET_MODE = byteArrayOf(0x00, 0x00, 0x00, 0x1B, 0x01) +
        "@EJL 1284.4\n@EJL     \n".toByteArray(Charsets.ISO_8859_1)

    /** `ESC @` — initialise. Resets the command parser, so whatever state the stream was in is gone. */
    private val INIT = byteArrayOf(0x1B, 0x40)

    /** `ESC ( R <len=8> NUL REMOTE1` — enter remote mode. */
    private val ENTER = byteArrayOf(0x1B, 0x28, 0x52, 0x08, 0x00, 0x00) +
        "REMOTE1".toByteArray(Charsets.ISO_8859_1)

    /**
     * What closes a remote session, before either the next one or the end of the job. `ESC NUL NUL
     * NUL` leaves remote mode; the trailing `ESC NUL` is escputil's and is emitted after every
     * session it opens.
     */
    private val SESSION_END = byteArrayOf(0x1B, 0x00, 0x00, 0x00, 0x1B, 0x00)

    /**
     * What ends the whole job, after the last session.
     *
     * `0x0C` is the ESC/P2 form feed and it is what ejects the sheet: without it an ET-2820 prints a
     * complete nozzle report and holds the paper until it is powered off. The trailing `ESC NUL`
     * pairs are escputil's, kept because this is a captured sequence rather than a derived one —
     * dropping bytes from a stream that works, to tidy it, is how this feature broke twice.
     *
     * The same ending follows every operation: nozzle check, cleaning and alignment alike.
     */
    private val JOB_END = byteArrayOf(0x0C, 0x1B, 0x00, 0x1B, 0x00)

    /**
     * One remote command: two ASCII characters, a little-endian length, then the parameters. The
     * same shape as a control-channel command — only the envelope around it differs.
     */
    fun command(name: String, parameters: List<Int> = emptyList()): ByteArray =
        name.uppercase().toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(
                (parameters.size and 0xFF).toByte(),
                ((parameters.size shr 8) and 0xFF).toByte(),
            ) +
            ByteArray(parameters.size) { parameters[it].toByte() }

    /**
     * The complete stream for one command: leave packet mode, one remote session carrying the
     * operation, then [JOB_END].
     *
     * Sent as a single write. Splitting it risks the printer sitting in remote mode between packets
     * if anything goes wrong in the middle, which is the state that needs a power cycle to leave.
     *
     * This is escputil's stream byte for byte, for both `NC` and `CH`. Two earlier shapes were tried
     * and are worth not repeating: a `JE` command in its own remote session, which escputil never
     * sends, and a form feed applied only to `NC`, which left cleaning without the ending its own
     * reference gives it. A `TI` clock prelude was also tried for cleaning;
     * escputil sends none, so it went the way of `JE`.
     */
    fun sequenceFor(name: String, parameters: List<Int> = emptyList()): ByteArray =
        sequenceOf(listOf(name to parameters))

    /** One session carrying several commands — escputil's nozzle check is `VI`, `NC`, `NC`. */
    fun sequenceOf(commands: List<Pair<String, List<Int>>>): ByteArray = stream(listOf(commands))

    /**
     * The general form: a job is one or more remote sessions, each carrying commands.
     *
     * Sessions matter because escputil uses them differently per operation. A nozzle check is one
     * session holding three commands; an alignment run is one session **per pass**, four of them.
     * Only the first session initialises twice — later ones use a single `ESC @`, which is why this
     * cannot be built by concatenating whole sequences.
     *
     * Sent as a single write. Splitting it risks the printer sitting in remote mode between packets,
     * which is the state that needs a power cycle to leave.
     */
    fun stream(sessions: List<List<Pair<String, List<Int>>>>): ByteArray {
        val body = sessions.mapIndexed { index, commands ->
            val init = if (index == 0) INIT + INIT else INIT
            val payload = commands.fold(ByteArray(0)) { acc, (name, params) -> acc + command(name, params) }
            init + ENTER + payload + SESSION_END
        }.fold(ByteArray(0)) { acc, part -> acc + part }

        return EXIT_PACKET_MODE + body + JOB_END
    }

    /** True when [data] has both the packet-mode exit and the remote-mode envelope. */
    fun isRemoteSequence(data: ByteArray): Boolean {
        val remoteStart = EXIT_PACKET_MODE.size
        val envelope = INIT + INIT + ENTER
        return data.size >= remoteStart + envelope.size &&
            data.copyOfRange(0, remoteStart).contentEquals(EXIT_PACKET_MODE) &&
            data.copyOfRange(remoteStart, remoteStart + envelope.size).contentEquals(envelope)
    }
}
