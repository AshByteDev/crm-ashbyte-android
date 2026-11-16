package ru.ashcrm.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionRulesTest {
    private fun release(code: Int, min: Int = 0, url: String = "https://github.com/AshByteDev/crm-ashbyte-android/releases/download/v0.$code.0/ashcrm.apk") =
        Release(code, "0.$code.0", min, "00", 1, url)

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

    @Test fun pickUsesGitHubByDefault() {
        val gh = release(10)
        val crm = release(11, url = "https://example.com/ashcrm.apk")
        assertSame(gh, VersionRules.pick(gh, crm, updatesFromCrm = false))
        assertNull(VersionRules.pick(null, crm, updatesFromCrm = false))
    }

    @Test fun pickUsesCrmWhenFlagSetAndFallsBackToGitHub() {
        val gh = release(10)
        val crm = release(9, url = "https://example.com/ashcrm.apk")
        assertSame(crm, VersionRules.pick(gh, crm, updatesFromCrm = true))
        assertSame(gh, VersionRules.pick(gh, null, updatesFromCrm = true))
        assertNull(VersionRules.pick(null, null, updatesFromCrm = true))
    }

    @Test fun onlyOurGitHubReleasesOrHttpsFromCrm() {
        val p = "https://github.com/AshByteDev/crm-ashbyte-android/releases/download/"
        assertTrue(Updater.releaseUrlOk("${p}v0.9.0/ashcrm.apk", fromCrm = false, prefix = p))
        assertTrue(Updater.releaseUrlOk("https://github.com/AshByteDev/crm-ashbyte-android/releases/latest/download/ashcrm.apk", fromCrm = false, prefix = p))
        assertFalse(Updater.releaseUrlOk("https://github.com/Other/crm-ashbyte-android/releases/latest/download/ashcrm.apk", fromCrm = false, prefix = p))
        assertFalse(Updater.releaseUrlOk("https://example.com/ashcrm.apk", fromCrm = false, prefix = p))
        assertTrue(Updater.releaseUrlOk("https://example.com/ashcrm.apk", fromCrm = true, prefix = p))
        assertFalse(Updater.releaseUrlOk("http://example.com/ashcrm.apk", fromCrm = true, prefix = p))
        assertFalse(Updater.releaseUrlOk("https://example.com/../ashcrm.apk", fromCrm = true, prefix = p))
    }
}
