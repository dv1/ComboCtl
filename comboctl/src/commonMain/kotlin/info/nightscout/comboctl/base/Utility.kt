package info.nightscout.comboctl.base

import kotlin.math.max
import kotlin.math.min

/**
 * Produces a ByteArray out of a sequence of integers.
 *
 * Producing a ByteArray with arrayOf() is only possible if the values
 * are less than 128. For example, this is not possible, because 0xF0
 * is >= 128:
 *
 *     val b = byteArrayOf(0xF0, 0x01)
 *
 * This function allows for such cases.
 *
 * [Original code from here](https://stackoverflow.com/a/51404278).
 *
 * @param ints Integers to convert to bytes for the new array.
 * @return The new ByteArray.
 */
fun byteArrayOfInts(vararg ints: Int) = ByteArray(ints.size) { pos -> ints[pos].toByte() }

/**
 * Variant of [byteArrayOfInts] which produces an ArrayList instead of an array.
 *
 * @param ints Integers to convert to bytes for the new arraylist.
 * @return The new arraylist.
 */
fun byteArrayListOfInts(vararg ints: Int) = ArrayList(ints.map { it.toByte() })

/**
 * Produces a hexadecimal string representation of the bytes in the array.
 *
 * The string is formatted with a separator (one whitespace character by default)
 * between the bytes. For example, the byte array 0x8F, 0xBC results in "8F BC".
 *
 * @return The string representation.
 */
fun ByteArray.toHexString(separator: String = " ") = this.joinToString(separator) { it.toHexString(width = 2, prependPrefix = false) }

/**
 * Produces a hexadecimal string representation of the bytes in the list.
 *
 * The string is formatted with a separator (one whitespace character by default)
 * between the bytes. For example, the byte list 0x8F, 0xBC results in "8F BC".
 *
 * @return The string representation.
 */
fun List<Byte>.toHexString(separator: String = " ") = this.joinToString(separator) { it.toHexString(width = 2, prependPrefix = false) }

/**
 * Produces a hexadecimal string describing the "surroundings" of a byte in a list.
 *
 * This is useful for error messages about invalid bytes in data. For example,
 * suppose that the 11th byte in this data block is invalid:
 *
 *    11 77 EE 44 77 EE 77 DD 00 77 DC 77 EE 55 CC
 *
 * Then with this function, it is possible to produce a string that highlights that
 * byte, along with its surrounding bytes:
 *
 *    "11 77 EE 44 77 EE 77 DD 00 77 [DC] 77 EE 55 CC"
 *
 * Such a surrounding is also referred to as a "context" in tools like GNU patch,
 * which is why it is called like this here.
 *
 * @param offset Offset in the list where the byte is.
 * @param contextSize The size of the context before and the one after the byte.
 *        For example, a size of 10 will include up to 10 bytes before and up
 *        to 10 bytes after the byte at offset (less if the byte is close to
 *        the beginning or end of the list).
 * @return The string representation.
 */
fun List<Byte>.toHexStringWithContext(offset: Int, contextSize: Int = 10): String {
    val byte = this[offset]
    val beforeByteContext = this.subList(max(offset - contextSize, 0), offset)
    val beforeByteContextStr = if (beforeByteContext.isEmpty()) "" else beforeByteContext.toHexString() + " "
    val afterByteContext = this.subList(offset + 1, min(this.size, offset + 1 + contextSize))
    val afterByteContextStr = if (afterByteContext.isEmpty()) "" else " " + afterByteContext.toHexString()

    return "$beforeByteContextStr[${byte.toHexString(width = 2, prependPrefix = false)}]$afterByteContextStr"
}

/**
 * Byte to Int conversion that treats all 8 bits of the byte as a positive value.
 *
 * Currently, support for unsigned byte (UByte) is still experimental
 * in Kotlin. The existing Byte type is signed. This poses a problem
 * when one needs to bitwise manipulate bytes, since the MSB will be
 * interpreted as a sign bit, leading to unexpected outcomes. Example:
 *
 * Example byte: 0xA2 (in binary: 0b10100010)
 *
 * Code:
 *
 *     val b = 0xA2.toByte()
 *     println("%08x".format(b.toInt()))
 *
 * Result:
 *
 *     ffffffa2
 *
 *
 * This is the result of the MSB of 0xA2 being interpreted as a sign
 * bit. This in turn leads to 0xA2 being interpreted as the negative
 * value -94. When cast to Int, a negative -94 Int value is produced.
 * Due to the 2-complement logic, all upper bits are set, leading to
 * the hex value 0xffffffa5. By masking out all except the lower
 * 8 bits, the correct positive value is retained:
 *
 *     println("%08x".format(b.toPosInt() xor 7))
 *
 * Result:
 *
 *     000000a2
 *
 * This is for example important when doing bit shifts:
 *
 *     println("%08x".format(b.toInt() ushr 4))
 *     println("%08x".format(b.toPosInt() ushr 4))
 *     println("%08x".format(b.toInt() shl 4))
 *     println("%08x".format(b.toPosInt() shl 4))
 *
 * Result:
 *
 *     0ffffffa
 *     0000000a
 *     fffffa20
 *     00000a20
 *
 * toPosInt produces the correct results.
 */
fun Byte.toPosInt() = toInt() and 0xFF

/**
 * Byte to Long conversion that treats all 8 bits of the byte as a positive value.
 *
 * This behaves identically to toPosInt(), except it produces a Long instead of an Int value.
 */
fun Byte.toPosLong() = toLong() and 0xFF

/**
 * Produces a hex string out of an Int.
 *
 * String.format() is JVM specific, so we can't use it in multiplatform projects.
 * Hence the existence of this function.
 *
 * @param width Width of the hex string. If the actual hex string is shorter
 *        than this, the excess characters to the left (the leading characters)
 *        are filled with zeros. If a "0x" prefix is added, the prefix length is
 *        not considered part of the hex string. For example, a width of 4 and
 *        a hex string of 0x45 will produce 0045 with no prefix and 0x0045 with
 *        prefix.
 * @param prependPrefix If true, the "0x" prefix is prepended.
 * @return Hex string representation of the Int.
 */
fun Int.toHexString(width: Int, prependPrefix: Boolean = true): String {
	val prefix = if (prependPrefix) "0x" else ""
	val hexstring = this.toString(16)
	val numLeadingChars = max(width - hexstring.length, 0)
	return prefix + "0".repeat(numLeadingChars) + hexstring
}

/**
 * Produces a hex string out of a Byte.
 *
 * String.format() is JVM specific, so we can't use it in multiplatform projects.
 * Hence the existence of this function.
 *
 * @param width Width of the hex string. If the actual hex string is shorter
 *        than this, the excess characters to the left (the leading characters)
 *        are filled with zeros. If a "0x" prefix is added, the prefix length is
 *        not considered part of the hex string. For example, a width of 4 and
 *        a hex string of 0x45 will produce 0045 with no prefix and 0x0045 with
 *        prefix.
 * @param prependPrefix If true, the "0x" prefix is prepended.
 * @return Hex string representation of the Byte.
 */
fun Byte.toHexString(width: Int, prependPrefix: Boolean = true): String {
	val intValue = this.toPosInt()
	val prefix = if (prependPrefix) "0x" else ""
	val hexstring = intValue.toString(16)
	val numLeadingChars = max(width - hexstring.length, 0)
	return prefix + "0".repeat(numLeadingChars) + hexstring
}
