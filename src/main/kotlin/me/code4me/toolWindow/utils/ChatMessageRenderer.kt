package me.code4me.toolWindow.utils

class ChatMessageRenderer {
    fun render(messages: List<Pair<String, String>>): String {
        val html = StringBuilder()
        html.append(
            """
            <html>
            <head>
                <style>
                    .message-container {
                        max-width: 600px;
                        word-break: break-word;
                        overflow-wrap: anywhere;
                        white-space: pre-wrap;
                    }
                </style>
            </head>
            <body style='font-family:sans-serif;'>
            """.trimIndent(),
        )

        for ((sender, message) in messages) {
            html.append(
                """
                <div style='margin-top:10px;'>
                    <div style='font-weight:bold;'>$sender</div>
                    <div class='message-container'>${formatMessage(message)}</div>
                </div>
                """.trimIndent(),
            )
        }

        html.append("</body></html>")
        return html.toString()
    }

    private fun formatMessage(text: String): String {
        // TODO: Add actual formatting (for example, code blocks, links, etc.)
        return text
    }
}
