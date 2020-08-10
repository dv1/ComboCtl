#ifndef COMBOCTL_EXCEPTION_HPP
#define COMBOCTL_EXCEPTION_HPP

#include <string>
#include <stdexcept>


namespace comboctl
{


/**
 * Base class for comboctl specific C++ exceptions.
 */
class exception
	: public std::runtime_error
{
public:
	explicit exception(std::string const &what);
};


/**
 * Thrown when a call to a function was invalid.
 *
 * One example would be some sort of setup() call when
 * the corresponding object already has been initialized.
 */
class invalid_call_exception
	: public exception
{
public:
	explicit invalid_call_exception(std::string const &what);
};


/**
 * Thrown when an IO error occurs.
 *
 * This includes Bluetooth errors like when no Bluetooth
 * adapter could be found.
 */
class io_exception
	: public exception
{
public:
	explicit io_exception(std::string description);
};


} // namespace comboctl end


#endif // COMBOCTL_EXCEPTION_HPP
