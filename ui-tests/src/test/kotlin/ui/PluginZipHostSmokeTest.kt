package ui

import com.intellij.remoterobot.RemoteRobot
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "CODE4ME_PLUGIN_ZIP_SMOKE", matches = "1")
class PluginZipHostSmokeTest {
    @Test
    fun packagedPluginLoadsInIde() {
        val robot = RemoteRobot("http://localhost:8082")
        val loaded = robot.callJs<Boolean>(
            "com.intellij.ide.plugins.PluginManagerCore.isPluginInstalled(" +
                "com.intellij.openapi.extensions.PluginId.getId('me.code4me'))",
            true,
        )
        assertTrue(loaded, "the packaged Code4Me plugin did not load in IntelliJ IDEA")
    }
}
