package ee.taltech.gps_sportmap.domain

data class ActivityType(
    val name: String,
    val description: String,
    val paceMin: Int,
    val paceMax: Int,
    val id: String
)
