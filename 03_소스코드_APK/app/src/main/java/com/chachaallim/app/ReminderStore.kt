package com.chachaallim.app

import android.content.Context

/** Stores a pending reminder independently of its input path. */
class ReminderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Pending(val original: String, val item: String?, val message: String, val source: String, val state: String)
    enum class Registration { REGISTERED, UNSUPPORTED, ALREADY_PENDING, SAVE_FAILED }

    fun load(): Pending? {
        val command = prefs.getString(KEY_COMMAND, null) ?: return null
        val message = prefs.getString(KEY_MESSAGE, null) ?: prefs.getString(KEY_GLEO_MESSAGE, null)
        val item = if (message == null) ReminderCommand.itemFrom(command) ?: return null else null
        return Pending(
            command,
            item,
            message ?: context.getString(R.string.reminder_message, item),
            prefs.getString(KEY_SOURCE, SOURCE_GLEO) ?: SOURCE_GLEO,
            prefs.getString(KEY_STATE, STATE_NEW) ?: STATE_NEW
        )
    }

    fun register(
        command: String,
        source: String,
        state: String,
        replacement: Pending? = null
    ): Registration {
        if (command.length > MAX_TEXT_LENGTH || !ReminderCommand.supportsParkingTime(command) ||
            ReminderCommand.hasUnresolvedReference(command)
        ) {
            return Registration.UNSUPPORTED
        }
        val message = if (source == SOURCE_GLEO) null else ReminderCommand.parkingMessage(command)
        if (message == null && ReminderCommand.itemFrom(command) == null) return Registration.UNSUPPORTED
        return save(command, source, state, message, replacement)
    }

    fun registerGleo(message: String, original: String?): Registration {
        if (message.isBlank() || message.length > MAX_TEXT_LENGTH) return Registration.UNSUPPORTED
        val command = original?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT_LENGTH } ?: message
        return save(command, SOURCE_GLEO, STATE_NEW, message = message)
    }

    private fun save(
        command: String,
        source: String,
        state: String,
        message: String?,
        replacement: Pending? = null
    ): Registration {
        if (replacement == null && prefs.contains(KEY_COMMAND)) return Registration.ALREADY_PENDING
        if (replacement != null && load() != replacement) return Registration.SAVE_FAILED

        if (write(command, source, state, message)) return Registration.REGISTERED
        if (replacement != null) {
            // A failed commit can still change the in-memory preferences.
            write(replacement.original, replacement.source, replacement.state, replacement.message)
        }
        return Registration.SAVE_FAILED
    }

    private fun write(command: String, source: String, state: String, message: String?): Boolean =
        prefs.edit()
            .putString(KEY_COMMAND, command)
            .putString(KEY_MESSAGE, message)
            .remove(KEY_GLEO_MESSAGE)
            .putString(KEY_SOURCE, source)
            .putString(KEY_STATE, state)
            .commit()

    fun updateState(state: String): Boolean {
        if (load() == null) return false
        return prefs.edit().putString(KEY_STATE, state).commit()
    }

    fun cancelPending(expected: Pending): Boolean {
        if (TriggerState.from(expected.state) == TriggerState.SHOWING || load() != expected) return false
        if (complete()) return true
        // A failed commit can still change the in-memory preferences.
        write(expected.original, expected.source, expected.state, expected.message)
        return false
    }

    fun complete(): Boolean = prefs.edit()
        .remove(KEY_COMMAND)
        .remove(KEY_MESSAGE)
        .remove(KEY_GLEO_MESSAGE)
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
        const val EXAMPLE_COMMAND = "내릴 때 노트북 챙기라고 알려줘"
        private const val KEY_MESSAGE = "reminder_message"
        private const val KEY_GLEO_MESSAGE = "gleo_message"
        private const val MAX_TEXT_LENGTH = 240
        private const val KEY_SOURCE = "source"
        internal const val KEY_STATE = "state"
    }
}
