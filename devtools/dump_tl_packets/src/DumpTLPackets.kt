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
//     java -jar devtools/dump_tl_packets/build/libs/dump_tl_packets-standalone.jar <Ruffy data dump file>

fun main(vararg args: String) {
    if (args.isEmpty()) {
        System.err.println("Datadump filename missing")
        return
    }

    val logger = LoggerFactory(StderrLoggerBackend(), LogLevel.DEBUG).getLogger(LogCategory.FRAME)

    val inputStream: BufferedInputStream

    try {
        inputStream = File(args[0]).inputStream().buffered()
    } catch (e: IOException) {
        logger.log(LogLevel.ERROR, e) { "Could not open file" }
        return
    }

    val datadumpReader = RuffyDatadumpReader(inputStream, logger)

    // The data dump contains both incoming and outgoing frame data
    // in an interleaved fashion. These two types of data need to
    // be parsed by two separate frame parsers, because said parsers
    // internally accumulate data until a complete frame is present.
    val inFrameParser = ComboFrameParser()
    val outFrameParser = ComboFrameParser()

    while (true) {
        val frameData = datadumpReader.readFrameData()
        if (frameData == null) {
            logger.log(LogLevel.DEBUG) { "No frame data was read; stopping" }
            break
        }

        val frameParser = (if (frameData.isOutgoingData) outFrameParser else inFrameParser)
        frameParser.pushData(frameData.frameData)
        val framePayload = frameParser.parseFrame()
        if (framePayload == null) {
            logger.log(LogLevel.DEBUG) { "No frame payload was parsed; skipping" }
            continue
        }

        logger.log(LogLevel.DEBUG) {
            "Got ${if (frameData.isOutgoingData) "outgoing" else "incoming"} frame payload with ${framePayload.size} byte(s)"
        }

        val packet = framePayload.toTransportLayerPacket()
        logger.log(LogLevel.DEBUG) {
            "Got packet:" +
            "  major/minor version: ${packet.majorVersion}/${packet.minorVersion}" +
            "  sequence bit: ${packet.sequenceBit}" +
            "  reliability bit: ${packet.reliabilityBit}" +
            "  source/destination address: ${packet.sourceAddress}/${packet.destinationAddress}" +
            "  nonce: ${packet.nonce.toHexString()}" +
            "  MAC: ${packet.machineAuthenticationCode.toHexString()}" +
            "  payload: ${packet.payload.size} byte(s): ${packet.payload.toHexString()}"
        }
    }
}
