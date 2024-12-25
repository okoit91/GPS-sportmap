package ee.taltech.gps_sportmap.dto

import kotlinx.serialization.Serializable


@Serializable
data class GpsLocationBulkDTO(
    val gpsLocations: List<GpsLocationDTO>
)
