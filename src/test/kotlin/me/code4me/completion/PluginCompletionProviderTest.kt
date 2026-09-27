package me.code4me.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProcess
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ProcessingContext
import kotlinx.coroutines.runBlocking
import me.code4me.api.generated.model.ResponseCompletionResponseData
import me.code4me.api.generated.model.ResponseCompletionResponseDataCompletionsInner
import me.code4me.services.app.AppService
import me.code4me.services.modules.manager.ModuleManager
import me.code4me.services.state.AuthSettings
import me.code4me.services.state.AuthState
import me.code4me.services.state.PrefState
import me.code4me.services.state.getAuthState
import me.code4me.services.state.getPrefState
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.lang.reflect.InvocationTargetException
import java.math.BigDecimal
import java.util.UUID

/**
 * Pins the dropdown-completion provider contract: no network call for an
 * unauthenticated user, no duplicate dropdown entries while inline (ghost text)
 * completions are enabled, and the API response is surfaced as a Code4Me lookup
 * element — with an explicit fallback when the API returns nothing.
 */
class PluginCompletionProviderTest : BasePlatformTestCase() {
    private lateinit var provider: PluginCompletionProvider
    private val authSettings = AuthSettings(null, null, null) { }
    private lateinit var appService: AppService
    private lateinit var originalAuthState: AuthState
    private lateinit var originalModuleManager: ModuleManager
    private lateinit var originalAppService: AppService

    override fun setUp() {
        super.setUp()
        provider = PluginCompletionProvider()

        val authState = mock<AuthState>()
        whenever(authState.state).thenReturn(authSettings)
        originalAuthState = replaceApplicationService(AuthState::class.java, authState)

        val moduleManager = mock<ModuleManager>()
        whenever(moduleManager.collectData(any())).thenReturn(emptyList())
        originalModuleManager = replaceApplicationService(ModuleManager::class.java, moduleManager)

        appService = mock()
        originalAppService = replaceApplicationService(AppService::class.java, appService)
    }

    override fun tearDown() {
        try {
            if (::originalAppService.isInitialized) {
                restoreApplicationService(AppService::class.java, originalAppService)
            }
            if (::originalModuleManager.isInitialized) {
                restoreApplicationService(ModuleManager::class.java, originalModuleManager)
            }
            if (::originalAuthState.isInitialized) {
                restoreApplicationService(AuthState::class.java, originalAuthState)
            }
            getPrefState().moduleValues.remove(INLINE_PREFERENCE_KEY)
        } finally {
            super.tearDown()
        }
    }

    fun testNoCompletionsAreOfferedWhenNotAuthenticated() {
        val results = mock<CompletionResultSet>()

        addCompletions(results)

        verifyNoInteractions(results)
    }

    fun testDropdownCompletionsAreSkippedWhenInlineCompletionsAreEnabled() {
        getAuthState().setToken("test-token")
        PrefState.setPreferenceValue("CompletionModel", "getCompletionInline", "true")
        val results = mock<CompletionResultSet>()

        addCompletions(results)

        verifyNoInteractions(results)
    }

    fun testApiCompletionsAreAddedToTheDropdown() {
        getAuthState().setToken("test-token")
        PrefState.setPreferenceValue("CompletionModel", "getCompletionInline", "false")
        runBlocking {
            whenever(appService.getInlineCompletion(any(), any(), anyOrNull())).thenReturn(
                ResponseCompletionResponseData(
                    metaQueryId = UUID.randomUUID(),
                    completions = listOf(completion("println(\"hello\")")),
                ),
            )
        }

        val results = mock<CompletionResultSet>()
        addCompletions(results)

        val element = captureSingleElement(results)
        assertEquals("code4me_completion_0", element.lookupString)
        assertEquals("println(\"hello\")", LookupElementPresentation.renderElement(element).itemText)
    }

    fun testFallbackElementIsAddedWhenTheApiReturnsNothing() {
        getAuthState().setToken("test-token")
        PrefState.setPreferenceValue("CompletionModel", "getCompletionInline", "false")
        runBlocking { whenever(appService.getInlineCompletion(any(), any(), anyOrNull())).thenReturn(null) }

        val results = mock<CompletionResultSet>()
        addCompletions(results)

        val element = captureSingleElement(results)
        assertEquals("Code4Me V2 Plugin Completion", element.lookupString)
        assertEquals(
            "Code4Me V2 Error: No completions found",
            LookupElementPresentation.renderElement(element).itemText,
        )
    }

    /**
     * `addCompletions` is protected on the platform's `CompletionProvider`; the
     * provider is final, so the test invokes it reflectively rather than
     * subclassing it.
     */
    private fun addCompletions(results: CompletionResultSet) {
        val method =
            PluginCompletionProvider::class.java.getDeclaredMethod(
                "addCompletions",
                CompletionParameters::class.java,
                ProcessingContext::class.java,
                CompletionResultSet::class.java,
            )
        method.isAccessible = true
        val parameters = completionParameters()
        try {
            // In the IDE the provider runs under a progress indicator; supplying one
            // here keeps runBlockingCancellable's thread-context check satisfied.
            ProgressManager.getInstance().runProcess(
                { method.invoke(provider, parameters, ProcessingContext(), results) },
                ProgressIndicatorBase(),
            )
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun captureSingleElement(results: CompletionResultSet): LookupElement {
        val captor = argumentCaptor<LookupElement>()
        verify(results).addElement(captor.capture())
        return captor.firstValue
    }

    private fun completion(text: String) =
        ResponseCompletionResponseDataCompletionsInner(
            modelId = 1,
            modelName = "deepseek-coder-1.3b",
            completion = text,
            generationTime = 5,
            confidence = BigDecimal.ONE,
        )

    private fun completionParameters(): CompletionParameters {
        myFixture.configureByText("Test.kt", "val greeting = ")
        val offset = myFixture.editor.caretModel.offset
        return CompletionParameters(
            myFixture.file,
            myFixture.file,
            CompletionType.BASIC,
            offset,
            0,
            myFixture.editor,
            mock<CompletionProcess>(),
        )
    }

    private companion object {
        const val INLINE_PREFERENCE_KEY = "CompletionModel.getCompletionInline"
    }
}
