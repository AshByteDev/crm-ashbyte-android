package ru.ashcrm.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionRulesTest {
    private fun release(code: Int, min: Int = 0) = Release(code, "0.$code.0", min, "00", 1,
        "https://github.com/AshByteDev/crm-ashbyte-android/releases/download/v0.$code.0/ashcrm.apk")

    @Test fun noAnswerOfGitHubChangesNothing() =
        assertEquals(UpdateDecision.None, VersionRules.decide(null, 5, null, CrmState.Supported))

    @Test fun sameOrOlderIsNothing() {
        assertEquals(UpdateDecision.None, VersionRules.decide(release(5), 5, null, CrmState.Supported))
        assertEquals(UpdateDecision.None, VersionRules.decide(release(4), 5, null, CrmState.Incompatible))
    }

    @Test fun newerIsOfferedOnce() {
        assertTrue(VersionRules.decide(release(6), 5, null, CrmState.Supported) is UpdateDecision.Optional)
        assertEquals(UpdateDecision.None, VersionRules.decide(release(6), 5, declined = 6, crm = CrmState.Supported))
        assertTrue(VersionRules.decide(release(7), 5, declined = 6, crm = CrmState.Supported) is UpdateDecision.Optional)
    }

    @Test fun belowMinimumIsAMustEvenIfDeclined() =
        assertTrue(VersionRules.decide(release(7, min = 6), 5, declined = 7, crm = CrmState.Supported) is UpdateDecision.Mandatory)

    @Test fun otherContractWithNewerAppIsAMust() =
        assertTrue(VersionRules.decide(release(6), 5, declined = 6, crm = CrmState.Incompatible) is UpdateDecision.Mandatory)

    @Test fun contractMajor() {
        assertEquals(CrmState.Supported, VersionRules.crm("1.0", own = "1.2"))
        assertEquals(CrmState.Incompatible, VersionRules.crm("2.0", own = "1.2"))
    }

    @Test fun onlyOurReleases() {
        assertTrue(Updater.releaseUrlOk("https://github.com/AshByteDev/crm-ashbyte-android/releases/download/v0.3.0/ashcrm.apk"))
        assertFalse(Updater.releaseUrlOk("https://github.com/someone/else/releases/download/v1/ashcrm.apk"))
        assertFalse(Updater.releaseUrlOk("http://github.com/AshByteDev/crm-ashbyte-android/releases/download/v0.3.0/ashcrm.apk"))
        assertFalse(Updater.releaseUrlOk("https://github.com/AshByteDev/crm-ashbyte-android/releases/download/../../x/ashcrm.apk"))
        assertFalse(Updater.releaseUrlOk("https://evil.example/ashcrm.apk"))
    }
}
