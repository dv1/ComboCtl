#ifndef COMBOCTL_GERROR_EXCEPTION_HPP
#define COMBOCTL_GERROR_EXCEPTION_HPP

#include <glib.h>
#include "exception.hpp"


namespace comboctl
{


// TODO: Perhaps it would be better to analyze the GError
// and throw error specific exceptions instead.
/**
 * Exception containing a GError.
 *
 * This is thrown when a GLib function reports an error.
 */
class gerror_exception
	: public exception
{
public:
	explicit gerror_exception(GError *gerror);
	gerror_exception(gerror_exception const &other);
	gerror_exception(gerror_exception &&other);
	~gerror_exception();

	gerror_exception& operator = (gerror_exception const &other);
	gerror_exception& operator = (gerror_exception &&other);

	GError const * get_gerror() const;
    GError* take_gerror();


private:
	GError *m_gerror;
};


} // namespace comboctl end


#endif // COMBOCTL_GERROR_EXCEPTION_HPP
