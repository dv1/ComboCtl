package info.nightscout.comboctl.base

import kotlinx.coroutines.*

/**
 * Interface for Combo IO operations.
 *
 * The send and receive functions are suspending functions to be
 * able to fo pairing and regular sessions by using coroutines.
 * Subclasses concern themselves with adapting blocking IO APIs
 * and framing the data in some way. Subclasses can also choose
 * to use Flows, Channels, and RxJava/RxKotlin mechanisms
 * if they wish.
 *
 * IO errors in subclasses are communicated to callers by
 * throwing exceptions.
 */
interface ComboIO {
    /**
     * Sends the given block of bytes, suspending the coroutine until it is done.
     *
     * This function either transmits all of the bytes, or throws an
     * exception if this fails. Partial transmissions are not done.
     * An exception is also thrown if sending fails due to something
     * that's not an error, like when a connection is closed.
     *
     * If an exception is thrown, the data is to be considered not
     * having been sent.
     *
     * @param dataToSend The data to send. Must not be empty.
     * @throws CancellationException if cancelled by [cancelSend].
     * @throws ComboIOException if sending fails.
     */
    suspend fun send(dataToSend: List<Byte>)

    /**
     * Receives a block of bytes, suspending the coroutine until it finishes.
     *
     * If receiving fails, an exception is thrown. An exception
     * is also thrown if receiving fails due to something that's not
     * an error, like when a connection is closed.
     *
     * @return Received block of bytes. This is never empty.
     * @throws CancellationException if cancelled by [cancelReceive].
     * @throws ComboIOException if receiving fails.
     */
    suspend fun receive(): List<Byte>

    /**
     * Cancels a currently suspended send call.
     *
     * The send call resumes immediately and throws a [CancellationException],
     * and the specified data is not send.
     *
     * Canceling is atomic - either, none of the specified data is sent, or,
     * if sending already started when the cancel call was made, all of the data
     * is sent. (In the latter case, no [CancellationException] happens, since
     * the send operation was successfully completed.)
     *
     * If no send call is ongoing, this does nothing.
     */
    fun cancelSend()

    /**
     * Cancels a currently suspended receive call.
     *
     * The receive call resumes immediately and throws a [CancellationException],
     * and nothing is received.
     *
     * If the subclass implements some sort of internal receive aggregation buffer
     * (for example to accumulate enough data to parse a full frame), this buffer
     * must be cleared when the receive call is canceled.
     *
     * Canceling is atomic - either, no data is received, or, if receiving
     * already started when the cancel call was made, it finishes, and is
     * returned to the caller. (In the latter case, no [CancellationException]
     * happens, since the receive operation was successfully completed.)
     *
     * If no receive call is ongoing, this does nothing.
     */
    fun cancelReceive()
}

/**
 * Abstract combo IO class for adapting blocking IO APIs.
 *
 * The implementations of the ComboIO interface send and receive
 * calls internally use blocking send/receive functions and run
 * them in the IO context to make sure their blocking behavior
 * does not block the coroutine. Subclasses must implement
 * blockingSend and blockingReceive.
 */
abstract class BlockingComboIO : ComboIO {
    final override suspend fun send(dataToSend: List<Byte>) {
        withContext(Dispatchers.IO) {
            blockingSend(dataToSend)
        }
    }

    final override suspend fun receive(): List<Byte> {
        return withContext(Dispatchers.IO) {
            blockingReceive()
        }
    }

    /**
     * Blocks the calling thread until the given block of bytes is fully sent.
     *
     * In case of an error, or some other reason why sending
     * cannot be done (like a cancellation), an exception
     * is thrown.
     *
     * This function sends atomically. Either, the entire data
     * is sent, or none of it is sent (the latter happens in
     * case of an exception).
     *
     * @param dataToSend The data to send. Must not be empty.
     * @throws CancellationException if cancelled by [cancelSend].
     * @throws ComboIOException if sending fails.
     */
    abstract fun blockingSend(dataToSend: List<Byte>)

    /**
     * Blocks the calling thread until a given block of bytes is received.
     *
     * In case of an error, or some other reason why receiving
     * cannot be done (like a cancellation), an exception
     * is thrown.
     *
     * @return Received block of bytes. This is never empty.
     *
     * @throws CancellationException if cancelled by [cancelReceive].
     * @throws ComboIOException if receiving fails.
     */
    abstract fun blockingReceive(): List<Byte>
}

/**
 * ComboIO subclass that puts data into Combo frames and uses
 * another ComboIO object for the actual transmission.
 *
 * This is intended to be used for composing framed IO with
 * another ComboIO subclass. This allows for easily adding
 * Combo framing without having to modify ComboIO subclasses
 * or having to manually integrate the Combo frame parser.
 *
 * @property io Underlying ComboIO to use for sending
 *           and receiving&parsing framed data.
 */
class FramedComboIO(private val io: ComboIO) : ComboIO {
    override suspend fun send(dataToSend: List<Byte>) = io.send(dataToSend.toComboFrame())

    override suspend fun receive(): List<Byte> {
        try {
            // Loop until a full frame is parsed, an
            // error occurs, or the job is canceled.
            // In the latter two cases, an exception
            // is thrown, so we won't end up in an
            // infinite loop here.
            while (true) {
                val parseResult = frameParser.parseFrame()
                if (parseResult == null) {
                    frameParser.pushData(io.receive())
                    continue
                }

                return parseResult
            }
        } catch (e: CancellationException) {
            frameParser.reset()
            throw e
        } catch (e: ComboIOException) {
            frameParser.reset()
            throw e
        }
    }

    override fun cancelSend() = io.cancelSend()
    override fun cancelReceive() = io.cancelReceive()

    /**
     * Resets the internal frame parser.
     *
     * Resetting means that any partial frame data inside
     * the parse is discarded. This is useful if this IO
     * object is reused.
     */
    fun reset() {
        frameParser.reset()
    }

    private val frameParser = ComboFrameParser()
}

open class ComboIOException(message: String?, cause: Throwable?) : ComboException(message, cause) {
    constructor(message: String) : this(message, null)
    constructor(cause: Throwable) : this(null, cause)
}
