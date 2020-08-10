#include "exception.hpp"


namespace comboctl
{


exception::exception(std::string const &what)
	: std::runtime_error(what)
{
}




invalid_call_exception::invalid_call_exception(std::string const &what)
    : exception(what)
{
}




io_exception::io_exception(std::string description)
	: exception(std::move(description))
{
}


} // namespace comboctl end
