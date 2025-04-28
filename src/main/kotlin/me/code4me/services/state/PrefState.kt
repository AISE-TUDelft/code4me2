package me.code4me.services.state

import com.intellij.openapi.components.*

const val PREF_STATE_NAME = "me.code4me.state.auth"

fun getPrefState(): PrefSettings {
    return service<PrefState>().state
}

@Service
@State(
    name = PREF_STATE_NAME,
    storages = [Storage("code4me-pref.xml")]
)
class PrefState : SimplePersistentStateComponent<PrefSettings>(PrefSettings())

class PrefSettings : BaseState() {
    var storeCompletions by property(false)
    var storeContext by property(false)
}