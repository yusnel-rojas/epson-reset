package nl.redlabs.epsonreset.device

/**
 * One printer, two answers. A USB descriptor reports the serial hex-encoded — `515745523031…` —
 * where SNMP reports the same characters plainly, as `QWER0123…`. Left alone the two links look
 * like two unrelated printers, which costs both the cross-link identification and any answer
 * remembered against a serial on the other one.
 */
object Serials {

    /**
     * [raw] with a hex-encoded serial decoded, and anything else returned as it came.
     *
     * The test is deliberately narrow, because guessing wrong here silently renames a printer: the
     * string has to be an even run of hex digits long enough to be a serial, *and* what it decodes
     * to has to look like one. A decimal-only serial passes the first half and fails the second —
     * its bytes decode to control characters — so it is left as it is.
     *
     * This is the spelling shown to the reader, so it has to commit to one answer. Comparing two
     * serials does not, and should go through [same] instead.
     */
    fun canonical(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length < MIN_ENCODED_LENGTH || text.length % 2 != 0) return text
        if (!text.all { it.isHexDigit() }) return text

        val decoded = decode(text) ?: return text
        return if (PLAUSIBLE.matches(decoded)) decoded else text
    }

    /**
     * Every spelling of [raw] that could name the same printer.
     *
     * [canonical] assumes a descriptor is hex the whole way through, and on an ET-2820 that is not
     * true: it hex-encodes the first eight characters and writes the last two literally. Using a
     * stand-in serial, `QWER012345` arrives as `515745523031323345` — hex for `QWER0123`, then
     * `45`. Read as uniform hex that trailing `45` becomes one byte, `0x45`, and the serial
     * decodes to `QWER0123E` rather than the `QWER012345` the same printer answers over SNMP.
     * Nothing in the string marks where the encoding stops, so neither reading can be ruled out
     * from the string alone.
     *
     * Rather than pick, every split is offered and [same] looks for the one the other link agrees
     * with. Only splits where the encoded part is the majority are included: past that point the
     * decoded prefix is short enough that any run of digits produces something, and the candidates
     * stop being evidence of anything.
     */
    fun readings(raw: String?): Set<String> {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptySet()
        val readings = linkedSetOf(text)
        if (text.length % 2 != 0 || !text.all { it.isHexDigit() }) return readings

        var cut = text.length
        while (cut >= MIN_ENCODED_LENGTH) {
            val decoded = decode(text.substring(0, cut)) ?: break
            val tail = text.substring(cut)
            // Once the unencoded tail has caught up with the decoded part there is no reading
            // left worth having, and every shorter prefix is further past that line.
            if (tail.length >= decoded.length) break
            (decoded + tail).takeIf { PLAUSIBLE.matches(it) }?.let { readings += it }
            cut -= 2
        }
        return readings
    }

    /**
     * Whether two serials name the same printer, whichever link each of them came from.
     *
     * A match means two whole candidate spellings agree exactly. Deliberately not a shared prefix:
     * units built in one batch differ only in their last characters, so a rule loose enough to
     * join this printer's two links would also join two different printers on the same desk.
     */
    fun same(a: String?, b: String?): Boolean {
        val left = readings(a).mapTo(mutableSetOf()) { it.lowercase() }
        if (left.isEmpty()) return false
        return readings(b).any { it.lowercase() in left }
    }

    /** Eight characters is four bytes — below that a hex run is as likely to be a serial as a code. */
    private const val MIN_ENCODED_LENGTH = 8

    /** What a decoded serial has to look like to be believed. */
    private val PLAUSIBLE = Regex("""[A-Za-z0-9]{6,}""")

    /** [hex] read as one byte per pair, or null if it will not read that way. */
    private fun decode(hex: String): String? = runCatching {
        String(
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            },
            Charsets.ISO_8859_1,
        )
    }.getOrNull()

    private fun Char.isHexDigit(): Boolean = isDigit() || this in 'a'..'f' || this in 'A'..'F'
}
