package me.code4me.services.app

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.services.config.models.ServerConfig
import me.code4me.services.project.getProjectTokenService
import me.code4me.services.state.getPrefState

/**
 * A server picked in Settings is restored at every IDE start. Restoring it is not
 * a switch: the stored login and project belong to it, so they must survive.
 */
class AppServiceServerSelectionTest : BasePlatformTestCase() {
    fun testRestoringTheSavedServerKeepsTheLoginWhileSwitchingClearsIt() {
        val prefs = getPrefState()
        val saved = Triple(prefs.lastServerHost, prefs.lastServerPort, prefs.lastServerContextPath)
        val tokens = getProjectTokenService(project)
        try {
            tokens.setProjectToken(PROJECT_ID)
            prefs.lastServerHost = "https://saved.invalid"
            prefs.lastServerPort = 8443
            prefs.lastServerContextPath = ""

            val appService = AppService() // what the IDE builds at startup

            assertEquals("https://saved.invalid:8443", appService.getApiBaseUrl())
            assertEquals(PROJECT_ID, tokens.getProjectToken())

            appService.setServerConfig(ServerConfig(host = "https://other.invalid", port = 0, contextPath = "", timeout = 30))

            assertNull(tokens.getProjectToken())
        } finally {
            prefs.lastServerHost = saved.first
            prefs.lastServerPort = saved.second
            prefs.lastServerContextPath = saved.third
            tokens.clearProjectToken()
        }
    }

    private companion object {
        const val PROJECT_ID = "0f1e2d3c-4b5a-4968-8776-655443322110"
    }
}
