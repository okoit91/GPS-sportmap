package ee.taltech.gps_sportmap

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import ee.taltech.gps_sportmap.dal.DbHelper
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import ee.taltech.gps_sportmap.domain.TrackData
import ee.taltech.gps_sportmap.dto.GpsLocationDTO

class TrackDetailActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var dbHelper: DbHelper
    private var trackId: Int = 0
    private lateinit var trackData: TrackData

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track_detail)

        dbHelper = DbHelper(this)

        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Track Details"
        }

        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }

        trackId = intent.getIntExtra("trackId", -1)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)
    }
    override fun onMapReady(googleMap: GoogleMap) {
        displayTrack(googleMap)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_gpx -> {
                exportGpx()
                true
            }
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_track_detail, menu)
        return true
    }

    private fun displayTrack(googleMap: GoogleMap) {
        val track = dbHelper.loadTrack(trackId)
        if (track != null) {
            trackData = parseTrackStateWithSpeeds(track.state)

            if (trackData.trackPoints.isNotEmpty()) {
                for (i in 1 until trackData.trackPoints.size) {
                    val startPoint = trackData.trackPoints[i - 1]
                    val endPoint = trackData.trackPoints[i]
                    val speed = trackData.speeds[i - 1]

                    val color = getColorForSpeed(speed)
                    val polylineOptions = PolylineOptions()
                        .add(startPoint, endPoint)
                        .color(color)
                        .width(8f)

                    googleMap.addPolyline(polylineOptions)
                }

                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trackData.trackPoints[0], 15f))
            }

            for (checkpoint in trackData.checkpoints) {
                googleMap.addMarker(
                    MarkerOptions()
                        .position(checkpoint)
                        .title("Checkpoint")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                )
            }

            for (waypoint in trackData.waypoints) {
                googleMap.addMarker(
                    MarkerOptions()
                        .position(waypoint)
                        .title("Waypoint")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
            }
        }
    }

    private fun parseTrackStateWithSpeeds(state: String): TrackData {
        val trackPoints = mutableListOf<LatLng>()
        val checkpoints = mutableListOf<LatLng>()
        val waypoints = mutableListOf<LatLng>()
        val speeds = mutableListOf<Float>()

        try {
            val jsonObject = org.json.JSONObject(state)

            if (!jsonObject.has("trackPoints")) {
                throw IllegalArgumentException("Missing 'trackPoints' in JSON")
            }
            if (!jsonObject.has("checkpoints")) {
                throw IllegalArgumentException("Missing 'checkpoints' in JSON")
            }
            if (!jsonObject.has("waypoints")) {
                throw IllegalArgumentException("Missing 'waypoints' in JSON")
            }
            if (!jsonObject.has("speeds")) {
                throw IllegalArgumentException("Missing 'speeds' in JSON")
            }

            val pointsArray = jsonObject.getJSONArray("trackPoints")
            val checkpointsArray = jsonObject.getJSONArray("checkpoints")
            val waypointsArray = jsonObject.getJSONArray("waypoints")
            val speedsArray = jsonObject.getJSONArray("speeds")

            for (i in 0 until pointsArray.length()) {
                val point = pointsArray.getJSONObject(i)
                trackPoints.add(LatLng(point.getDouble("latitude"), point.getDouble("longitude")))
            }

            for (i in 0 until checkpointsArray.length()) {
                val checkpoint = checkpointsArray.getJSONObject(i)
                checkpoints.add(LatLng(checkpoint.getDouble("latitude"), checkpoint.getDouble("longitude")))
            }

            for (i in 0 until waypointsArray.length()) {
                val waypoint = waypointsArray.getJSONObject(i)
                waypoints.add(LatLng(waypoint.getDouble("latitude"), waypoint.getDouble("longitude")))
            }

            for (i in 0 until speedsArray.length()) {
                speeds.add(speedsArray.getDouble(i).toFloat())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error parsing track state: ${e.message}", Toast.LENGTH_LONG).show()
        }

        return TrackData(trackPoints, checkpoints, waypoints, speeds)
    }

    private fun getColorForSpeed(speed: Float): Int {
        return when {
            speed < 5 -> Color.RED
            speed < 10 -> Color.YELLOW
            speed < 15 -> Color.GREEN
            else -> Color.BLUE
        }
    }

    private fun exportGpx() {
        val track = dbHelper.loadTrack(trackId)
        if (track == null) {
            Toast.makeText(this, "Track not found.", Toast.LENGTH_SHORT).show()
            return
        }

        val (trackPoints, waypoints, _) = parseTrackStateWithSpeeds(track.state)

        if (trackPoints.isEmpty()) {
            Toast.makeText(this, "No track points to export.", Toast.LENGTH_SHORT).show()
            return
        }


        val trackPointsDto = trackPoints.map { latLng ->
            GpsLocationDTO(
                recordedAt = getCurrentISO8601Timestamp(),
                latitude = latLng.latitude,
                longitude = latLng.longitude,
                accuracy = 0.0,
                altitude = 0.0,
                verticalAccuracy = 0.0,
                gpsLocationTypeId = "00000000-0000-0000-0000-000000000001"
            )
        }

        val checkpointsDto = waypoints.map { latLng ->
            GpsLocationDTO(
                recordedAt = getCurrentISO8601Timestamp(),
                latitude = latLng.latitude,
                longitude = latLng.longitude,
                accuracy = 0.0,
                altitude = 0.0,
                verticalAccuracy = 0.0,
                gpsLocationTypeId = "00000000-0000-0000-0000-000000000003"
            )
        }

        // Generate GPX file
        val gpxFile = GpxHelper.generateGpxFile(
            context = this,
            sessionName = track.name,
            trackPoints = trackPointsDto,
            checkpoints = checkpointsDto
        )

        if (gpxFile != null) {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                gpxFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "GPX Export")
                putExtra(Intent.EXTRA_TEXT, "Exported from GPS Sport Map")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share GPX File"))
        } else {
            Toast.makeText(this, "Failed to export GPX file.", Toast.LENGTH_SHORT).show()
        }
    }
}