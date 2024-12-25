package ee.taltech.gps_sportmap.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginResponseDTO(
    val token: String,
    val status: String,
    val firstName: String,
    val lastName: String,
)
