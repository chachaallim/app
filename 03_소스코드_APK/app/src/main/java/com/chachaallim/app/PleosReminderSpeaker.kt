package com.chachaallim.app

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/** Plays one spoken confirmation for each successful reminder state transition. */
class PleosReminderSpeaker(
    context: Context,
    private val onPlaybackFinished: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            Log.i(TAG, "audio focus changed=$change")
            if (change < 0 && focusHeld) {
                pending = null
                try {
                    sdk?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "stop after focus loss failed", e)
                }
                finishCurrent("focus lost=$change")
            }
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(focusListener, mainHandler)
        .setWillPauseWhenDucked(true)
        .build()

    private data class Speech(val eventId: String, val kind: String, val text: String)

    private var active = false
    private var sdk: TextToSpeech? = null
    private var listener: EventListener? = null
    private var initialized = false
    private var generation = 0L
    private var pending: Speech? = null
    private var current: Speech? = null
    private var focusHeld = false

    fun start() {
        active = true
    }

    fun announce(eventId: String, kind: String, text: String) {
        if (!active) {
            Log.w(TAG, "announcement skipped: speaker stopped kind=$kind")
            return
        }
        if (current?.eventId == eventId || pending?.eventId == eventId) {
            Log.i(TAG, "duplicate announcement ignored id=$eventId")
            return
        }
        pending = Speech(eventId, kind, text)
        Log.i(TAG, "announcement queued id=$eventId kind=$kind")
        when {
            sdk == null -> initialize()
            initialized && current == null -> speakPending()
            initialized -> Log.i(TAG, "announcement waiting for active speech to finish")
        }
    }

    private fun initialize() {
        if (appContext.checkSelfPermission(TTS_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "TTS permission denied: $TTS_PERMISSION")
            pending = null
            return
        }
        try {
            val created = TextToSpeech(appContext, Mode.ON_DEVICE)
            sdk = created
            val session = ++generation
            Log.i(TAG, "SDK initializing session=$session mode=ON_DEVICE")
            created.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    mainHandler.post {
                        if (!isCurrent(created, session)) return@post
                        try {
                            val eventListener = object : EventListener {
                                override fun onReady() {
                                    Log.i(TAG, "SDK ready session=$session")
                                }

                                override fun onStart() {
                                    Log.i(TAG, "playback started session=$session kind=${current?.kind}")
                                }

                                override fun onUpdatedRms(rms: Double) = Unit

                                override fun onDone() {
                                    mainHandler.post {
                                        if (isCurrent(created, session)) finishCurrent("done")
                                    }
                                }

                                override fun onStop() {
                                    mainHandler.post {
                                        if (isCurrent(created, session)) finishCurrent("stopped")
                                    }
                                }

                                override fun onError(errorType: ErrorType) {
                                    mainHandler.post {
                                        if (isCurrent(created, session)) {
                                            Log.e(TAG, "playback error session=$session error=$errorType")
                                            finishCurrent("error=$errorType")
                                        }
                                    }
                                }
                            }
                            listener = eventListener
                            created.addEventListener(eventListener)
                            created.setLocale(Locale.KOREA)
                            created.setAudioAttributes(audioAttributes)
                            initialized = true
                            Log.i(TAG, "SDK initialized session=$session locale=ko-KR")
                            speakPending()
                        } catch (e: Exception) {
                            Log.e(TAG, "SDK configuration failed session=$session", e)
                            releaseSdk()
                        }
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    mainHandler.post {
                        if (isCurrent(created, session)) {
                            Log.e(TAG, "SDK initialization failed session=$session error=$errorType")
                            releaseSdk()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "SDK creation failed", e)
            releaseSdk()
        }
    }

    private fun speakPending() {
        if (!active || !initialized || current != null) return
        val request = pending ?: return
        val engine = sdk ?: return
        pending = null
        val focusResult = try {
            audioManager.requestAudioFocus(focusRequest)
        } catch (e: Exception) {
            Log.e(TAG, "audio focus request failed kind=${request.kind}", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        Log.i(TAG, "audio focus result=$focusResult kind=${request.kind}")
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        focusHeld = true
        current = request
        try {
            engine.speak(request.text)
            Log.i(TAG, "speak submitted id=${request.eventId} kind=${request.kind}")
        } catch (e: Exception) {
            Log.e(TAG, "speak failed id=${request.eventId}", e)
            finishCurrent("speak exception")
        }
    }

    private fun finishCurrent(reason: String) {
        val finished = current
        current = null
        if (finished != null) Log.i(TAG, "playback finished id=${finished.eventId} reason=$reason")
        abandonFocus()
        if (active) speakPending()
        if (finished != null) onPlaybackFinished()
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        Log.i(TAG, "audio focus abandoned")
    }

    private fun isCurrent(engine: TextToSpeech, session: Long): Boolean =
        active && sdk === engine && generation == session

    fun stop() {
        active = false
        releaseSdk()
    }

    private fun releaseSdk() {
        val engine = sdk
        sdk = null
        generation++
        pending = null
        current = null
        initialized = false
        if (engine != null) {
            try {
                engine.stop()
            } catch (e: Exception) {
                Log.e(TAG, "SDK stop failed", e)
            }
            try {
                listener?.let(engine::removeEventListener)
            } catch (e: Exception) {
                Log.e(TAG, "SDK listener removal failed", e)
            }
            try {
                engine.release()
                Log.i(TAG, "SDK released")
            } catch (e: Exception) {
                Log.e(TAG, "SDK release failed", e)
            }
        }
        listener = null
        abandonFocus()
    }

    private companion object {
        private const val TAG = "ChachaTts"
        private const val TTS_PERMISSION = "pleos.car.permission.TTS_SERVICE"
    }
}
