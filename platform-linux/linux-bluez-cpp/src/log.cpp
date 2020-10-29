#include <assert.h>
#include <iostream>
#include <mutex>
#include "log.hpp"


namespace comboctl
{


namespace
{


std::mutex default_logging_function_mutex;

void default_logging_function(std::string const &tag, log_level level, std::string log_string) {
	std::lock_guard<std::mutex> lock(default_logging_function_mutex);
	std::cerr << "[" << to_string(level) << "] [" << tag << "] " << log_string << std::endl;
};


logging_function current_logging_function = default_logging_function;


} // unnamed namespace end


std::string_view to_string(log_level level)
{
	switch (level)
	{
		case log_level::trace: return "trace";
		case log_level::debug: return "debug";
		case log_level::info: return "info";
		case log_level::warn: return "warn";
		case log_level::error: return "error";
		case log_level::fatal: return "fatal";
	}
}


logging_function get_default_logging_function()
{
	return default_logging_function;
}


void set_logging_function(logging_function new_logging_function)
{
	assert(new_logging_function);
	current_logging_function = new_logging_function;
}


void do_log(std::string const &tag, log_level level, std::string log_string)
{
	assert(current_logging_function);
	current_logging_function(tag, level, std::move(log_string));
}


} // namespace comboctl end
