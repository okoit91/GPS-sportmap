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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.maps.model.LatLng
import ee.taltech.gps_sportmap.dto.GpsLocationDTO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class LocationForegroundService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val bulkUploadIntervalSeconds = 10L

    private var previousLocation: Location? = null

    val locationBuffer = mutableListOf<GpsLocationDTO>()
    val bufferLock = ReentrantLock()

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): LocationForegroundService = this@LocationForegroundService
    }

    companion object {
        private const val CHANNEL_ID = "location_channel_id"
        private const val NOTIFICATION_ID = 123456
        private const val TAG = "LocationForegroundService"

        const val ACTION_LOCATION_UPDATE = "LOCATION_UPDATE"
        const val ACTION_START_TRACKING = "com.example.gps_sportmap.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.gps_sportmap.STOP_TRACKING"

        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"

        const val ACTION_PLACE_CP = "com.example.gps_sportmap.PLACE_CP"
        const val ACTION_PLACE_WP = "com.example.gps_sportmap.PLACE_WP"

        const val EXTRA_SESSION_ID = "GpsSessionId"
    }

    override fun onCreate() {
        super.onCreate()
        // Log.d(TAG, "onCreate: Service created")

        createNotificationChannel()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager


        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        } else {
            // Log.e(TAG, "Missing location permission in onCreate, stopping.")
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, createNotification())

        scheduler.scheduleWithFixedDelay(
            { sendLocationsInBulk() },
            bulkUploadIntervalSeconds,
            bulkUploadIntervalSeconds,
            TimeUnit.SECONDS
        )

    }


    private fun sendLocationsInBulk() {
        CoroutineScope(Dispatchers.IO).launch {
            val sessionId = getSessionId() ?: return@launch
            val bulkRequestBody: List<GpsLocationDTO>

            synchronized(locationBuffer) {
                if (locationBuffer.isEmpty()) return@launch
                bulkRequestBody = locationBuffer.toList()
                locationBuffer.clear()
            }

            if (!isInternetAvailable(this@LocationForegroundService)) {
                Log.w("BulkUpload", "No internet connection. Locations will be kept in the buffer.")
                synchronized(locationBuffer) {
                    locationBuffer.addAll(bulkRequestBody) // Re-add locations to buffer
                }
                return@launch
            }

            try {
                WebClient.postLocationsInBulk(
                    context = this@LocationForegroundService,
                    sessionId = sessionId,
                    bulkRequestBody
                )
                // Log.d("BulkUpload", "Uploaded ${bulkRequestBody.size} locations successfully.")
            } catch (e: Exception) {
                // Log.e("BulkUpload", "Failed to upload locations: ${e.message}")
                synchronized(locationBuffer) {
                    locationBuffer.addAll(bulkRequestBody) // Re-add locations to buffer
                }
            }
        }
    }

    fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        return networkCapabilities != null && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }


    private fun getSessionId(): String? {
        // Retrieve the session ID from SharedPreferences
        val sharedPref = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        return sharedPref.getString("GpsSessionId", null)
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Log.d(TAG, "Service started, processing intent actions")

        when (intent?.action) {
            ACTION_START_TRACKING -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                if (sessionId != null) {
                    // Log.d(TAG, "Starting tracking with session ID: $sessionId")
                    startLocationUpdates()
                } else {
                    // Log.e(TAG, "Session ID is missing. Cannot start tracking.")
                    stopSelf()
                }
            }
            ACTION_STOP_TRACKING -> {
                // Log.d(TAG, "Stopping tracking as per ACTION_STOP_TRACKING")
                stopLocationUpdates()
                sendLocationsInBulk()
                stopForeground(true)
                stopSelf()
            }
            ACTION_PLACE_CP -> {
                // Log.d(TAG, "Service received ACTION_PLACE_CP")
                placeCheckpoint()
            }
            ACTION_PLACE_WP -> {
                // Log.d(TAG, "Service received ACTION_PLACE_WP")
                placeWaypoint()
            }
            else -> {
                // Log.d(TAG, "Service received default action, no tracking action performed.")
            }
        }

        return START_NOT_STICKY
    }

    private fun placeCheckpoint() {
        val currentLocation = LocationRepository.currentLocation
        if (currentLocation != null) {
            LocationRepository.addCheckpoint(currentLocation)
            // Log.d(TAG, "Checkpoint placed at: ${currentLocation.latitude}, ${currentLocation.longitude}")

            val checkpointLocation = GpsLocationDTO(
                recordedAt = getCurrentISO8601Timestamp(),
                latitude = currentLocation.latitude,
                longitude = currentLocation.longitude,
                accuracy = currentLocation.accuracy.toDouble(),
                altitude = currentLocation.altitude,
                verticalAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) currentLocation.verticalAccuracyMeters.toDouble() else 0.0,
                gpsLocationTypeId = "00000000-0000-0000-0000-000000000003" // Replace with actual GUID for checkpoint
            )

            bufferLock.withLock {
                locationBuffer.add(checkpointLocation)
            }

            updateNotificationWithNewData()

            sendDistanceUpdateBroadcast()
        } else {
            // Log.e(TAG, "Cannot place checkpoint: currentLocation is null")
        }
    }



    private fun placeWaypoint() {
        val currentLocation = LocationRepository.currentLocation
        if (currentLocation != null) {
            LocationRepository.addWaypoint(currentLocation)

            // Log.d(TAG, "Waypoint placed at: ${currentLocation.latitude}, ${currentLocation.longitude}")

            val waypointLocation = GpsLocationDTO(
                recordedAt = getCurrentISO8601Timestamp(),
                latitude = currentLocation.latitude,
                longitude = currentLocation.longitude,
                accuracy = currentLocation.accuracy.toDouble(),
                altitude = currentLocation.altitude,
                verticalAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) currentLocation.verticalAccuracyMeters.toDouble() else 0.0,
                gpsLocationTypeId = "00000000-0000-0000-0000-000000000002" // Replace with actual GUID for waypoint
            )

            bufferLock.withLock {
                locationBuffer.add(waypointLocation)
            }


            updateNotificationWithNewData()
            sendDistanceUpdateBroadcast()
        } else {
            // Log.e(TAG, "Cannot place waypoint: currentLocation is null")
        }
    }


    private fun sendDistanceUpdateBroadcast() {
        val broadcastIntent = Intent("UPDATE_METERS")
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        // Log.d(TAG, "Broadcast sent: UPDATE_METERS")
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
        wpAvgSpd: String = "N/A"
    ): Notification {
        val expandedLayout = RemoteViews(packageName, R.layout.notification_layout)

        expandedLayout.setTextViewText(R.id.distanceCpToCurrent, cpDistance)
        expandedLayout.setTextViewText(R.id.flyCpToCurrent, cpFlyDist)
        expandedLayout.setTextViewText(R.id.cpAverageSpeed, cpAvgSpd)

        expandedLayout.setTextViewText(R.id.wpToCurrent, wpDistance)
        expandedLayout.setTextViewText(R.id.flyWpToCurrent, wpFlyDist)
        expandedLayout.setTextViewText(R.id.wpAverageSpeed, wpAvgSpd)

        val cpIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_PLACE_CP
        }
        val cpPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            cpIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val wpIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_PLACE_WP
        }
        val wpPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            wpIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        expandedLayout.setOnClickPendingIntent(R.id.checkPointButton, cpPendingIntent)
        expandedLayout.setOnClickPendingIntent(R.id.wayPointButton, wpPendingIntent)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCustomBigContentView(expandedLayout)
            .setCustomContentView(expandedLayout)
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
        // Log.d(TAG, "Notification updated with new data")
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Log.e(TAG, "startLocationUpdates: Missing permission, stopping service.")
            stopSelf()
            return
        }
        // Log.d(TAG, "startLocationUpdates: Requesting GPS updates.")
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            1f,
            this
        )
    }

    override fun onLocationChanged(location: Location) {
        // Log.d(TAG, "onLocationChanged: New location received: $location")

        var distance = 0f
        var speed: Float = 0f

        if (previousLocation != null) {
            distance = previousLocation!!.distanceTo(location)
            val timeDiff = (location.time - previousLocation!!.time) / 1000.0f
            speed = if (timeDiff > 0) (distance / timeDiff) * 3.6f else 0f
        }

        previousLocation = location

        synchronized(LocationRepository) {
            LocationRepository.currentLocation = location
            val latLng = LatLng(location.latitude, location.longitude)
            LocationRepository.trackedPoints.add(latLng)
            LocationRepository.speeds.add(speed)

            LocationRepository.distanceSum += distance


            if (LocationRepository.cpStartLocation != null) {
                LocationRepository.regularDistanceFromCP += distance
                // Log.d(TAG, "Updated regularDistanceFromCP: ${LocationRepository.regularDistanceFromCP} m")
            }

            if (LocationRepository.wpStartLocation != null) {
                LocationRepository.regularDistanceFromWP += distance
                // Log.d(TAG, "Updated regularDistanceFromWP: ${LocationRepository.regularDistanceFromWP} m")
            }
        }

        val gpsLocationDTO = GpsLocationDTO(
            recordedAt = getCurrentISO8601Timestamp(),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.toDouble(),
            altitude = location.altitude,
            verticalAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) location.verticalAccuracyMeters.toDouble() else 0.0,
            gpsLocationTypeId = "00000000-0000-0000-0000-000000000001"
        )

        bufferLock.withLock {
            locationBuffer.add(gpsLocationDTO)
        }


        val intent = Intent(ACTION_LOCATION_UPDATE).apply {
            putExtra(EXTRA_LATITUDE, location.latitude)
            putExtra(EXTRA_LONGITUDE, location.longitude)
            putExtra("speed", speed)
            putExtra("bearing", location.bearing)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        // Log.d(TAG, "Broadcast sent: ACTION_LOCATION_UPDATE with speed $speed")

        updateNotificationWithNewData()
    }



    fun updateNotificationWithNewData() {
        val cpDistanceValue = LocationRepository.regularDistanceFromCP
        val wpDistanceValue = LocationRepository.regularDistanceFromWP

        val currentLocation = LocationRepository.currentLocation
        if (currentLocation == null) {
            // Log.e(TAG, "Current location is null. Cannot update notification.")
            return
        }

        val cpFlyDist = LocationRepository.cpStartLocation?.distanceTo(currentLocation) ?: 0f
        val wpFlyDist = LocationRepository.wpStartLocation?.distanceTo(currentLocation) ?: 0f

        val cpDistanceText = if (cpDistanceValue > 0) "${cpDistanceValue.toInt()} m" else "0 m"
        val wpDistanceText = if (wpDistanceValue > 0) "${wpDistanceValue.toInt()} m" else "0 m"

        val cpFlyDistText = if (cpFlyDist > 0) "${cpFlyDist.toInt()} m" else "N/A"
        val wpFlyDistText = if (wpFlyDist > 0) "${wpFlyDist.toInt()} m" else "N/A"

        val cpAvgSpdText = if (LocationRepository.regularDistanceFromCP > 0) {
            String.format(
                "%.1f min/km",
                LocationRepository.calculateAverageSpeed(
                    startTime = LocationRepository.cpSessionStartTime,
                    distance = LocationRepository.regularDistanceFromCP
                )
            )
        } else {
            "N/A"
        }

        val wpAvgSpdText = if (LocationRepository.regularDistanceFromWP > 0) {
            String.format(
                "%.1f min/km",
                LocationRepository.calculateAverageSpeed(
                    startTime = LocationRepository.wpSessionStartTime,
                    distance = LocationRepository.regularDistanceFromWP
                )
            )
        } else {
            "N/A"
        }

        updateNotification(
            cpDistance = cpDistanceText,
            cpFlyDist = cpFlyDistText,
            cpAvgSpd = cpAvgSpdText,
            wpDistance = wpDistanceText,
            wpFlyDist = wpFlyDistText,
            wpAvgSpd = wpAvgSpdText
        )
    }


    override fun onDestroy() {
        super.onDestroy()
        // Log.d(TAG, "onDestroy: Service is being destroyed")
        locationManager.removeUpdates(this)
        stopLocationUpdates()
        scheduler.shutdown()
    }

    private fun stopLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.removeUpdates(this)
            // Log.d(TAG, "stopLocationUpdates: Location updates removed")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

}
