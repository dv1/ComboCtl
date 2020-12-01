package info.nightscout.comboctl.base

/**
 * Pairing data for a pump.
 *
 * This data is created by [HighLevelIO.performPairing]. Once
 * it is created, it does not change until the pump is unpaired,
 * at which point it is erased. This data is managed by the
 * [PersistentPumpStateStore] class, which stores / retrieves it.
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
 */
data class PumpPairingData(
    val clientPumpCipher: Cipher,
    val pumpClientCipher: Cipher,
    val keyResponseAddress: Byte
)

/**
 * Exception thrown when a request to get a store for a specific pump fails.
 *
 * @param cause The exception that was thrown in the loop specifying
 *        want went wrong there.
 */
class PumpStateStoreRequestException(cause: Exception) : ComboException(cause)

/**
 * Exception thrown when accessing data from a store fails.
 *
 * @param cause The exception that was thrown in the loop specifying
 *        want went wrong there.
 */
class PumpStateStoreAccessException(cause: Exception) : ComboException(cause)

/**
 * Persistent state store interface for a specific pump.
 *
 * This interface provides access to a store that persistently
 * records the data of [PumpPairingData] instances along with
 * the current Tx nonce.
 *
 * As the name suggests, these states are recorded persistently,
 * immediately, and ideally also atomically. If atomic storage cannot
 * be guaranteed, then there must be some sort of data error detection
 * in place to ensure that no corrupted data is retrieved. (For example,
 * if the device running ComboCtl crashes or freezes while the data is
 * being written into the store, the data may not be written completely.)
 *
 * There is one [PersistentPumpStateStore] instance for each paired
 * pump. Each store contains the pairing data, which does not change
 * after the pump was paired, and the Tx nonce, which does change
 * after each packet sent to the Combo. These two parts of the store
 * are kept separate due to this difference in access, since this allows
 * for optimizations in implementations.
 *
 * [PersistentPumpStateStore] instances also know an "initial state".
 * In this state, [isValid] returns false, and [retrievePumpPairingData]
 * throws an IllegalStateException. There is no actual data stored in
 * this state. Only when pump pairing data is set by calling
 * [storePumpPairingData] does the store become properly initialized.
 * [isValid] returns true then. This "initial state" should only ever
 * exist while pairing a pump (since at that point, no store for that
 * pump actually exists yet); the pairing process will fill the store
 * with valid [PumpPairingData]. Regular connections must always get
 * a valid store.
 *
 * Instances of [PersistentPumpStateStore] subclasses are requested
 * via [PersistentPumpStateStoreBackend.requestStore].
 *
 * If a function or property access throws [PumpStateStoreAccessException],
 * then the store is to be considered invalid, any existing connections
 * to a pump that use this store must be terminated, and the pump must
 * be unpaired. This is because such an exception indicates an error in
 * the underlying pump state store implementation that said implementation
 * could not recover from. And this also implies that the data inside the
 * store is in an undefined state - it cannot be relied upon anymore.
 * Internally, the implementation must delete any remaining store data when
 * such an error occurs, invalidating said store immediately. Callers must
 * then also unpair the pump at the Bluetooth level. The user must be told
 * about this error, and instructed that the pump must be paired again.
 */
interface PersistentPumpStateStore {
    /**
     * Retrieves pairing data from the store.
     *
     * @throws IllegalStateException if [isValid] returns false,
     *         since then, there is no such data in the store.
     * @throws PumpStateStoreAccessException if retrieving the data
     *         fails due to an error that occurred in the underlying
     *         implementation.
     */
    fun retrievePumpPairingData(): PumpPairingData

    /**
     * Persistently stores the given pairing data.
     *
     * This is called during the pairing process. In regular
     * connections, this is not used.
     *
     * @throws PumpStateStoreAccessException if storing the data
     *         fails due to an error that occurred in the underlying
     *         implementation.
     */
    fun storePumpPairingData(pumpPairingData: PumpPairingData): Unit

    /**
     * Returns true if the store is in a valid state.
     */
    fun isValid(): Boolean

    /**
     * Resets the store back to its initial state.
     *
     * The [PumpPairingData] in the store is erased. The
     * [currentTxNonce] returns a null nonce and [isValid]
     * returns false after this call.
     *
     * Implementations are encouraged to completely wipe any data
     * associated with this pump state store when this is called.
     *
     * @throws PumpStateStoreAccessException if resetting the data
     *         fails due to an error that occurred in the underlying
     *         implementation.
     */
    fun reset(): Unit

    /*
     * The pump ID from the ID_RESPONSE packet.
     * This is useful for displaying the pump in a UI, since the
     * Bluetooth address itself may not be very clear to the user.
     * @throws IllegalStateException if an attempt is made to
     *         read this property while [isValid] returns false.
     * @throws PumpStateStoreAccessException if accessing the pump
     *         ID fails due to an error that occurred in the
     *         underlying implementation.
     */
    var pumpID: String

    /**
     * Current Tx nonce.
     *
     * This is set to an initial value during pairing, and
     * incremented after every sent packet afterwards. Both
     * of these steps are performed by [TransportLayer].
     * Every time a new value is set, said value must be stored
     * persistently and immediately by subclasses.
     *
     * @throws IllegalStateException if an attempt is made to
     *         read this property while [isValid] returns false.
     * @throws PumpStateStoreAccessException if accessing the pump
     *         ID fails due to an error that occurred in the
     *         underlying implementation.
     */
    var currentTxNonce: Nonce
}

/**
 * Backend for retrieving and querying [PersistentPumpStateStore] instances.
 *
 * This is used by [MainControl] to fetch a [PersistentPumpStateStore]
 * when creating a new [Pump] instance.
 */
interface PersistentPumpStateStoreBackend {
    /**
     * Requests a [PersistentPumpStateStore] instance for a pump with the given address.
     *
     * If no such store exists, this returns an instance which is set to the
     * initial state (see the [PersistentPumpStateStore] store for details).
     * It throws only if a non-recoverable error happens while fetching the
     * pump state store data. In such a case, implementations must wipe any
     * stored data associated with [pumpAddress], and callers must unpair the
     * pump at the Bluetooth level. This is because failure to retrieve the
     * store data may be caused by data corruption, so even if a subsequent
     * attempt to retrieve the store suceeds, the data may no longer be valid.
     *
     * pumpAddress Bluetooth address of the pump this call shall fetch a
     *             [PersistentPumpStateStore] for.
     * @return [PersistentPumpStateStore] instance for the given address.
     * @throws PumpStateStoreRequestException in case of an error while
     *         retrieving the store.
     */
    fun requestStore(pumpAddress: BluetoothAddress): PersistentPumpStateStore

    /**
     * Checks if there is a valid store associated with the given address.
     *
     * @return true if there is one, false otherwise.
     */
    fun hasValidStore(pumpAddress: BluetoothAddress): Boolean
}
