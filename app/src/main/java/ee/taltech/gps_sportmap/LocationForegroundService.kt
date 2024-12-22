package ee.taltech.gps_sportmap

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class LocationForegroundService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var cpDistance: String = "N/A"
    private var wpDistance: String = "N/A"

    private var lastCpDistance: String = ""
    private var lastWpDistance: String = ""

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): LocationForegroundService = this@LocationForegroundService
    }



    companion object {
        private const val CHANNEL_ID = "location_channel_id"
        private const val NOTIFICATION_ID = 123456
        private const val TAG = "LocationForegroundService"

        const val ACTION_UPDATE_NOTIFICATION = "com.example.gps_sportmap.UPDATE_NOTIFICATION"
        const val ACTION_PLACE_CP = "com.example.gps_sportmap.PLACE_CP"
        const val ACTION_PLACE_WP = "com.example.gps_sportmap.PLACE_WP"

    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started, calling startForeground")

        // Call startForeground immediately
        startForeground(NOTIFICATION_ID, createNotification())

        // Process intent actions
        when (intent?.action) {
            ACTION_PLACE_CP -> {
                sendActionToMainActivity(ACTION_PLACE_CP)
            }
            ACTION_PLACE_WP -> {
                sendActionToMainActivity(ACTION_PLACE_WP)
            }
            else -> {
                startLocationUpdates()
            }
        }

        return START_STICKY
    }

    private fun placeCheckpoint() {
        // Broadcast to MainActivity or handle logic directly
        val broadcastIntent = Intent("UPDATE_CP").apply {
            putExtra("message", "Checkpoint placed")
        }
        sendBroadcast(broadcastIntent)
    }

    private fun placeWaypoint() {
        val broadcastIntent = Intent("UPDATE_WP").apply {
            putExtra("message", "Waypoint placed")
        }
        sendBroadcast(broadcastIntent)
    }


    private fun sendActionToMainActivity(action: String) {
        val broadcastIntent = Intent(action)
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        Log.d(TAG, "Broadcast sent: $action")
    }

    private fun pauseLocationUpdates() {
        // Remove location updates from LocationManager if running
        locationManager.removeUpdates(this)
        // You might also update the notification to show it's paused
        updateNotificationPausedState()
    }

    private fun updateNotificationPausedState() {
        val notificationLayout = RemoteViews(packageName, R.layout.notification_layout)
        notificationLayout.setTextViewText(R.id.sessionDuration, "Paused")

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCustomContentView(notificationLayout)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
    @Synchronized
    fun setCheckpointDistance(distance: String) {
        cpDistance = distance
    }
    @Synchronized
    fun setWaypointDistance(distance: String) {
        wpDistance = distance
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Tracking Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        cpDistance: String = "N/A",
        cpFlyDist: String = "N/A",
        cpAvgSpd: String = "N/A",

        wpDistance: String = "N/A",
        wpFlyDist: String = "N/A",
        wpAvgSpd: String = "N/A",
    ): Notification {
        val expandedLayout = RemoteViews(packageName, R.layout.notification_layout)

        // Dynamically set TextView values
        expandedLayout.setTextViewText(R.id.distanceCpToCurrent, cpDistance)
        expandedLayout.setTextViewText(R.id.flyCpToCurrent, cpFlyDist)
        expandedLayout.setTextViewText(R.id.cpAverageSpeed, cpAvgSpd)

        expandedLayout.setTextViewText(R.id.wpToCurrent, wpDistance)
        expandedLayout.setTextViewText(R.id.flyWpToCurrent, wpFlyDist)
        expandedLayout.setTextViewText(R.id.wpAverageSpeed, wpAvgSpd)

        // PendingIntent for Place CP
        val cpIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_PLACE_CP
        }
        val cpPendingIntent = PendingIntent.getBroadcast(this, 0, cpIntent, PendingIntent.FLAG_IMMUTABLE)

        // PendingIntent for Place WP
        val wpIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_PLACE_WP
        }
        val wpPendingIntent = PendingIntent.getBroadcast(this, 1, wpIntent, PendingIntent.FLAG_IMMUTABLE)

        expandedLayout.setOnClickPendingIntent(R.id.checkPointButton, cpPendingIntent)
        expandedLayout.setOnClickPendingIntent(R.id.wayPointButton, wpPendingIntent)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCustomBigContentView(expandedLayout)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(null)
            .setVibrate(null)
            .setOngoing(true)
            .build()
    }


    fun updateNotification(
        cpDistance: String,
        cpFlyDist: String,
        cpAvgSpd: String,
        wpDistance: String,
        wpFlyDist: String,
        wpAvgSpd: String
    ) {
        val updatedNotification = createNotification(
            cpDistance = cpDistance,
            cpFlyDist = cpFlyDist,
            cpAvgSpd = cpAvgSpd,
            wpDistance = wpDistance,
            wpFlyDist = wpFlyDist,
            wpAvgSpd = wpAvgSpd
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, updatedNotification) // Notify with updated notification
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L,
                1f,
                this
            )

        } else {
            Log.e(TAG, "Location permissions not granted, cannot start location updates.")
            stopSelf()
        }
    }


    override fun onLocationChanged(location: Location) {
        val intent = Intent("LOCATION_UPDATE").apply {
            putExtra("latitude", location.latitude)
            putExtra("longitude", location.longitude)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        scheduler.shutdown()
    }

    private fun stopLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}


}