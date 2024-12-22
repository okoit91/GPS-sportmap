package ee.taltech.gps_sportmap.domain

data class Track(
    val id: Int = 0,
    val dt: Long = 0,
    val state: String,
    val name: String = "Unnamed Track"
)
