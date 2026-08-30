package nl.redlabs.epsonreset.device

import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.i18n.UiText
import nl.redlabs.epsonreset.net.EpsonMib
import nl.redlabs.epsonreset.net.SnmpTransport
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.DeviceId
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.connection_advice_net
import nl.redlabs.epsonreset.resources.connection_advice_nothing
import nl.redlabs.epsonreset.resources.connection_advice_usb
import nl.redlabs.epsonreset.resources.connection_failed
import nl.redlabs.epsonreset.resources.connection_net_counters
import nl.redlabs.epsonreset.resources.connection_net_refused
import nl.redlabs.epsonreset.resources.connection_net_status_only
import nl.redlabs.epsonreset.resources.connection_no_answer
import nl.redlabs.epsonreset.resources.connection_usb_no_answer
import nl.redlabs.epsonreset.resources.connection_usb_no_counters
import nl.redlabs.epsonreset.resources.connection_usb_ok

/** Proves a printer can be talked to, and — over the network — how far. */
object ConnectionTest {

    /** How far a network printer will let this app go. */
    enum class Reach {
        /** Nothing answered. */
        NONE,

        /** Identity and status only — the printer refuses factory commands over this link. */
        STATUS_ONLY,

        /** A counter read came back. Everything the USB path can do, this link can do. */
        COUNTERS,
    }

    data class Result(
        val opened: Boolean,
        val identity: DeviceId.Id?,
        val answered: Boolean,
        val status: Status.Report?,
        val overNetwork: Boolean = false,
        val reach: Reach = Reach.NONE,
        /** The exact model the printer named itself, when it can — better than any match. */
        val reportedModel: String? = null,
        val firmware: String? = null,
        /** The printer's own words when it declined, via [nl.redlabs.epsonreset.protocol.FactoryReply]. */
        val refusal: String? = null,
        val failure: String? = null,
    ) {
        /** Whether this connection could carry a reset. */
        val usable: Boolean
            get() = opened && if (overNetwork) reach == Reach.COUNTERS else answered

        val serial: String? get() = status?.serial ?: identity?.serial

        val model: String? get() = reportedModel ?: identity?.model

        /** [failure] and [refusal] pass through raw — the transport's and the printer's own words. */
        val headline: UiText
            get() = when {
                !opened -> failure?.let { UiText.raw(it) } ?: UiText.of(Res.string.connection_failed)
                !overNetwork && answered -> UiText.of(Res.string.connection_usb_ok)
                !overNetwork && identity != null -> UiText.of(Res.string.connection_usb_no_counters)
                !overNetwork -> UiText.of(Res.string.connection_usb_no_answer)
                reach == Reach.COUNTERS -> UiText.of(Res.string.connection_net_counters)
                reach == Reach.STATUS_ONLY && refusal != null -> UiText.of(Res.string.connection_net_refused)
                reach == Reach.STATUS_ONLY -> UiText.of(Res.string.connection_net_status_only)
                else -> UiText.of(Res.string.connection_no_answer)
            }

        val advice: UiText?
            get() = when {
                !opened -> null
                usable -> null
                overNetwork ->
                    refusal?.let { UiText.raw(it) }
                        ?: UiText.of(Res.string.connection_advice_net)
                identity != null -> UiText.of(Res.string.connection_advice_usb)
                else -> UiText.of(Res.string.connection_advice_nothing)
            }
    }

    /** Opens, asks, and closes. */
    fun run(printer: DetectedPrinter, model: PrinterModel? = null): Result {
        val link = printer.link
        return if (link is Link.Network) overNetwork(link, model) else overUsb(printer)
    }

    private fun overNetwork(link: Link.Network, model: PrinterModel?): Result {
        val opened = SnmpTransport.open(link, port = link.port)
        if (opened is SnmpTransport.OpenResult.Failed) {
            return Result(
                opened = false,
                identity = null,
                answered = false,
                status = null,
                overNetwork = true,
                failure = listOfNotNull(opened.message, opened.remedy).joinToString(" "),
            )
        }

        val transport = (opened as SnmpTransport.OpenResult.Ok).transport
        val host = link.host

        // Identity first, straight from the MIB — no command channel needed, so this works even on
        // firmware that refuses everything below.
        val port = link.port
        val identity = SnmpTransport.string(host, EpsonMib.DEVICE_ID, port = port)?.let {
            DeviceId.parse(it.toByteArray(Charsets.ISO_8859_1))
        }
        val reportedModel = SnmpTransport.string(host, EpsonMib.MODEL, port = port)
        val firmware = SnmpTransport.string(host, EpsonMib.FIRMWARE, port = port)
        val status = SnmpTransport.bytes(host, EpsonMib.STATUS, port = port)?.let { Status.parse(it) }

        val answered = identity != null || status != null
        if (!answered) {
            return Result(
                opened = true,
                identity = null,
                answered = false,
                status = null,
                overNetwork = true,
                reach = Reach.NONE,
            )
        }

        // The question only the printer can settle. One read, of one address, with the model's own
        // key — and the answer decides whether anything beyond status is possible here.
        var reach = Reach.STATUS_ONLY
        val address = model?.padGroups?.firstOrNull()?.addresses?.firstOrNull()

        if (model != null && address != null) {
            transport.send(SequenceGenerator.readPacket(model.readKey, address))
            val reply = transport.drain()
            if (CounterReader.parseReplies(reply).any { it.first == address }) reach = Reach.COUNTERS
        }

        return Result(
            opened = true,
            identity = identity,
            answered = true,
            status = status,
            overNetwork = true,
            reach = reach,
            reportedModel = reportedModel,
            firmware = firmware,
            refusal = transport.refusal,
        )
    }

    private fun overUsb(printer: DetectedPrinter): Result {
        val opened = PrinterTransports.open(printer)
        if (opened is PrinterTransports.OpenResult.Failed) {
            return Result(
                opened = false,
                identity = null,
                answered = false,
                status = null,
                failure = opened.detail,
            )
        }

        return (opened as PrinterTransports.OpenResult.Ok).transport.use { channel ->
            // Identity first: it is the one question that works before the channel is negotiated.
            val identity = DeviceId.query(channel)

            var channelOpened = false
            for (packet in SequenceGenerator.handshake()) {
                if (!channel.send(packet)) break
                if (Executor.isChannelOpenAck(channel.drain())) channelOpened = true
            }

            Result(
                opened = true,
                identity = identity,
                answered = channelOpened,
                status = if (channelOpened) CounterReader.readStatus(channel) else null,
                reach = if (channelOpened) Reach.COUNTERS else Reach.NONE,
            )
        }
    }
}
