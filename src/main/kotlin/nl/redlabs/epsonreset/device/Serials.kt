package nl.redlabs.epsonreset.device

/**
 * One printer, two answers. A USB descriptor reports the serial hex-encoded — `584144413032…` —
 * where SNMP reports the same characters plainly, as `XADA0202…`. Left alone the two links look
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
     */
    fun canonical(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length < MIN_ENCODED_LENGTH || text.length % 2 != 0) return text
        if (!text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return text

        val decoded = runCatching {
            String(
                ByteArray(text.length / 2) { i ->
                    text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                },
                Charsets.ISO_8859_1,
            )
        }.getOrNull() ?: return text

        return if (PLAUSIBLE.matches(decoded)) decoded else text
    }

    /** Whether two serials name the same printer, whichever link each of them came from. */
    fun same(a: String?, b: String?): Boolean {
        val left = canonical(a) ?: return false
        val right = canonical(b) ?: return false
        return left.equals(right, ignoreCase = true)
    }

    /** Eight characters is four bytes — below that a hex run is as likely to be a serial as a code. */
    private const val MIN_ENCODED_LENGTH = 8

    /** What a decoded serial has to look like to be believed. */
    private val PLAUSIBLE = Regex("""[A-Za-z0-9]{6,}""")
}
