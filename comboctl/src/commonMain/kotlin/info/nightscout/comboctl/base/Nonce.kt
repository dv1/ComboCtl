package info.nightscout.comboctl.base

const val NUM_NONCE_BYTES = 13

/**
 * Class containing a 13-byte nonce.
 *
 * The nonce is a byte sequence used in Combo transport layer packets.
 * It uniquely identifies a packet, since each nonce is only ever used once.
 * After sending out a packet, the current tx nonce gets incremented, and the
 * next packet that is to be sent out will use the incremented nonce.
 *
 * This class is immutable, since modification operations on a nonce don't
 * really make any sense. The only two modification-like operations one ever
 * wants to perform is assigning the 13 bytes (done by the constructor) and
 * incrementing (done by [getIncrementedNonce] returning an incremented copy).
 * The behavior of [getIncrementedNonce] may seem wasteful at first, but it
 * actually isn't, because one would have to do a copy of the current tx nonce
 * anyway to make sure nonces assigned to outgoing packets retain their original
 * value even after the current tx nonce was incremented (since assignments
 * of non-primitives in Kotlin by default are done by-reference).
 */
data class Nonce(private val nonceBytes: List<Byte>) : Iterable<Byte> {
    /**
     * Number of Nonce bytes (always 13).
     *
     * This mainly exists to make this class compatible with
     * code that operates on collections.
     */
    val size = NUM_NONCE_BYTES

    init {
        // Check that we actually got exactly 13 nonce bytes.
        require(nonceBytes.size == size)
    }

    operator fun get(index: Int) = nonceBytes[index]

    override operator fun iterator() = nonceBytes.iterator()

    override fun toString() = nonceBytes.toHexString(" ")

    /**
     * Return an incremented copy of this nonce.
     *
     * This nonce's bytes will not be modified by this call.
     */
    fun getIncrementedNonce(): Nonce {
        val outputNonceBytes = ArrayList<Byte>(NUM_NONCE_BYTES)

        var carry = true

        nonceBytes.forEach { nonceByte ->
            if (carry) {
                if (nonceByte == 0xFF.toByte()) {
                    outputNonceBytes.add(0x00.toByte())
                } else {
                    outputNonceBytes.add((nonceByte + 1).toByte())
                    carry = false
                }
            } else
                outputNonceBytes.add(nonceByte)
        }

        return Nonce(outputNonceBytes)
    }
}

fun String.toNonce() = Nonce(this.split(" ").map { it.toInt(radix = 16).toByte() })

/**
 * Nonce consisting of 13 nullbytes. Useful for initializations.
 */
val NullNonce = Nonce(List(NUM_NONCE_BYTES) { 0x00 })
