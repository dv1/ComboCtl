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
 * Such a request is typically done via a callback, for example in [MainControl].
 *
 * @param cause The exception that was thrown in the loop specifying
 *        want went wrong there.
 */
class PumpStateStoreRequestException(cause: Exception) : ComboException(cause)

/**
 * Exception thrown when storing [PumpPairingData] fails.
 *
 * @param cause The exception that was thrown in the loop specifying
 *        want went wrong there.
 */
class PumpStateStoreStorageException(cause: Exception) : ComboException(cause)

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
 * by [MainControl] and other classes via callbacks.
 */
interface PersistentPumpStateStore {
    /**
     * Retrieves pairing data from the store.
     *
     * @throws IllegalStateException if [isValid] returns false,
     *         since then, there is no such data in the store.
     */
    fun retrievePumpPairingData(): PumpPairingData

    /**
     * Persistently stores the given pairing data.
     *
     * This is called during the pairing process. If this throws
     * an exception, the pairing process is aborted.
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
     * [currentTxNonce] returns a null nonce. [isValid]
     * returns false after this call.
     */
    fun reset(): Unit

    /*
     * The pump ID from the ID_RESPONSE packet.
     * This is useful for displaying the pump in a UI, since the
     * Bluetooth address itself may not be very clear to the user.
     * @throws PumpStateStoreStorageException if storing or retrieving
     *         the pump ID fails. This happens when trying to fetch
     *         the pump ID while the store is in an invalid state
     *         (see [isValid] for more).
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
     * If the store is in its initial state, this must return
     * a null nonce.
     */
    var currentTxNonce: Nonce
}
