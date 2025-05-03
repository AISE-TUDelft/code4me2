package me.code4me

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

/**
 * Path to the resource bundle containing localized messages.
 */
@NonNls
private const val BUNDLE = "messages.MyBundle"

/**
 * Resource bundle for internationalization of the Code4Me plugin.
 * 
 * This object provides access to localized messages defined in the resource bundle.
 * It extends [DynamicBundle] to support dynamic loading of messages based on the
 * current locale.
 */
object MyBundle : DynamicBundle(BUNDLE) {

    /**
     * Retrieves a localized message from the resource bundle.
     * 
     * @param key The key of the message in the resource bundle.
     * @param params Optional parameters to be substituted into the message.
     * @return The localized message with parameters substituted.
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getMessage(key, *params)

    /**
     * Creates a lazy message pointer that will be evaluated only when needed.
     * 
     * This is useful for messages that might be expensive to format or are not
     * always needed.
     * 
     * @param key The key of the message in the resource bundle.
     * @param params Optional parameters to be substituted into the message.
     * @return A lazy message pointer that will be evaluated when toString() is called.
     */
    @Suppress("unused")
    @JvmStatic
    fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getLazyMessage(key, *params)
}
