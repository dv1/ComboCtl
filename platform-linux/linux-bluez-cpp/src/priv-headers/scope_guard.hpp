#ifndef COMBOCTL_SCOPE_GUARD_HPP
#define COMBOCTL_SCOPE_GUARD_HPP

#include <functional>


namespace comboctl
{


namespace detail
{


class scope_guard_impl
{
public:
	template<typename Func>
	explicit scope_guard_impl(Func &&func)
		: m_func(std::forward<Func> (func))
		, m_dismissed(false)
	{
	}

	~scope_guard_impl()
	{
		if (!m_dismissed)
		{
			// Make sure exceptions never exit the destructor, otherwise
			// undefined behavior occurs. For details about this, see
			// https://isocpp.org/wiki/faq/exceptions#dtors-shouldnt-throw

			try
			{
				m_func();
			}
			catch (...)
			{
			}
		}
	}

	scope_guard_impl(scope_guard_impl &&p_other)
		: m_func(std::move(p_other.m_func))
		, m_dismissed(p_other.m_dismissed)
	{
		p_other.m_dismissed = true;
	}

	void dismiss() const throw()
	{
		m_dismissed = true;
	}


private:
	scope_guard_impl(scope_guard_impl const &) = delete;
	scope_guard_impl& operator = (scope_guard_impl const &) = delete;

	std::function<void()> m_func;
	mutable bool m_dismissed;
};


} // namespace detail end


typedef detail::scope_guard_impl scope_guard_type;


/**
 * Creates a lightweight object that executes the given function in its destructor.
 *
 * Scope guards are useful for making sure that a certain piece of code is
 * always run when the current scope is left, no matter how this happens
 * (via a return statement, or via a throw statement).
 *
 * In C++ terms, this makes use of RAII to ensure execution when the scope is left.
 *
 * It is recommended to use the auto keyword for defining the return value. Example:
 *
 *   {
 *       auto guard = make_scope_guard([](){ cleanup_at_exit(); });
 *       [...]
 *   }
 *
 * It is possible to turn off the function execution. This is useful if the scope
 * guard acts as a safeguard during constructor execution to make sure any changes
 * performed in the constructor are rolled back in case of an error. If the
 * initialization succeeds, one does not want the scope guard to execute the
 * rollback of course. The dismiss() function exists for this purpose.
 *
 *   {
 *       auto error_guard = make_scope_guard([](){ cleanup_at_exit(); });
 *       [...]
 *       // At this point, initialization was successful. We dismiss the guard
 *       // to make sure it does not call cleanup_at_exit().
 *       error_guard.dismiss();
 *   }
 +
 * @param func Function to run when the scope is left.
 * @return Scope guard object.
 */
template < typename Func >
detail::scope_guard_impl make_scope_guard(Func &&func)
{
	return detail::scope_guard_impl(std::forward<Func>(func));
}


} // namespace comboctl end


#endif // COMBOCTL_SDP_SERVICE_HPP
