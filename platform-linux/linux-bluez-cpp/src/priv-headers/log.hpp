#ifndef COMBOCTL_LOG_HPP
#define COMBOCTL_LOG_HPP

#include <string>
#include <functional>
#include "fmt/format.h"


#define LOG(LEVEL, ...) \
	do { \
		std::string str = fmt::format(__VA_ARGS__); \
		::comboctl::do_log(::comboctl::log_level::LEVEL, __FILE__, __LINE__, std::move(str)); \
	} while (false)


namespace comboctl
{


enum class log_level
{
	trace,
	debug,
	info,
	warn,
	error,
	fatal
};

std::string_view to_string(log_level level);


typedef std::function<void(log_level level, std::string source_file, int source_line, std::string log_string)> logging_function;


logging_function get_default_logging_function();
void set_logging_function(logging_function new_logging_function);

void do_log(log_level level, std::string source_file, int source_line, std::string log_string);


} // namespace comboctl end


#endif // COMBOCTL_LOG_HPP
