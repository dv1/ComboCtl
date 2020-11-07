#ifndef COMBOCTL_TYPES_HPP
#define COMBOCTL_TYPES_HPP

#include <cstdint>
#include <array>
#include <string>
#include <functional>


namespace comboctl
{


/**
 * 6-byte Bluetooth address type.
 *
 * The address bytes are stored in the printed order.
 * For example, a Blutooth address 11:22:33:44:55:66
 * is stored as a 0x11, 0x22, 0x33, 0x44, 0x55, 0x66
 * array, with 0x11 being the first byte. This is how
 * Android stores Bluetooth address bytes. Note though
 * that BlueZ stores the bytes in the reverse order.
 */
typedef std::array<std::uint8_t, 6> bluetooth_address;

/**
 * Generates a string representation of the Bluetooth address.
 *
 * The string representation is in the typical format,
 *
 *   11:22:33:44:55:66
 *
 * where 0x11 would be the first byte, 0x66 the last.
 *
 * @param address Address to convert.
 * @return String representation of the Bluetooth address.
 */
std::string to_string(bluetooth_address const &address);

/**
 * Converts a string representation to a bluetooth_address.
 *
 * See the to_string() call above for details about the
 * string representation.
 *
 * @param address Where to store the converted address bytes.
 * @param str String representation.
 * @return true if the conversion succeeded, false otherwise.
 */
bool from_string(bluetooth_address &address, std::string_view const &str);


/**
 * Callback for when a device was found.
 *
 * This one is invoked for paired and unpaired devices. The
 * paired flag indicator distinguishes between these.
 *
 * This callback is not used outside of the C++ code.
 */
typedef std::function<void(bluetooth_address paired_device_address, bool paired)> found_new_device_callback;

/**
 * Callback for when a paired device was found.
 *
 * When a new device appears, the found_new_device_callback
 * is invoked. This then leads to the paired flag being
 * evaluated. If it is set to true, this callback is invoked
 * in turn. That way, users get notified only about devices
 * they can actually interact with right away (a detected)
 * but unpaired device is unusable until it is paired).
 *
 * This call is intended to be used in external code, since
 * only paired Combos are useful for comboctl.
 */
typedef std::function<void(bluetooth_address paired_device_address)> found_new_paired_device_callback;

/**
 * Callback for when a previously detected device is gone.
 *
 * In some situations, this may be called more than once for
 * the same device, so callbacks should check if the address
 * is still known to them, and ignore the call if it isn't.
 */
typedef std::function<void(bluetooth_address removed_device_address)> device_is_gone_callback;

/**
 * Callback for filtering devices based on their address.
 *
 * comboctl uses this to filter out devices that are not
 * a Combo. The first 3 bytes of all Combos are the same,
 * so checking for those 3 bytes is a useful way to filter
 * devices so that all non-Combo ones are ignored.
 *
 * If this callback returns false, then the device was rejected
 * by this callback, and is to be ignored.
 *
 * Internally, any authentication and pairing requests that
 * come from devices which were rejected by this callback
 * are also rejected at the Bluetooth level, and any notifications
 * about newly discovered devices are ignored if they are rejected
 * by the callback (no matter if they are paired or not).
 */
typedef std::function<bool(bluetooth_address device_address)> filter_device_callback;


} // namespace comboctl end


#endif // COMBOCTL_TYPES_HPP
