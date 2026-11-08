package com.chachaallim.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

class GleoReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADD_REMINDER) return
        val result = try {
            val raw = intent.getStringExtra("ACTION_PARAM")
                ?: throw IllegalArgumentException("ACTION_PARAM missing")
            val json = JSONObject(raw)
            val parameters = json.optJSONObject("actionParameters") ?: json
            val command = parameters.optString("command").trim()
            when (ReminderStore(context).register(command, ReminderStore.SOURCE_GLEO, ReminderStore.STATE_NEW)) {
                ReminderStore.Registration.UNSUPPORTED -> "fail" to "Unsupported reminder command."
                ReminderStore.Registration.ALREADY_PENDING -> "fail" to "A reminder is already pending."
                ReminderStore.Registration.SAVE_FAILED -> "fail" to "Could not save the reminder."
                ReminderStore.Registration.REGISTERED -> {
                    Log.i(TAG, "ADD_REMINDER stored; awaiting vehicle gear transition")
                    "success" to "Reminder registered for the next drive-to-park transition."
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ADD_REMINDER rejected: ${e.javaClass.simpleName}", e)
            "fail" to "Invalid reminder parameters."
        }
        val response = JSONObject()
            .put("functionName", ACTION_ADD_REMINDER)
            .put("status", result.first)
            .put("message", result.second)
        context.sendBroadcast(Intent(ACTION_RESPONSE).apply {
            putExtra("result", result.first)
            putExtra("packageName", context.packageName)
            putExtra("response", response.toString())
        })
        Log.i(TAG, "ADD_REMINDER callback status=${result.first}")
    }

    companion object {
        const val ACTION_ADD_REMINDER = "ADD_REMINDER"
        const val PREFS = ReminderStore.PREFS
        const val KEY_COMMAND = ReminderStore.KEY_COMMAND
        const val SUPPORTED_COMMAND = ReminderStore.SUPPORTED_COMMAND
        private const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        private const val TAG = "ChachaGleo"
    }
}
