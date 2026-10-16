package me.code4me.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ComponentManagerEx

/**
 * Test-only helpers for swapping application services with fakes.
 *
 * `ServiceContainerUtil` from the platform test framework is not resolvable on
 * this module's test compile classpath, so these helpers use the platform's own
 * [ComponentManagerEx.replaceRegularServiceInstance] and return the previous
 * instance so a test can restore it in `tearDown`.
 */
internal fun <T : Any> replaceApplicationService(
    serviceInterface: Class<T>,
    instance: T,
): T {
    val application = ApplicationManager.getApplication() as ComponentManagerEx
    val original = application.getService(serviceInterface)
    application.replaceRegularServiceInstance(serviceInterface, instance)
    return original
}

internal fun <T : Any> restoreApplicationService(
    serviceInterface: Class<T>,
    original: T,
) {
    (ApplicationManager.getApplication() as ComponentManagerEx)
        .replaceRegularServiceInstance(serviceInterface, original)
}
