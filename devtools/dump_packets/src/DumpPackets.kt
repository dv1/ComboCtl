package devtools

import devtools.common.RuffyDatadumpReader
import info.nightscout.comboctl.base.*
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

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

    val loggerFactory = LoggerFactory(StderrLoggerBackend(), LogLevel.DEBUG)
    val frameLogger = loggerFactory.getLogger(LogCategory.FRAME)
    val packetLogger = loggerFactory.getLogger(LogCategory.PACKET)

    val inputStream: BufferedInputStream

    try {
        inputStream = File(args[0]).inputStream().buffered()
    } catch (e: IOException) {
        frameLogger.log(LogLevel.ERROR, e) { "Could not open file" }
        return
    }

    val datadumpReader = RuffyDatadumpReader(inputStream, frameLogger)

    // The data dump contains both incoming and outgoing frame data
    // in an interleaved fashion. These two types of data need to
    // be parsed by two separate frame parsers, because said parsers
    // internally accumulate data until a complete frame is present.
    val inFrameParser = ComboFrameParser()
    val outFrameParser = ComboFrameParser()

    while (true) {
        val frameData = datadumpReader.readFrameData()
        if (frameData == null) {
            frameLogger.log(LogLevel.DEBUG) { "No frame data was read; stopping" }
            break
        }

        val frameParser = (if (frameData.isOutgoingData) outFrameParser else inFrameParser)
        frameParser.pushData(frameData.frameData)
        val framePayload = frameParser.parseFrame()
        if (framePayload == null) {
            frameLogger.log(LogLevel.DEBUG) { "No frame payload was parsed; skipping" }
            continue
        }

        frameLogger.log(LogLevel.DEBUG) {
            "Got ${if (frameData.isOutgoingData) "outgoing" else "incoming"} frame payload with ${framePayload.size} byte(s)"
        }

        try {
            val packet = framePayload.toTransportLayerPacket()
            packetLogger.log(LogLevel.DEBUG) {
                val directionDesc = if (frameData.isOutgoingData) "<=== Outgoing" else "===> Incoming"
                "$directionDesc packet:" +
                "  major/minor version: ${packet.majorVersion}/${packet.minorVersion}" +
                "  command ID: ${packet.commandID?.name ?: "<unknown command ID>"}" +
                "  sequence bit: ${packet.sequenceBit}" +
                "  reliability bit: ${packet.reliabilityBit}" +
                "  source/destination address: ${packet.sourceAddress}/${packet.destinationAddress}" +
                "  nonce: ${packet.nonce}" +
                "  MAC: ${packet.machineAuthenticationCode.toHexString()}" +
                "  payload: ${packet.payload.size} byte(s): ${packet.payload.toHexString()}"
            }

            if (packet.commandID == TransportLayer.CommandID.DATA) {
                try {
                    val appLayerPacket = ApplicationLayer.Packet(packet)
                    packetLogger.log(LogLevel.DEBUG) {
                        "  Application layer packet: " +
                        "  major/minor version: ${appLayerPacket.majorVersion}/${appLayerPacket.minorVersion}" +
                        "  service ID: ${appLayerPacket.command?.serviceID ?: "<unknown service ID>"}" +
                        "  command: ${appLayerPacket.command ?: "<unknown command ID>"}" +
                        "  payload: ${appLayerPacket.payload.size} byte(s): ${appLayerPacket.payload.toHexString()}"
                    }
                } catch (exc: ApplicationLayer.ExceptionBase) {
                    packetLogger.log(LogLevel.ERROR) { "Could not parse DATA packet as application layer packet: $exc" }
                }
            }
        } catch (exc: TransportLayer.InvalidCommandIDException) {
            packetLogger.log(LogLevel.ERROR) { exc.message ?: "<got InvalidCommandIDException with no message>" }
        } catch (exc: ComboException) {
            packetLogger.log(LogLevel.ERROR) { "Caught ComboException: $exc" }
        }
    }
}
