package info.nightscout.comboctl.base

import java.lang.Exception

    /**
     * Base class for ComboCtl specific exceptions.
     *
     * @param message The detail message.
     */
open class ComboException(message: String) : Exception(message)
