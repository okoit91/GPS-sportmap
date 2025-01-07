package ee.taltech.gps_sportmap.domain

import com.google.android.gms.maps.model.LatLng

data class TrackData(
    val trackPoints: List<LatLng>,
    val checkpoints: List<LatLng>,
    val waypoints: List<LatLng>,
    val speeds: List<Float>
)
