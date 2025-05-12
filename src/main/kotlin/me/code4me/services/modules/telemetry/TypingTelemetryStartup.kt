package me.code4me.services.modules.telemetry

import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.util.concurrency.AppExecutorUtil
import me.code4me.services.modules.telemetry.TypingSpeed
import java.util.concurrent.TimeUnit

class TypingTelemetryStartup : StartupActivity {
    override fun runActivity(project: Project) {
//        val executor = AppExecutorUtil.getAppScheduledExecutorService()
//
//        executor.scheduleWithFixedDelay({
//            val typingSpeed = TypingSpeed(project)
//            val records = typingSpeed.collectData()
//            println("[TypingSpeed] Collected: ${records.firstOrNull()?.toString()}")
//        }, 5, 10, TimeUnit.SECONDS)

        // Register custom typed handler
        val originalHandler = TypedAction.getInstance().rawHandler
        TypedAction.getInstance().setupRawHandler(TypingSpeedHandler(originalHandler, project))
        println("[TypingTelemetryStartup] TypingSpeedHandler registered.")


        // Schedule periodic telemetry data collection
        val executor = AppExecutorUtil.getAppScheduledExecutorService()
        executor.scheduleWithFixedDelay({
            val typingSpeed = TypingSpeed(project)
            val records = typingSpeed.collectData()
            println("[TypingSpeed] Collected: ${records.firstOrNull()?.toString()}")
        }, 5, 10, TimeUnit.SECONDS)
    }
}
