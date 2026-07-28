package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

// ── Models ────────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class LoginRequest(val username: String, val password: String)

@JsonClass(generateAdapter = true)
data class LoginResponse(val token: String)

@JsonClass(generateAdapter = true)
data class RecordingResponse(
    val id: Long,
    val name: String,
    val size: Long,
    val sha256: String,
    val duration: Double,
    @Json(name = "upload_date")   val uploadDate: String,
    @Json(name = "creation_date") val creationDate: String,
    val path: String,
    @Json(name = "device_id") val deviceId: String
)

@JsonClass(generateAdapter = true)
data class HealthResponse(
    val status: String,
    val version: String? = null,
    val time: String? = null,
    val app: String? = null
)

@JsonClass(generateAdapter = true)
data class StorageStatsResponse(
    val recordings: Long,
    @Json(name = "recordings_bytes") val recordingsBytes: Long,
    @Json(name = "disk_total")  val diskTotal: Long,
    @Json(name = "disk_free")   val diskFree: Long,
    @Json(name = "disk_used")   val diskUsed: Long,
    @Json(name = "db_total_size") val dbTotalSize: Long
)

@JsonClass(generateAdapter = true)
data class PurgeResponse(
    val message: String,
    val deleted: Int,
    val errors: Int,
    val total: Int
)

/** Deletion command queued by the Flutter client for this Android device */
@JsonClass(generateAdapter = true)
data class DeleteCommand(
    val id: Long,
    @Json(name = "device_id")    val deviceId: String,
    val sha256: String,
    @Json(name = "recording_id") val recordingId: Long,
    @Json(name = "created_at")   val createdAt: String
)

// ── API Interface ─────────────────────────────────────────────────────────────

interface CallSyncApi {

    @POST("login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("health")
    suspend fun checkHealth(): Response<HealthResponse>

    @Multipart
    @POST("upload")
    suspend fun uploadFile(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part,
        @Part("phone_id")        phoneId: RequestBody,
        @Part("device_name")     deviceName: RequestBody,
        @Part("android_version") androidVersion: RequestBody,
        @Part("timestamp")       timestamp: RequestBody,
        @Part("sha256")          sha256: RequestBody
    ): Response<ResponseBody>

    @GET("records")
    suspend fun getRecords(@Header("Authorization") token: String): Response<List<RecordingResponse>>

    @GET("record/{id}")
    suspend fun getRecordDetails(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<RecordingResponse>

    @GET("stream/{id}")
    suspend fun streamRecord(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<ResponseBody>

    @Streaming
    @GET("download/{id}")
    suspend fun downloadRecord(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<ResponseBody>

    @DELETE("record/{id}")
    suspend fun deleteRecord(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<ResponseBody>

    @DELETE("purge-all")
    suspend fun purgeAllRecords(@Header("Authorization") token: String): Response<PurgeResponse>

    @GET("storage/stats")
    suspend fun getStorageStats(@Header("Authorization") token: String): Response<StorageStatsResponse>

    // ── Delete-at-source commands ─────────────────────────────────────────────

    /** Poll pending deletion commands for this device */
    @GET("pending-commands/{deviceId}")
    suspend fun getPendingCommands(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String
    ): Response<List<DeleteCommand>>

    /** Acknowledge execution of a command so the server removes it */
    @DELETE("delete-command/{id}")
    suspend fun acknowledgeDeleteCommand(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<ResponseBody>
}
