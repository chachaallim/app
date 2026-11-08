package com.chachaallim.app

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.car.Car
import android.car.VehicleGear
import android.car.VehicleIgnitionState
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var connection: TextView
    private lateinit var gear: TextView
    private lateinit var raw: TextView
    private lateinit var lastEvent: TextView
    private lateinit var ignitionSupport: TextView
    private lateinit var ignitionState: TextView
    private lateinit var ignitionRaw: TextView
    private lateinit var ignitionEvent: TextView
    private lateinit var commandInput: EditText
    private lateinit var taskStatus: TextView
    private lateinit var reminderCard: LinearLayout
    private lateinit var reminderText: TextView
    private lateinit var sttButton: ImageButton
    private lateinit var sttPulse: View
    private var pulseAnimator: ObjectAnimator? = null
    private lateinit var sttStatus: TextView
    private lateinit var sttResult: TextView
    private lateinit var normalContent: LinearLayout
    private lateinit var voiceArea: LinearLayout
    private lateinit var statusArea: LinearLayout
    private lateinit var stateDescription: TextView
    private lateinit var pendingText: TextView
    private lateinit var pendingHint: TextView
    private lateinit var statusTitle: TextView
    private var feedbackKind = FeedbackKind.NONE
    private var sttHasResult = false
    private var sttError = false
    private var completionReset: Runnable? = null
    private var speechToText: SpeechToText? = null
    private var speechListener: ResultListener? = null
    private val sttHandler = Handler(Looper.getMainLooper())
    private var finalResultTimeout: Runnable? = null
    private var sttSessionCounter = 0L
    @Volatile private var activeSttSessionId = 0L
    @Volatile private var sttState = SttState.IDLE
    private var gearReadSinceStart = false
    @Volatile private var currentGear: Int? = null
    private var pendingCommand: Reminder? = null
    private var pendingSource: String? = null
    private var needsReminderReconcile = true
    private val reminderStore by lazy { ReminderStore(this) }
    private val reminderSpeaker by lazy { PleosReminderSpeaker(this) }
    private var speechEventCounter = 0L
    private val reminderPrefs by lazy { getSharedPreferences(ReminderStore.PREFS, Context.MODE_PRIVATE) }
    private val reminderPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ReminderStore.KEY_COMMAND) runOnUiThread { syncStoredReminder() }
    }
    private var triggerState = TriggerState.NONE
    private val worker = Executors.newSingleThreadExecutor()
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var subscribed = false
    private var ignitionSubscribed = false
    @Volatile private var started = false
    private var uxManager: CarUxRestrictionsManager? = null
    private var uxSubscribed = false
    @Volatile private var lastUxRestrictions: CarUxRestrictions? = null
    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
        lastUxRestrictions = restrictions
        logDrive("ux_change", uxDetails(restrictions))
    }

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION && value.areaId == 0) {
                logDrive("gear_callback", "raw=${value.value} area=${value.areaId} vhalTimestampNs=${value.timestamp}")
                runOnUiThread {
                    if (started) {
                        val observed = value.value as? Int
                        showGear(observed, true)
                        onGearEvent(observed)
                    }
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (propertyId == VehiclePropertyIds.GEAR_SELECTION) {
                fail("기어 이벤트 오류 (area=$areaId)")
            }
        }
    }

    private val ignitionCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId == VehiclePropertyIds.IGNITION_STATE && value.areaId == 0) {
                runOnUiThread { if (started) showIgnition(value.value as? Int, true) }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (propertyId == VehiclePropertyIds.IGNITION_STATE) {
                ignitionFailure("시동 이벤트 오류 (area=$areaId)")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        connection = findViewById(R.id.connection)
        gear = findViewById(R.id.gear)
        raw = findViewById(R.id.raw)
        lastEvent = findViewById(R.id.lastEvent)
        ignitionSupport = findViewById(R.id.ignitionSupport)
        ignitionState = findViewById(R.id.ignitionState)
        ignitionRaw = findViewById(R.id.ignitionRaw)
        ignitionEvent = findViewById(R.id.ignitionEvent)
        commandInput = findViewById(R.id.commandInput)
        taskStatus = findViewById(R.id.taskStatus)
        reminderCard = findViewById(R.id.reminderCard)
        reminderText = findViewById(R.id.reminderText)
        sttButton = findViewById(R.id.sttButton)
        sttPulse = findViewById(R.id.sttPulse)
        sttStatus = findViewById(R.id.sttStatus)
        sttResult = findViewById(R.id.sttResult)
        normalContent = findViewById(R.id.normalContent)
        voiceArea = findViewById(R.id.voiceArea)
        statusArea = findViewById(R.id.statusArea)
        stateDescription = findViewById(R.id.stateDescription)
        pendingText = findViewById(R.id.pendingText)
        pendingHint = findViewById(R.id.pendingHint)
        statusTitle = findViewById(R.id.statusTitle)
        val screen = findViewById<ScrollView>(R.id.screenScroll)
        val contentColumn = findViewById<LinearLayout>(R.id.contentColumn)
        val completeButton = findViewById<Button>(R.id.completeButton)
        findViewById<LinearLayout>(R.id.mainStateCard).addOnLayoutChangeListener {
                card, left, _, right, _, _, _, _, _ ->
            val maxWidth = (320 * resources.displayMetrics.density).roundToInt()
            val availableWidth = right - left - card.paddingLeft - card.paddingRight
            val targetWidth = minOf(maxWidth, availableWidth)
            if (targetWidth > 0 && completeButton.layoutParams.width != targetWidth) {
                completeButton.layoutParams = completeButton.layoutParams.apply { width = targetWidth }
            }
        }
        screen.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val availableDp = (right - left) / resources.displayMetrics.density
            val wide = availableDp >= 900f
            val padding = (if (wide) 40 else 20) * resources.displayMetrics.density
            val paddingPx = padding.roundToInt()
            if (contentColumn.paddingLeft != paddingPx) {
                contentColumn.setPadding(paddingPx, contentColumn.paddingTop,
                    paddingPx, contentColumn.paddingBottom)
            }
            val orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (normalContent.orientation != orientation) {
                normalContent.orientation = orientation
                val gap = (if (wide) 36 else 20) * resources.displayMetrics.density
                voiceArea.layoutParams = LinearLayout.LayoutParams(
                    if (wide) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    if (wide) 0.42f else 0f
                ).apply { if (wide) marginEnd = gap.roundToInt() else bottomMargin = gap.roundToInt() }
                statusArea.layoutParams = LinearLayout.LayoutParams(
                    if (wide) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    if (wide) 0.58f else 0f
                )
            }
        }
        commandInput.setText(ReminderStore.SUPPORTED_COMMAND)
        findViewById<Button>(R.id.registerButton).setOnClickListener { registerCommand() }
        completeButton.setOnClickListener { completeReminder() }
        sttButton.isEnabled = false
        sttButton.setOnClickListener {
            if (speechToText == null) startStt()
            else {
                reportStt("사용자 요청으로 인식 중지")
                finishSttSession()
            }
        }
        val developerPanel = findViewById<LinearLayout>(R.id.developerPanel)
        findViewById<View>(R.id.brandHeader).setOnLongClickListener {
            developerPanel.visibility = if (developerPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }
        sttStatus.text = "STT 상태: 대기"
        sttResult.text = "인식 결과: 없음"
        taskStatus.text = "등록된 알림 없음"
        connection.text = "차량 서비스: 대기"
        gear.text = "기어 확인 중"
        raw.text = "원시 값: 없음"
        lastEvent.text = "마지막 이벤트: 없음"
        ignitionSupport.text = "시동 속성: 확인 중"
        ignitionState.text = "시동 상태: 확인 중"
        ignitionRaw.text = "시동 원시 값: 없음"
        ignitionEvent.text = "시동 마지막 이벤트: 없음"
        renderUiState()
    }

    override fun onStart() {
        logDrive("onStart_enter")
        super.onStart()
        started = true
        reminderSpeaker.start()
        reminderPrefs.registerOnSharedPreferenceChangeListener(reminderPreferenceListener)
        needsReminderReconcile = true
        logDrive("onStart_started")
        gearReadSinceStart = false
        updateSttButton()
        connection.text = "차량 서비스: 연결 중"
        try {
            car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                runOnUiThread {
                    if (!started) return@runOnUiThread
                    if (!ready) {
                        connection.text = "차량 서비스: 연결 실패"
                        fail("차량 서비스 연결 실패")
                        return@runOnUiThread
                    }
                    connection.text = "차량 서비스: 연결됨"
                    logDrive("car_connected")
                    observeUxRestrictions(connected)
                    try {
                        if (checkSelfPermission(Car.PERMISSION_POWERTRAIN) != PackageManager.PERMISSION_GRANTED) {
                            fail("권한 오류: CAR_POWERTRAIN 미부여")
                            return@runOnUiThread
                        }
                        val properties = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                        manager = properties
                        if (properties.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) == null) {
                            fail("GEAR_SELECTION 미지원 또는 속성 접근 불가")
                            return@runOnUiThread
                        }
                        subscribed = properties.registerCallback(
                            callback, VehiclePropertyIds.GEAR_SELECTION,
                            CarPropertyManager.SENSOR_RATE_ONCHANGE
                        )
                        if (!subscribed) fail("기어 이벤트 구독 실패")
                        worker.execute {
                            try {
                                val value = properties.getProperty(
                                    java.lang.Integer::class.java,
                                    VehiclePropertyIds.GEAR_SELECTION, 0
                                )
                                runOnUiThread { if (started) showGear(value?.value?.toInt(), false) }
                            } catch (e: SecurityException) {
                                fail("권한 오류: CAR_POWERTRAIN", e)
                            } catch (e: Exception) {
                                fail("현재 기어 조회 실패: ${e.javaClass.simpleName}", e)
                            }
                        }
                        checkIgnition(properties)
                    } catch (e: SecurityException) {
                        fail("권한 오류: CAR_POWERTRAIN", e)
                    } catch (e: Exception) {
                        fail("차량 속성 연결 실패: ${e.javaClass.simpleName}", e)
                    }
                }
            }
            if (car == null) fail("차량 서비스 생성 실패")
        } catch (e: Exception) {
            fail("차량 서비스 연결 실패: ${e.javaClass.simpleName}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        logDrive("onResume")
    }

    override fun onPause() {
        logDrive("onPause")
        super.onPause()
    }

    override fun onStop() {
        logDrive("onStop_enter")
        if (speechToText != null) {
            reportStt("Activity 중지로 인식 종료")
            finishSttSession()
        }
        reminderSpeaker.stop()
        started = false
        reminderPrefs.unregisterOnSharedPreferenceChangeListener(reminderPreferenceListener)
        gearReadSinceStart = false
        updateSttButton()
        try {
            if (subscribed) manager?.unregisterCallback(callback, VehiclePropertyIds.GEAR_SELECTION)
            if (ignitionSubscribed) manager?.unregisterCallback(ignitionCallback, VehiclePropertyIds.IGNITION_STATE)
        } catch (e: Exception) {
            Log.e(TAG, "Unregister failed", e)
        }
        subscribed = false
        ignitionSubscribed = false
        manager = null
        try {
            if (uxSubscribed) uxManager?.unregisterListener()
            logDrive("ux_unregistered", "wasSubscribed=$uxSubscribed")
        } catch (e: Exception) {
            Log.e(DRIVE_TAG, "UX listener unregister failed", e)
        }
        uxSubscribed = false
        uxManager = null
        car?.disconnect()
        car = null
        logDrive("onStop_disconnected")
        super.onStop()
    }

    override fun onDestroy() {
        completionReset?.let(sttHandler::removeCallbacks)
        stopMicMotion()
        worker.shutdown()
        super.onDestroy()
    }

    private fun observeUxRestrictions(connected: Car) {
        try {
            val ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            if (ux == null) {
                logDrive("ux_unavailable", "manager=null")
                return
            }
            uxManager = ux
            ux.registerListener(uxListener)
            uxSubscribed = true
            logDrive("ux_registered")
            val current = ux.currentCarUxRestrictions
            lastUxRestrictions = current
            logDrive("ux_current", uxDetails(current))
        } catch (e: SecurityException) {
            Log.e(DRIVE_TAG, "UX permission denied", e)
            logDrive("ux_permission_error", "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Exception) {
            Log.e(DRIVE_TAG, "UX query or subscription failed", e)
            logDrive("ux_failure", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun uxDetails(restrictions: CarUxRestrictions?): String =
        "requiresDO=${restrictions?.isRequiresDistractionOptimization} " +
            "activeRestrictions=${restrictions?.activeRestrictions}"

    private fun logDrive(event: String, details: String = "") {
        Log.i(DRIVE_TAG, "elapsedNs=${SystemClock.elapsedRealtimeNanos()} " +
            "event=$event started=$started gearRaw=$currentGear " +
            "sttState=$sttState sttSession=$activeSttSessionId " +
            "uxRequiresDO=${lastUxRestrictions?.isRequiresDistractionOptimization} $details")
    }

    private fun checkIgnition(properties: CarPropertyManager) {
        try {
            val config = properties.getCarPropertyConfig(VehiclePropertyIds.IGNITION_STATE)
            if (config == null) {
                ignitionSupport.text = "시동 속성: 미지원 또는 접근 불가"
                ignitionState.text = "시동 상태: 확인 불가"
                Log.w(IGNITION_TAG, "IGNITION_STATE config unavailable")
                return
            }
            ignitionSupport.text = "시동 속성: 지원됨"
            Log.i(IGNITION_TAG, "IGNITION_STATE supported")
            try {
                ignitionSubscribed = properties.registerCallback(
                    ignitionCallback, VehiclePropertyIds.IGNITION_STATE,
                    CarPropertyManager.SENSOR_RATE_ONCHANGE
                )
                if (ignitionSubscribed) Log.i(IGNITION_TAG, "IGNITION_STATE subscription succeeded")
                else ignitionFailure("시동 이벤트 구독 실패")
            } catch (e: SecurityException) {
                ignitionFailure("시동 권한 오류: CAR_POWERTRAIN", e)
            } catch (e: Exception) {
                ignitionFailure("시동 이벤트 구독 실패: ${e.javaClass.simpleName}", e)
            }
            worker.execute {
                try {
                    val value = properties.getProperty(
                        java.lang.Integer::class.java, VehiclePropertyIds.IGNITION_STATE, 0
                    )
                    runOnUiThread { if (started) showIgnition(value?.value?.toInt(), false) }
                } catch (e: SecurityException) {
                    ignitionFailure("시동 권한 오류: CAR_POWERTRAIN", e)
                } catch (e: Exception) {
                    val kind = if (e.javaClass.simpleName.contains("PropertyNotAvailable"))
                        "시동 상태 일시적 사용 불가" else "시동 상태 조회 실패"
                    ignitionFailure("$kind: ${e.javaClass.simpleName}", e)
                }
            }
        } catch (e: SecurityException) {
            ignitionFailure("시동 권한 오류: CAR_POWERTRAIN", e)
        } catch (e: Exception) {
            ignitionFailure("시동 속성 확인 실패: ${e.javaClass.simpleName}", e)
        }
    }

    private fun showIgnition(value: Int?, event: Boolean) {
        val label = when (value) {
            VehicleIgnitionState.OFF -> "OFF"
            VehicleIgnitionState.ACC -> "ACC"
            VehicleIgnitionState.ON -> "ON"
            VehicleIgnitionState.START -> "START"
            VehicleIgnitionState.LOCK -> "LOCK"
            VehicleIgnitionState.UNDEFINED -> "UNDEFINED"
            null -> "사용 불가"
            else -> "알 수 없음"
        }
        ignitionState.text = "시동 상태: $label"
        ignitionRaw.text = "시동 원시 값: ${value ?: "없음"}"
        if (event) {
            ignitionEvent.text = "시동 마지막 이벤트: " +
                SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        }
        Log.i(IGNITION_TAG, "${if (event) "event" else "read"} ignition=$label raw=$value")
    }

    private fun ignitionFailure(message: String, error: Exception? = null) {
        runOnUiThread {
            if (started) ignitionState.text = message
            Log.e(IGNITION_TAG, message, error)
        }
    }

    private fun showGear(value: Int?, event: Boolean) {
        val label = when (value) {
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            VehicleGear.GEAR_DRIVE -> "D"
            null -> "사용 불가"
            else -> "알 수 없음"
        }
        currentGear = value
        gearReadSinceStart = true
        syncStoredReminder()
        if (value != VehicleGear.GEAR_PARK && speechToText != null) {
            reportStt("P단 이탈로 인식 종료")
            finishSttSession()
        }
        updateSttButton()
        gear.text = when (value) {
            VehicleGear.GEAR_PARK -> "P · 주차 중"
            VehicleGear.GEAR_DRIVE -> "D · 주행 중"
            VehicleGear.GEAR_REVERSE -> "R · 후진"
            VehicleGear.GEAR_NEUTRAL -> "N · 중립"
            else -> "기어 $label"
        }
        raw.text = "원시 값: ${value ?: "없음"}"
        if (event) {
            lastEvent.text = "마지막 이벤트: " +
                SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        }
        Log.i(TAG, "${if (event) "event" else "read"} gear=$label raw=$value")
        logDrive(if (event) "gear_ui_event" else "gear_read", "gear=$label raw=$value")
    }

    private fun syncStoredReminder() {
        if (!started || !gearReadSinceStart || currentGear == null) return
        val stored = reminderStore.load() ?: return
        if (stored.original != ReminderStore.SUPPORTED_COMMAND) {
            Log.w(REMINDER_TAG, "stored command rejected")
            return
        }
        if (pendingCommand == null) {
            pendingCommand = Reminder(stored.original, stored.message)
            pendingSource = stored.source
            triggerState = TriggerState.entries.firstOrNull { it.name == stored.state } ?: TriggerState.NEW
        }
        if (needsReminderReconcile || triggerState == TriggerState.NEW) {
            if (triggerState != TriggerState.SHOWING) {
                triggerState = if (currentGear == VehicleGear.GEAR_DRIVE)
                    TriggerState.WAITING_FOR_P else TriggerState.WAITING_FOR_D
                if (!reminderStore.updateState(triggerState.name)) {
                    Log.e(REMINDER_TAG, "reminder state reconciliation failed")
                    return
                }
            }
            needsReminderReconcile = false
            Log.i(REMINDER_TAG, "restored source=$pendingSource state=$triggerState gear=$currentGear")
        }
        if (triggerState == TriggerState.SHOWING) {
            reminderText.text = stored.message
            taskStatus.text = "알림 실행됨"
        } else {
            taskStatus.text = if (triggerState == TriggerState.WAITING_FOR_P)
                "실행 대기: P단 진입" else "실행 대기: 다음 D → P 전환"
        }
        renderUiState()
    }

    private fun registerCommand() {
        if (registerReminder(commandInput.text.toString().trim(), ReminderStore.SOURCE_TEXT)) {
            announceRegistration()
        }
    }

    private fun announceRegistration() {
        if (!started || currentGear != VehicleGear.GEAR_PARK) return
        reminderSpeaker.announce(
            eventId = "registration:${++speechEventCounter}",
            kind = "registration",
            text = getString(R.string.registration_spoken_confirmation)
        )
    }

    private fun registerReminder(original: String, source: String): Boolean {
        val initialState = if (source == ReminderStore.SOURCE_TEXT && currentGear == VehicleGear.GEAR_DRIVE)
            TriggerState.WAITING_TO_LEAVE_D else TriggerState.WAITING_FOR_D
        if (currentGear == null) {
            taskStatus.text = "기어 상태 확인 후 다시 맡겨 주세요"
            feedbackKind = FeedbackKind.ERROR
            renderUiState()
            Log.w(REMINDER_TAG, "registration rejected: gear unavailable source=$source")
            return false
        }
        val registered = when (reminderStore.register(original, source, initialState.name)) {
            ReminderStore.Registration.UNSUPPORTED -> {
                taskStatus.text = "지원되지 않는 명령입니다"
                feedbackKind = FeedbackKind.ERROR
                Log.w(REMINDER_TAG, "unsupported command source=$source text=$original")
                false
            }
            ReminderStore.Registration.ALREADY_PENDING -> {
                taskStatus.text = "이미 대기 중인 알림이 있습니다"
                feedbackKind = FeedbackKind.ERROR
                Log.w(REMINDER_TAG, "registration rejected: reminder already pending source=$source")
                false
            }
            ReminderStore.Registration.SAVE_FAILED -> {
                taskStatus.text = "알림 저장 실패"
                feedbackKind = FeedbackKind.ERROR
                Log.e(REMINDER_TAG, "registration save failed source=$source")
                false
            }
            ReminderStore.Registration.REGISTERED -> {
                pendingCommand = Reminder(original, ReminderStore.REMINDER_MESSAGE)
                pendingSource = source
                triggerState = initialState
                feedbackKind = FeedbackKind.NONE
                taskStatus.text = "실행 대기: 다음 D → P 전환"
                Log.i(REMINDER_TAG, "registered source=$source original=$original state=$triggerState")
                true
            }
        }
        renderUiState()
        return registered
    }

    private fun onGearEvent(observed: Int?) {
        Log.i(REMINDER_TAG, "vehicle gear event raw=$observed state=$triggerState")
        when (triggerState) {
            TriggerState.WAITING_TO_LEAVE_D -> {
                if (observed != null && observed != VehicleGear.GEAR_DRIVE) {
                    triggerState = TriggerState.WAITING_FOR_D
                    reminderStore.updateState(triggerState.name)
                    Log.i(REMINDER_TAG, "left D; waiting for next D")
                }
            }
            TriggerState.WAITING_FOR_D -> {
                if (observed == VehicleGear.GEAR_DRIVE) {
                    triggerState = TriggerState.WAITING_FOR_P
                    reminderStore.updateState(triggerState.name)
                    taskStatus.text = "실행 대기: P단 진입"
                    Log.i(REMINDER_TAG, "D observed; armed for P")
                }
            }
            TriggerState.WAITING_FOR_P -> {
                if (observed == VehicleGear.GEAR_PARK) {
                    val reminder = pendingCommand ?: return
                    if (!reminderStore.updateState(TriggerState.SHOWING.name)) {
                        Log.e(REMINDER_TAG, "reminder show state save failed")
                        return
                    }
                    triggerState = TriggerState.SHOWING
                    reminderText.text = reminder.message
                    taskStatus.text = "알림 실행됨"
                    Log.i(REMINDER_TAG, "reminder shown original=${reminder.original} message=${reminder.message}")
                    reminderSpeaker.announce(
                        eventId = "reminder:${++speechEventCounter}",
                        kind = "reminder",
                        text = reminder.message
                    )
                }
            }
            else -> Unit
        }
        renderUiState()
    }

    private fun completeReminder() {
        val reminder = pendingCommand ?: return
        if (triggerState != TriggerState.SHOWING) return
        if (!reminderStore.complete()) {
            taskStatus.text = "완료 저장 실패"
            Log.e(REMINDER_TAG, "reminder completion save failed")
            return
        }
        Log.i(REMINDER_TAG, "reminder completed original=${reminder.original}")
        triggerState = TriggerState.NONE
        pendingCommand = null
        pendingSource = null
        feedbackKind = FeedbackKind.COMPLETE
        taskStatus.text = "완료"
        renderUiState()
        completionReset?.let(sttHandler::removeCallbacks)
        completionReset = Runnable {
            if (feedbackKind == FeedbackKind.COMPLETE && pendingCommand == null) {
                feedbackKind = FeedbackKind.NONE
                renderUiState()
            }
            completionReset = null
        }.also { sttHandler.postDelayed(it, 3_000L) }
    }

    private fun updateSttButton() {
        sttButton.contentDescription = when {
            speechToText == null -> getString(R.string.voice_action)
            sttState == SttState.WAITING_FOR_FINAL -> getString(R.string.voice_cancel_final)
            else -> getString(R.string.voice_stop)
        }
        sttButton.isEnabled = started && (speechToText != null ||
            (gearReadSinceStart && currentGear == VehicleGear.GEAR_PARK))
        sttButton.alpha = if (sttButton.isEnabled) 1f else 0.45f
        renderUiState()
    }

    private fun isCurrentSttSession(sdk: SpeechToText, sessionId: Long): Boolean =
        speechToText === sdk && activeSttSessionId == sessionId

    private fun startStt() {
        if (!started || !gearReadSinceStart || currentGear != VehicleGear.GEAR_PARK) {
            reportStt("P단 확인 후 음성 인식을 시작할 수 있습니다", true)
            return
        }
        if (speechToText != null) return
        if (checkSelfPermission(STT_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            reportStt("권한 오류: STT_SERVICE 미부여", true)
            return
        }
        completionReset?.let(sttHandler::removeCallbacks)
        completionReset = null
        feedbackKind = FeedbackKind.NONE
        sttHasResult = false
        sttError = false
        sttResult.text = "인식 결과: 없음"
        sttResult.visibility = View.GONE
        sttResult.setTextColor(getColor(R.color.chacha_text))
        try {
            val sdk = SpeechToText(applicationContext, Mode.ON_DEVICE)
            val sessionId = ++sttSessionCounter
            speechToText = sdk
            activeSttSessionId = sessionId
            sttState = SttState.INITIALIZING
            updateSttButton()
            reportStt("SDK 초기화 중 (ON_DEVICE)")
            Log.i(STT_TAG, "session=$sessionId created")
            sdk.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runOnUiThread {
                        if (!isCurrentSttSession(sdk, sessionId) || !started) return@runOnUiThread
                        sttState = SttState.RECOGNIZING
                        reportStt("SDK 초기화 성공")
                        try {
                            sdk.setLocale(Locale.KOREA)
                            val listener = object : ResultListener {
                                private var rmsSamples = 0

                                override fun onReady() {
                                    runOnUiThread {
                                        if (isCurrentSttSession(sdk, sessionId) &&
                                            sttState == SttState.RECOGNIZING) reportStt("마이크 준비")
                                    }
                                }

                                override fun onStartedRecognition() {
                                    runOnUiThread {
                                        if (isCurrentSttSession(sdk, sessionId) &&
                                            sttState == SttState.RECOGNIZING) reportStt("음성 인식 시작")
                                    }
                                }

                                override fun onUpdated(stt: String, completed: Boolean) {
                                    Log.i(STT_TAG, "callback onUpdated session=$sessionId " +
                                        "active=$activeSttSessionId state=$sttState " +
                                        "completed=$completed text=$stt")
                                    runOnUiThread {
                                        if (!isCurrentSttSession(sdk, sessionId)) {
                                            Log.w(STT_TAG, "stale onUpdated ignored session=$sessionId " +
                                                "active=$activeSttSessionId completed=$completed")
                                            return@runOnUiThread
                                        }
                                        sttHasResult = true
                                        sttResult.text = "인식 결과: $stt${if (completed) " (최종)" else " (중간)"}"
                                        sttResult.visibility = View.VISIBLE
                                        sttResult.setTextColor(getColor(if (completed) R.color.chacha_success else R.color.chacha_muted))
                                        Log.i(STT_TAG, "result displayed session=$sessionId " +
                                            "completed=$completed text=$stt")
                                        if (completed) {
                                            reportStt("최종 결과 수신")
                                            val registered = registerReminder(stt.trim(), ReminderStore.SOURCE_STT)
                                            finishSttSession(
                                                stopRecognition = sttState != SttState.WAITING_FOR_FINAL
                                            )
                                            if (registered) announceRegistration()
                                        }
                                    }
                                }

                                override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                                override fun onUpdatedRms(rms: Double) {
                                    rmsSamples++
                                    if (rmsSamples <= 3 || rmsSamples % 25 == 0) {
                                        Log.i(STT_TAG, "mic rms=$rms sample=$rmsSamples")
                                    }
                                }

                                override fun onEndedRecognition() {
                                    Log.i(STT_TAG, "callback onEndedRecognition session=$sessionId " +
                                        "active=$activeSttSessionId state=$sttState")
                                    runOnUiThread {
                                        if (!isCurrentSttSession(sdk, sessionId) ||
                                            sttState == SttState.WAITING_FOR_FINAL) return@runOnUiThread
                                        sttState = SttState.WAITING_FOR_FINAL
                                        reportStt("인식 종료, 최종 결과 대기")
                                        updateSttButton()
                                        val timeout = Runnable {
                                            if (isCurrentSttSession(sdk, sessionId) &&
                                                sttState == SttState.WAITING_FOR_FINAL) {
                                                reportStt("최종 결과 미수신 (대기 종료)", true)
                                                finishSttSession(stopRecognition = false)
                                            }
                                        }
                                        finalResultTimeout = timeout
                                        sttHandler.postDelayed(timeout, FINAL_RESULT_WAIT_MS)
                                    }
                                }

                                override fun onError(errorType: ErrorType) {
                                    Log.e(STT_TAG, "callback onError session=$sessionId error=$errorType")
                                    runOnUiThread {
                                        if (isCurrentSttSession(sdk, sessionId)) {
                                            reportStt("STT 오류: $errorType", true)
                                            finishSttSession()
                                        }
                                    }
                                }
                            }
                            speechListener = listener
                            sdk.addListener(listener)
                            reportStt("인식 요청 (ko-KR)")
                            sdk.request()
                        } catch (e: Exception) {
                            reportStt("인식 요청 실패: ${e.javaClass.simpleName}: ${e.message}", true)
                            finishSttSession()
                        }
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    runOnUiThread {
                        if (isCurrentSttSession(sdk, sessionId)) {
                            reportStt("SDK 초기화 실패: $errorType", true)
                            finishSttSession()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            reportStt("SDK 생성 실패: ${e.javaClass.simpleName}: ${e.message}", true)
            finishSttSession()
        }
    }

    private fun finishSttSession(stopRecognition: Boolean = true) {
        finalResultTimeout?.let(sttHandler::removeCallbacks)
        finalResultTimeout = null
        val sdk = speechToText ?: return
        val sessionId = activeSttSessionId
        speechToText = null
        activeSttSessionId = 0L
        sttState = SttState.IDLE
        val listener = speechListener
        speechListener = null
        Log.i(STT_TAG, "session=$sessionId cleanup stopRecognition=$stopRecognition")
        logDrive("stt_cleanup", "session=$sessionId stopRecognition=$stopRecognition")
        try {
            if (listener != null) sdk.removeListener(listener)
            if (stopRecognition) sdk.stop()
        } catch (e: Exception) {
            Log.e(STT_TAG, "STT 정리 오류", e)
        } finally {
            try {
                sdk.release()
                Log.i(STT_TAG, "SDK 리소스 해제 session=$sessionId")
                logDrive("stt_released", "session=$sessionId")
            } catch (e: Exception) {
                Log.e(STT_TAG, "SDK 해제 오류", e)
            }
            updateSttButton()
        }
    }

    private fun reportStt(message: String, error: Boolean = false) {
        sttError = error
        sttStatus.text = "STT 상태: $message"
        sttStatus.visibility = View.VISIBLE
        sttStatus.setTextColor(getColor(if (error) R.color.chacha_error else R.color.chacha_muted))
        if (error) Log.e(STT_TAG, message) else Log.i(STT_TAG, message)
        logDrive(if (error) "stt_error" else "stt_status", "message=$message")
        renderUiState()
    }

    private fun renderUiState() {
        val showing = triggerState == TriggerState.SHOWING && pendingCommand != null
        val listening = sttState != SttState.IDLE
        val registered = pendingCommand != null
        reminderCard.visibility = if (showing) View.VISIBLE else View.GONE
        normalContent.visibility = if (showing) View.GONE else View.VISIBLE

        statusTitle.text = when {
            listening && sttState == SttState.INITIALIZING -> "음성 인식을 준비하고 있어요"
            listening && sttState == SttState.WAITING_FOR_FINAL -> "최종 문장을 확인하고 있어요"
            listening -> "듣고 있어요"
            feedbackKind == FeedbackKind.ERROR -> "알림을 등록하지 못했어요"
            sttError -> "음성 인식에 문제가 있어요"
            registered -> "알림 등록 완료"
            feedbackKind == FeedbackKind.COMPLETE -> "알림을 완료했어요"
            else -> getString(R.string.hero_question)
        }
        stateDescription.text = when {
            listening -> "인식된 문장을 확인하고 있어요."
            feedbackKind == FeedbackKind.ERROR -> "인식 결과와 등록 결과를 확인해 주세요."
            sttError -> "다시 마이크를 눌러 시도해 주세요."
            registered -> "차가 다음 주차를 기억할게요."
            feedbackKind == FeedbackKind.COMPLETE -> "필요한 알림을 다시 맡길 수 있어요."
            else -> getString(R.string.brand_slogan)
        }
        pendingText.text = if (pendingCommand?.original == ReminderStore.SUPPORTED_COMMAND)
            getString(R.string.pending_notebook) else pendingCommand?.original.orEmpty()
        pendingHint.text = if (triggerState == TriggerState.WAITING_FOR_P)
            getString(R.string.pending_armed) else getString(R.string.pending_next_park)
        pendingText.visibility = if (registered && !listening) View.VISIBLE else View.GONE
        pendingHint.visibility = if (registered && !listening) View.VISIBLE else View.GONE
        sttResult.visibility = if (sttHasResult && (listening ||
            (feedbackKind == FeedbackKind.ERROR && !registered))) View.VISIBLE else View.GONE
        taskStatus.visibility = if (feedbackKind == FeedbackKind.ERROR) View.VISIBLE else View.GONE
        sttStatus.visibility = if (listening || sttError) View.VISIBLE else View.GONE
        updateMicMotion()
    }

    private fun updateMicMotion() {
        val shouldPulse = started && currentGear == VehicleGear.GEAR_PARK &&
            speechToText != null && sttState != SttState.IDLE
        if (!shouldPulse) {
            stopMicMotion()
            return
        }
        if (pulseAnimator != null) return
        sttPulse.visibility = View.VISIBLE
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            sttPulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.45f, 0f)
        ).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    private fun stopMicMotion() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        if (::sttPulse.isInitialized) {
            sttPulse.visibility = View.GONE
            sttPulse.scaleX = 1f
            sttPulse.scaleY = 1f
            sttPulse.alpha = 1f
        }
    }

    private enum class FeedbackKind { NONE, ERROR, COMPLETE }

    private enum class SttState { IDLE, INITIALIZING, RECOGNIZING, WAITING_FOR_FINAL }

    private data class Reminder(val original: String, val message: String)

    private enum class TriggerState {
        NONE, NEW, WAITING_TO_LEAVE_D, WAITING_FOR_D, WAITING_FOR_P, SHOWING
    }

    private fun fail(message: String, error: Exception? = null) {
        runOnUiThread {
            gear.text = message
            Log.e(TAG, message, error)
        }
    }

    companion object {
        private const val TAG = "ChachaGear"
        private const val IGNITION_TAG = "ChachaIgnition"
        private const val REMINDER_TAG = "ChachaReminder"
        private const val STT_TAG = "ChachaStt"
        private const val DRIVE_TAG = "ChachaDrive"
        private const val STT_PERMISSION = "pleos.car.permission.STT_SERVICE"
        private const val FINAL_RESULT_WAIT_MS = 15_000L
    }
}
