package info.nightscout.comboctl.base

/**
 * Pump related data that is set during pairing and not changed afterwards.
 *
 * This data is created by [PumpIO.performPairing]. Once it is
 * created, it does not change until the pump is unpaired, at
 * which point it is erased. This data is managed by the
 * [PumpStateStore] class, which stores / retrieves it.
 *
 * @property clientPumpCipher This cipher is used for authenticating
 *           packets going to the Combo.
 * @property pumpClientCipher This cipher is used for verifying
 *           packets coming from the Combo.
 * @property keyResponseAddress The address byte of a previously
 *           received KEY_RESPONSE packet. The source and destination
 *           address values inside this address byte must have been
 *           reordered to match the order that outgoing packets expect.
 *           That is: Source address stored in the upper, destination
 *           address in the lower 4 bit of the byte. (In incoming
 *           packets - and KEY_RESPONSE is an incoming packet - these
 *           two are ordered the other way round.)
 * @property pumpID The pump ID from the ID_RESPONSE packet.
 *           This is useful for displaying the pump in a UI, since the
 *           Bluetooth address itself may not be very clear to the user.
 */
data class InvariantPumpData(
    val clientPumpCipher: Cipher,
    val pumpClientCipher: Cipher,
    val keyResponseAddress: Byte,
    val pumpID: String
) {
    companion object {
        /**
         * Convenience function to create an instance with default "null" values.
         *
         * Useful for an initial state.
         */
        fun nullData() =
            InvariantPumpData(
                clientPumpCipher = Cipher(ByteArray(CIPHER_KEY_SIZE)),
                pumpClientCipher = Cipher(ByteArray(CIPHER_KEY_SIZE)),
                keyResponseAddress = 0x00.toByte(),
                pumpID = ""
            )
    }
}

/**
 * Exception thrown when accessing the stored state of a specific pump fails.
 *
 * @param pumpAddress Bluetooth address of the pump whose
 *        state could not be accessed or created.
 * @param message The detail message.
 * @param cause The exception that was thrown in the loop specifying
 *        want went wrong there.
 */
class PumpStateStoreAccessException(val pumpAddress: BluetoothAddress, message: String?, cause: Throwable?) :
    ComboException(message, cause) {
    constructor(pumpAddress: BluetoothAddress, message: String) : this(pumpAddress, message, null)
    constructor(pumpAddress: BluetoothAddress, cause: Throwable) : this(pumpAddress, null, cause)
}

/**
 * Exception thrown when trying to create a new pump state even though one already exists.
 *
 * @param pumpAddress Bluetooth address of the pump.
 */
class PumpStateAlreadyExistsException(val pumpAddress: BluetoothAddress) :
    ComboException("Pump state for pump with address $pumpAddress already exists")

/**
 * Exception thrown when trying to access new pump state that does not exist.
 *
 * @param pumpAddress Bluetooth address of the pump.
 */
class PumpStateDoesNotExistException(val pumpAddress: BluetoothAddress) :
    ComboException("Pump state for pump with address $pumpAddress does not exist")

/**
 * State store interface for a specific pump.
 *
 * This interface provides access to a store that persistently
 * records the data of [InvariantPumpData] instances along with
 * the current Tx nonce.
 *
 * As the name suggests, these states are recorded persistently,
 * immediately, and ideally also atomically. If atomic storage cannot
 * be guaranteed, then there must be some sort of data error detection
 * in place to ensure that no corrupted data is retrieved. (For example,
 * if the device running ComboCtl crashes or freezes while the data is
 * being written into the store, the data may not be written completely.)
 *
 * There is one state for each paired pump. Each instance contains the
 * [InvariantPumpData], which does not change after the pump was paired,
 * and the Tx nonce, which does change after each packet that is sent to
 * the Combo. These two parts of a pump's state are kept separate due to
 * this difference in access, since this allows for optimizations in
 * implementations.
 *
 * Each state is associate with a pump via the pump's Bluetooth address.
 *
 * If a function or property access throws [PumpStateStoreAccessException],
 * then the state is to be considered invalid, any existing connections
 * to a pump associated with the state must be terminated, and the pump must
 * be unpaired. This is because such an exception indicates an error in
 * the underlying pump state store implementation that said implementation
 * could not recover from. And this also implies that this pump's state inside
 * the store is in an undefined state - it cannot be relied upon anymore.
 * Internally, the implementation must delete any remaining state data when
 * such an error occurs. Callers must then also unpair the pump at the Bluetooth
 * level. The user must be told about this error, and instructed that the pump
 * must be paired again.
 *
 * Different pump states can be accessed, created, deleted concurrently.
 * However, operations on the same state must not happen concurrently.
 * For example, it is valid to create a pump state while an existing [Pump]
 * instance updates the Tx nonce of its associated state, but no two threads
 * may update the Tx nonce at the same time, or try to access state data
 * and delete the same state simultaneously.
 */
interface PumpStateStore {
    /**
     * Creates a new pump state and fills the state's invariant data.
     *
     * This is called during the pairing process. In regular
     * connections, this is not used. It initializes a state for the pump
     * with the given ID in the store. Before this call, trying to access
     * the state with [getInvariantPumpData], [getCurrentTxNonce], or
     * [setCurrentTxNonce] fails with an exception. The new state's nonce
     * is set to a null nonce (= all of its bytes set to zero).
     *
     * The state is removed by calling [deletePumpState].
     *
     * Subclasses must store the invariant pump data immediately and persistently.
     *
     * @param pumpAddress Bluetooth address of the pump to create a state for.
     * @param invariantPumpData Invariant pump data to use in the new state.
     * @throws PumpStateAlreadyExistsException if there is already a state
     *         with the given Bluetooth address.
     * @throws PumpStateStoreAccessException if writing the new state fails
     *         due to an error that occurred in the underlying implementation.
     */
    fun createPumpState(pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData)

    /**
     * Deletes a pump state that is associated with the given address.
     *
     * If there is no such state, this returns false.
     *
     * NOTE: This does not throw.
     *
     * @param pumpAddress Bluetooth address of the pump whose corresponding
     *        state in the store shall be deleted.
     * @return true if there was such a state, false otherwise.
     */
    fun deletePumpState(pumpAddress: BluetoothAddress): Boolean

    /**
     * Checks if there is a valid state associated with the given address.
     *
     * @return true if there is one, false otherwise.
     */
    fun hasPumpState(pumpAddress: BluetoothAddress): Boolean

    /**
     * Returns a set of Bluetooth addresses of the states in this store.
     */
    fun getAvailablePumpStateAddresses(): Set<BluetoothAddress>

    /**
     * Returns the [InvariantPumpData] from the state associated with the given address.
     *
     * @throws PumpStateDoesNotExistException if no pump state associated with
     *         the given address exists in the store.
     * @throws PumpStateStoreAccessException if accessing the data fails
     *         due to an error that occurred in the underlying implementation.
     */
    fun getInvariantPumpData(pumpAddress: BluetoothAddress): InvariantPumpData

    /**
     * Returns the current Tx [Nonce] from the state associated with the given address.
     *
     * @throws PumpStateDoesNotExistException if no pump state associated with
     *         the given address exists in the store.
     * @throws PumpStateStoreAccessException if accessing the nonce fails
     *         due to an error that occurred in the underlying implementation.
     */
    fun getCurrentTxNonce(pumpAddress: BluetoothAddress): Nonce

    /**
     * Sets the current Tx [Nonce] in the state associated with the given address.
     *
     * Subclasses must store the new Tx nonce immediately and persistently.
     *
     * @throws PumpStateDoesNotExistException if no pump state associated with
     *         the given address exists in the store.
     * @throws PumpStateStoreAccessException if accessing the nonce fails
     *         due to an error that occurred in the underlying implementation.
     */
    fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce)
}

/*
 * Increments the nonce of a pump state associated with the given address.
 *
 * @param pumpAddress Bluetooth address of the pump state.
 * @param incrementAmount By how much the nonce is to be incremented.
 *   Must be at least 1.
 */
fun PumpStateStore.incrementTxNonce(pumpAddress: BluetoothAddress, incrementAmount: Int = 1): Nonce {
    require(incrementAmount >= 1)

    val currentTxNonce = this.getCurrentTxNonce(pumpAddress)
    val newTxNonce = currentTxNonce.getIncrementedNonce(incrementAmount)
    this.setCurrentTxNonce(pumpAddress, newTxNonce)
    return newTxNonce
}
