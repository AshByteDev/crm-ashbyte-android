package ru.ashcrm.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ashcrm.app.net.AppInfo

class VersionRulesTest {
    private fun info(contract: String, code: Int) = AppInfo(contract, code, "x", "00", 1, "/download/ashcrm.apk")

    @Test fun noApiMeansUnsupported() = assertEquals(UpdateDecision.Unsupported, VersionRules.decide(null, 5, "1.0", null))

    @Test fun sameVersionIsUpToDate() = assertEquals(UpdateDecision.UpToDate, VersionRules.decide(info("1.3", 5), 5, "1.0", null))

    @Test fun newerIsOfferedOnce() {
        assertTrue(VersionRules.decide(info("1.2", 6), 5, "1.0", null) is UpdateDecision.Optional)
        assertEquals(UpdateDecision.UpToDate, VersionRules.decide(info("1.2", 6), 5, "1.0", declined = 6))
        assertTrue(VersionRules.decide(info("1.2", 7), 5, "1.0", declined = 6) is UpdateDecision.Optional)
    }

    @Test fun otherMajorIsAMust() {
        assertTrue(VersionRules.decide(info("2.0", 6), 5, "1.4", declined = 6) is UpdateDecision.Mandatory)
        assertTrue(VersionRules.decide(info("1.0", 5), 5, "2.0", null) is UpdateDecision.CrmTooOld)
    }
}
