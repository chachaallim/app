package com.chachaallim.app

import android.content.Context

/** Stores the one reminder supported by this PoC, independently of its input path. */
class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Pending(val original: String, val message: String, val source: String, val state: String)
    enum class Registration { REGISTERED, UNSUPPORTED, ALREADY_PENDING, SAVE_FAILED }

    fun load(): Pending? {
        val command = prefs.getString(KEY_COMMAND, null) ?: return null
        return Pending(
            command,
            REMINDER_MESSAGE,
            prefs.getString(KEY_SOURCE, SOURCE_GLEO) ?: SOURCE_GLEO,
            prefs.getString(KEY_STATE, STATE_NEW) ?: STATE_NEW
        )
    }

    fun register(command: String, source: String, state: String): Registration {
        if (command != SUPPORTED_COMMAND) return Registration.UNSUPPORTED
        if (load() != null) return Registration.ALREADY_PENDING
        val saved = prefs.edit()
            .putString(KEY_COMMAND, command)
            .putString(KEY_SOURCE, source)
            .putString(KEY_STATE, state)
            .commit()
        return if (saved) Registration.REGISTERED else Registration.SAVE_FAILED
    }

    fun updateState(state: String): Boolean {
        if (load() == null) return false
        return prefs.edit().putString(KEY_STATE, state).commit()
    }

    fun complete(): Boolean = prefs.edit()
        .remove(KEY_COMMAND)
        .remove(KEY_SOURCE)
        .remove(KEY_STATE)
        .commit()

    companion object {
        const val PREFS = "gleo_reminder"
        const val KEY_COMMAND = "command"
        const val SOURCE_GLEO = "gleo"
        const val SOURCE_TEXT = "text"
        const val SOURCE_STT = "stt"
        const val STATE_NEW = "NEW"
        const val SUPPORTED_COMMAND = "내릴 때 노트북 챙기라고 알려줘"
        const val REMINDER_MESSAGE = "내리기 전에 노트북을 챙겨 주세요."
        private const val KEY_SOURCE = "source"
        private const val KEY_STATE = "state"
    }
}
