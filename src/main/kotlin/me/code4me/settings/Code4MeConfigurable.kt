package me.code4me.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.NlsContexts
import me.code4me.components.settings.Code4MeConfigurableComponent
import javax.swing.JComponent

class Code4MeConfigurable : Configurable {
    private var code4MeConfigurableComponent: Code4MeConfigurableComponent? = null

    override fun getDisplayName(): @NlsContexts.ConfigurableName String? {
        return "Code4Me V2 Settings"
    }

    override fun createComponent(): JComponent? {
        code4MeConfigurableComponent = Code4MeConfigurableComponent()
        return code4MeConfigurableComponent?.getPanel()
    }

    override fun isModified(): Boolean {
        return code4MeConfigurableComponent?.isModified() ?: false
    }

    override fun apply() {
        code4MeConfigurableComponent?.save()
    }

    override fun reset() {
        code4MeConfigurableComponent?.reset()
    }

    override fun disposeUIResources() {
        code4MeConfigurableComponent = null
    }
}