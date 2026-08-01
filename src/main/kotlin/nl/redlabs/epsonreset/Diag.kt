package nl.redlabs.epsonreset

/**
 * Developer-mode diagnostic logging, off by default. A cross-cutting sink so low-level code (the
 * USB/spooler transports, the scanners) can emit detail without carrying a logger through every
 * signature. The UI wires [sink] to the log panel and flips [enabled] from the Developer setting;
 * the lines land as TRACE, so they are captured by the log's Copy whether or not the panel shows them.
 *
 * Messages are built lazily via [log], so a disabled Developer mode costs nothing at the call site.
 */
object Diag {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var sink: ((String) -> Unit)? = null

    /** Point diagnostics at a destination (the log panel). Safe to call again to replace it. */
    fun wire(sink: (String) -> Unit) {
        this.sink = sink
    }

    /** Emit one already-built line, if Developer mode is on and a sink is wired. */
    fun emit(line: String) {
        if (enabled) sink?.invoke(line)
    }

    /** Emit a line whose text is only built when Developer mode is on. */
    inline fun log(message: () -> String) {
        if (enabled) emit(message())
    }
}
