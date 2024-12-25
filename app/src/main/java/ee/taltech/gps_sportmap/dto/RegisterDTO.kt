package ee.taltech.gps_sportmap.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterDTO(
    val email: String,
    val password: String,
    val firstName: String,
    val lastName: String
)
