package com.chachaallim.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

internal enum class TriggerState {
    NONE, NEW, WAITING_TO_LEAVE_D, WAITING_FOR_D, WAITING_FOR_P, SHOWING;

    companion object {
        fun from(stored: String?): TriggerState =
            if (stored == null) NONE else entries.firstOrNull { it.name == stored } ?: NEW
    }
}

class ParkingReminderService : Service() {
    private val store by lazy { ReminderStore(this) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val speaker by lazy { PleosReminderSpeaker(this, ::finishDelivery) }
    private val playbackTimeout = Runnable { finishDelivery() }
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var delivering = false

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (properties != null && value.propertyId == VehiclePropertyIds.GEAR_SELECTION &&
                value.areaId == 0 && value.status == CarPropertyValue.STATUS_AVAILABLE
            ) {
                (value.value as? Int)?.let(::observeGear)
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "Gear callback error property=$propertyId area=$areaId")
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(MONITORING_NOTIFICATION_ID, monitoringNotification())
        when (TriggerState.from(store.load()?.state)) {
            TriggerState.NONE, TriggerState.SHOWING -> if (!delivering) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            else -> if (car == null && !connectToCar()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (!delivering) {
            when (intent?.action) {
                ACTION_CONFIRM_REGISTRATION -> {
                    if (store.load()?.state == TriggerState.WAITING_FOR_D.name) {
                        speaker.stop()
                        speaker.start()
                        speaker.announce(
                            "registration:${System.nanoTime()}",
                            "registration",
                            getString(R.string.registration_spoken_confirmation)
                        )
                    }
                }
                ACTION_STOP_CONFIRMATION -> speaker.stop()
            }
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun connectToCar(): Boolean = try {
        car = Car.createCar(this, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
            if (!ready) {
                properties = null
            } else if (properties == null) {
                try {
                    val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    properties = manager
                    // Registration also delivers the current value once the property is available.
                    if (!manager.registerCallback(
                            gearCallback, VehiclePropertyIds.GEAR_SELECTION,
                            CarPropertyManager.SENSOR_RATE_ONCHANGE
                        )
                    ) {
                        Log.e(TAG, "Gear subscription rejected")
                        stopSelf()
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Gear subscription failed", error)
                    stopSelf()
                }
            }
        }
        car != null
    } catch (error: Exception) {
        Log.e(TAG, "Car connection failed", error)
        false
    }

    private fun observeGear(gear: Int) {
        val reminder = store.load() ?: return
        val state = TriggerState.from(reminder.state)
        val next = when (state) {
            TriggerState.NEW -> if (gear == VehicleGear.GEAR_DRIVE)
                TriggerState.WAITING_FOR_P else TriggerState.WAITING_FOR_D
            TriggerState.WAITING_TO_LEAVE_D ->
                if (gear != VehicleGear.GEAR_DRIVE) TriggerState.WAITING_FOR_D else return
            TriggerState.WAITING_FOR_D ->
                if (gear == VehicleGear.GEAR_DRIVE) TriggerState.WAITING_FOR_P else return
            TriggerState.WAITING_FOR_P ->
                if (gear == VehicleGear.GEAR_PARK) TriggerState.SHOWING else return
            else -> return
        }
        if (!store.updateState(next.name)) {
            Log.e(TAG, "Failed to save reminder state $next")
            return
        }
        Log.i(TAG, "Gear=$gear reminder state=$next")
        if (next == TriggerState.WAITING_FOR_P && !delivering) speaker.stop()
        if (next == TriggerState.SHOWING) deliver(reminder.message)
    }

    private fun openReminder(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun monitoringNotification(): Notification {
        notifications.createNotificationChannel(NotificationChannel(
            MONITORING_CHANNEL, getString(R.string.monitoring_channel), NotificationManager.IMPORTANCE_LOW
        ))
        return Notification.Builder(this, MONITORING_CHANNEL)
            .setSmallIcon(R.drawable.ic_chacha_bell)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.monitoring_notification))
            .setContentIntent(openReminder())
            .setOngoing(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun deliver(message: String) {
        delivering = true
        notifications.createNotificationChannel(NotificationChannel(
            REMINDER_CHANNEL, getString(R.string.reminder_notification_channel), NotificationManager.IMPORTANCE_DEFAULT
        ).apply { setSound(null, null) })
        if (notifications.areNotificationsEnabled()) {
            notifications.notify(REMINDER_NOTIFICATION_ID, Notification.Builder(this, REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_chacha_bell)
                .setContentTitle(getString(R.string.reminder_title))
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(openReminder())
                .build())
        }
        handler.removeCallbacks(playbackTimeout)
        handler.postDelayed(playbackTimeout, 30_000)
        speaker.stop()
        speaker.start()
        speaker.announce("parking:${System.nanoTime()}", "reminder", message)
    }

    private fun finishDelivery() {
        if (!delivering) return
        handler.removeCallbacks(playbackTimeout)
        delivering = false
        when (TriggerState.from(store.load()?.state)) {
            TriggerState.NONE, TriggerState.SHOWING -> stopSelf()
            else -> Unit
        }
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        handler.removeCallbacks(playbackTimeout)
        speaker.stop()
        val manager = properties
        properties = null
        try {
            manager?.unregisterCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION)
        } catch (error: Exception) {
            Log.w(TAG, "Could not unregister gear callback", error)
        }
        car?.disconnect()
        car = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_CONFIRM_REGISTRATION = "confirm_registration"
        internal const val ACTION_STOP_CONFIRMATION = "stop_confirmation"
        internal const val REMINDER_NOTIFICATION_ID = 2
        private const val MONITORING_NOTIFICATION_ID = 1
        private const val MONITORING_CHANNEL = "parking_monitor"
        private const val REMINDER_CHANNEL = "parking_reminder"
        private const val TAG = "ChachaParking"
    }
}
