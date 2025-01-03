package ee.taltech.gps_sportmap

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager

import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ee.taltech.gps_sportmap.dal.DbHelper
import ee.taltech.gps_sportmap.domain.Track
import android.os.Handler
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import ee.taltech.gps_sportmap.dto.GpsLocationDTO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {


    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_FINE_LOCATION = 999
    }

    private lateinit var viewModel: MainViewModel

    private lateinit var startButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var dbHelper: DbHelper

    private lateinit var compassButton: Button

    private var isUpdateMetersReceiverRegistered = false

    private var locationService: LocationForegroundService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocationForegroundService.LocalBinder
            locationService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            locationService = null
            isBound = false
        }
    }

    private lateinit var locationManager: LocationManager



    lateinit var sensorManager: SensorManager

    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private lateinit var compassImageView: ImageView

    private lateinit var distanceCovered: TextView


    private lateinit var sessionDuration: TextView

    private var isNotificationReceiverRegistered = false
    private var isLocationReceiverRegistered = false


    private val handler = Handler()

    private var bottomSheetDialog: BottomSheetDialog? = null

    private lateinit var averageSpeed: TextView

    private lateinit var distanceCpToCurrent: TextView
    private lateinit var flyCpToCurrent: TextView
    private lateinit var cpAverageSpeed: TextView

    private lateinit var wpToCurrent: TextView
    private lateinit var flyWpToCurrent: TextView
    private lateinit var wpAverageSpeed: TextView

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LocationForegroundService.ACTION_PLACE_CP -> {
                    // Log.d(TAG, "NotificationReceiver: PLACE_CP received")
                    addCheckpoint()
                }

                LocationForegroundService.ACTION_PLACE_WP -> {
                    // Log.d(TAG, "NotificationReceiver: PLACE_WP received")
                    addWaypoint()
                }
            }
        }
    }


    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationForegroundService.ACTION_LOCATION_UPDATE) {
                val latitude = intent.getDoubleExtra(LocationForegroundService.EXTRA_LATITUDE, 0.0)
                val longitude = intent.getDoubleExtra(LocationForegroundService.EXTRA_LONGITUDE, 0.0)
                val speed = intent.getFloatExtra("speed", 0f)
                val bearing = intent.getFloatExtra("bearing", 0f)
                val location = Location(LocationManager.GPS_PROVIDER).apply {
                    this.latitude = latitude
                    this.longitude = longitude
                    this.time = System.currentTimeMillis()
                    this.bearing = bearing
                }
                handleNewLocationFromService(location, speed)
            }
        }
    }

    private val updateMetersReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "UPDATE_METERS") {
                // Log.d(TAG, "UpdateMetersReceiver: UPDATE_METERS received")
                updateDistanceFromCP()
                updateFlyDistanceFromCP()
                updateCpAverageSpeed()

                updateDistanceFromWP()
                updateFlyDistanceFromWP()
                updateWpAverageSpeed()

                val distanceFormatted = String.format("%.0f", LocationRepository.distanceSum)
                distanceCovered.text = "$distanceFormatted m"

                val cpDist = LocationRepository.regularDistanceFromCP
                distanceCpToCurrent.text = if (cpDist > 0) "${cpDist.toInt()} m" else "N/A"

                val wpDist = LocationRepository.regularDistanceFromWP
                wpToCurrent.text = if (wpDist > 0) "${wpDist.toInt()} m" else "N/A"
            }
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            // Log.d(TAG, "Updating session duration")

            val elapsedMillis = System.currentTimeMillis() - LocationRepository.sessionStartTime
            val elapsedSeconds = elapsedMillis / 1000.0

            val distanceInKm = LocationRepository.distanceSum / 1000.0
            if (distanceInKm > 0) {
                val elapsedMinutes = elapsedSeconds / 60.0
                val minutesPerKm = elapsedMinutes / distanceInKm
                val formattedSpeed = String.format("%.1f min/km", minutesPerKm)
                averageSpeed.text = formattedSpeed
            } else {
                averageSpeed.text = "N/A"
            }

            val totalSeconds = elapsedSeconds.toLong()
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            val formattedDuration = String.format("%02d:%02d:%02d", hours, minutes, seconds)
            sessionDuration.text = formattedDuration

            updateCpAverageSpeed()
            updateWpAverageSpeed()

            handler.postDelayed(this, 5000)
        }
    }

    var map: GoogleMap? = null
    private var polyLine: Polyline? = null


    private fun isDeviceReady(): Boolean {
        return try {
            android.os.SystemClock.uptimeMillis() > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun addCheckpoint() {
        if (viewModel.isTracking) {
            val serviceIntent = Intent(this, LocationForegroundService::class.java).apply {
                action = LocationForegroundService.ACTION_PLACE_CP
            }
            startService(serviceIntent)
        } else {
            // Log.d(TAG, "Cannot add a checkpoint. Not tracking.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        if (!checkLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_FINE_LOCATION
            )
        }

        val sharedPref = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        val isLoggedIn = sharedPref.getBoolean("isLoggedIn", false)

        if (!isLoggedIn) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            // Log.d(TAG, "We did not log in!!")
            return
        }

        // Log.d(TAG, "We logged in!!")

        setContentView(R.layout.activity_main)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (!checkLocationPermission()) {
            // Log.e(TAG, "Location permissions not granted. Redirecting to login.")
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        compassImageView = findViewById(R.id.compassImageView)


        if (isDeviceReady()) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }


        dbHelper = DbHelper(this)


        // UI elements

        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        compassButton = findViewById(R.id.compassButton)

        distanceCovered = findViewById(R.id.distanceCovered)
        sessionDuration = findViewById(R.id.sessionDuration)
        averageSpeed = findViewById(R.id.averageSpeed)

        distanceCpToCurrent = findViewById(R.id.distanceCpToCurrent)
        flyCpToCurrent = findViewById(R.id.flyCpToCurrent)
        cpAverageSpeed = findViewById(R.id.cpAverageSpeed)

        wpToCurrent = findViewById(R.id.wpToCurrent)
        flyWpToCurrent = findViewById(R.id.flyWpToCurrent)
        wpAverageSpeed = findViewById(R.id.wpAverageSpeed)


        trackAdapter = TrackAdapter(
            tracks = emptyList(),
            onTrackSelected = {},
            onTrackDeleted = {},
            onTrackRenamed = {}
        )


        if (viewModel.isTracking) {
            startButton.visibility = View.GONE
            stopButton.visibility = View.VISIBLE

            handler.post(updateRunnable)


            val lastLocation = LocationRepository.currentLocation
            if (lastLocation != null && map != null && viewModel.isTrackingUserRotation) {
                val latLng = LatLng(lastLocation.latitude, lastLocation.longitude)
                map!!.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.builder()
                            .target(latLng)
                            .zoom(17f)
                            .bearing(lastLocation.bearing)
                            .tilt(0f)
                            .build()
                    )
                )
            }

            val distanceFormatted = String.format("%.0f", LocationRepository.distanceSum)
            distanceCovered.text = "$distanceFormatted m"

            val cpDist = LocationRepository.regularDistanceFromCP
            distanceCpToCurrent.text = if (cpDist > 0) "${cpDist.toInt()} m" else "N/A"
        } else {
            startButton.visibility = View.VISIBLE
            stopButton.visibility = View.GONE
        }


        // map
        val mapFragment =
            supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_FINE_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Log.d(TAG, "onRequestPermissionsResult: FINE_LOCATION granted.")
            } else {
                // Log.e(TAG, "onRequestPermissionsResult: FINE_LOCATION denied.")
            }
        }
    }


    private fun checkLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()

        if (viewModel.isTracking) {
            handler.post(updateRunnable)
        }

        if (!isLocationReceiverRegistered) {
            val locationFilter = IntentFilter(LocationForegroundService.ACTION_LOCATION_UPDATE)
            LocalBroadcastManager.getInstance(this).registerReceiver(locationReceiver, locationFilter)
            isLocationReceiverRegistered = true
        }

        if (!isNotificationReceiverRegistered) {
            val notificationFilter = IntentFilter().apply {
                addAction(LocationForegroundService.ACTION_PLACE_CP)
                addAction(LocationForegroundService.ACTION_PLACE_WP)
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(notificationReceiver, notificationFilter)
            isNotificationReceiverRegistered = true
        }

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)

        if (map != null) {
            rebuildPolylineFromRepository()
        }

        if (!isUpdateMetersReceiverRegistered) {
            val filter = IntentFilter("UPDATE_METERS")
            LocalBroadcastManager.getInstance(this).registerReceiver(updateMetersReceiver, filter)
            isUpdateMetersReceiverRegistered = true
        }

        refreshUI()
    }



    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
        sensorManager.unregisterListener(this)

        if (isLocationReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(locationReceiver)
            isLocationReceiverRegistered = false
        }
        if (isNotificationReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver)
            isNotificationReceiverRegistered = false
        }

        if (isUpdateMetersReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(updateMetersReceiver)
            isUpdateMetersReceiverRegistered = false
        }
    }

    private fun refreshUI() {
        LocationRepository.currentLocation?.let { location ->
            updateDistanceFromCP()
            updateFlyDistanceFromCP()
            updateCpAverageSpeed()

            updateDistanceFromWP()
            updateFlyDistanceFromWP()
            updateWpAverageSpeed()

            val distanceFormatted = String.format("%.0f", LocationRepository.distanceSum)
            distanceCovered.text = "$distanceFormatted m"

            val cpDist = LocationRepository.regularDistanceFromCP
            distanceCpToCurrent.text = if (cpDist > 0) "${cpDist.toInt()} m" else "N/A"

            val wpDist = LocationRepository.regularDistanceFromWP
            wpToCurrent.text = if (wpDist > 0) "${wpDist.toInt()} m" else "N/A"
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }


    override fun onDestroy() {
        super.onDestroy()

        bottomSheetDialog?.dismiss()
        bottomSheetDialog = null

    }

    private fun getColorForSpeed(speed: Float): Int {
        return when {
            speed <= 5 -> Color.RED
            speed <= 10 -> Color.YELLOW
            speed <= 15 -> Color.GREEN
            else -> Color.BLUE
        }
    }

    private fun resetOverallData() {
        LocationRepository.distanceSum = 0.0
        LocationRepository.sessionStartTime = 0L

        distanceCovered.text = "N/A"
        averageSpeed.text = "N/A"
        sessionDuration.text = "00:00:00"
    }

    private fun resetCPValues() {
        LocationRepository.cpStartLocation = null
        LocationRepository.cpSessionStartTime = 0L
        LocationRepository.regularDistanceFromCP = 0.0

        distanceCpToCurrent.text = "N/A"
        flyCpToCurrent.text = "N/A"
        cpAverageSpeed.text = "N/A"
    }

    private fun resetWPValues() {
        LocationRepository.wpStartLocation = null
        LocationRepository.wpSessionStartTime = 0L
        LocationRepository.regularDistanceFromWP = 0.0

        wpToCurrent.text = "N/A"
        flyWpToCurrent.text = "N/A"
        wpAverageSpeed.text = "N/A"
    }



    private fun calculateAverageSpeed(
        startLocation: Location?,
        startTime: Long,
        distanceSum: Double
    ): Double {
        if (startLocation == null || startTime == 0L || distanceSum == 0.0) return 0.0
        val elapsedTimeMillis = System.currentTimeMillis() - startTime
        val elapsedMinutes = elapsedTimeMillis / 60000.0
        val distanceInKm = distanceSum / 1000.0
        return if (distanceInKm > 0) elapsedMinutes / distanceInKm else 0.0
    }

    override fun onSensorChanged(event: SensorEvent?) {

        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(
                    event.values,
                    0,
                    accelerometerReading,
                    0,
                    accelerometerReading.size
                )
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(
                    event.values,
                    0,
                    magnetometerReading,
                    0,
                    magnetometerReading.size
                )
            }
        }

        if (SensorManager.getRotationMatrix(
                rotationMatrix,
                null,
                accelerometerReading,
                magnetometerReading
            )
        ) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthInRadians = orientationAngles[0]
            val azimuthInDegrees = Math.toDegrees(azimuthInRadians.toDouble()).toFloat()

            compassImageView.rotation = -azimuthInDegrees
        }

    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Log.d(TAG, "Sensor accuracy changed: $accuracy")
    }


    override fun onMapReady(googleMap: GoogleMap) {
        this.map = googleMap

        map?.let { map ->
            map.uiSettings.isCompassEnabled = false
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                map.isMyLocationEnabled = true

                val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val provider = LocationManager.GPS_PROVIDER
                val location = locationManager.getLastKnownLocation(provider)

                if (location != null) {
                    val userLatLng = LatLng(location.latitude, location.longitude)
                    if (viewModel.isTracking && viewModel.isTrackingUserRotation) {
                        map.moveCamera(CameraUpdateFactory.newCameraPosition(
                            CameraPosition.builder()
                                .target(userLatLng)
                                .zoom(17f)
                                .bearing(location.bearing)
                                .tilt(0f)
                                .build()
                        ))
                    } else {
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 17.0f))
                    }
                    // Log.d(TAG, "Centered map on user's location: ${userLatLng.latitude}, ${userLatLng.longitude}")
                } else {
                    // Log.e(TAG, "Last known location is not available. Defaulting to Tallinn.")
                    val tallinn = LatLng(59.4, 24.7) // Default location
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(tallinn, 17.0f))
                }
            } else {
                // Log.e(TAG, "Location permissions are not granted.")
            }
        }

        rebuildPolylineFromRepository()
    }

    private fun rebuildPolylineFromRepository() {
        // Log.d(TAG, "Rebuilding polyline from repository. Total points: ${LocationRepository.trackedPoints.size}")

        map?.clear()

        for (i in 1 until LocationRepository.trackedPoints.size) {
            val startPoint = LocationRepository.trackedPoints[i - 1]
            val endPoint = LocationRepository.trackedPoints[i]
            val segmentSpeed = LocationRepository.speeds.getOrElse(i - 1) { 0f }
            val color = getColorForSpeed(segmentSpeed)

            map?.addPolyline(
                PolylineOptions()
                    .add(startPoint, endPoint)
                    .color(color)
                    .width(10f)
            )
        }

        for (checkpoint in LocationRepository.checkpoints) {
            map?.addMarker(
                MarkerOptions()
                    .position(checkpoint)
                    .title("Checkpoint")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
        }

        // Log.d(TAG, "Rebuilt polyline with ${LocationRepository.trackedPoints.size - 1} segments")
    }

    private fun showRenameTrackDialog(track: Track) {
        val dialog = AlertDialog.Builder(this)
        val input = EditText(this).apply {
            setText(track.name)
            setSelection(track.name.length)
        }

        dialog.setTitle("Rename Track")
        dialog.setView(input)
        dialog.setPositiveButton("Save") { _, _ ->
            val newName = input.text.toString()

            if (newName.isNotEmpty()) {

                dbHelper.renameTrack(track.id, newName)

                val updatedTracks = dbHelper.getAllTracks()
                Log.d("RenameDialog", "Renaming track ID ${track.id} to $newName")
                trackAdapter.updateTracks(updatedTracks)
            }
        }
        dialog.setNegativeButton("Cancel", null)
        dialog.show()
    }

    fun onClickStartButton(view: View) {
        if (!checkLocationPermission()) {
            Toast.makeText(this, "Grant location permission first!", Toast.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val sessionId = createAndStartGpsSession()

                val serviceIntent = Intent(this@MainActivity, LocationForegroundService::class.java).apply {
                    putExtra("GpsSessionId", sessionId)
                    action = LocationForegroundService.ACTION_START_TRACKING
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                val bindResult = bindService(
                    Intent(this@MainActivity, LocationForegroundService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
                // Log.d(TAG, "bindService result = $bindResult")

                viewModel.isTracking = true
                viewModel.isTrackingUserRotation = true
                startButton.visibility = View.GONE
                stopButton.visibility = View.VISIBLE

                LocationRepository.sessionStartTime = System.currentTimeMillis()
                handler.post(updateRunnable)

                val currentLocation = LocationRepository.currentLocation
                if (currentLocation != null && map != null) {
                    val latLng = LatLng(currentLocation.latitude, currentLocation.longitude)
                    map!!.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.builder()
                                .target(latLng)
                                .zoom(17f)
                                .bearing(currentLocation.bearing)
                                .tilt(0f)
                                .build()
                        )
                    )
                }


            } catch (e: Exception) {
                // Log.e(TAG, "Failed to start session: ${e.message}")
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Failed to start session: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun createAndStartGpsSession(): String {
        return withContext(Dispatchers.IO) {
            val sessionId = WebClient.createGpsSession(
                context = this@MainActivity,
                name = "New Session",
                description = "Starting a new session",
                gpsSessionTypeId = "00000000-0000-0000-0000-000000000001"
            )

            val sharedPref = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("GpsSessionId", sessionId)
                apply()
            }

            sessionId
        }
    }

    fun getCurrentGpsSessionId(): String? {
        val sharedPref = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        return sharedPref.getString("GpsSessionId", null)
    }

    fun onClickStopButton(view: View) {
        // Log.d(TAG, "onClickStopButton: Pressed stop")

        if (!viewModel.isTracking) {
            Log.w(TAG, "onClickStopButton: Was not tracking, ignoring.")
            return
        }

        showConfirmationDialog(
            context = this,
            title = "Confirm Stop",
            message = "Are you sure you want to stop tracking?",
            onConfirm = {
                try {
                    val serviceIntent = Intent(this, LocationForegroundService::class.java)
                    // Log.d(TAG, "Stopping service via stopService")
                    stopService(serviceIntent)

                    // Unbind if bound
                    if (isBound) {
                        // Log.d(TAG, "Unbinding from service in onClickStopButton")
                        unbindService(serviceConnection)
                        isBound = false
                    }

                    viewModel.isTracking = false
                    stopButton.visibility = View.GONE
                    startButton.visibility = View.VISIBLE

                    clearTrackingData()

                    locationService?.let { service ->
                        CoroutineScope(Dispatchers.IO).launch {
                            val sessionId = getCurrentGpsSessionId()
                            val bulkRequestBody: List<GpsLocationDTO>

                            synchronized(service.locationBuffer) {
                                if (service.locationBuffer.isEmpty()) {
                                    // Log.d(TAG, "Location buffer is empty. No data to upload.")
                                    return@launch
                                }
                                bulkRequestBody = service.locationBuffer.toList()
                            }

                            try {
                                if (sessionId != null && isInternetAvailable(this@MainActivity)) {
                                    WebClient.postLocationsInBulk(
                                        context = this@MainActivity,
                                        sessionId = sessionId,
                                        locations = bulkRequestBody
                                    )
                                    // Log.d(TAG, "Remaining locations uploaded successfully.")

                                    synchronized(service.locationBuffer) {
                                        service.locationBuffer.clear()
                                    }
                                } else {
                                    // Log.e(TAG, "No internet or sessionId null. Buffer not cleared.")
                                }
                            } catch (e: Exception) {
                                // Log.e(TAG, "Failed to upload remaining locations: ${e.message}")
                            } finally {
                                // Ensure data is cleared after upload attempt
                                withContext(Dispatchers.Main) {
                                    clearTrackingData()
                                }
                            }
                        }
                    } ?: run {
                        // Log.e(TAG, "locationService is null. Cannot access locationBuffer.")
                    }
                } catch (ex: Exception) {
                    // Log.e(TAG, "onClickStopButton Exception: ${ex.message}")
                }
            }
        )
    }


    private fun clearTrackingData() {
        saveTrack()
        handler.removeCallbacks(updateRunnable)

        CoroutineScope(Dispatchers.Main).launch {
            map?.clear()
            polyLine?.remove()
        }

        LocationRepository.trackedPoints.clear()
        LocationRepository.checkpoints.clear()

        resetOverallData()
        resetCPValues()
        resetWPValues()
        clearGpsSessionId()
    }

    fun clearGpsSessionId() {
        val sharedPref = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            remove("GpsSessionId")
            apply()
        }
    }

    fun saveTrack() {
        val currentTrackState = generateTrackStateJson()
        val track = Track(
            id = dbHelper.getNextTrackId(),
            dt = System.currentTimeMillis(),
            state = currentTrackState
        )
        dbHelper.saveTrack(track)

    }

    private fun generateTrackStateJson(): String {
        val jsonObject = org.json.JSONObject()
        val trackPointsArray = org.json.JSONArray()
        val checkpointsArray = org.json.JSONArray()
        val speedsArray = org.json.JSONArray()

        for (point in LocationRepository.trackedPoints) {
            val pointObject = org.json.JSONObject()
            pointObject.put("latitude", point.latitude)
            pointObject.put("longitude", point.longitude)
            trackPointsArray.put(pointObject)
        }

        for (checkpoint in LocationRepository.checkpoints) {
            val checkpointObject = org.json.JSONObject()
            checkpointObject.put("latitude", checkpoint.latitude)
            checkpointObject.put("longitude", checkpoint.longitude)
            checkpointsArray.put(checkpointObject)
        }

        for (speed in LocationRepository.speeds) {
            speedsArray.put(speed)
        }

        jsonObject.put("trackPoints", trackPointsArray)
        jsonObject.put("checkpoints", checkpointsArray)
        jsonObject.put("speeds", speedsArray)

        return jsonObject.toString()
    }

    private fun updateDistanceFromCP() {
        if (LocationRepository.cpStartLocation != null) {
            val cpDist = LocationRepository.regularDistanceFromCP
            distanceCpToCurrent.text = if (cpDist > 0) {
                String.format("%.0f m", cpDist)
            } else {
                "0 m"
            }
        } else {
            distanceCpToCurrent.text = "N/A"
        }
    }

    private fun updateDistanceFromWP() {
        if (LocationRepository.wpStartLocation != null) {
            val wpDist = LocationRepository.regularDistanceFromWP
            wpToCurrent.text = if (wpDist > 0) {
                String.format("%.0f m", wpDist)
            } else {
                "0 m"
            }
        } else {
            wpToCurrent.text = "N/A"
        }
    }

    private fun updateFlyDistanceFromWP() {
        if (LocationRepository.wpStartLocation != null && LocationRepository.currentLocation != null) {
            val flyDistance = LocationRepository.wpStartLocation!!.distanceTo(LocationRepository.currentLocation!!)
            flyWpToCurrent.text = String.format("%.0f m", flyDistance)
        } else {
            flyWpToCurrent.text = "N/A"
        }
    }

    private fun updateWpAverageSpeed() {
        if (LocationRepository.wpStartLocation != null && LocationRepository.regularDistanceFromWP > 0) {
            val elapsedMillis = System.currentTimeMillis() - LocationRepository.wpSessionStartTime
            val elapsedMinutes = elapsedMillis / 60000.0 // to min

            val distanceInKm = LocationRepository.regularDistanceFromWP / 1000.0 // to km

            if (distanceInKm > 0) {
                val minutesPerKm = elapsedMinutes / distanceInKm
                wpAverageSpeed.text = String.format("%.1f min", minutesPerKm)
            } else {
                wpAverageSpeed.text = "N/A"
            }
        } else {
            wpAverageSpeed.text = "N/A"
        }
    }


    private fun updateCpAverageSpeed() {
        if (LocationRepository.cpStartLocation != null && LocationRepository.regularDistanceFromCP > 0) {
            val elapsedMillis = System.currentTimeMillis() - LocationRepository.cpSessionStartTime
            val elapsedMinutes = elapsedMillis / 60000.0 // to min

            val distanceInKm = LocationRepository.regularDistanceFromCP / 1000.0 // to km

            if (distanceInKm > 0) {
                val minutesPerKm = elapsedMinutes / distanceInKm
                cpAverageSpeed.text = String.format("%.1f min", minutesPerKm)
            } else {
                cpAverageSpeed.text = "N/A"
            }
        } else {
            cpAverageSpeed.text = "N/A"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        when (intent.action) {
            "ACTION_PLACE_CP" -> {
                onClickCheckPointButton(View(this))
                // Log.d(TAG, "Place Checkpoint action triggered from notification")
            }

            "ACTION_PLACE_WP" -> {
                onClickWayPointButton(View(this))
                // Log.d(TAG, "Place Waypoint action triggered from notification")
            }
        }
    }

    fun onClickWayPointButton(view: View) {
        addWaypoint()
    }


    private fun addWaypoint() {
        if (viewModel.isTracking) {
            val serviceIntent = Intent(this, LocationForegroundService::class.java).apply {
                action = LocationForegroundService.ACTION_PLACE_WP
            }
            startService(serviceIntent)
        } else {
            // Log.d(TAG, "Cannot add a waypoint. Not tracking.")
        }
    }

    private fun updateFlyDistanceFromCP() {
        if (LocationRepository.cpStartLocation != null && LocationRepository.currentLocation != null) {
            val flyDistance = LocationRepository.cpStartLocation!!.distanceTo(LocationRepository.currentLocation!!)
            flyCpToCurrent.text = String.format("%.0f m", flyDistance)
        } else {
            flyCpToCurrent.text = "N/A"
        }
    }

    fun onClickCheckPointButton(view: View) {
        addCheckpoint()
    }

    fun onClickOptionsButton(view: View) {
        bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.options, null)
        bottomSheetDialog?.setContentView(sheetView)

        val toolbar: LinearLayout = sheetView.findViewById(R.id.toolbar)
        val backButton: ImageButton = sheetView.findViewById(R.id.backButton)
        val showTracksButton: Button = sheetView.findViewById(R.id.btnShowTracks)
        val logoutButton: Button = sheetView.findViewById(R.id.logoutButton)
        val recyclerView: RecyclerView = sheetView.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val tracks = dbHelper.getAllTracks()

        trackAdapter = TrackAdapter(
            tracks,
            onTrackSelected = { track ->
                val intent = Intent(
                    this,
                    TrackDetailActivity::class.java
                )
                intent.putExtra("trackId", track.id)
                startActivity(intent)
                bottomSheetDialog?.dismiss()
            },
            onTrackDeleted = { track ->
                showConfirmationDialog(
                    context = this,
                    title = "Delete Track",
                    message = "Are you sure you want to delete this track?",
                    onConfirm = {
                        dbHelper.deleteTrack(track.id)
                        val updatedTracks = dbHelper.getAllTracks()
                        trackAdapter.updateTracks(updatedTracks)
                    }
                )
            },
            onTrackRenamed = { track ->
                showRenameTrackDialog(track)
            }
        )

        recyclerView.adapter = trackAdapter

        showTracksButton.setOnClickListener {
            showTracksButton.visibility = View.GONE
            toolbar.visibility = View.VISIBLE
            recyclerView.visibility = View.VISIBLE
        }

        backButton.setOnClickListener {
            toolbar.visibility = View.GONE
            recyclerView.visibility = View.GONE
            showTracksButton.visibility = View.VISIBLE
        }

        logoutButton.setOnClickListener {
            showConfirmationDialog(
                context = this,
                title = "Logout",
                message = "Are you sure you want to logout?",
                onConfirm = {
                    performLogout()
                }
            )
        }

        bottomSheetDialog?.show()
    }

    fun performLogout() {
        val sharedPref = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            clear()
            apply()
        }

        if (!isFinishing) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }


    fun onClickNorthUpButton(view: View) {
        viewModel.isTrackingUserRotation = false

        LocationRepository.currentLocation?.let { location ->
            if (map != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                map!!.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.builder()
                            .target(latLng)
                            .zoom(17f)
                            .bearing(0f)
                            .tilt(0f)
                            .build()
                    )
                )
                // Log.d(TAG, "North Up activated. Camera moved to: ${latLng.latitude}, ${latLng.longitude} with bearing: 0°")
            }
        } ?: run {
            // Log.e(TAG, "Current location is null. Cannot move camera to North Up.")
        }
    }


    fun onClickResetButton(view: View) {
        viewModel.isTrackingUserRotation = true

        LocationRepository.currentLocation?.let { location ->
            if (map != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                map!!.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.builder()
                            .target(latLng)
                            .zoom(17f)
                            .bearing(location.bearing)
                            .tilt(0f)
                            .build()
                    )
                )
                // Log.d(TAG, "Tracking rotation reset. Camera moved to: ${latLng.latitude}, ${latLng.longitude} with bearing: ${location.bearing}°")
            }
        } ?: run {
            // Log.e(TAG, "Current location is null. Cannot reset camera to tracking user rotation.")
        }
    }


    fun onClickCompassButton(view: View) {
        viewModel.isCompassVisible = !viewModel.isCompassVisible
        compassImageView.visibility = if (viewModel.isCompassVisible) View.VISIBLE else View.GONE
    }


    private fun handleNewLocationFromService(location: Location, speed: Float) {
        // Log.d(TAG, "handleNewLocationFromService: Received location - Latitude: ${location.latitude}, Longitude: ${location.longitude}, Speed: $speed km/h")

        updateUIWithRepositoryData()

        rebuildPolylineFromRepository()

        locationService?.updateNotificationWithNewData()

        if (map != null) {
            val latLng = LatLng(location.latitude, location.longitude)
            val cameraUpdate = if (viewModel.isTrackingUserRotation) {
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.builder()
                        .target(latLng)
                        .zoom(17f)
                        .bearing(location.bearing)
                        .tilt(0f)
                        .build()
                )
            } else {

                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.builder()
                        .target(latLng)
                        .zoom(17f)
                        .bearing(0f)
                        .tilt(0f)
                        .build()
                )
            }

            map!!.animateCamera(cameraUpdate)
            // Log.d(TAG, "Camera moved to: ${latLng.latitude}, ${latLng.longitude} with bearing: ${if (viewModel.isTrackingUserRotation) location.bearing else 0f}")
        } else {
            // Log.d(TAG, "Map is null. Cannot move camera.")
        }
    }

    private fun updateUIWithRepositoryData() {

        val distanceSum = LocationRepository.distanceSum
        distanceCovered.text = String.format("%.0f m", distanceSum)


        val cpDist = LocationRepository.regularDistanceFromCP
        distanceCpToCurrent.text = if (cpDist > 0) {
            String.format("%.0f m", cpDist)
        } else {
            "0 m"
        }

        val wpDist = LocationRepository.regularDistanceFromWP
        wpToCurrent.text = if (wpDist > 0) {
            String.format("%.0f m", wpDist)
        } else {
            "0 m"
        }

        updateFlyDistanceFromCP()
        updateFlyDistanceFromWP()

        updateCpAverageSpeed()
        updateWpAverageSpeed()

        updateSessionDurationAndAverageSpeed()
    }

    private fun updateSessionDurationAndAverageSpeed() {
        val elapsedMillis = System.currentTimeMillis() - LocationRepository.sessionStartTime
        val elapsedSeconds = elapsedMillis / 1000.0

        val distanceInKm = LocationRepository.distanceSum / 1000.0
        if (distanceInKm > 0) {
            val elapsedMinutes = elapsedSeconds / 60.0
            val minutesPerKm = elapsedMinutes / distanceInKm
            val formattedSpeed = String.format("%.1f min/km", minutesPerKm)
            averageSpeed.text = formattedSpeed
        } else {
            averageSpeed.text = "N/A"
        }

        val totalSeconds = elapsedSeconds.toLong()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val formattedDuration = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        sessionDuration.text = formattedDuration
    }

}