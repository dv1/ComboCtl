#include <string>
#include <cstring>
#include "gerror_exception.hpp"


namespace comboctl
{


gerror_exception::gerror_exception(GError *gerror)
	: exception(std::string("GError: ") + ((gerror != nullptr) ? gerror->message : "<null>"))
	, m_gerror(gerror)
{
}


gerror_exception::gerror_exception(gerror_exception const &other)
	: gerror_exception(g_error_copy(other.get_gerror()))
{
}


gerror_exception::gerror_exception(gerror_exception &&other)
	: gerror_exception(other.m_gerror)
{
	other.m_gerror = nullptr;
}


gerror_exception::~gerror_exception()
{
	if (m_gerror != nullptr)
		g_error_free(m_gerror);
}


gerror_exception& gerror_exception::operator = (gerror_exception const &other)
{
	GError *copied_gerror = g_error_copy(other.get_gerror());
	if (m_gerror != nullptr)
		g_error_free(m_gerror);
	m_gerror = copied_gerror;

	return *this;
}


gerror_exception& gerror_exception::operator = (gerror_exception &&other)
{
	m_gerror = other.m_gerror;

	other.m_gerror = nullptr;

	return *this;
}


GError const * gerror_exception::get_gerror() const
{
	return m_gerror;
}


GError* gerror_exception::take_gerror()
{
	GError *error = m_gerror;
	m_gerror = nullptr;
	return error;
}


} // namespace comboctl end
