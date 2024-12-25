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
import android.location.LocationListener
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ee.taltech.gps_sportmap.dal.DbHelper
import ee.taltech.gps_sportmap.domain.Track
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

class MainActivity : AppCompatActivity(), OnMapReadyCallback, LocationListener, SensorEventListener {

    private lateinit var startButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var dbHelper: DbHelper

    private lateinit var compassButton: Button
    private var isCompassVisible = false

    private var isTracking = false
    private var currentLocation: Location? = null

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
    private var provider: String = LocationManager.GPS_PROVIDER


    private var isTrackingUserRotation = true


    private  var previousLocation : Location? = null
    private var distanceSum: Double = 0.0

    lateinit var sensorManager: SensorManager

    private var rotationSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private lateinit var compassImageView: ImageView

    private lateinit var distanceCovered: TextView


    private var startLocation: Location? = null
    private lateinit var sessionDuration: TextView
    private var sessionStartTime: Long = 0L

    private var isNotificationReceiverRegistered = false
    private var isLocationReceiverRegistered = false


    private val handler = android.os.Handler()

    private var bottomSheetDialog: BottomSheetDialog? = null

    private lateinit var averageSpeed: TextView

    private lateinit var distanceCpToCurrent: TextView
    private lateinit var flyCpToCurrent: TextView
    private lateinit var cpAverageSpeed: TextView

    private lateinit var wpToCurrent: TextView
    private lateinit var flyWpToCurrent: TextView
    private lateinit var wpAverageSpeed: TextView

    private val speeds = mutableListOf<Float>()

    private var cpStartLocation: Location? = null
    private var cpSessionStartTime: Long = 0L
    private var regularDistanceFromCP = 0.0


    private var wpStartLocation: Location? = null
    private var wpSessionStartTime: Long = 0L
    private var regularDistanceFromWP = 0.0

    private val trackedPoints = mutableListOf<LatLng>()
    private val checkpoints = mutableListOf<LatLng>()

    private val locationBuffer = mutableListOf<GpsLocationDTO>()
    private val bulkUploadInterval = 10
    private var lastUploadTime = System.currentTimeMillis()

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LocationForegroundService.ACTION_PLACE_CP -> {
                    Log.d(TAG, "NotificationReceiver: PLACE_CP received")
                    onClickCheckPointButton(View(context))
                }
                LocationForegroundService.ACTION_PLACE_WP -> {
                    Log.d(TAG, "NotificationReceiver: PLACE_WP received")
                    onClickWayPointButton(View(context))
                }
            }
        }
    }


    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val latitude = intent?.getDoubleExtra("latitude", 0.0)
            val longitude = intent?.getDoubleExtra("longitude", 0.0)
            if (latitude != null && longitude != null) {
                val location = Location(LocationManager.GPS_PROVIDER).apply {
                    this.latitude = latitude
                    this.longitude = longitude
                }
                onLocationChanged(location)
            }
        }
    }


    private val updateRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "Updating session duration")
            val elapsedMillis = System.currentTimeMillis() - sessionStartTime
            val elapsedSeconds = elapsedMillis / 1000.0

            val distanceInKm = distanceSum / 1000.0

            if (distanceInKm > 0) {
                val elapsedMinutes = elapsedSeconds / 60.0
                val minutesPerKm = elapsedMinutes / distanceInKm

                val formattedSpeed = String.format("%.1f min", minutesPerKm)
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

            handler.postDelayed(this, 5000)
        }
    }


    companion object {
        private const val TAG = "MainActivity"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 100
    }

    var map: GoogleMap? = null
    private var polyLine: Polyline? = null
    private var polylineOptions = mutableListOf<PolylineOptions>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            Log.d(TAG, "Location permissions granted")
            enableUserLocation()
        } else {
            Log.e(TAG, "Location permissions denied")
        }
    }

    private fun isDeviceReady(): Boolean {
        return try {
            android.os.SystemClock.uptimeMillis() > 0
        } catch (e: Exception) {
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        val isLoggedIn = sharedPref.getBoolean("isLoggedIn", false)

        if (!isLoggedIn) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            Log.d(TAG, "We did not log in!!")
            return
        }

        Log.d(TAG, "We logged in!!")

        setContentView(R.layout.activity_main)

        compassImageView = findViewById(R.id.compassImageView)


        if (isDeviceReady()) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }



        if (!checkLocationPermission()) {
            requestLocationPermission()
            return
        }

        dbHelper = DbHelper(this)

        trackAdapter = TrackAdapter(
            tracks = emptyList(),
            onTrackSelected = {},
            onTrackDeleted = {},
            onTrackRenamed = {}
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                locationReceiver,
                IntentFilter("LOCATION_UPDATE"),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(locationReceiver, IntentFilter("LOCATION_UPDATE"),
                RECEIVER_NOT_EXPORTED
            )
        }

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



        // map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val providers = locationManager.getProviders(true)

        // permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.FOREGROUND_SERVICE
                ),
                123
            )
            Log.e(TAG, "No rights yet, requested permissions.")
        }


        val location = locationManager.getLastKnownLocation(provider)

        if (providers.isEmpty()) {
            Log.e(TAG, "Location providers empty!")
            finish()
        } else {
            Log.d(TAG, providers.toString())
        }

        if (location != null) {
            Log.d(TAG, "Initial location: ${location.latitude}, ${location.longitude}")
            onLocationChanged(location)
        }

        requestLocationPermissions()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, LocationForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onResume() {
        super.onResume()

        if (isTracking) {
            handler.post(updateRunnable)
        }

        if (!isNotificationReceiverRegistered) {
            val notificationFilter = IntentFilter().apply {
                addAction(LocationForegroundService.ACTION_PLACE_CP)
                addAction(LocationForegroundService.ACTION_PLACE_WP)
            }

            LocalBroadcastManager.getInstance(this)
                .registerReceiver(notificationReceiver, notificationFilter)
            isNotificationReceiverRegistered = true
            Log.d(TAG, "notificationReceiver registered")


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(notificationReceiver, notificationFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(notificationReceiver, notificationFilter, RECEIVER_NOT_EXPORTED)
            }
            isNotificationReceiverRegistered = true
            Log.d(TAG, "notificationReceiver registered")
        }

        if (!isLocationReceiverRegistered) {
            val locationFilter = IntentFilter("LOCATION_UPDATE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(locationReceiver, locationFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(locationReceiver, locationFilter, RECEIVER_NOT_EXPORTED)
            }
            isLocationReceiverRegistered = true
        }

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)

        if (map != null) {
            rebuildPolylineFromMemory()
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationService()
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required for this feature",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startLocationService() {
        if (checkLocationPermission()) {
            val intent = Intent(this, LocationForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            requestLocationPermission()
        }
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onDestroy() {
        super.onDestroy()

        bottomSheetDialog?.dismiss()
        bottomSheetDialog = null

        if (isNotificationReceiverRegistered) {
            LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(notificationReceiver)
            isNotificationReceiverRegistered = false
            Log.d(TAG, "notificationReceiver unregistered in onDestroy")
        }
        if (isLocationReceiverRegistered) {
            unregisterReceiver(locationReceiver)
            isLocationReceiverRegistered = false
        }


    }

    private fun checkLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }


    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)

        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
        }

        if (isLocationReceiverRegistered) {
            unregisterReceiver(locationReceiver)
            isLocationReceiverRegistered = false
        }

    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.requestLocationUpdates(provider, 1000, 1f, this)
        }
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
        distanceSum = 0.0
        sessionStartTime = 0L

        distanceCovered.text = "N/A"
        averageSpeed.text = "N/A"
        sessionDuration.text = "00:00:00"
    }

    private fun resetCPValues() {
        cpStartLocation = null
        cpSessionStartTime = 0L
        regularDistanceFromCP = 0.0

        distanceCpToCurrent.text = "N/A"
        flyCpToCurrent.text = "N/A"
        cpAverageSpeed.text = "N/A"
    }

    private fun resetWPValues() {
        wpStartLocation = null
        wpSessionStartTime = 0L
        regularDistanceFromWP = 0.0

        wpToCurrent.text = "N/A"
        flyWpToCurrent.text = "N/A"
        wpAverageSpeed.text = "N/A"
    }

    private fun stopLocationUpdates() {
        locationManager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {

        val gpsLocation = GpsLocationDTO(
            recordedAt = getCurrentISO8601Timestamp(),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.toDouble(),
            altitude = location.altitude,
            verticalAccuracy = location.verticalAccuracyMeters.toDouble(),
            gpsLocationTypeId = "00000000-0000-0000-0000-000000000001"
        )

        synchronized(locationBuffer) {
            locationBuffer.add(gpsLocation)
        }

        if (locationBuffer.size >= bulkUploadInterval ||
            System.currentTimeMillis() - lastUploadTime > 60_000) {
            sendLocationsInBulk()
            lastUploadTime = System.currentTimeMillis()
        }


        val speed = if (previousLocation != null) {
            val distance = previousLocation!!.distanceTo(location)
            val time = (location.time - previousLocation!!.time) / 1_000.0
            if (time > 0) distance / time * 3.6f else 0f
        } else {
            0f
        }

        val cpDistanceValue = cpStartLocation?.distanceTo(location) ?: 0.0
        val wpDistanceValue = wpStartLocation?.distanceTo(location) ?: 0.0

        val cpFlyDist = currentLocation?.let { cpStartLocation?.distanceTo(it) } ?: 0.0
        val wpFlyDist = currentLocation?.let { wpStartLocation?.distanceTo(it) } ?: 0.0

        val cpAvgSpdValue = calculateAverageSpeed(cpStartLocation, cpSessionStartTime)
        val wpAvgSpdValue = calculateAverageSpeed(wpStartLocation, wpSessionStartTime)

        val cpDistanceText = String.format("%.0f m", cpDistanceValue)
        val wpDistanceText = String.format("%.0f m", wpDistanceValue)
        val cpFlyDistText = String.format("%.0f m", cpFlyDist)
        val wpFlyDistText = String.format("%.0f m", wpFlyDist)
        val cpAvgSpdText = String.format("%.1f min", cpAvgSpdValue)
        val wpAvgSpdText = String.format("%.1f min", wpAvgSpdValue)

        locationService?.updateNotification(
            cpDistance = cpDistanceText,
            cpFlyDist = cpFlyDistText,
            cpAvgSpd = cpAvgSpdText,
            wpDistance = wpDistanceText,
            wpFlyDist = wpFlyDistText,
            wpAvgSpd = wpAvgSpdText
        )

        currentLocation = location

        if (map == null) {
            return
        }

        val latLng = LatLng(location.latitude, location.longitude)
        trackedPoints.add(latLng)
        speeds.add(speed.toFloat())

        if (isTrackingUserRotation) {
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
        } else {
            map!!.moveCamera(CameraUpdateFactory.newLatLng(latLng))
        }

        if (previousLocation != null) {
            val distance = previousLocation!!.distanceTo(location)
            distanceSum += distance
            val distanceFormatted = String.format("%.0f", distanceSum)
            distanceCovered.text = "$distanceFormatted m"
        }

        updateDistanceFromWP(location)
        updateFlyDistanceFromWP()
        updateWpAverageSpeed()

        updateDistanceFromCP(location)
        updateFlyDistanceFromCP()
        updateCpAverageSpeed()

        rebuildPolylineFromMemory()

        previousLocation = location
    }

    private fun sendLocationsInBulk() {
        CoroutineScope(Dispatchers.IO).launch {
            val sessionId = getCurrentGpsSessionId()
            val bulkRequestBody: List<GpsLocationDTO>

            synchronized(locationBuffer) {
                if (locationBuffer.isEmpty()) return@launch
                bulkRequestBody = locationBuffer.toList()
                locationBuffer.clear()
            }

            if (!isInternetAvailable(this@MainActivity)) {
                Log.w("BulkUpload", "No internet connection. Locations will be kept in the buffer.")
                synchronized(locationBuffer) {
                    locationBuffer.addAll(bulkRequestBody) // Re-add locations to buffer
                }
                return@launch
            }

            try {
                if (sessionId != null) {
                    WebClient.postLocationsInBulk(context = this@MainActivity, sessionId, bulkRequestBody)
                }
                Log.d("BulkUpload", "Uploaded ${bulkRequestBody.size} locations successfully.")
            } catch (e: Exception) {
                Log.e("BulkUpload", "Failed to upload locations: ${e.message}")
                synchronized(locationBuffer) {
                    locationBuffer.addAll(bulkRequestBody) // Re-add locations to buffer
                }
            }
        }
    }


    private fun calculateAverageSpeed(startLocation: Location?, startTime: Long): Double {
        if (startLocation == null || startTime == 0L || regularDistanceFromCP == 0.0) return 0.0

        val elapsedTimeMillis = System.currentTimeMillis() - startTime
        val elapsedMinutes = elapsedTimeMillis / 60000.0

        val distanceInKm = regularDistanceFromCP / 1000.0

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
                System.arraycopy(event.values,
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
                magnetometerReading)
            ) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthInRadians = orientationAngles[0]
            val azimuthInDegrees = Math.toDegrees(azimuthInRadians.toDouble()).toFloat()

            compassImageView.rotation = -azimuthInDegrees
        }

    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: $accuracy")
    }


    private fun requestLocationPermissions() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {

            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            enableUserLocation()
        }
    }

    private fun enableUserLocation() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            map?.isMyLocationEnabled = true
        } else {
            Log.e(TAG, "Location permissions are not granted")
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        this.map = googleMap

        map?.let { map ->
            map.uiSettings.isCompassEnabled = false
            map.isMyLocationEnabled = checkLocationPermission()
        }



        val tallinn = LatLng(59.4, 24.7)
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(tallinn, 17.0f))
        enableUserLocation()

        rebuildPolylineFromMemory()
    }

    private fun rebuildPolylineFromMemory() {
        if (trackedPoints.isNotEmpty() && speeds.isNotEmpty()) {


            polylineOptions.clear()
            map?.clear()

            // gradient polyline
            for (i in 1 until trackedPoints.size) {
                val startPoint = trackedPoints[i - 1]
                val endPoint = trackedPoints[i]
                val speed = speeds[i - 1]

                val color = getColorForSpeed(speed)
                val segment = PolylineOptions()
                    .add(startPoint, endPoint)
                    .color(color)
                    .width(10f)

                polylineOptions.add(segment)
                map?.addPolyline(segment)
            }

            // cp markers
            for (checkpoint in checkpoints) {
                map?.addMarker(
                    MarkerOptions()
                        .position(checkpoint)
                        .title("Checkpoint")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                )
            }


            val lastLatLng = trackedPoints.last()
            val tempLocation = Location(provider).apply {
                latitude = lastLatLng.latitude
                longitude = lastLatLng.longitude
            }
            previousLocation = tempLocation
        }
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
        CoroutineScope(Dispatchers.Main).launch {
            try {

                val sessionId = createAndStartGpsSession()

                val serviceIntent = Intent(this@MainActivity, LocationForegroundService::class.java).apply {
                    putExtra("GpsSessionId", sessionId)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                startLocation = null
                currentLocation = null
                previousLocation = null
                cpStartLocation = null

                trackedPoints.clear()
                checkpoints.clear()

                isTracking = true
                startButton.visibility = View.GONE
                stopButton.visibility = View.VISIBLE
                startLocationUpdates()

                if (startLocation == null) {
                    startLocation = currentLocation
                }

                sessionStartTime = System.currentTimeMillis()
                handler.post(updateRunnable)

            } catch (e: Exception) {
                Log.e("GpsSession", "Failed to start session: ${e.message}")
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

            // Save the session ID in SharedPreferences
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
        showConfirmationDialog(
            context = this,
            title = "Confirm Stop",
            message = "Are you sure you want to stop tracking?",
            onConfirm = {
                val serviceIntent = Intent(this, LocationForegroundService::class.java)
                stopService(serviceIntent)

                isTracking = false
                stopButton.visibility = View.GONE
                startButton.visibility = View.VISIBLE
                stopLocationUpdates()

                CoroutineScope(Dispatchers.IO).launch {
                    val sessionId = getCurrentGpsSessionId()
                    val bulkRequestBody: List<GpsLocationDTO>

                    synchronized(locationBuffer) {
                        if (locationBuffer.isEmpty()) {
                            clearTrackingData()
                            return@launch
                        }
                        bulkRequestBody = locationBuffer.toList()
                    }

                    try {
                        if (sessionId != null && isInternetAvailable(this@MainActivity)) {
                            WebClient.postLocationsInBulk(
                                context = this@MainActivity,
                                sessionId = sessionId,
                                locations = bulkRequestBody
                            )
                            Log.d("StopButton", "Remaining locations uploaded successfully.")

                            synchronized(locationBuffer) {
                                locationBuffer.clear()
                            }
                        } else {
                            Log.e("StopButton", "No internet connection. Buffer not cleared.")
                        }
                    } catch (e: Exception) {
                        Log.e("StopButton", "Failed to upload remaining locations: ${e.message}")
                    } finally {
                        withContext(Dispatchers.Main) {
                            clearTrackingData()
                        }
                    }
                }
            }
        )
    }


    private fun clearTrackingData() {
        saveTrack()
        handler.removeCallbacks(updateRunnable)
        map?.clear()
        polyLine?.remove()
        trackedPoints.clear()
        checkpoints.clear()

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
        Toast.makeText(this, "Track saved", Toast.LENGTH_SHORT).show()
    }

    private fun generateTrackStateJson(): String {
        val jsonObject = org.json.JSONObject()
        val trackPointsArray = org.json.JSONArray()
        val checkpointsArray = org.json.JSONArray()
        val speedsArray = org.json.JSONArray()

        for (point in trackedPoints) {
            val pointObject = org.json.JSONObject()
            pointObject.put("latitude", point.latitude)
            pointObject.put("longitude", point.longitude)
            trackPointsArray.put(pointObject)
        }

        for (checkpoint in checkpoints) {
            val checkpointObject = org.json.JSONObject()
            checkpointObject.put("latitude", checkpoint.latitude)
            checkpointObject.put("longitude", checkpoint.longitude)
            checkpointsArray.put(checkpointObject)
        }

        for (speed in speeds) {
            speedsArray.put(speed)
        }

        jsonObject.put("trackPoints", trackPointsArray)
        jsonObject.put("checkpoints", checkpointsArray)
        jsonObject.put("speeds", speedsArray)

        return jsonObject.toString()
    }

    private fun updateDistanceFromCP(location: Location) {
        if (cpStartLocation != null && currentLocation != null) {
            if(previousLocation != null) {
                val incrementalDistance = previousLocation!!.distanceTo(location)
                regularDistanceFromCP += incrementalDistance
            }



            distanceCpToCurrent.text = String.format("%.0f m", regularDistanceFromCP)
        } else {
            distanceCpToCurrent.text = "N/A"
        }
    }

    private fun updateDistanceFromWP(location: Location) {
        if (wpStartLocation != null) {
            if (previousLocation != null) {
                val incrementalDistance = previousLocation!!.distanceTo(location)
                regularDistanceFromWP += incrementalDistance
            }
            wpToCurrent.text = String.format("%.0f m", regularDistanceFromWP)
        } else {
            wpToCurrent.text = "N/A"
        }
    }

    private fun updateFlyDistanceFromWP() {
        if (wpStartLocation != null && currentLocation != null) {
            val flyDistance = wpStartLocation!!.distanceTo(currentLocation!!)
            flyWpToCurrent.text = String.format("%.0f m", flyDistance)
        } else {
            flyWpToCurrent.text = "N/A"
        }
    }

    private fun updateWpAverageSpeed() {
        if (wpStartLocation != null && regularDistanceFromWP > 0) {
            val elapsedMillis = System.currentTimeMillis() - wpSessionStartTime
            val elapsedMinutes = elapsedMillis / 60000.0 // to min

            val distanceInKm = regularDistanceFromWP / 1000.0 // to km

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
        if (cpStartLocation != null && regularDistanceFromCP > 0) {
            val elapsedMillis = System.currentTimeMillis() - cpSessionStartTime
            val elapsedMinutes = elapsedMillis / 60000.0 // to min

            val distanceInKm = regularDistanceFromCP / 1000.0 // to km

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
                Log.d(TAG, "Place Checkpoint action triggered from notification")
            }
            "ACTION_PLACE_WP" -> {
                onClickWayPointButton(View(this))
                Log.d(TAG, "Place Waypoint action triggered from notification")
            }
        }
    }

    fun onClickWayPointButton(view: View) {
        if (isTracking && currentLocation != null) {
            wpStartLocation = currentLocation
            regularDistanceFromWP = 0.0
            wpSessionStartTime = System.currentTimeMillis()

            wpToCurrent.text = "0 m"
            flyWpToCurrent.text = "N/A"
            wpAverageSpeed.text = "N/A"

            val waypointLocation = GpsLocationDTO(
                recordedAt = getCurrentISO8601Timestamp(),
                latitude = currentLocation!!.latitude,
                longitude = currentLocation!!.longitude,
                accuracy = currentLocation!!.accuracy.toDouble(),
                altitude = currentLocation!!.altitude,
                verticalAccuracy = currentLocation!!.verticalAccuracyMeters.toDouble(),
                gpsLocationTypeId = "00000000-0000-0000-0000-000000000002"
            )

            synchronized(locationBuffer) {
                locationBuffer.add(waypointLocation)
            }

            Log.d(TAG, "Waypoint saved at: ${wpStartLocation?.latitude}," +
                    " ${wpStartLocation?.longitude}")
        }
    }
    private fun updateFlyDistanceFromCP() {
        if (cpStartLocation != null && currentLocation != null) {
            val flyDistance = cpStartLocation!!.distanceTo(currentLocation!!)
            flyCpToCurrent.text = String.format("%.0f m", flyDistance)
        } else {
            flyCpToCurrent.text = "N/A"
        }
    }

    fun onClickCheckPointButton(view: View) {
        if (isTracking && currentLocation != null && map != null) {
            val cpLatLng = LatLng(currentLocation!!.latitude, currentLocation!!.longitude)
            addCP(map!!, cpLatLng)

            checkpoints.add(cpLatLng)

            cpStartLocation = currentLocation

            regularDistanceFromCP = 0.0

            cpSessionStartTime = System.currentTimeMillis()
            cpAverageSpeed.text = "N/A"

            val checkpointLocation = GpsLocationDTO(
                recordedAt = getCurrentISO8601Timestamp(),
                latitude = currentLocation!!.latitude,
                longitude = currentLocation!!.longitude,
                accuracy = currentLocation!!.accuracy.toDouble(),
                altitude = currentLocation!!.altitude,
                verticalAccuracy = currentLocation!!.verticalAccuracyMeters.toDouble(),
                gpsLocationTypeId = "00000000-0000-0000-0000-000000000003" // Checkpoint ID
            )

            synchronized(locationBuffer) {
                locationBuffer.add(checkpointLocation)
            }


        } else {
            Log.d(TAG, "Cannot add a checkpoint." +
                    " Tracking: $isTracking," +
                    " Current Location: $currentLocation")
        }
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

        // Logout button functionality
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
        isTrackingUserRotation = false

        if (map != null) {
            val currentLocation = map!!.cameraPosition.target
            map!!.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.builder()
                        .target(currentLocation)
                        .zoom(17f)
                        .bearing(0f)
                        .tilt(0f)
                        .build()
                )
            )
        }
    }


    fun onClickResetButton(view: View) {
        isTrackingUserRotation = true
    }


    fun onClickCompassButton(view: View) {
        isCompassVisible = !isCompassVisible
        compassImageView.visibility = if (isCompassVisible) View.VISIBLE else View.GONE
    }

}