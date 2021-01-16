package devtools

import devtools.common.RuffyDatadumpReader
import info.nightscout.comboctl.base.*
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

private val frameLogger = Logger.get("Frame")
private val packetLogger = Logger.get("Packet")

// Tool for dumping Combo transport layer packet information to stdout.
//
// It can be run from the command line like this:
//
//     java -jar devtools/dump_packets/build/libs/dump_packets-standalone.jar <Ruffy data dump file>

fun main(vararg args: String) {
    if (args.isEmpty()) {
        System.err.println("Datadump filename missing")
        return
    }

    val inputStream: BufferedInputStream

    try {
        inputStream = File(args[0]).inputStream().buffered()
    } catch (e: IOException) {
        frameLogger(LogLevel.ERROR) { "Could not open file" }
        return
    }

    val datadumpReader = RuffyDatadumpReader(inputStream)

    // The data dump contains both incoming and outgoing frame data
    // in an interleaved fashion. These two types of data need to
    // be parsed by two separate frame parsers, because said parsers
    // internally accumulate data until a complete frame is present.
    val inFrameParser = ComboFrameParser()
    val outFrameParser = ComboFrameParser()

    while (true) {
        val frameData = datadumpReader.readFrameData()
        if (frameData == null) {
            frameLogger(LogLevel.DEBUG) { "No frame data was read; stopping" }
            break
        }

        val frameParser = (if (frameData.isOutgoingData) outFrameParser else inFrameParser)
        frameParser.pushData(frameData.frameData)
        val framePayload = frameParser.parseFrame()
        if (framePayload == null) {
            frameLogger(LogLevel.DEBUG) { "No frame payload was parsed; skipping" }
            continue
        }

        frameLogger(LogLevel.DEBUG) {
            "Got ${if (frameData.isOutgoingData) "outgoing" else "incoming"} frame payload with ${framePayload.size} byte(s)"
        }

        try {
            val packet = framePayload.toTransportLayerPacket()
            packetLogger(LogLevel.DEBUG) {
                "${if (frameData.isOutgoingData) "<=== Outgoing" else "===> Incoming"} packet:  $packet"
            }

            if (packet.command == TransportLayerIO.Command.DATA) {
                try {
                    val appLayerPacket = ApplicationLayerIO.Packet(packet)
                    packetLogger(LogLevel.DEBUG) { "  Application layer packet: $appLayerPacket" }
                } catch (exc: ApplicationLayerIO.ExceptionBase) {
                    packetLogger(LogLevel.ERROR) { "Could not parse DATA packet as application layer packet: $exc" }
                }
            }
        } catch (exc: TransportLayerIO.InvalidCommandIDException) {
            packetLogger(LogLevel.ERROR) { exc.message ?: "<got InvalidCommandIDException with no message>" }
        } catch (exc: ComboException) {
            packetLogger(LogLevel.ERROR) { "Caught ComboException: $exc" }
        }
    }
}
