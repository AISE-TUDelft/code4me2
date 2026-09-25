package ui

import com.google.gson.JsonParser
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JTreeFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Exercises the original Code4Me surfaces against a live IDE + backend:
 * signs in through the settings UI, opens the Code4Me V2 chat tool window,
 * sends a chat message and records the resulting conversation, then triggers the
 * manual inline-completion action.
 *
 * This is deliberately separate from [Code4MeUiNavigationTest] (which covers the
 * research/ACP surface). It is inert unless the gate is set:
 *
 *   CODE4ME_UI_TEST=1
 *   CODE4ME_E2E_BASE_URL       backend to point the plugin at (default http://localhost:8008)
 *   CODE4ME_E2E_EMAIL          account email
 *   CODE4ME_E2E_PASSWORD       account password
 *   CODE4ME_UI_ROBOT_URL       robot-server base URL (default http://localhost:8082)
 *   CODE4ME_UI_RESULT_FILE     where the evidence JSON is written (optional)
 */
@EnabledIfEnvironmentVariable(named = "CODE4ME_UI_TEST", matches = "1")
class Code4MeChatExerciseUiTest {
    private data class StepResult(val id: String, val status: String, val reason: String)

    private val results = mutableListOf<StepResult>()

    @Test
    fun exercise() {
        var blockedBy: String? = null
        fun runStep(id: String, body: () -> String?) {
            if (blockedBy != null) {
                results += StepResult(id, "BLOCKED", "prerequisite '$blockedBy' did not complete")
                return
            }
            println("chat exercise step: $id")
            try {
                results += StepResult(id, "PASS", body() ?: "")
            } catch (error: Throwable) {
                val type = error::class.qualifiedName ?: "Throwable"
                val message = "$type: ${error.message ?: error.toString()}"
                results += StepResult(id, "FAIL", message)
                blockedBy = id
            }
        }

        runStep("connect") {
            waitForRobot(180_000)
            ensureProjectOpen()
            waitFor(180_000, "the sandbox IDE never left dumb mode") { !isDumb() }
            "robot=$robotUrl"
        }

        runStep("plugin_loaded") {
            val loaded =
                robot.callJs<Boolean>(
                    "com.intellij.ide.plugins.PluginManagerCore.isPluginInstalled(" +
                        "com.intellij.openapi.extensions.PluginId.getId(${jsQuote(PLUGIN_ID)}))",
                    true,
                )
            assertTrue(loaded, "the Code4Me plugin ($PLUGIN_ID) is not loaded in the sandbox IDE")
            val visible = robot.findAll<ComponentFixture>(byXpath(STRIPE_BUTTON_XPATH)).isNotEmpty()
            assertTrue(visible, "the Code4Me V2 tool-window stripe button is absent")
            "stripe button present"
        }

        runStep("point_at_backend") {
            configurePluginServer()
            "plugin now targets $baseUrl"
        }

        runStep("auth_state") {
            val token = authToken()
            "auth token present: ${token.isNotBlank()}"
        }

        runStep("sign_in") {
            if (authToken().isNotBlank()) {
                return@runStep "already authenticated (auth_token cookie present)"
            }
            val injected = System.getenv("CODE4ME_E2E_TOKEN")?.trim().orEmpty()
            if (injected.isNotEmpty()) {
                val outcome = injectTokenAndAcquireSession(injected)
                check(outcome == "TOKEN_INJECTED") { "token injection failed: $outcome" }
                waitFor(60_000, "the session was not acquired after token injection") { sessionToken().isNotBlank() }
                return@runStep "authenticated via in-memory token; session acquired"
            }
            openCode4MeSettingsAndWaitForCredentials()
            robot.find<ComponentFixture>(byXpath(EMAIL_FIELD_XPATH), Duration.ofSeconds(10))
                .callJs<Boolean>("component.setText(${jsQuote(email)}); true", true)
            robot.find<ComponentFixture>(byXpath(PASSWORD_FIELD_XPATH), Duration.ofSeconds(10))
                .callJs<Boolean>("component.setText(${jsQuote(password)}); true", true)
            robot.find<ComponentFixture>(byXpath(LOGIN_BUTTON_XPATH), Duration.ofSeconds(10))
                .runJs("component.doClick();", true)
            waitFor(90_000, "sign-in did not store an auth token for $email") { authToken().isNotBlank() }
            closeSettings()
            "authenticated as $email"
        }

        runStep("open_chat") {
            openChatToolWindow()
            waitFor(60_000, "the chat tool window content never appeared") { chatPanelNodeCount() > 0 }
            "chat panel rendered (${chatPanelNodeCount()} nodes, inputArea=${chatInputAvailable()})"
        }

        runStep("open_editor") {
            val opened = openSampleEditor()
            Thread.sleep(3000)
            val hasEditor = editorOpen()
            check(hasEditor) { "no editor available for chat context collection ($opened)" }
            "editor ready ($opened)"
        }

        runStep("send_message") {
            if (System.getenv("CODE4ME_UI_SKIP_CHAT") == "1") {
                return@runStep "skipped by CODE4ME_UI_SKIP_CHAT"
            }
            val message = "Hello Code4Me, please reply with a short greeting."
            val sent = sendChatMessage(message)
            check(sent == "SENT") { "could not send the chat message: $sent" }
            // The input controls swap the send button for the stop button while generating.
            waitFor(60_000, "the chat panel never entered the generating state") { generating() }
            waitFor(300_000, "the chat panel never finished generating") { !generating() }
            waitFor(15_000, "no chat bubble captured after sending") { bubbleTexts().isNotEmpty() }
            "sent; bubbles=${bubbleTexts()}"
        }

        runStep("inline_completion_trigger") {
            triggerInlineCompletion()
        }

        writeResults()
    }

    private fun injectTokenAndAcquireSession(token: String): String {
        val script =
            """
            (function() {
                const pd = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                    com.intellij.openapi.extensions.PluginId.getId(${jsQuote(PLUGIN_ID)}));
                const cl = pd.getPluginClassLoader();
                const settings = cl.loadClass("me.code4me.services.state.AuthStateKt")
                    .getMethod("getAuthState").invoke(null);
                const tokenField = settings.getClass().getDeclaredField("cachedToken");
                tokenField.setAccessible(true);
                tokenField.set(settings, ${jsQuote(token)});
                const pcsField = settings.getClass().getDeclaredField("propertyChangeSupport");
                pcsField.setAccessible(true);
                const pcs = pcsField.get(settings);
                pcs.firePropertyChange("authToken", null, ${jsQuote(token)});
                const appSvc = cl.loadClass("me.code4me.services.app.AppServiceKt")
                    .getMethod("getAppService").invoke(null);
                const sessionMethod = appSvc.getClass().getMethod("acquireSessionWithStoredToken");
                const runnable = new java.lang.Runnable({ run: function() {
                    try { sessionMethod.invoke(appSvc); } catch (e) { }
                } });
                com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(runnable);
                return "TOKEN_INJECTED";
            })()
            """.trimIndent()
        return robot.callJs<String>(script, true)
    }

    private fun sessionToken(): String {
        val script =
            """
            const pd = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId(${jsQuote(PLUGIN_ID)}));
            const cl = pd.getPluginClassLoader();
            const cls = cl.loadClass("me.code4me.api.wrapper.CookieAwareApiClient");
            const companion = cls.getField("Companion").get(null);
            const token = companion.getClass().getMethod("getSessionToken").invoke(companion);
            token === null ? "" : String(token)
            """.trimIndent()
        return robot.callJs(script, true)
    }

    private fun openCode4MeSettingsAndWaitForCredentials() {
        var lastError: AssertionError? = null
        for (attempt in 1..3) {
            openCode4MeSettings()
            try {
                val tree = robot.find<JTreeFixture>(byXpath(SETTINGS_TREE_XPATH), Duration.ofSeconds(10))
                waitFor(30_000, "the settings tree never selected Tools > Code4Me V2") {
                    tree.collectSelectedPaths().any { path -> path.lastOrNull() == "Code4Me V2" }
                }
                waitFor(30_000, "the Code4Me settings page never rendered its credential fields") {
                    exists(CREDENTIALS_TITLE_XPATH) && exists(EMAIL_FIELD_XPATH) &&
                        exists(PASSWORD_FIELD_XPATH) && exists(LOGIN_BUTTON_XPATH)
                }
                lastError = null
                break
            } catch (error: AssertionError) {
                lastError = error
                closeSettings()
            }
        }
        lastError?.let {
            throw AssertionError("${it.message} (after 3 attempts); ui snapshot: ${uiSnapshot().take(4000)}")
        }
    }

    private fun uiSnapshot(): String =
        runCatching {
            robot.callJs<String>(
                """
                (function() {
                    const out = [];
                    const windows = java.awt.Window.getWindows();
                    for (let i = 0; i < windows.length && out.length < 500; i++) {
                        const w = windows[i];
                        let title = "";
                        try { title = String(w.getTitle ? (w.getTitle() || "") : ""); } catch (e) { }
                        out.push({ cls: String(w.getClass().getName()), text: title, name: "" });
                        (function walk(c, depth) {
                            if (depth > 18 || out.length > 500) { return; }
                            let cn = ""; let tx = ""; let an = "";
                            try { cn = String(c.getClass().getName()); } catch (e) { }
                            try { tx = String(c.getText ? (c.getText() || "") : ""); } catch (e) { }
                            try {
                                const ac = c.getAccessibleContext ? c.getAccessibleContext() : null;
                                an = String(ac && ac.getAccessibleName ? (ac.getAccessibleName() || "") : "");
                            } catch (e) { }
                            out.push({ cls: cn, text: tx, name: an });
                            try {
                                const kids = c.getComponents();
                                for (let j = 0; j < kids.length; j++) { walk(kids[j], depth + 1); }
                            } catch (e) { }
                        })(w, 0);
                    }
                    return JSON.stringify(out);
                })()
                """.trimIndent(),
                true,
            )
        }.getOrDefault("<snapshot failed>")

    private fun openSampleEditor(): String =
        robot.callJs<String>(
            """
            (function() {
                const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                const fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                if (fem.getSelectedTextEditor() !== null) { return "ALREADY_OPEN"; }
                const dir = java.nio.file.Files.createTempDirectory("code4me-chat-context");
                const f = new java.io.File(dir.toFile(), "Sample.kt");
                java.nio.file.Files.writeString(f.toPath(), "fun main() {\n    val greeting = \"hello\"\n}\n");
                const vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
                const runnable = new java.lang.Runnable({ run: function() { fem.openFile(vf, true); } });
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(runnable);
                return "OPENING";
            })()
            """.trimIndent(),
            true,
        )

    private fun editorOpen(): Boolean =
        robot.callJs<Boolean>(
            "(function() { const p = com.intellij.openapi.project.ProjectManager.getInstance()" +
                ".getOpenProjects()[0]; if (!p) return false; return com.intellij.openapi.fileEditor" +
                ".FileEditorManager.getInstance(p).getSelectedTextEditor() !== null; })()",
            true,
        )

    // ------------------------------------------------------------------
    // Chat window interaction (JS bridge, no Accessibility permission needed)
    // ------------------------------------------------------------------

    private fun openChatToolWindow() {
        val script =
            """
            (function() {
                const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                const tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Code4Me V2");
                if (!tw) { return "NO_TOOLWINDOW"; }
                if (tw.isVisible()) { return "ALREADY_VISIBLE"; }
                const runnable = new java.lang.Runnable({ run: function() { tw.activate(null); } });
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(runnable);
                return "ACTIVATING";
            })()
            """.trimIndent()
        val outcome = robot.callJs<String>(script, true)
        check(outcome != "NO_TOOLWINDOW") { "the Code4Me V2 tool window is not registered" }
    }

    private fun chatInputAvailable(): Boolean =
        runCatching {
            robot.callJs<Boolean>(
                "(function() { $CHAT_TREE_SCRIPT return __code4meTree.textArea !== null; })()",
                true,
            )
        }.getOrDefault(false)

    private fun chatPanelNodeCount(): Int =
        runCatching {
            robot.callJs<String>(
                "(function() { $CHAT_TREE_SCRIPT return JSON.stringify(__code4meTree.nodes.length); })()",
                true,
            ).trim().toInt()
        }.getOrDefault(0)

    private fun generating(): Boolean =
        robot.callJs<Boolean>(
            "(function() { $CHAT_TREE_SCRIPT" +
                " for (let i = 0; i < __code4meTree.nodes.length; i++) {" +
                " if (__code4meTree.nodes[i].tip === 'Stop generation') { return true; } }" +
                " return false; })()",
            true,
        )

    private fun bubbleTexts(): List<String> {
        val raw =
            robot.callJs<String>(
                "(function() { $CHAT_TREE_SCRIPT" +
                    " return JSON.stringify(__code4meTree.nodes.map(function(n) { return n.text; })" +
                    ".filter(function(t) { return t && t.length > 0 && t !== 'Ask Code4Me V2!'; })" +
                    ".slice(-12)); })()",
                true,
            )
        val array = JsonParser.parseString(raw).asJsonArray
        return array.map { it.asString }
    }

    private fun sendChatMessage(message: String): String {
        val script =
            """
            (function() {
                $CHAT_TREE_SCRIPT
                if (__code4meTree.error) { return "NO_PANEL:" + __code4meTree.error; }
                const area = __code4meTree.textArea;
                if (!area) { return "NO_TEXTAREA"; }
                const action = area.getActionMap().get("send");
                if (!action) { return "NO_SEND_ACTION"; }
                const runnable = new java.lang.Runnable({ run: function() {
                    area.setText(${jsQuote(message)});
                    action.actionPerformed(null);
                } });
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(runnable);
                return "SENT";
            })()
            """.trimIndent()
        return robot.callJs<String>(script, true)
    }

    private fun triggerInlineCompletion(): String {
        val script =
            """
            (function() {
                const pd = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                    com.intellij.openapi.extensions.PluginId.getId(${jsQuote(PLUGIN_ID)}));
                const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                const fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                let editor = fem.getSelectedTextEditor();
                if (editor === null) {
                    const dir = java.nio.file.Files.createTempDirectory("code4me-exercise");
                    const f = new java.io.File(dir.toFile(), "Sample.kt");
                    java.nio.file.Files.writeString(f.toPath(), "fun main() {\n    val greeting = \n}\n");
                    const vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
                    fem.openFile(vf, true);
                    editor = fem.getSelectedTextEditor();
                }
                if (editor === null) { return "NO_EDITOR"; }
                editor.getCaretModel().moveToOffset(editor.getDocument().getTextLength());
                const am = com.intellij.openapi.actionSystem.ActionManager.getInstance();
                const action = am.getAction("me.code4me.actions.TriggerInlineCompletionAction");
                if (action === null) { return "NO_ACTION"; }
                const key = com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR;
                const base = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project);
                const dc = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getSimpleContext(key, editor, base);
                const event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "ToolsMenu", dc);
                const runnable = new java.lang.Runnable({ run: function() { action.actionPerformed(event); } });
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(runnable);
                return "INVOKED";
            })()
            """.trimIndent()
        return robot.callJs<String>(script, true)
    }

    // ------------------------------------------------------------------
    // Plugin/backend configuration
    // ------------------------------------------------------------------

    private fun configurePluginServer() {
        val script =
            """
            const pd = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId(${jsQuote(PLUGIN_ID)}));
            if (pd === null) { throw new Error("Code4Me plugin is not loaded"); }
            const cl = pd.getPluginClassLoader();
            const sc = cl.loadClass("me.code4me.services.config.models.ServerConfig");
            const ctor = sc.getConstructor(
                java.lang.String, java.lang.Integer.TYPE, java.lang.String,
                java.lang.Integer.TYPE, java.lang.String);
            const cfg = ctor.newInstance(
                ${jsQuote(baseUrl)}, new java.lang.Integer(0), "",
                new java.lang.Integer(30), ${jsQuote(baseUrl)});
            const appSvc = cl.loadClass("me.code4me.services.app.AppServiceKt").getMethod("getAppService").invoke(null);
            appSvc.getClass().getMethod("setServerConfig", sc).invoke(appSvc, cfg);
            const configSvc = cl.loadClass("me.code4me.services.config.ConfigServiceKt").getMethod("getConfig").invoke(null);
            const field = configSvc.getClass().getDeclaredField("serverConfig");
            field.setAccessible(true);
            field.set(configSvc, cfg);
            String(appSvc.getApiBaseUrl())
            """.trimIndent()
        val resolved = robot.callJs<String>(script, true)
        check(resolved.startsWith(baseUrl)) { "could not point the plugin at $baseUrl (it reports $resolved)" }
    }

    private fun authToken(): String {
        val script =
            """
            const pd = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId(${jsQuote(PLUGIN_ID)}));
            const cl = pd.getPluginClassLoader();
            const cls = cl.loadClass("me.code4me.api.wrapper.CookieAwareApiClient");
            const companion = cls.getField("Companion").get(null);
            const token = companion.getClass().getMethod("getAuthToken").invoke(companion);
            token === null ? "" : String(token)
            """.trimIndent()
        return robot.callJs(script, true)
    }

    private fun isDumb(): Boolean =
        robot.callJs<Boolean>(
            "(function() { const p = com.intellij.openapi.project.ProjectManager.getInstance()" +
                ".getOpenProjects()[0]; return p ? com.intellij.openapi.project.DumbService" +
                ".getInstance(p).isDumb() : false; })()",
            true,
        )

    private fun exists(xpath: String): Boolean = robot.findAll<ComponentFixture>(byXpath(xpath)).isNotEmpty()

    private fun openCode4MeSettings() {
        val script =
            """
            const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            const runnable = new java.lang.Runnable({
                run: function() {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, "Code4Me V2");
                }
            });
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(runnable);
            true
            """.trimIndent()
        robot.callJs<Boolean>(script, true)
    }

    private fun closeSettings() {
        runCatching {
            robot.findAll<ComponentFixture>(byXpath(SETTINGS_CANCEL_XPATH)).firstOrNull()
                ?.runJs("component.doClick();", true)
        }
    }

    private fun ensureProjectOpen() {
        val alreadyOpen =
            robot.callJs<Boolean>(
                "com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length > 0",
                true,
            )
        if (alreadyOpen) return
        val projectDir = System.getenv("CODE4ME_UI_PROJECT_DIR")?.trim().orEmpty()
            .ifEmpty { Files.createTempDirectory("code4me-chat-exercise").toString() }
        Files.createDirectories(Path.of(projectDir))
        val script =
            """
            const path = new java.io.File(${jsQuote(projectDir)}).toPath();
            com.intellij.ide.trustedProjects.TrustedProjects.setProjectTrusted(path, true);
            const pm = com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx();
            const task = com.intellij.ide.impl.OpenProjectTask.build().asNewProject();
            const runnable = new java.lang.Runnable({ run: function() { pm.openProject(path, task); } });
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(runnable);
            true
            """.trimIndent()
        robot.callJs<Boolean>(script, true)
        waitFor(180_000, "the sandbox IDE never opened the test project") {
            robot.callJs<Boolean>(
                "com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length > 0",
                true,
            )
        }
    }

    // ------------------------------------------------------------------
    // Results + robot plumbing
    // ------------------------------------------------------------------

    private fun writeResults() {
        val overall =
            when {
                results.any { it.status == "FAIL" } -> "FAIL"
                results.any { it.status == "BLOCKED" } -> "BLOCKED"
                results.isEmpty() -> "UNKNOWN"
                else -> "PASS"
            }
        val json =
            buildString {
                append("{\n  \"overall\": ").append(jsonString(overall)).append(",\n")
                append("  \"robot_url\": ").append(jsonString(robotUrl)).append(",\n")
                append("  \"base_url\": ").append(jsonString(baseUrl)).append(",\n")
                append("  \"steps\": [\n")
                results.forEachIndexed { index, step ->
                    append("    { \"id\": ").append(jsonString(step.id))
                        .append(", \"status\": ").append(jsonString(step.status))
                        .append(", \"reason\": ").append(jsonString(step.reason)).append(" }")
                    if (index != results.lastIndex) append(",")
                    append("\n")
                }
                append("  ]\n}\n")
            }
        val target = System.getenv("CODE4ME_UI_RESULT_FILE")?.trim().orEmpty()
        val path = if (target.isNotEmpty()) Path.of(target) else Path.of("build", "ui-chat-exercise-results.json")
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, json)
        }.onFailure { System.err.println("WARNING: could not write $path: $it") }
        println("chat-exercise-results: $json")
    }

    private fun waitFor(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = Instant.now().plusMillis(timeoutMs)
        var last: Throwable? = null
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition()) return
            } catch (error: Throwable) {
                last = error
            }
            Thread.sleep(500)
        }
        throw AssertionError("$message (after ${timeoutMs / 1000}s)${last?.let { "; last error: ${it.message}" } ?: ""}")
    }

    private fun waitForRobot(timeoutMs: Long) {
        val health = "$robotUrl/hello"
        val deadline = Instant.now().plusMillis(timeoutMs)
        var last: Throwable? = null
        while (Instant.now().isBefore(deadline)) {
            try {
                val connection = URI(health).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                try {
                    if (connection.responseCode == 200) return
                } finally {
                    connection.disconnect()
                }
            } catch (error: Throwable) {
                last = error
            }
            Thread.sleep(2_000)
        }
        throw IllegalStateException("robot-server at $robotUrl did not answer: ${last?.message}")
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun jsQuote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    companion object {
        private const val PLUGIN_ID = "me.code4me"
        private const val STRIPE_BUTTON_XPATH =
            "//div[@class='SquareStripeButton' and @accessiblename='Code4Me V2']"
        private const val SETTINGS_TREE_XPATH = "//div[@class='MyTree']"
        private const val CREDENTIALS_TITLE_XPATH =
            "//div[@class='JBLabel' and @accessiblename='Credential-based Authentication']"
        private const val EMAIL_FIELD_XPATH = "//div[@class='JBTextField']"
        private const val PASSWORD_FIELD_XPATH = "//div[@class='JBPasswordField']"
        private const val LOGIN_BUTTON_XPATH = "//div[@class='JButton' and @accessiblename='Login']"
        private const val SETTINGS_CANCEL_XPATH =
            "//div[@class='FloatDialog' and @accessiblename='Settings']" +
                "//div[@class='JButton' and @accessiblename='Cancel']"

        /**
         * Walks the chat tool window component tree and exposes
         * `__code4meTree.nodes` (class/text/tooltip/visible/enabled) plus the
         * input `JTextArea`. Evaluated inside a surrounding IIFE.
         */
        private val CHAT_TREE_SCRIPT =
            """
            const __code4meTree = (function() {
                const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                const tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Code4Me V2");
                if (!tw) { return { error: "tool window missing", nodes: [], textArea: null }; }
                const contents = tw.getContentManager().getContents();
                if (contents.length === 0) { return { error: "no content", nodes: [], textArea: null }; }
                const root = contents[0].getComponent();
                const nodes = [];
                let textArea = null;
                function textOf(c) {
                    try {
                        const cn = String(c.getClass().getName());
                        if (cn === "javax.swing.JTextArea" || cn === "javax.swing.JEditorPane" ||
                            cn === "javax.swing.JLabel" || cn === "javax.swing.JButton") {
                            return String(c.getText() || "");
                        }
                    } catch (e) { }
                    return "";
                }
                function walk(c, depth) {
                    if (depth > 30 || nodes.length > 800) { return; }
                    let cn = ""; let tip = ""; let vis = null; let en = null;
                    try { cn = String(c.getClass().getName()); } catch (e) { }
                    try { tip = String(c.getToolTipText() || ""); } catch (e) { }
                    try { vis = c.isVisible(); } catch (e) { }
                    try { en = c.isEnabled(); } catch (e) { }
                    const text = textOf(c);
                    nodes.push({ cls: cn, text: text, tip: tip, visible: vis, enabled: en });
                    if (textArea === null && cn === "javax.swing.JTextArea") { textArea = c; }
                    try {
                        const kids = c.getComponents();
                        for (let i = 0; i < kids.length; i++) { walk(kids[i], depth + 1); }
                    } catch (e) { }
                }
                walk(root, 0);
                return { error: null, nodes: nodes, textArea: textArea };
            })();
            """.trimIndent()

        private lateinit var robot: RemoteRobot
        private lateinit var robotUrl: String
        private lateinit var baseUrl: String
        private lateinit var email: String
        private lateinit var password: String

        @BeforeAll
        @JvmStatic
        fun connect() {
            robotUrl = System.getenv("CODE4ME_UI_ROBOT_URL")?.trim().orEmpty().ifEmpty { "http://localhost:8082" }
            baseUrl = System.getenv("CODE4ME_E2E_BASE_URL")?.trim().orEmpty().ifEmpty { "http://localhost:8008" }
            email = System.getenv("CODE4ME_E2E_EMAIL")?.trim().orEmpty()
            password = System.getenv("CODE4ME_E2E_PASSWORD")?.trim().orEmpty()
            check(email.isNotEmpty() && password.isNotEmpty()) {
                "CODE4ME_E2E_EMAIL and CODE4ME_E2E_PASSWORD must be set"
            }
            robot = RemoteRobot(robotUrl)
        }
    }
}
