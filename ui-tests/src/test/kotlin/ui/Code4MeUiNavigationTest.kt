package ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JTreeFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

/**
 * Real-IDE UI navigation suite for the Code4Me plugin.
 *
 * It talks to the robot-server that the `:runIdeForUiTests` Gradle task starts
 * in a **real** IntelliJ IDEA sandbox with the plugin (and AI Assistant)
 * installed. It does not boot the IDE itself — `python3 -m code4me_e2e ui-test`
 * owns that lifecycle and supplies the environment contract below.
 *
 * The suite is inert unless the gate is set, so a plain `./gradlew test` never
 * needs a running IDE:
 *
 *   CODE4ME_UI_TEST=1               gate (class-level @EnabledIfEnvironmentVariable)
 *   CODE4ME_E2E_BASE_URL            the disposable backend, e.g. http://localhost:28008
 *   CODE4ME_E2E_EMAIL               participant account email
 *   CODE4ME_E2E_PASSWORD            participant account password
 *   CODE4ME_E2E_JOIN_CODE           the published study's join code
 *   CODE4ME_UI_ROBOT_URL            robot-server base URL (default http://localhost:8082)
 *   CODE4ME_UI_PROJECT_DIR          directory opened as the test project (optional)
 *   CODE4ME_UI_RESULT_FILE          where the per-step results JSON is written (optional)
 *
 * Every step is recorded as PASS / BLOCKED / FAIL in the results JSON so the
 * harness can report a typed reason. A step that cannot be driven on macOS
 * without OS-level Accessibility permission is BLOCKED, not failed; a genuine
 * assertion failure fails the Gradle test.
 *
 * The IDE-side interaction deliberately avoids AWT-Robot synthesized input
 * (`ComponentFixture.click()`, `Keyboard.enterText()`): those need macOS
 * Accessibility/Automation permission and fail silently without it. Instead it
 * uses in-process component actions (`component.doClick()`, `component.setText`)
 * and in-process IDE API calls through the robot-server's JS bridge. The one
 * step that is not automatable here (AI Assistant launching the registered ACP
 * agent) is reported as BLOCKED with its reason.
 *
 * `~/.jetbrains/acp.json` belongs to the developer: it is snapshotted (bytes +
 * mtime) before the run and restored afterwards, and the harness restores its
 * own pre-launch snapshot after the IDE exits as a backstop.
 */
@EnabledIfEnvironmentVariable(named = "CODE4ME_UI_TEST", matches = "1")
class Code4MeUiNavigationTest {
    @Test
    fun navigate() {
        val steps =
            listOf(
                Step("plugin_loaded", ::pluginLoaded),
                Step("settings_navigation", ::settingsNavigation),
                Step("sign_in", ::signIn),
                Step("enrollment_activation", ::activateEnrollment),
                Step("status_surface", ::statusSurface),
                Step("acp_registration", ::acpRegistration),
                Step("prepare_agent", ::prepareAgent),
            )
        for (step in steps) {
            runStep(step)
        }
    }

    // ------------------------------------------------------------------
    // 1. Plugin loaded
    // ------------------------------------------------------------------

    private fun pluginLoaded() {
        val loaded = robot.callJs<Boolean>(isPluginLoadedScript(PLUGIN_ID), true)
        assertTrue(loaded, "the Code4Me plugin ($PLUGIN_ID) is not loaded in the sandbox IDE")

        // A stable UI element that only exists while the plugin is loaded and a
        // project is open: the Code4Me V2 tool-window stripe button.
        // XPath: //div[@class='SquareStripeButton' and @accessiblename='Code4Me V2']
        val visible = robot.findAll<ComponentFixture>(byXpath(STRIPE_BUTTON_XPATH)).isNotEmpty()
        assertTrue(
            visible,
            "the Code4Me plugin reports loaded but its tool-window stripe button " +
                "($STRIPE_BUTTON_XPATH) is absent",
        )
    }

    // ------------------------------------------------------------------
    // 2. Settings navigation
    // ------------------------------------------------------------------

    private fun settingsNavigation() {
        openCode4MeSettings()

        val tree = robot.find<JTreeFixture>(byXpath(SETTINGS_TREE_XPATH), Duration.ofSeconds(10))
        waitFor(60_000, "the settings tree never selected Tools > Code4Me V2") {
            tree.collectSelectedPaths().any { path -> path.lastOrNull() == "Code4Me V2" }
        }

        // The authentication section only exists on the Code4Me page.
        assertTrue(
            exists(CREDENTIALS_TITLE_XPATH),
            "the Code4Me settings page is selected but its authentication section is missing " +
                "($CREDENTIALS_TITLE_XPATH)",
        )
        assertTrue(exists(EMAIL_LABEL_XPATH), "the Code4Me authentication page has no Email field label")
        assertTrue(exists(PASSWORD_LABEL_XPATH), "the Code4Me authentication page has no Password field label")
        assertTrue(exists(LOGIN_BUTTON_XPATH), "the Code4Me authentication page has no Login button")
    }

    // ------------------------------------------------------------------
    // 3. Sign-in
    // ------------------------------------------------------------------

    private fun signIn() {
        val emailField = robot.find<ComponentFixture>(byXpath(EMAIL_FIELD_XPATH), Duration.ofSeconds(10))
        val passwordField = robot.find<ComponentFixture>(byXpath(PASSWORD_FIELD_XPATH), Duration.ofSeconds(10))

        // In-process text mutation. No AWT Robot keystrokes, so no macOS
        // Accessibility permission is required; the button handler reads the
        // component's own text model.
        emailField.callJs<Boolean>("component.setText(${jsQuote(email)}); true", true)
        passwordField.callJs<Boolean>("component.setText(${jsQuote(password)}); true", true)

        val login = robot.find<ComponentFixture>(byXpath(LOGIN_BUTTON_XPATH), Duration.ofSeconds(10))
        login.runJs("component.doClick();", true)

        val authenticated =
            try {
                waitFor(90_000, "sign-in did not store an auth token for $email") { authToken().isNotBlank() }
                true
            } catch (error: AssertionError) {
                false
            }
        if (!authenticated) {
            val notifications = notificationTexts()
            throw UiBlocked(
                "sign-in did not complete: no auth_token appeared in the plugin's cookie jar. " +
                    "Backend=$baseUrl. Plugin notifications: " +
                    notifications.ifEmpty { listOf("<none>") }.joinToString(" | "),
            )
        }
        assertTrue(authToken().isNotBlank(), "sign-in reported success but the plugin holds no auth_token")
    }

    // ------------------------------------------------------------------
    // 4. Join Research Study
    // ------------------------------------------------------------------

    private fun activateEnrollment() {
        closeSettings()
        // Enrollment/consent now happen on the web. Reopen the project after
        // sign-in to exercise the real startup discovery/activation hook.
        val script = """
            const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            const path = project.getBasePath();
            const runnable = new java.lang.Runnable({ run: function() {
                const pm = com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx();
                pm.closeAndDispose(project);
                pm.openProject(java.nio.file.Path.of(path), com.intellij.ide.impl.OpenProjectTask.build());
            }});
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(runnable);
            true
        """.trimIndent()
        robot.callJs<Boolean>(script, true)
        waitFor(120_000, "project did not reopen after login") {
            robot.callJs<Boolean>(
                "com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length > 0", true)
        }
        // Activation is asynchronous (bootstrap + runtime setup). The participant
        // status only becomes "active" once the session collects a qualifying IDE
        // activity signal, so drive the editor while we wait.
        triggerIdeActivity()

        val deadline = Instant.now().plusSeconds(240)
        var headline = researchStatusHeadline()
        while (Instant.now().isBefore(deadline) && headline != ACTIVE_HEADLINE) {
            Thread.sleep(2_000)
            runCatching { triggerIdeActivity() }
            headline = researchStatusHeadline()
        }
        if (headline == ACTIVE_HEADLINE) return

        val notifications = notificationTexts()
        throw UiBlocked(
            "the join action completed but the study did not activate: the participant status " +
                "headline is '$headline' (expected '$ACTIVE_HEADLINE'). Runtime/join notifications: " +
                notifications.ifEmpty { listOf("<none>") }.joinToString(" | ") +
                ". research state = " + researchStateDump() +
                "; tooltip = " + researchStatusTooltip(),
        )
    }

    // ------------------------------------------------------------------
    // 5. Status surface
    // ------------------------------------------------------------------

    private fun statusSurface() {
        val headline = researchStatusHeadline()
        assertEquals(
            ACTIVE_HEADLINE,
            headline,
            "the research status-bar widget must reflect the joined state ('$ACTIVE_HEADLINE'), " +
                "but shows '$headline'",
        )
        // Text only: never assert ids, paths, digests or secrets.
        assertTrue(
            notificationTexts().none { it.contains("Research Study Not Joined") },
            "a 'Research Study Not Joined' notification is still showing after the status bar " +
                "reported an active study",
        )
    }

    // ------------------------------------------------------------------
    // 6. ACP registration
    // ------------------------------------------------------------------

    private fun acpRegistration() {
        assertTrue(
            Files.isRegularFile(acpPath),
            "activation completed but $acpPath does not exist; the research proxy ACP entry was " +
                "never registered",
        )
        val text = Files.readString(acpPath)
        assertTrue(
            text.contains(ACP_ENTRY_NAME),
            "$acpPath has no '$ACP_ENTRY_NAME' entry after activation",
        )
        assertTrue(
            text.contains("agent_servers"),
            "$acpPath has a Code4Me entry outside the 'agent_servers' registry",
        )
        // Never dump the file: it may hold other people's tokens.
        assertTrue(
            text.trimStart().startsWith("{"),
            "$acpPath is not a JSON object after activation (first bytes only checked)",
        )
    }

    // ------------------------------------------------------------------
    // 7. Host launches the registered agent (best effort)
    // ------------------------------------------------------------------

    private fun prepareAgent() {
        val script = """
            const am = com.intellij.openapi.actionSystem.ActionManager.getInstance();
            const action = am.getAction("me.code4me.actions.PrepareAcpAgentSessionAction");
            if (action === null) { throw new Error("Prepare agent action is missing"); }
            const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            const dc = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project);
            const event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "ToolsMenu", dc);
            const runnable = new java.lang.Runnable({ run: function() { action.actionPerformed(event); } });
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(runnable);
            true
        """.trimIndent()
        robot.callJs<Boolean>(script, true)
        // The bridge directory follows the IDE's own system path. The harness
        // redirects it into its private home, but the OS layout differs (macOS
        // uses ~/Library/Caches/JetBrains/<product>), so resolve it by searching
        // the harness home instead of assuming one layout.
        val bridges = bridgesDirectory()
        waitFor(120_000, "Prepare agent did not create a managed authentication bridge") {
            Files.isDirectory(bridges) && Files.list(bridges).use { it.anyMatch { p -> p.toString().endsWith(".json") } }
        }
    }

    // ------------------------------------------------------------------
    // Step bookkeeping
    // ------------------------------------------------------------------

    private data class Step(val id: String, val body: () -> Unit)

    private class UiBlocked(val reason: String) : RuntimeException(reason)

    private fun runStep(step: Step) {
        println("UI step: ${step.id}")
        val prerequisite = blockedPrerequisite
        if (prerequisite != null) {
            // A BLOCKED step does not abort the suite, but the later steps cannot
            // make their assertion any more; record them as BLOCKED with the
            // prerequisite so the overall result is a typed BLOCKED, not a FAIL.
            results += StepResult(
                step.id,
                "BLOCKED",
                "not asserted: prerequisite '${prerequisite.first}' was BLOCKED (${prerequisite.second})",
            )
            return
        }
        try {
            step.body()
            results += StepResult(step.id, "PASS", "")
        } catch (blocked: UiBlocked) {
            results += StepResult(step.id, "BLOCKED", blocked.reason)
            blockedPrerequisite = step.id to blocked.reason
        } catch (error: Throwable) {
            val type = error::class.qualifiedName ?: error::class.simpleName ?: "Throwable"
            results += StepResult(step.id, "FAIL", "$type: ${error.message ?: error.toString()}")
            blockedPrerequisite = step.id to "failed"

        }
    }

    private fun notificationTexts(): List<String> =
        runCatching {
            robot
                .findAll<ComponentFixture>(byXpath("//div[@class='JEditorPane']"))
                .mapNotNull { fixture ->
                    runCatching { fixture.callJs<String>("component.getText() || ''", true) }.getOrNull()
                }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // Robot helpers
    // ------------------------------------------------------------------

    private fun exists(xpath: String): Boolean = robot.findAll<ComponentFixture>(byXpath(xpath)).isNotEmpty()

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

    private fun researchStatusHeadline(): String {
        val script =
            """
            const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            const sb = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project);
            sb.updateWidget("me.code4me.research.status");
            const widget = sb.getWidget("me.code4me.research.status");
            String(widget.getPresentation().getText())
            """.trimIndent()
        return robot.callJs(script, true)
    }

    private fun researchStatusTooltip(): String {
        val script =
            """
            const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            const widget = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
                .getWidget("me.code4me.research.status");
            String(widget.getPresentation().getTooltipText())
            """.trimIndent()
        return runCatching { robot.callJs<String>(script, true) }.getOrElse { "<tooltip failed: ${it.message}>" }
    }

    /**
     * Drive a qualifying IDE activity signal (open + touch a source file) so an
     * activated research session transitions to collecting. The plugin only
     * starts the session on real IDE activity, mirroring a participant using the
     * IDE; it is metadata-only.
     */
    private fun triggerIdeActivity() {
        val script =
            """
            com.intellij.openapi.application.WriteIntentReadAction.run(new java.lang.Runnable({ run: function() {
            const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            const basePath = project.getBasePath();
            const dir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(basePath);
            if (dir === null) { throw new Error("project base dir not found: " + basePath); }
            dir.refresh(false, false);
            const file = dir.findChild("Activity.kt");
            if (file === null) { throw new Error("Activity.kt probe file is missing"); }
            const manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            manager.closeFile(file);
            manager.openFile(file, true);
            }}));
            true
            """.trimIndent()
        robot.callJs<Boolean>(script, true)
    }

    /** Diagnostic only: the live participant state rendered by the plugin. */
    private fun researchStateDump(): String {
        val script =
            """
            const pd = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId(${jsQuote(PLUGIN_ID)}));
            const cl = pd.getPluginClassLoader();
            const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            const cls = cl.loadClass("me.code4me.research.session.ResearchSessionService");
            const companion = cls.getField("Companion").get(null);
            const svc = companion.getClass()
                .getMethod("getInstance", com.intellij.openapi.project.Project)
                .invoke(companion, project);
            String(svc.getClass().getMethod("state").invoke(svc))
            """.trimIndent()
        return runCatching { robot.callJs<String>(script, true) }.getOrElse { "<state dump failed: ${it.message}>" }
    }

    private fun openCode4MeSettings() {
        // Open asynchronously. `ShowSettingsUtil.showSettingsDialog` can create a
        // *modal* dialog (depending on the modality state at call time); calling
        // it synchronously on the EDT would keep the robot-server's single call
        // thread busy until the dialog closes, starving every later request.
        // Scheduling it with invokeLater returns immediately, and the modal
        // dialog's nested event loop still serves subsequent robot calls.
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

    private fun scheduleJoinAction() {
        val script =
            """
            const am = com.intellij.openapi.actionSystem.ActionManager.getInstance();
            const action = am.getAction("me.code4me.research.actions.JoinResearchStudyAction");
            if (action === null) { throw new Error("Join Research Study action is not registered"); }
            const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            const dc = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project);
            const event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "ToolsMenu", dc);
            const runnable = new java.lang.Runnable({ run: function() { action.actionPerformed(event); } });
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(runnable);
            true
            """.trimIndent()
        robot.callJs<Boolean>(script, true)
    }

    companion object {
        private const val PLUGIN_ID = "me.code4me"
        private const val AI_PLUGIN_ID = "com.intellij.ml.llm"
        private const val ACP_ENTRY_NAME = "Code4Me Research Proxy"
        private const val ACTIVE_HEADLINE = "Research: active"

        // Stable selectors, all verified against the 2026.2 sandbox DOM.
        private const val STRIPE_BUTTON_XPATH =
            "//div[@class='SquareStripeButton' and @accessiblename='Code4Me V2']"
        private const val SETTINGS_TREE_XPATH = "//div[@class='MyTree']"
        private const val CREDENTIALS_TITLE_XPATH =
            "//div[@class='JBLabel' and @accessiblename='Credential-based Authentication']"
        private const val EMAIL_LABEL_XPATH = "//div[@class='JLabel' and @visible_text='Email:']"
        private const val PASSWORD_LABEL_XPATH = "//div[@class='JLabel' and @visible_text='Password:']"
        private const val EMAIL_FIELD_XPATH = "//div[@class='JBTextField']"
        private const val PASSWORD_FIELD_XPATH = "//div[@class='JBPasswordField']"
        private const val LOGIN_BUTTON_XPATH = "//div[@class='JButton' and @accessiblename='Login']"
        private const val SETTINGS_CANCEL_XPATH =
            "//div[@class='FloatDialog' and @accessiblename='Settings']" +
                "//div[@class='JButton' and @accessiblename='Cancel']"
        private const val JOIN_DIALOG_XPATH =
            "//div[@class='MyDialog' and @accessiblename='Join Research Study']"
        private const val JOIN_INPUT_XPATH = "$JOIN_DIALOG_XPATH//div[@class='JTextField']"
        private const val JOIN_OK_XPATH = "$JOIN_DIALOG_XPATH//div[@class='JButton' and @accessiblename='OK']"

        private lateinit var robot: RemoteRobot
        private lateinit var robotUrl: String
        private lateinit var baseUrl: String
        private lateinit var email: String
        private lateinit var password: String
        private lateinit var joinCode: String

        private lateinit var acpPath: Path
        private var acpBytes: ByteArray? = null
        private var acpMtime: FileTime? = null
        private var acpExisted: Boolean = false

        private val results = mutableListOf<StepResult>()
        private var blockedPrerequisite: Pair<String, String>? = null

        private data class StepResult(val id: String, val status: String, val reason: String)

        @BeforeAll
        @JvmStatic
        fun setUp() {
            baseUrl = env("CODE4ME_E2E_BASE_URL")
            email = env("CODE4ME_E2E_EMAIL")
            password = env("CODE4ME_E2E_PASSWORD")
            joinCode = System.getenv("CODE4ME_E2E_JOIN_CODE")?.trim().orEmpty()
            robotUrl =
                System.getenv("CODE4ME_UI_ROBOT_URL")?.trim().orEmpty()
                    .ifEmpty { "http://localhost:8082" }

            acpPath = Path.of(env("CODE4ME_UI_HOME"), ".jetbrains", "acp.json")

            waitForRobot(180_000)
            robot = RemoteRobot(robotUrl)
            // Configure the plugin *before* a project is opened: the research
            // session service is a project service whose manager captures the
            // ConfigService base URL when the project's startup activity first
            // touches it. Setting the server afterwards would leave the research
            // layer pointed at plugin.conf's default host.
            configurePluginServer()
            ensureProjectOpen()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            writeResults()
            val failed = results.filter { it.status != "PASS" }
            if (failed.isNotEmpty()) {
                throw AssertionError(
                    "Code4Me UI navigation FAILED: " +
                        failed.joinToString("; ") { "${it.id}: ${it.reason}" },
                )
            }
        }

        private fun env(name: String): String =
            System.getenv(name)?.trim().orEmpty().ifEmpty {
                throw IllegalStateException("required environment variable $name is not set")
            }

        /**
         * Locate the managed-auth bridge directory inside the harness home.
         *
         * The IDE writes it under its system path (`<system>/code4me/bridges`),
         * which the harness redirects into [CODE4ME_UI_HOME]; the exact layout
         * depends on the OS (macOS nests it under Library/Caches/JetBrains).
         */
        private fun bridgesDirectory(): Path {
            val home = Path.of(env("CODE4ME_UI_HOME"))
            val expected = home.resolve("system").resolve("code4me").resolve("bridges")
            if (Files.isDirectory(expected)) return expected
            Files.walk(home, 7).use { stream ->
                return stream
                    .filter { Files.isDirectory(it) }
                    .filter { it.endsWith(Path.of("code4me", "bridges")) }
                    .findFirst()
                    .orElse(expected)
            }
        }

        private fun isPluginLoadedScript(pluginId: String): String =
            "com.intellij.ide.plugins.PluginManagerCore.isPluginInstalled(" +
                "com.intellij.openapi.extensions.PluginId.getId(${jsQuote(pluginId)}))"

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
            throw AssertionError(
                "$message (after ${timeoutMs / 1000}s)${last?.let { "; last error: ${it.message}" } ?: ""}",
            )
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

        /**
         * Open a throwaway project (trusted up front, so no trust dialog blocks
         * the EDT). The plugin's project-scoped surfaces (tool window, status
         * bar, research session) only exist with a project open.
         */
        private fun ensureProjectOpen() {
            val alreadyOpen =
                robot.callJs<Boolean>(
                    "com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length > 0",
                    true,
                )
            if (!alreadyOpen) {
                val projectDir =
                    System.getenv("CODE4ME_UI_PROJECT_DIR")?.trim().orEmpty()
                        .ifEmpty { Files.createTempDirectory("code4me-ui-project").toString() }
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

            // Wait for smart mode before opening Settings. Calling ShowSettings
            // while a modal startup progress dialog owns the modality makes the
            // IDE create a *modal* settings dialog, which blocks the EDT (and the
            // robot-server's single call thread) until it is closed.
            waitFor(240_000, "the test project never reached smart mode") {
                robot.callJs<Boolean>(
                    """
                    const project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                    !com.intellij.openapi.project.DumbService.isDumb(project)
                    """.trimIndent(),
                    true,
                )
            }

            // A metadata-only probe file the suite opens to emit a qualifying IDE
            // activity signal (the research session only starts collecting on real
            // IDE activity).
            runCatching {
                val basePath = robot.callJs<String>(
                    "com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0].getBasePath()",
                    true,
                )
                if (basePath.isNotBlank()) {
                    val probe = Path.of(basePath, "Activity.kt")
                    if (!Files.exists(probe)) {
                        Files.writeString(probe, "fun activityProbe(): Int = 1\n")
                    }
                }
            }
        }

        /**
         * Point the plugin at the disposable backend.
         *
         * Two services must agree. `AppService` handles login; the research layer
         * resolves its base URL from `ConfigService` (`plugin.conf`'s
         * `http://localhost:8008`), and the plugin's own "Advanced Server Options"
         * UI only updates `AppService`. The fixture test
         * [integration.LiveStudyWorkflowPluginTest] sets `AppService` directly; the
         * UI suite sets both so a real join/activation reaches the disposable
         * stack. This is a precondition, not a UI assertion of its own.
         */
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
            check(resolved.startsWith(baseUrl)) {
                "could not point the plugin at $baseUrl (it reports $resolved)"
            }
        }

        private fun writeResults() {
            val overall =
                when {
                    results.isEmpty() -> "UNKNOWN"
                    results.any { it.status == "FAIL" } -> "FAIL"
                    results.any { it.status == "BLOCKED" } -> "BLOCKED"
                    else -> "PASS"
                }
            val json =
                buildString {
                    append("{\n")
                    append("  \"overall\": ").append(jsonString(overall)).append(",\n")
                    append("  \"robot_url\": ").append(jsonString(robotUrl)).append(",\n")
                    append("  \"base_url\": ")
                        .append(jsonString(if (::baseUrl.isInitialized) baseUrl else ""))
                        .append(",\n")
                    append("  \"steps\": [\n")
                    results.forEachIndexed { index, step ->
                        append("    { \"id\": ").append(jsonString(step.id))
                            .append(", \"status\": ").append(jsonString(step.status))
                            .append(", \"reason\": ").append(jsonString(step.reason)).append(" }")
                        if (index != results.lastIndex) append(",")
                        append("\n")
                    }
                    append("  ]\n")
                    append("}\n")
                }
            val target = System.getenv("CODE4ME_UI_RESULT_FILE")?.trim().orEmpty()
            val path = if (target.isNotEmpty()) Path.of(target) else Path.of("build", "ui-test-results.json")
            runCatching {
                path.parent?.let { Files.createDirectories(it) }
                Files.writeString(path, json)
            }.onFailure { System.err.println("WARNING: could not write $path: $it") }
            println("ui-test-results: $json")
        }

        private fun jsonString(value: String): String {
            val escaped =
                value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
            return "\"$escaped\""
        }

        /** Kotlin single-quoted JavaScript string literal. */
        private fun jsQuote(value: String): String =
            "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }
}
