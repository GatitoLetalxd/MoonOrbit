package online.moondev.orbit.data.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// Models
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val phone: String? = null,
    val avatarVersion: Long = 0,
    val mustChangePassword: Boolean = false,
    val acceptedTermsVersion: String? = null
)

data class SessionDto(
    val token: String,
    val expiresAt: String
)

data class GroupSummaryDto(
    val id: String,
    val name: String,
    val role: String? = null,
    val status: String? = null,
    val memberCount: Int? = null
)

data class AuthResponse(
    val user: UserDto,
    val session: SessionDto,
    val group: GroupSummaryDto? = null,
    val serverTime: String
)

data class MemberStateDto(
    val status: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Double? = null,
    val lowAccuracy: Boolean = false,
    val batteryLevel: Int? = null,
    val isCharging: Boolean? = null,
    val recordedAt: String? = null,
    val lastSeenAt: String? = null,
    val statusChangedAt: String? = null
)

data class GroupMemberDto(
    val userId: String,
    val username: String? = null,
    val displayName: String,
    val phone: String? = null,
    val role: String,
    val avatarVersion: Long = 0,
    val joinedAt: String? = null,
    val state: MemberStateDto? = null,
    // Flattened telemetry fields as per official documentation
    val status: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Double? = null,
    val lowAccuracy: Boolean = false,
    val batteryLevel: Int? = null,
    val isCharging: Boolean? = null,
    val recordedAt: String? = null,
    val lastSeenAt: String? = null,
    val statusChangedAt: String? = null
) {
    val memberState: MemberStateDto
        get() = state ?: MemberStateDto(
            status = status ?: "no_signal",
            lat = lat,
            lng = lng,
            accuracyM = accuracyM,
            lowAccuracy = lowAccuracy,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            recordedAt = recordedAt,
            lastSeenAt = lastSeenAt,
            statusChangedAt = statusChangedAt
        )
}

data class SnapshotResponse(
    val group: GroupSummaryDto,
    val members: List<GroupMemberDto>,
    val serverTime: String
)

data class PendingMemberDto(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarVersion: Long = 0,
    val joinedAt: String
)

data class PendingListResponse(
    val pending: List<PendingMemberDto>,
    val serverTime: String
)

data class InviteCodeResponse(
    val inviteCode: String,
    val expiresAt: String,
    val serverTime: String
)

data class LocationPointDto(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double? = null,
    val batteryLevel: Int? = null,
    val isCharging: Boolean? = null,
    val recordedAt: String
)

data class LocationBatchResponse(
    val accepted: Int,
    val ignored: Int,
    val effectiveRecordedAt: String?,
    val status: String,
    val serverTime: String
)

data class AvatarUploadResponse(
    val avatarVersion: Long,
    val serverTime: String
)

data class ApiErrorDetails(
    val code: String,
    val message: String,
    val requestId: String? = null
)

data class ApiErrorResponse(
    val error: ApiErrorDetails
)

class MoonOrbitApiException(val code: String, override val message: String) : Exception(message)

class MoonOrbitApi(
    private var baseUrl: String = "http://10.0.2.2:3000",
    private val appVersionCode: Int = 1
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private var authToken: String? = null

    fun setAuthToken(token: String?) {
        this.authToken = token
    }

    fun setBaseUrl(url: String) {
        this.baseUrl = url.trimEnd('/')
    }

    fun getBaseUrl(): String = baseUrl

    fun getAvatarUrl(userId: String, avatarVersion: Long = 0): String {
        val tokenParam = if (!authToken.isNullOrEmpty()) "&token=$authToken" else ""
        return "$baseUrl/api/v1/users/$userId/avatar?v=$avatarVersion$tokenParam"
    }

    private fun newRequestBuilder(endpoint: String): Request.Builder {
        val builder = Request.Builder()
            .url("$baseUrl$endpoint")
            .addHeader("X-App-Version-Code", appVersionCode.toString())
            .addHeader("Accept", "application/json")

        authToken?.let {
            builder.addHeader("Authorization", "Bearer $it")
        }
        return builder
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> executeRequest(request: Request, classType: Class<T>): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                try {
                    val err = gson.fromJson(bodyString, ApiErrorResponse::class.java)
                    throw MoonOrbitApiException(err.error.code, err.error.message)
                } catch (e: Exception) {
                    if (e is MoonOrbitApiException) throw e
                    throw MoonOrbitApiException("http_error_${response.code}", "Error ${response.code}: $bodyString")
                }
            }

            if (classType == Unit::class.java) {
                return@withContext Unit as T
            }

            gson.fromJson(bodyString, classType)
        }
    }

    // 1. Login
    suspend fun login(username: String, pass: String, deviceName: String?): AuthResponse {
        val json = gson.toJson(mapOf("username" to username, "password" to pass, "deviceName" to deviceName))
        val body = json.toRequestBody("application/json".toMediaType())
        val req = newRequestBuilder("/api/v1/auth/login").post(body).build()
        val res = executeRequest(req, AuthResponse::class.java)
        setAuthToken(res.session.token)
        return res
    }

    // 2. Register
    suspend fun register(
        inviteCode: String,
        username: String,
        pass: String,
        displayName: String,
        termsVersion: String,
        deviceName: String?
    ): AuthResponse {
        val json = gson.toJson(
            mapOf(
                "inviteCode" to inviteCode,
                "username" to username,
                "password" to pass,
                "displayName" to displayName,
                "termsVersion" to termsVersion,
                "deviceName" to deviceName
            )
        )
        val body = json.toRequestBody("application/json".toMediaType())
        val req = newRequestBuilder("/api/v1/auth/register").post(body).build()
        val res = executeRequest(req, AuthResponse::class.java)
        setAuthToken(res.session.token)
        return res
    }

    // 3. Logout
    suspend fun logout() {
        val req = newRequestBuilder("/api/v1/auth/logout").post("{}".toRequestBody("application/json".toMediaType())).build()
        executeRequest(req, Unit::class.java)
        setAuthToken(null)
    }

    // 4. Snapshot
    suspend fun getSnapshot(groupId: String): SnapshotResponse {
        val req = newRequestBuilder("/api/v1/groups/$groupId/snapshot").get().build()
        return executeRequest(req, SnapshotResponse::class.java)
    }

    // 5. Send location batch
    suspend fun sendLocation(points: List<LocationPointDto>): LocationBatchResponse {
        val json = gson.toJson(mapOf("points" to points))
        val body = json.toRequestBody("application/json".toMediaType())
        val req = newRequestBuilder("/api/v1/location").post(body).build()
        return executeRequest(req, LocationBatchResponse::class.java)
    }

    // 6. Send heartbeat
    suspend fun sendHeartbeat(batteryLevel: Int?, isCharging: Boolean?) {
        val json = gson.toJson(mapOf("batteryLevel" to batteryLevel, "isCharging" to isCharging))
        val body = json.toRequestBody("application/json".toMediaType())
        val req = newRequestBuilder("/api/v1/heartbeat").post(body).build()
        executeRequest(req, Unit::class.java)
    }

    // 7. Change Status (active / paused / location_disabled)
    suspend fun updateStatus(status: String, batteryLevel: Int?, isCharging: Boolean?) {
        val json = gson.toJson(mapOf("status" to status, "batteryLevel" to batteryLevel, "isCharging" to isCharging))
        val body = json.toRequestBody("application/json".toMediaType())
        val req = newRequestBuilder("/api/v1/me/status").post(body).build()
        executeRequest(req, Unit::class.java)
    }

    // 8. Pending members
    suspend fun getPendingMembers(groupId: String): PendingListResponse {
        val req = newRequestBuilder("/api/v1/groups/$groupId/pending").get().build()
        return executeRequest(req, PendingListResponse::class.java)
    }

    // 9. Approve member
    suspend fun approveMember(groupId: String, userId: String) {
        val req = newRequestBuilder("/api/v1/groups/$groupId/members/$userId/approve")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        executeRequest(req, Unit::class.java)
    }

    // 10. Reject member
    suspend fun rejectMember(groupId: String, userId: String) {
        val req = newRequestBuilder("/api/v1/groups/$groupId/members/$userId/reject")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        executeRequest(req, Unit::class.java)
    }

    // 11. Get invite code
    suspend fun getInviteCode(groupId: String): InviteCodeResponse {
        val req = newRequestBuilder("/api/v1/groups/$groupId/invite").get().build()
        return executeRequest(req, InviteCodeResponse::class.java)
    }

    // 12. Regenerate invite code
    suspend fun regenerateInviteCode(groupId: String): InviteCodeResponse {
        val req = newRequestBuilder("/api/v1/groups/$groupId/invite/regenerate")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        return executeRequest(req, InviteCodeResponse::class.java)
    }

    // 13. Get Current User / Group Status
    suspend fun getMe(): AuthResponse {
        val req = newRequestBuilder("/api/v1/users/me").get().build()
        return executeRequest(req, AuthResponse::class.java)
    }

    // 14. Update Profile
    suspend fun updateProfile(displayName: String?, phone: String?) {
        val map = mutableMapOf<String, Any?>()
        if (displayName != null) map["displayName"] = displayName
        if (phone != null) map["phone"] = phone
        val json = gson.toJson(map)
        val body = json.toRequestBody("application/json".toMediaType())
        val req = newRequestBuilder("/api/v1/users/me").patch(body).build()
        executeRequest(req, Unit::class.java)
    }

    // 15. Change Password
    suspend fun changePassword(currentPass: String, newPass: String) {
        val json = gson.toJson(mapOf("currentPassword" to currentPass, "newPassword" to newPass))
        val body = json.toRequestBody("application/json".toMediaType())
        val req = newRequestBuilder("/api/v1/auth/change-password").post(body).build()
        executeRequest(req, Unit::class.java)
    }

    // 16. Upload Avatar
    suspend fun uploadAvatar(webpBytes: ByteArray): Long {
        val body = webpBytes.toRequestBody("image/webp".toMediaType())
        val req = newRequestBuilder("/api/v1/users/me/avatar").put(body).build()
        val res = executeRequest(req, AvatarUploadResponse::class.java)
        return res.avatarVersion
    }

    // 17. Delete Avatar
    suspend fun deleteAvatar() {
        val req = newRequestBuilder("/api/v1/users/me/avatar").delete().build()
        executeRequest(req, Unit::class.java)
    }
}
