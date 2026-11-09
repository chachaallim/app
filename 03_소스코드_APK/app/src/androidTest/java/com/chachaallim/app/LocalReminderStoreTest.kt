package com.chachaallim.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalReminderStoreTest {
    @Test
    fun replacesOnlyTheExpectedReminderAfterAValidVoiceRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReminderStore(context)
        val state = "WAITING_FOR_D"
        val source = ReminderStore.SOURCE_STT
        val newCommand = "내릴 때 우산 챙기라고 알려줘"
        store.complete()
        try {
            assertEquals(
                ReminderStore.Registration.REGISTERED,
                store.register("내릴 때 노트북 챙기라고 알려줘", source, state)
            )
            val original = checkNotNull(store.load())

            assertEquals(ReminderStore.Registration.ALREADY_PENDING, store.register(newCommand, source, state))
            assertEquals(
                ReminderStore.Registration.UNSUPPORTED,
                store.register("내일 우산 챙기라고 알려줘", source, state, original)
            )
            assertEquals(original, store.load())

            assertEquals(
                ReminderStore.Registration.UNSUPPORTED,
                store.register("내릴 때 그거만 챙겨줘", source, state, original)
            )
            assertEquals(original, store.load())

            assertTrue(store.updateState("WAITING_FOR_P"))
            assertEquals(ReminderStore.Registration.SAVE_FAILED, store.register(newCommand, source, state, original))
            assertEquals(original.copy(state = "WAITING_FOR_P"), store.load())

            assertTrue(store.updateState(state))
            assertEquals(ReminderStore.Registration.REGISTERED, store.register(newCommand, source, state, original))
            assertEquals("우산 챙기세요.", ReminderStore(context).load()?.message)
            assertEquals(state, store.load()?.state)
        } finally {
            store.complete()
        }
    }

    @Test
    fun savesAndRestoresTheMessageProducedFromSpeech() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReminderStore(context)
        val command = "내릴 때 미팅 약속을 잊지 말라고 알려줘"
        val message = "미팅 약속을 잊지 마세요."
        store.complete()
        try {
            assertEquals(
                ReminderStore.Registration.UNSUPPORTED,
                store.register("내일 우산 챙겨줘", ReminderStore.SOURCE_STT, "WAITING_FOR_D")
            )
            assertNull(store.load())
            assertEquals(
                ReminderStore.Registration.REGISTERED,
                store.register(command, ReminderStore.SOURCE_STT, "WAITING_FOR_D")
            )
            assertTrue(store.updateState("WAITING_FOR_P"))
            val restored = ReminderStore(context).load()
            assertEquals(command, restored?.original)
            assertEquals(message, restored?.message)
            assertEquals("WAITING_FOR_P", restored?.state)
            assertTrue(store.complete())
            assertNull(store.load())
        } finally {
            store.complete()
        }
    }
}
