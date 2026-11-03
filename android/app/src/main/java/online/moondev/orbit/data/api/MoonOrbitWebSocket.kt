package online.moondev.orbit.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed class WsEvent {
    data class Hello(val serverTime: String, val sessionId: String) : WsEvent()
    data class Snapshot(val snapshot: SnapshotResponse) : WsEvent()
    data class MemberState(
        val userId: String,
        val status: String,
        val lat: Double?,
        val lng: Double?,
        val accuracyM: Double?,
        val lowAccuracy: Boolean,
        val batteryLevel: Int?,
        val isCharging: Boolean?,
        val recordedAt: String?,
        val lastSeenAt: String?
    ) : WsEvent()
    data class MemberJoined(val member: GroupMemberDto) : WsEvent()
    data class MemberLeft(val userId: String) : WsEvent()
    data class MemberPending(val user: PendingMemberDto) : WsEvent()
    data class MemberApproved(val groupId: String) : WsEvent()
    data class MemberRejected(val serverTime: String) : WsEvent()
    data class ProfileUpdated(val userId: String, val displayName: String, val phone: String?, val avatarVersion: Long) : WsEvent()
    data class GroupUpdated(val groupId: String, val name: String) : WsEvent()
    data class SessionRevoked(val sessionId: String) : WsEvent()
    data class ConnectionClosed(val code: Int, val reason: String) : WsEvent()
}

class MoonOrbitWebSocket(
    private var wsUrl: String = "ws://10.0.2.2:3000/ws",
    private val appVersionCode: Int = 1
) {
    companion object {
        private const val TAG = "MoonOrbitWS"

        @Volatile
        private var instance: MoonOrbitWebSocket? = null

        fun getInstance(): MoonOrbitWebSocket {
            return instance ?: synchronized(this) {
                instance ?: MoonOrbitWebSocket().also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive
        .pingInterval(20, TimeUnit.SECONDS) // Ping every 20s to prevent NAT/carrier timeout
        .build()

    private var webSocket: WebSocket? = null
    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<WsEvent> = _events

    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastToken: String? = null
    private var isExplicitDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var pingJob: Job? = null

    fun isConnected(): Boolean = webSocket != null

    fun setWsUrl(url: String) {
        this.wsUrl = url
    }

    @Synchronized
    fun connect(token: String) {
        lastToken = token
        isExplicitDisconnect = false
        reconnectJob?.cancel()

        if (webSocket != null) {
            Log.d(TAG, "WebSocket already connected or connecting")
            return
        }

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-App-Version-Code", appVersionCode.toString())
            .build()

        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened successfully")
                reconnectAttempts = 0
                reconnectJob?.cancel()
                pingJob?.cancel()
                pingJob = scope.launch {
                    while (isActive) {
                        delay(30000L)
                        sendPing()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = gson.fromJson(text, JsonObject::class.java)
                    val type = obj.get("type")?.asString ?: return

                    when (type) {
                        "hello" -> {
                            val serverTime = obj.get("serverTime").asString
                            val sessionId = obj.getAsJsonObject("session").get("id").asString
                            scope.launch { _events.emit(WsEvent.Hello(serverTime, sessionId)) }
                        }
                        "snapshot" -> {
                            val snap = gson.fromJson(text, SnapshotResponse::class.java)
                            scope.launch { _events.emit(WsEvent.Snapshot(snap)) }
                        }
                        "member_state" -> {
                            val userId = obj.get("userId").asString
                            val status = obj.get("status").asString
                            val lat = if (obj.has("lat") && !obj.get("lat").isJsonNull) obj.get("lat").asDouble else null
                            val lng = if (obj.has("lng") && !obj.get("lng").isJsonNull) obj.get("lng").asDouble else null
                            val acc = if (obj.has("accuracyM") && !obj.get("accuracyM").isJsonNull) obj.get("accuracyM").asDouble else null
                            val lowAcc = obj.get("lowAccuracy")?.asBoolean ?: false
                            val bat = if (obj.has("batteryLevel") && !obj.get("batteryLevel").isJsonNull) obj.get("batteryLevel").asInt else null
                            val chg = if (obj.has("isCharging") && !obj.get("isCharging").isJsonNull) obj.get("isCharging").asBoolean else null
                            val rec = if (obj.has("recordedAt") && !obj.get("recordedAt").isJsonNull) obj.get("recordedAt").asString else null
                            val seen = if (obj.has("lastSeenAt") && !obj.get("lastSeenAt").isJsonNull) obj.get("lastSeenAt").asString else null

                            scope.launch {
                                _events.emit(WsEvent.MemberState(userId, status, lat, lng, acc, lowAcc, bat, chg, rec, seen))
                            }
                        }
                        "member_joined" -> {
                            val member = gson.fromJson(obj.getAsJsonObject("member"), GroupMemberDto::class.java)
                            scope.launch { _events.emit(WsEvent.MemberJoined(member)) }
                        }
                        "member_left" -> {
                            val uId = obj.get("userId").asString
                            scope.launch { _events.emit(WsEvent.MemberLeft(uId)) }
                        }
                        "member_pending" -> {
                            val user = gson.fromJson(obj.getAsJsonObject("user"), PendingMemberDto::class.java)
                            scope.launch { _events.emit(WsEvent.MemberPending(user)) }
                        }
                        "member_approved" -> {
                            val gId = obj.getAsJsonObject("group").get("id").asString
                            scope.launch { _events.emit(WsEvent.MemberApproved(gId)) }
                        }
                        "member_rejected" -> {
                            val time = obj.get("serverTime").asString
                            scope.launch { _events.emit(WsEvent.MemberRejected(time)) }
                        }
                        "profile_updated" -> {
                            val u = obj.getAsJsonObject("user")
                            val uId = u.get("userId").asString
                            val dName = u.get("displayName").asString
                            val phone = if (u.has("phone") && !u.get("phone").isJsonNull) u.get("phone").asString else null
                            val ver = u.get("avatarVersion").asLong
                            scope.launch { _events.emit(WsEvent.ProfileUpdated(uId, dName, phone, ver)) }
                        }
                        "group_updated" -> {
                            val g = obj.getAsJsonObject("group")
                            val gId = g.get("id").asString
                            val name = g.get("name").asString
                            scope.launch { _events.emit(WsEvent.GroupUpdated(gId, name)) }
                        }
                        "session_revoked" -> {
                            val sId = obj.get("sessionId").asString
                            scope.launch { _events.emit(WsEvent.SessionRevoked(sId)) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                scope.launch { _events.emit(WsEvent.ConnectionClosed(code, reason)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                pingJob?.cancel()
                this@MoonOrbitWebSocket.webSocket = null
                if (!isExplicitDisconnect) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                pingJob?.cancel()
                this@MoonOrbitWebSocket.webSocket = null
                if (!isExplicitDisconnect) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = when {
                reconnectAttempts < 3 -> 3000L
                reconnectAttempts < 6 -> 10000L
                else -> 30000L
            }
            reconnectAttempts++
            Log.d(TAG, "Reconnecting WebSocket in ${delayMs}ms (attempt $reconnectAttempts)...")
            delay(delayMs)
            val token = lastToken
            if (!isExplicitDisconnect && !token.isNullOrBlank() && webSocket == null) {
                connect(token)
            }
        }
    }

    fun requestResync() {
        webSocket?.send("""{"type":"resync"}""")
    }

    fun sendPing() {
        webSocket?.send("""{"type":"ping"}""")
    }

    @Synchronized
    fun disconnect() {
        isExplicitDisconnect = true
        reconnectJob?.cancel()
        pingJob?.cancel()
        try {
            webSocket?.close(1000, "Explicit disconnect")
        } catch (e: Exception) {}
        webSocket = null
    }
}
