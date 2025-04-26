package me.code4me.components.settings.fields

interface StateValueField<T> {
    fun getState(): FieldStates

    fun setState(state: FieldStates)

    fun getValue(): T

    fun setValue(value: T)
}

typealias TextualStateValueField = StateValueField<String>
typealias BooleanStateValueField = StateValueField<Boolean>
typealias IntStateValueField = StateValueField<Int>