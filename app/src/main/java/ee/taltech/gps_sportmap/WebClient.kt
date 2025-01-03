package ee.taltech.gps_sportmap

import android.content.Context
import android.util.Log
import ee.taltech.gps_sportmap.dto.GpsLocationDTO
import ee.taltech.gps_sportmap.dto.GpsSessionsDTO
import ee.taltech.gps_sportmap.dto.GpsSessionsResponseDTO
import ee.taltech.gps_sportmap.dto.LoginDTO
import ee.taltech.gps_sportmap.dto.LoginResponseDTO
import ee.taltech.gps_sportmap.dto.RegisterDTO
import ee.taltech.gps_sportmap.dto.RegisterResponseDTO
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object WebClient {
    private const val TAG = "WebClient"

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout)

        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }

        install(DefaultRequest) {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
        }

        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.ALL
            filter { request ->
                request.url.host.contains("ktor.io")
            }
            sanitizeHeader { header -> header == HttpHeaders.Authorization }
        }
    }

    object AppPreferences {
        fun getAuthToken(context: Context): String? {
            val sharedPref = context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            return sharedPref.getString("authToken", null)
        }
    }

    suspend fun login(context: Context, user: String, password: String): LoginResponseDTO {
        try {
            val response: HttpResponse = client
                .post("https://sportmap.akaver.com/api/v1/Account/Login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginDTO(user, password))
                }

            val responseBody = response.bodyAsText()

            if (response.status.isSuccess()) {
                val result = response.body<LoginResponseDTO>()

                // Save token and login status
                val sharedPref = context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
                with(sharedPref.edit()) {
                    putString("authToken", result.token)
                    putBoolean("isLoggedIn", true)
                    apply()
                }

                return result
            } else {
                // Log.e(TAG, "Error: ${response.status} - $responseBody")
                throw Exception("Login failed: ${response.status.description} - $responseBody")
            }
        } catch (e: Exception) {
            // Log.e(TAG, "Unexpected error during login", e)
            throw Exception("Unexpected error: ${e.message}")
        }
    }

    suspend fun register(context: Context, email: String, password: String, firstName: String, lastName: String): RegisterResponseDTO {
        try {
            val response: HttpResponse = client
                .post("https://sportmap.akaver.com/api/v1/Account/Register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterDTO(email, password, firstName, lastName))
                }

            val responseBody = response.bodyAsText()

            if (response.status.isSuccess()) {
                val result = response.body<RegisterResponseDTO>()

                val sharedPref = context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
                with(sharedPref.edit()) {
                    putString("authToken", result.token)
                    putBoolean("isLoggedIn", true)
                    apply()
                }

                return result
            } else {
                // Log.e(TAG, "Error: ${response.status} - $responseBody")
                throw Exception("Registration failed: ${response.status.description} - $responseBody")
            }
        } catch (e: Exception) {
            // Log.e(TAG, "Unexpected error during registration", e)
            throw Exception("Unexpected error: ${e.message}")
        }
    }

    suspend fun createGpsSession(
        context: Context,
        name: String,
        description: String,
        gpsSessionTypeId: String
    ): String {
        try {
            val sharedPref = context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            val token = sharedPref.getString("authToken", null)

            val response: GpsSessionsResponseDTO = client.post("https://sportmap.akaver.com/api/v1/GpsSessions") {
                contentType(ContentType.Application.Json)
                if (!token.isNullOrEmpty()) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                setBody(
                    GpsSessionsDTO(
                        name = name,
                        description = description,
                        gpsSessionTypeId = gpsSessionTypeId
                    )
                )
            }.body()

            return response.id
        } catch (e: Exception) {
            // Log.e(TAG, "Error creating GPS session", e)
            throw Exception("Failed to create GPS session: ${e.message}")
        }
    }

    suspend fun postLocationsInBulk(context: Context, sessionId: String, locations: List<GpsLocationDTO>) {
        val endpoint = "https://sportmap.akaver.com/api/v1/GpsLocations/bulk/$sessionId"

        try {
            val serializedPayload = Json.encodeToString(locations)

            // Log.d("BulkUpload", "Sending payload: $serializedPayload")

            val response: HttpResponse = client.post(endpoint) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${AppPreferences.getAuthToken(context)}")
                setBody(serializedPayload)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw Exception("Failed to upload locations in bulk: ${response.status} - $errorBody")
            }

            // Log.d("BulkUpload", "Successfully uploaded locations.")
        } catch (e: Exception) {
            // Log.e("BulkUpload", "Error posting locations in bulk", e)
            throw e
        }
    }

}