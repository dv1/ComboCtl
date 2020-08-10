#include "glib_misc.hpp"


namespace comboctl
{


std::string to_string(GVariant *variant)
{
    if (variant == nullptr)
        return "<null>";

	gchar *cstr = g_variant_print(variant, TRUE);
	std::string str = cstr;
	g_free(cstr);
	return str;
}


gvariant_uptr make_gvariant_uptr(GVariant *gvariant)
{
    return gvariant_uptr(gvariant, g_variant_unref);
}


gvariant_iter_uptr make_gvariant_iter_uptr(GVariantIter *gvariant_iter)
{
    return gvariant_iter_uptr(gvariant_iter, g_variant_iter_free);
}


gvariant_iter_uptr get_gvariant_iter_from(GVariant *value, gchar const *format_string)
{
    GVariantIter *iter;
    g_variant_get(value, format_string, &iter);
    return make_gvariant_iter_uptr(iter);
}


gvariant_iter_uptr get_gvariant_iter_from(gvariant_uptr &value, gchar const *format_string)
{
    return get_gvariant_iter_from(value.get(), format_string);
}


gvariant_uptr get_gvariant_from(GVariant *value, gchar const *format_string)
{
    GVariant *variant;
    g_variant_get(value, format_string, &variant);
    return make_gvariant_uptr(variant);
}


gvariant_uptr get_gvariant_from(gvariant_uptr &value, gchar const *format_string)
{
    return get_gvariant_from(value.get(), format_string);
}


} // namespace comboctl end
