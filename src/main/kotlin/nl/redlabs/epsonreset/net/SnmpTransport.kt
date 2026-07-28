package nl.redlabs.epsonreset.net

import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.EscpRemote
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.FactoryReply
import nl.redlabs.epsonreset.protocol.Transport

/** [Transport] over SNMP, through Epson's command passthrough. */
class SnmpTransport private constructor(
    private val host: String,
    private val community: String,
    private val timeoutMs: Int,
    private val port: Int = Snmp.PORT,
) : Transport {

    private var pending: ByteArray = ByteArray(0)

    /** Set once a real reading has come back, which is what unlocks writes. */
    var readProven: Boolean = false
        private set

    /** Set when the printer answered a factory command with `:NA;`. */
    var refusal: String? = null
        private set

    /** Set when a write was refused locally, so the caller can say why. */
    var refusedWrite: Boolean = false
        private set

    override fun send(packet: ByteArray): Boolean {
        if (Executor.isWritePacket(packet) && !readProven) {
            refusedWrite = true
            return false
        }

        // No channel to open and no credit to grant. Reporting success because nothing failed.
        if (EscpRemote.isChannelPacket(packet)) {
            pending = ByteArray(0)
            return true
        }

        val command = EscpRemote.remoteCommandOf(packet) ?: return false

        val oid = EpsonMib.passthroughFor(command)
        return when (val result = Snmp.get(host, oid, community, timeoutMs, port = port)) {
            is Snmp.Result.Ok -> {
                pending = EpsonMib.payloadOf(result.value)
                observe(pending)
                true
            }

            // The passthrough is not walkable, so a printer without it answers this rather than
            // timing out. It means "this model has no command channel here", not "wrong request".
            Snmp.Result.NoSuchObject -> {
                pending = ByteArray(0)
                refusal = "This printer does not expose Epson's SNMP command passthrough."
                false
            }

            Snmp.Result.Timeout -> {
                pending = ByteArray(0)
                false
            }

            is Snmp.Result.Error -> {
                pending = ByteArray(0)
                false
            }

            is Snmp.Result.Failed -> {
                pending = ByteArray(0)
                false
            }
        }
    }

    /** Notes what the reply proves. */
    private fun observe(reply: ByteArray) {
        if (CounterReader.parseReplies(reply).isNotEmpty()) readProven = true
        FactoryReply.explain(reply)?.let { refusal = it }
    }

    override fun drain(): ByteArray {
        val reply = pending
        pending = ByteArray(0)
        return reply
    }

    /** Nothing to close: every exchange is its own datagram. */
    override fun close() = Unit

    sealed interface OpenResult {
        data class Ok(val transport: SnmpTransport) : OpenResult
        data class Failed(val message: String, val remedy: String?) : OpenResult
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 2000

        /** Opens by asking the printer to identify itself. */
        fun open(
            link: Link.Network,
            community: String = "public",
            timeoutMs: Int = DEFAULT_TIMEOUT_MS,
            port: Int = Snmp.PORT,
        ): OpenResult {
            val transport = SnmpTransport(link.host, community, timeoutMs, port)

            return when (val probe = Snmp.get(link.host, EpsonMib.DEVICE_ID, community, timeoutMs, port = port)) {
                is Snmp.Result.Ok -> OpenResult.Ok(transport)

                Snmp.Result.Timeout -> OpenResult.Failed(
                    "${link.host} did not answer on SNMP.",
                    "Check the printer is switched on and on this network, and that SNMP is " +
                        "enabled in its network settings — Epson ships it on by default.",
                )

                Snmp.Result.NoSuchObject -> OpenResult.Failed(
                    "${link.host} answered SNMP but is not an Epson.",
                    "Check the address. This looks like some other device on the network.",
                )

                is Snmp.Result.Error -> OpenResult.Failed(
                    "${link.host} refused the SNMP query (error ${probe.status}).",
                    "The printer may use a community string other than 'public'.",
                )

                is Snmp.Result.Failed -> OpenResult.Failed(
                    "Could not reach ${link.host} over SNMP (${probe.message}).",
                    null,
                )
            }
        }

        /** What a printer says it is, without any command channel being involved. */
        data class Identity(val model: String?, val product: String?, val serial: String?)

        /** Asks a printer to identify itself. */
        fun identify(
            host: String,
            community: String = "public",
            timeoutMs: Int = 1200,
            port: Int = Snmp.PORT,
        ): Identity? {
            val model = string(host, EpsonMib.MODEL, community, timeoutMs, port)
            val product = string(host, EpsonMib.PRODUCT, community, timeoutMs, port)
            val serial = string(host, EpsonMib.SERIAL, community, timeoutMs, port)

            return if (model == null && product == null && serial == null) {
                null
            } else {
                Identity(model, product, serial)
            }
        }

        /** Reads one string-valued OID, for the identity queries that need no command. */
        fun string(
            host: String,
            oid: List<Int>,
            community: String = "public",
            timeoutMs: Int = DEFAULT_TIMEOUT_MS,
            port: Int = Snmp.PORT,
        ): String? = (Snmp.get(host, oid, community, timeoutMs, port = port) as? Snmp.Result.Ok)
            ?.value
            ?.toString(Charsets.ISO_8859_1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        /** Reads one OID as raw bytes, for the status block. */
        fun bytes(
            host: String,
            oid: List<Int>,
            community: String = "public",
            timeoutMs: Int = DEFAULT_TIMEOUT_MS,
            port: Int = Snmp.PORT,
        ): ByteArray? = (Snmp.get(host, oid, community, timeoutMs, port = port) as? Snmp.Result.Ok)
            ?.value
            ?.takeIf { it.isNotEmpty() }
    }
}
