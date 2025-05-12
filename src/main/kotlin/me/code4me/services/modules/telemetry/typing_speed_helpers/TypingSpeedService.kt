package me.code4me.services.modules.telemetry.typing_speed_helpers

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class TypingSpeedService(private val project: Project) {
    private val typedTimestamps: MutableList<Long> = mutableListOf()
    private var numOfTypedChars: Int = 0

//    init {
//        val editorMulticaster: EditorEventMulticaster = EditorFactory.getInstance().eventMulticaster
//
//        editorMulticaster.addDocumentListener(object : DocumentListener {
//            override fun documentChanged(event: DocumentEvent) {
//                val now = System.currentTimeMillis()
//                val newText = event.newFragment.toString().trimStart() // Remove leading whitespaces
//                println("[TypingSpeedService] New text: $newText")
//                val charsTyped = newText.count { !it.isWhitespace() }
//                if (charsTyped > 0) {
//                    repeat(charsTyped) {
//                        typedTimestamps.add(now)
//                        numOfTypedChars++
//                    }
//                }
//            }
//        }, project)
//    }

    fun recordCharTyped() {
        val now = System.currentTimeMillis()
        typedTimestamps.add(now)
        numOfTypedChars++
    }

    fun getTypingSpeed(window_size: Int): Double {
        val now = System.currentTimeMillis()
        val oneMinuteAgo = now - (window_size * 1000)

        // Keep only timestamps within the last 60 seconds
        typedTimestamps.removeIf { it < oneMinuteAgo }

        val recentCharCount = typedTimestamps.size
        val speed = (recentCharCount / window_size.toDouble()) // 1 minute window
        println("[TypingSpeedService] Current CPS (last 10s): $speed")
        println("[TypingSpeedService] Typed chars since $window_size seconds ago: $numOfTypedChars")
        numOfTypedChars = 0 // Reset the count after reporting
        return speed
    }

    fun getStatus(): String {
        return "Typed  chars since last activity. WPM: ${getTypingSpeed(10)}"
    }
}
