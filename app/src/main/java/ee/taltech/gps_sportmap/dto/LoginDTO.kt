package ee.taltech.gps_sportmap.dto
import kotlinx.serialization.Serializable

@Serializable
data class LoginDTO(
    val email: String,
    val password: String,
)
