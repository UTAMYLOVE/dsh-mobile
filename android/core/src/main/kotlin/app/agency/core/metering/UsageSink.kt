package app.agency.core.metering

/** Where the usage of every paid request goes (the app's CostMeter). Called after a response, never with a key or a body. */
fun interface UsageSink {
    fun record(p: Provider, model: String, u: Usage)
}
