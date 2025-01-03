package ee.taltech.gps_sportmap

import com.google.android.gms.maps.model.LatLng
import android.location.Location
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object LocationRepository {
    val trackedPoints = mutableListOf<LatLng>()
    val speeds = mutableListOf<Float>()
    val checkpoints = mutableListOf<LatLng>()
    val waypoints = mutableListOf<LatLng>()


    var currentLocation: Location? = null

    var distanceSum: Double = 0.0

    var cpStartLocation: Location? = null
    var cpSessionStartTime: Long = 0L
    var regularDistanceFromCP: Double = 0.0

    var wpStartLocation: Location? = null
    var wpSessionStartTime: Long = 0L
    var regularDistanceFromWP: Double = 0.0

    var sessionStartTime: Long = 0L

    private val lock = ReentrantLock()

    fun calculateAverageSpeed(startTime: Long, distance: Double): Double {
        if (distance == 0.0) return 0.0
        val elapsedTimeMinutes = (System.currentTimeMillis() - startTime) / 60000.0
        return if (elapsedTimeMinutes > 0) elapsedTimeMinutes / (distance / 1000.0) else 0.0
    }

    fun addCheckpoint(location: Location) {
        lock.withLock {
            val cpLatLng = LatLng(location.latitude, location.longitude)
            checkpoints.add(cpLatLng)
            cpStartLocation = location
            regularDistanceFromCP = 0.0
            cpSessionStartTime = System.currentTimeMillis()
        }
    }

    fun addWaypoint(location: Location) {
        lock.withLock {
            val wpLatLng = LatLng(location.latitude, location.longitude)
            waypoints.add(wpLatLng)
            wpStartLocation = location
            regularDistanceFromWP = 0.0
            wpSessionStartTime = System.currentTimeMillis()
        }
    }

}