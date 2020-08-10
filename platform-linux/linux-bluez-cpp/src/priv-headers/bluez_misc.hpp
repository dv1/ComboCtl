#ifndef COMBOCTL_BLUEZ_MISC_HPP
#define COMBOCTL_BLUEZ_MISC_HPP

#include <array>
#include <memory>
#include <thread>
#include <bluetooth/bluetooth.h>


namespace comboctl
{


// THe bluetooth.h header has the BDADDR_ANY and BDADDR_LOCAL macros. However,
// we cannot use BDADDR_ANY, since that macro creates a temporary bdaddr_t
// object and takes its address. Addresses to temporaries are not allowed in
// C++. So, we take the definition of ANY (= all bytes zero) and create our
// own. Same goes for BDADDR_LOCAL.
// Also note that bdaddr_t stores the Bluetooth MAC address bits in
// little endian format, meaning that for example the first byte of the MAC
// address is byte #5 in bdaddr_t, the last one is byte #0 in bdaddr_t etc.
// This is reversed compared to the MAC bytes are stored in Android
// data types and in the comboctl::bluetooth_address array data type.
inline constexpr bdaddr_t bdaddr_any = {{0, 0, 0, 0, 0, 0}};
inline constexpr bdaddr_t bdaddr_local = {{0, 0, 0, 0xff, 0xff, 0xff}};


} // namespace comboctl end


#endif // COMBOCTL_BLUEZ_MISC_HPP
