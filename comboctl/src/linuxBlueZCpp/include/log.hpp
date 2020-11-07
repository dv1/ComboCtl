#ifndef COMBOCTL_LOG_HPP
#define COMBOCTL_LOG_HPP

#include <string>
#include <functional>
#include "fmt/format.h"


#define DEFINE_LOGGING_TAG(TAG) \
	static std::string const LOGGING_TAG = TAG;

#define LOG(LEVEL, ...) \
	do { \
		std::string str = fmt::format(__VA_ARGS__); \
		::comboctl::do_log(LOGGING_TAG, ::comboctl::log_level::LEVEL, std::move(str)); \
	} while (false)


namespace comboctl
{


enum class log_level
{
	trace = 0,
	debug,
	info,
	warn,
	error,
	fatal
};

std::string_view to_string(log_level level);


typedef std::function<void(std::string const &tag, log_level level, std::string log_string)> logging_function;


logging_function get_default_logging_function();
void set_logging_function(logging_function new_logging_function);

void do_log(std::string const &tag, log_level level, std::string log_string);


} // namespace comboctl end


#endif // COMBOCTL_LOG_HPP
