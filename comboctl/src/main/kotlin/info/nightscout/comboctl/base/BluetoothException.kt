package info.nightscout.comboctl.base

    /**
     * Base class for Bluetooth specific exceptions.
     *
     * @param message The detail message.
     */
open class BluetoothException(message: String?, cause: Throwable?) : ComboException(message, cause) {
    constructor(message: String) : this(message, null)
    constructor(cause: Throwable) : this(null, cause)
}
