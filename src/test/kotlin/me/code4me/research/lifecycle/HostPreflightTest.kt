package me.code4me.research.lifecycle

import me.code4me.research.session.StudyBlockReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A-03: the JetBrains AI Assistant preflight is pure and decides by presence, enablement and build version. */
class HostPreflightTest {
    @Test
    fun `dotted build versions compare numerically per component`() {
        assertTrue(HostPreflight.compareBuildVersions("262.8665.344", "262.10315.125") < 0, "8665 < 10315 numerically, not lexically")
        assertTrue(HostPreflight.compareBuildVersions("262.10315.125", "262.8665.344") > 0)
        assertTrue(HostPreflight.compareBuildVersions("263.1.2", "262.10315.125") > 0)
        assertEquals(0, HostPreflight.compareBuildVersions("262.8665.344", "262.8665.344"))
        assertEquals(0, HostPreflight.compareBuildVersions("263.1", "263.1.0"), "a missing component is zero")
        assertTrue(HostPreflight.compareBuildVersions("262.8665.345", "262.8665.344") > 0)
        assertEquals(0, HostPreflight.compareBuildVersions("262.8665.344-EAP", "262.8665.344"), "a suffix is ignored")
        assertTrue(HostPreflight.compareBuildVersions("", "262.8665.344") < 0, "blank is older than anything")
        assertTrue(HostPreflight.compareBuildVersions(null, "1") < 0)
        assertTrue(HostPreflight.compareBuildVersions("1", null) > 0)
        assertEquals(0, HostPreflight.compareBuildVersions(null, " "))
    }

    @Test
    fun `a missing or disabled AI Assistant blocks as missing`() {
        val missing = HostPreflight.evaluate(installed = false, enabled = false, version = null)
        assertTrue(missing is HostPreflightResult.Blocked)
        assertEquals(StudyBlockReason.AI_ASSISTANT_MISSING, (missing as HostPreflightResult.Blocked).reason)
        assertTrue(missing.detail.contains(HostPreflight.MIN_AI_ASSISTANT_VERSION), missing.detail)

        val disabled = HostPreflight.evaluate(installed = true, enabled = false, version = "262.10315.125")
        assertTrue(disabled is HostPreflightResult.Blocked)
        assertEquals(StudyBlockReason.AI_ASSISTANT_MISSING, (disabled as HostPreflightResult.Blocked).reason)
        assertTrue(disabled.detail.contains("disabled"), disabled.detail)
    }

    @Test
    fun `an outdated or unknown AI Assistant version blocks as outdated and names the minimum`() {
        val outdated = HostPreflight.evaluate(installed = true, enabled = true, version = "262.8665.343")
        assertTrue(outdated is HostPreflightResult.Blocked)
        assertEquals(StudyBlockReason.AI_ASSISTANT_OUTDATED, (outdated as HostPreflightResult.Blocked).reason)
        assertTrue(outdated.detail.contains("262.8665.343"), "the installed version is reported: ${outdated.detail}")
        assertTrue(outdated.detail.contains(HostPreflight.MIN_AI_ASSISTANT_VERSION), outdated.detail)

        val older = HostPreflight.evaluate(installed = true, enabled = true, version = "261.9999.9999")
        assertEquals(StudyBlockReason.AI_ASSISTANT_OUTDATED, (older as HostPreflightResult.Blocked).reason)

        val unknown = HostPreflight.evaluate(installed = true, enabled = true, version = "  ")
        assertTrue(unknown is HostPreflightResult.Blocked, "an unprovable version is treated as too old")
        assertEquals(StudyBlockReason.AI_ASSISTANT_OUTDATED, (unknown as HostPreflightResult.Blocked).reason)
        assertTrue(unknown.detail.contains("no version"), unknown.detail)
    }

    @Test
    fun `a new enough AI Assistant passes and reports its version`() {
        listOf("262.8665.344", "262.10315.125", "263.1.2").forEach { version ->
            val ok = HostPreflight.evaluate(installed = true, enabled = true, version = version)
            assertTrue(ok is HostPreflightResult.Ok, "$version: $ok")
            assertEquals(version, (ok as HostPreflightResult.Ok).aiAssistantVersion)
        }
    }
}
