package ee.taltech.gps_sportmap.dto

import kotlinx.serialization.Serializable

@Serializable
data class GpsSessionsDTO(
    val name: String,
    val description: String,
    val gpsSessionTypeId: String
)
