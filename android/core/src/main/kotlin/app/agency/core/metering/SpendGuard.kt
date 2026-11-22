package app.agency.core.metering

/** The owner's hard spend cap (the app's CostMeter). Checked before every model call and every search, before any socket opens. */
fun interface SpendGuard {
    fun isBlocked(): Boolean
}
