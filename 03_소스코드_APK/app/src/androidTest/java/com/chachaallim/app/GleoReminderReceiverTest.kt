package com.chachaallim.app

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GleoReminderReceiverTest {
    @Test
    fun receiverValidatesAndStoresCommandWithoutExternalExposure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(GleoReminderReceiver.PREFS, Context.MODE_PRIVATE)
        val store = ReminderStore(context)
        store.complete()
        try {
            val receiver = GleoReminderReceiver()
            receiver.onReceive(context, action(JSONObject().put("command", "unknown").toString()))
            assertFalse(prefs.contains(GleoReminderReceiver.KEY_COMMAND))

            val command = "주차하면 서류를 가져가라고 알려줘."
            val supported = JSONObject()
                .put("command", command)
                .toString()
            receiver.onReceive(context, action(supported))
            assertEquals(
                command,
                prefs.getString(GleoReminderReceiver.KEY_COMMAND, null)
            )

            assertEquals("서류", store.load()?.item)
            assertEquals("내리기 전에 서류 챙겨 주세요.", store.load()?.message)
            assertEquals(ReminderStore.SOURCE_GLEO, ReminderStore(context).load()?.source)
            assertEquals(ReminderStore.STATE_NEW, ReminderStore(context).load()?.state)
            receiver.onReceive(context, action(JSONObject().put("command", "unknown").toString()))
            assertEquals(
                command,
                prefs.getString(GleoReminderReceiver.KEY_COMMAND, null)
            )
            assertTrue(store.updateState("WAITING_FOR_P"))
            assertEquals("WAITING_FOR_P", ReminderStore(context).load()?.state)
            assertEquals(
                ReminderStore.Registration.ALREADY_PENDING,
                store.register(ReminderStore.EXAMPLE_COMMAND, ReminderStore.SOURCE_STT, "WAITING_FOR_D")
            )
        } finally {
            store.complete()
        }
    }

    @Test
    fun structuredRequestWaitsForClearTimingAndKeepsTheParkingMessage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReminderStore(context)
        val receiver = GleoReminderReceiver()
        val original = "트렁크에 있는 식혜 내릴 때 챙겨가라 해줘"
        val message = "트렁크에 있는 식혜를 챙겨 가세요."
        store.complete()
        try {
            for (trigger in listOf(null, "unclear", "unsupported", "tomorrow")) {
                receiver.onReceive(context, Intent(GleoReminderReceiver.ACTION_ADD_REMINDER)
                    .putExtra("trigger", trigger)
                    .putExtra("message", message)
                    .putExtra("command", original))
                assertEquals(null, store.load())
            }

            receiver.onReceive(context, Intent(GleoReminderReceiver.ACTION_ADD_REMINDER)
                .putExtra("trigger", "next_park")
                .putExtra("message", message)
                .putExtra("command", original))
            assertEquals(original, store.load()?.original)
            assertEquals(message, store.load()?.message)
            assertEquals(null, store.load()?.item)
            assertEquals(ReminderStore.SOURCE_GLEO, store.load()?.source)
            assertTrue(store.updateState("WAITING_FOR_P"))
            assertEquals(message, ReminderStore(context).load()?.message)

            receiver.onReceive(context, action(JSONObject().put("actionParameters", JSONObject()
                .put("trigger", "next_park").put("message", "우산을 챙겨 가세요.")).toString()))
            assertEquals(message, store.load()?.message)
            assertTrue(store.complete())
            assertEquals(null, store.load())

            receiver.onReceive(context, action(JSONObject().put("actionParameters", JSONObject()
                .put("trigger", "next_park").put("message", message)).toString()))
            assertEquals(message, store.load()?.message)
        } finally {
            store.complete()
        }
    }

    private fun action(parameters: String) = Intent(GleoReminderReceiver.ACTION_ADD_REMINDER)
        .putExtra("ACTION_PARAM", parameters)
}
