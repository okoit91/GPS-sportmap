package ee.taltech.gps_sportmap.dto

import kotlinx.serialization.Serializable

@Serializable
data class GpsLocationDTO(
    val recordedAt: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val altitude: Double,
    val verticalAccuracy: Double,
    val gpsLocationTypeId: String
)
