package app.agency.core.sim

import org.junit.Test

/** The fourteen scripted days ([Sim14]) on an in-memory store. */
class Sim14Test {
    @Test fun fourteenDaysWithoutADeadEnd() {
        val sim = Sim()
        Sim14(sim).run()
        // The whole trace is kept next to the test reports for reading.
        runCatching { java.io.File("build/sim14-trace.txt").writeText(sim.trace.toString()) }
    }
}
