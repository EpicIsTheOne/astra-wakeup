package com.astra.wakeup.brain.actions

sealed class Action {
    data class Speak(val text: String) : Action()
    data class ShowNotification(val text: String) : Action()
    data class ChangePersonality(val mode: String) : Action()
    data class Log(val level: String, val message: String) : Action()
}
