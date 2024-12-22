package ee.taltech.gps_sportmap

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ee.taltech.gps_sportmap.dal.DbHelper
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions

class TrackDetailActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var dbHelper: DbHelper
    private var trackId: Int = 0

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

    private fun displayTrack(googleMap: GoogleMap) {
        val track = dbHelper.loadTrack(trackId)
        if (track != null) {
            val (trackPoints, checkpoints, speeds) = parseTrackStateWithSpeeds(track.state)

            if (trackPoints.isNotEmpty()) {
                for (i in 1 until trackPoints.size) {
                    val startPoint = trackPoints[i - 1]
                    val endPoint = trackPoints[i]
                    val speed = speeds[i - 1]

                    val color = getColorForSpeed(speed)
                    val polylineOptions = PolylineOptions()
                        .add(startPoint, endPoint)
                        .color(color)
                        .width(8f)

                    googleMap.addPolyline(polylineOptions)
                }

                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trackPoints[0], 15f))
            }
            for (checkpoint in checkpoints) {
                googleMap.addMarker(
                    MarkerOptions()
                        .position(checkpoint)
                        .title("Checkpoint")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                )
            }
        }
    }

    private fun parseTrackStateWithSpeeds(state: String): Triple<List<LatLng>, List<LatLng>, List<Float>> {
        val trackPoints = mutableListOf<LatLng>()
        val checkpoints = mutableListOf<LatLng>()
        val speeds = mutableListOf<Float>()

        try {
            val jsonObject = org.json.JSONObject(state)
            val pointsArray = jsonObject.getJSONArray("trackPoints")
            val checkpointsArray = jsonObject.getJSONArray("checkpoints")
            val speedsArray = jsonObject.getJSONArray("speeds")

            for (i in 0 until pointsArray.length()) {
                val point = pointsArray.getJSONObject(i)
                trackPoints.add(LatLng(point.getDouble("latitude"), point.getDouble("longitude")))
            }

            for (i in 0 until checkpointsArray.length()) {
                val checkpoint = checkpointsArray.getJSONObject(i)
                checkpoints.add(LatLng(checkpoint.getDouble("latitude"), checkpoint.getDouble("longitude")))
            }

            for (i in 0 until speedsArray.length()) {
                speeds.add(speedsArray.getDouble(i).toFloat())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return Triple(trackPoints, checkpoints, speeds)
    }

    private fun getColorForSpeed(speed: Float): Int {
        return when {
            speed < 5 -> Color.RED
            speed < 10 -> Color.YELLOW
            speed < 15 -> Color.GREEN
            else -> Color.BLUE
        }
    }
}