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

            val supported = JSONObject()
                .put("command", GleoReminderReceiver.SUPPORTED_COMMAND)
                .toString()
            receiver.onReceive(context, action(supported))
            assertEquals(
                GleoReminderReceiver.SUPPORTED_COMMAND,
                prefs.getString(GleoReminderReceiver.KEY_COMMAND, null)
            )

            assertEquals(ReminderStore.SOURCE_GLEO, ReminderStore(context).load()?.source)
            assertEquals(ReminderStore.STATE_NEW, ReminderStore(context).load()?.state)
            receiver.onReceive(context, action(JSONObject().put("command", "unknown").toString()))
            assertEquals(
                GleoReminderReceiver.SUPPORTED_COMMAND,
                prefs.getString(GleoReminderReceiver.KEY_COMMAND, null)
            )
            assertTrue(store.updateState("WAITING_FOR_P"))
            assertEquals("WAITING_FOR_P", ReminderStore(context).load()?.state)
            assertEquals(
                ReminderStore.Registration.ALREADY_PENDING,
                store.register(ReminderStore.SUPPORTED_COMMAND, ReminderStore.SOURCE_STT, "WAITING_FOR_D")
            )
        } finally {
            store.complete()
        }
    }

    private fun action(parameters: String) = Intent(GleoReminderReceiver.ACTION_ADD_REMINDER)
        .putExtra("ACTION_PARAM", parameters)
}
