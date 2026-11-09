package com.chachaallim.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

class GleoReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADD_REMINDER) return
        val (status, message) = try {
            handleRequest(ReminderStore(context), intent)
        } catch (_: JSONException) {
            "fail" to "Invalid reminder parameters."
        }
        val response = JSONObject()
            .put("functionName", ACTION_ADD_REMINDER)
            .put("status", status)
            .put("message", message)
        context.sendBroadcast(Intent(ACTION_RESPONSE).apply {
            setPackage(GLEO_PACKAGE)
            putExtra("result", status)
            putExtra("packageName", context.packageName)
            putExtra("response", response.toString())
        })
        Log.i(TAG, "ADD_REMINDER callback status=$status")
    }

    private fun handleRequest(store: ReminderStore, intent: Intent): Pair<String, String> {
        val payload = intent.getStringExtra("ACTION_PARAM")?.let(::JSONObject)
        val parameters = payload?.optJSONObject("actionParameters") ?: payload
        val command = intent.parameter(parameters, "command")
        val trigger = intent.parameter(parameters, "trigger")
        val message = intent.parameter(parameters, "message")

        val registration = when {
            trigger == null && message == null && command != null ->
                store.register(command, ReminderStore.SOURCE_GLEO, ReminderStore.STATE_NEW)
            trigger == "next_park" && !message.isNullOrBlank() ->
                store.registerGleo(message, command)
            trigger == "unsupported" ->
                return "fail" to "Only reminders for the next parking after driving are supported."
            trigger.isNullOrBlank() || trigger == "unclear" ->
                return "asking" to "Please confirm whether to remind you the next time you park."
            trigger == "next_park" ->
                return "asking" to "What would you like to be reminded to do?"
            else -> return "fail" to "Invalid reminder timing."
        }
        return when (registration) {
            ReminderStore.Registration.UNSUPPORTED -> "fail" to "Unsupported reminder request."
            ReminderStore.Registration.ALREADY_PENDING -> "fail" to "A reminder is already pending."
            ReminderStore.Registration.SAVE_FAILED -> "fail" to "Could not save the reminder."
            ReminderStore.Registration.REGISTERED ->
                "success" to "Reminder registered for the next parking after driving."
        }
    }

    private fun Intent.parameter(parameters: JSONObject?, name: String): String? =
        if (hasExtra(name)) getStringExtra(name)?.trim()
        else (parameters?.opt(name) as? String)?.trim()

    companion object {
        const val ACTION_ADD_REMINDER = "ADD_REMINDER"
        const val PREFS = ReminderStore.PREFS
        const val KEY_COMMAND = ReminderStore.KEY_COMMAND
        private const val GLEO_PACKAGE = "ai.umos.ambientai"
        private const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        private const val TAG = "ChachaGleo"
    }
}
