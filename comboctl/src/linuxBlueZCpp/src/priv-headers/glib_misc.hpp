#ifndef COMBOCTL_GLIB_MISC_HPP
#define COMBOCTL_GLIB_MISC_HPP

#include <array>
#include <memory>
#include <thread>
#include <string>
#include <glib.h>
#include <gio/gio.h>


namespace comboctl
{


// This is a workaround to avoid warnings about uninitialized members
// in the GDBusInterfaceVTable struct (it contains an additional
// undocumented member that is there purely for padding purposes)
constexpr GDBusInterfaceVTable make_gdbus_iface_vtable(GDBusInterfaceMethodCallFunc method_call)
{
	GDBusInterfaceVTable vtable { };
	vtable.method_call = method_call;
	return vtable;
}


/**
 * Generates a string representation of the GVariant's contents.
 *
 * This is typically used for logging GVariant contents.
 *
 * The variant is not modified in any way.
 *
 * @param variant GVariant to convert.
 * @return String representation of the variant and its contents.
 */
std::string to_string(GVariant *variant);


// C++ smart pointers
typedef std::unique_ptr<GVariant, void (*)(GVariant *)> gvariant_uptr;
typedef std::unique_ptr<GVariantIter, void (*)(GVariantIter *)> gvariant_iter_uptr;

// Utility functions to reduce the amount of boilerplate needed
// to traverse GVariant structures. They return unique_ptr
// smart pointers which use g_variant_unref(), g_variant_iter_free()
// etc. as a deleter. That way, we make sure that the GVariant
// and GVariantIter instances are properly cleaned up in an
// RAII compliant manner, reducing the likelihood of memory leaks.

gvariant_uptr make_gvariant_uptr(GVariant *gvariant);
gvariant_iter_uptr make_gvariant_iter_uptr(GVariantIter *gvariant_iter);

gvariant_iter_uptr get_gvariant_iter_from(GVariant *value, gchar const *format_string);
gvariant_iter_uptr get_gvariant_iter_from(gvariant_uptr &value, gchar const *format_string);

gvariant_uptr get_gvariant_from(GVariant *value, gchar const *format_string);
gvariant_uptr get_gvariant_from(gvariant_uptr &value, gchar const *format_string);


} // namespace comboctl end


#endif // COMBOCTL_GLIB_MISC_HPP
