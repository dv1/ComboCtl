#include <algorithm>
#include <sstream>
#include <iomanip>
#include <fmt/format.h>
#include "types.hpp"
#include "log.hpp"


namespace comboctl
{


// XXX: Using stringstream for bluetooth_address<->string conversions.
// In the future, try to use fmtlib instead once fmt::scan() is established.


std::string to_string(bluetooth_address const &address)
{
	std::ostringstream sstr;

	sstr << std::uppercase << std::hex;
	for (std::size_t i = 0; i < address.size(); ++i)
	{
		if (i != 0)
			sstr << ":";
		sstr << std::setw(2) << std::setfill('0') << int(address[i]);
	}

	return sstr.str();
}


bool from_string(bluetooth_address &address, std::string_view const &str)
{
	std::istringstream sstr{std::string{str}};

	for (std::size_t i = 0; i < address.size(); ++i)
	{
		std::string token;
		std::getline(sstr, token, ':');

		if (token.empty())
			return false;

		address[i] = std::stoi(token, nullptr, 16);
	}

	return true;
}


} // namespace comboctl end
