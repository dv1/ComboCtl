package info.nightscout.comboctl.base

import kotlinx.datetime.Instant

/**
 * Data class containing details about a TBR (temporary basal rate).
 *
 * This is typically associated with some event or record about a TBR that just
 * started or stopped. The timestamp is stored as an [Instant] to preserve the
 * timezone offset that was used at the time when the TBR started / stopped.
 *
 * The valid TBR percentage range is 0-500. 100 would mean 100% and is not actually
 * a TBR, but is sometimes used to communicate a TBR cancel operation. Only integer
 * multiples of 10 are valid (for example, 210 is valid, 209 isn't).
 *
 * If [percentage] is 100, the [durationInMinutes] is ignored. Otherwise, this
 * argument must be in the 15-1440 range (= 15 minutes to 24 hours), and must
 * be an integer multiple of 15.
 *
 * @property timestamp Timestamp when the TBR started/stopped.
 * @property percentage TBR percentage.
 * @property durationInMinutes Duration of the TBR, in minutes.
 * @property type Type of this TBR.
 */
data class Tbr(val timestamp: Instant, val percentage: Int, val durationInMinutes: Int, val type: Type) {
    enum class Type(val stringId: String) {
        /**
         * Normal TBR.
         * */
        NORMAL("normal"),

        /**
         * 15-minute 0% TBR that is produced when the Combo is stopped.
         * This communicates to callers that there is no insulin delivery
         * when the pump is stopped.
         */
        COMBO_STOPPED("comboStopped"),

        /**
         * Caller emulates a stopped pump by setting a special 0% TBR.
         *
         * Actually stopping the Combo has other side effects, so typically,
         * if for example the pump's cannula is to be disconnected, this
         * TBR type is used instead.
         */
        EMULATED_COMBO_STOP("emulatedComboStop"),

        /**
         * Caller wanted to cancel a bolus but without actually setting a 100 percent TBR to avoid the W6 warning.
         *
         * Normally, a TBR is cancelled by replacing it with a 100% "TBR".
         * Doing so however always triggers a W6 warning. As an alternative,
         * for example, an alternating sequence of 90% and 100% TBRs can
         * be used. Such TBRs would use this as their type.
         */
        EMULATED_100_PERCENT("emulated100Percent"),

        /**
         * TBR set when a superbolus is delivered.
         */
        SUPERBOLUS("superbolus")
    }

    init {
        require((percentage >= 0) && (percentage <= 500))
        require((percentage % 10) == 0)
        require(
            (percentage == 100) ||
            ((durationInMinutes >= 15) && (durationInMinutes <= (24 * 60)) && ((durationInMinutes % 15) == 0))
        )
    }

    companion object {
        private val values = Tbr.Type.values()
        /**
         * Converts a string ID to a [Tbr.Type].
         *
         * @return TBR type, or null if there is no matching type.
         */
        fun fromStringId(stringId: String) = values.firstOrNull { it.stringId == stringId }
    }
}
