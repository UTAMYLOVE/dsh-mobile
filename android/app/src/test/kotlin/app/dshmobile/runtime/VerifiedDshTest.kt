package app.dshmobile.runtime

import app.dshmobile.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedDshTest {
    @Test fun pinnedVersionIsVerified() {
        assertTrue(BuildConfig.DSH_PINNED_VERSION in VerifiedDsh.VERSIONS)
    }

    @Test fun newestIsTheLastEntry() {
        assertEquals(VerifiedDsh.VERSIONS.last(), VerifiedDsh.newest())
    }

    @Test fun nullAndUnknownAreNotVerified() {
        assertFalse(VerifiedDsh.isVerified(null))
        assertFalse(VerifiedDsh.isVerified("0.2.2"))
        assertFalse(VerifiedDsh.isVerified(""))
        assertTrue(VerifiedDsh.isVerified("0.2.1-rc.5"))
    }
}

class VerifiedUpdatePolicyTest {
    @Test fun upToDateWhenCurrentIsTheNewestVerified() {
        assertNull(VerifiedUpdatePolicy.target("0.2.1-rc.5"))
    }

    @Test fun anUnverifiedCurrentTargetsTheNewestVerified() {
        assertEquals("0.2.1-rc.5", VerifiedUpdatePolicy.target("0.2.2"))
        assertEquals("0.2.1-rc.5", VerifiedUpdatePolicy.target(null))
    }

    @Test fun anUnverifiedPreviousIsNoPrevious() {
        assertNull(VerifiedUpdatePolicy.previous("0.2.2"))
        assertEquals("0.2.1-rc.5", VerifiedUpdatePolicy.previous("0.2.1-rc.5"))
        assertNull(VerifiedUpdatePolicy.previous(null))
    }
}
