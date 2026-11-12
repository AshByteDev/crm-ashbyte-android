package ru.ashcrm.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionRulesTest {
    private fun release(code: Int, min: Int = 0) = Release(code, "0.$code.0", min, "00", 1,
        "https://github.com/AshByteDev/crm-ashbyte-android/releases/download/v0.$code.0/ashcrm.apk")

    @Test fun noAnswerOfGitVerseChangesNothing() =
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

    @Test fun onlyFilesOfReleasesOnGitVerse() {
        val p = "https://gitverse.ru/api/attachments/"
        assertTrue(Updater.releaseUrlOk("${p}c344d2da-b0bd-4568-acd1-10786a99a095", p))
        assertFalse(Updater.releaseUrlOk("http://gitverse.ru/api/attachments/c344d2da-b0bd-4568-acd1-10786a99a095", p))
        assertFalse(Updater.releaseUrlOk("${p}c344d2da-b0bd-4568-acd1-10786a99a095/../x", p))
        assertFalse(Updater.releaseUrlOk("${p}../repos/x", p))
        assertFalse(Updater.releaseUrlOk("https://gitverse.ru.evil.example/api/attachments/c344d2da-b0bd-4568-acd1-10786a99a095", p))
        assertFalse(Updater.releaseUrlOk("https://github.com/AshByteDev/crm-ashbyte-android/releases/download/v0.4.3/ashcrm.apk", p))
    }
}
