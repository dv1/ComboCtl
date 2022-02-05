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
 */
data class Tbr(val timestamp: Instant, val percentage: Int, val durationInMinutes: Int) {
    init {
        require((percentage >= 0) && (percentage <= 500))
        require((percentage % 10) == 0)
        require(
            (percentage == 100) ||
            ((durationInMinutes >= 15) && (durationInMinutes <= (24 * 60)) && ((durationInMinutes % 15) == 0))
        )
    }
}