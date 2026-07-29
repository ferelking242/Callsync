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

/**
 * Response from GET /delete-commands/{device_id}.
 * The server marks all returned commands as done atomically on this call.
 */
@JsonClass(generateAdapter = true)
data class DeleteCommandsResponse(
    @Json(name = "device_id")   val deviceId: String,
    @Json(name = "sha256_list") val sha256List: List<String>,
    val count: Int
)

/**
 * Response from GET /known-hashes.
 * Contains all SHA256s known to the server: files currently stored
 * AND files already downloaded by Flutter clients.
 * Used by the Kotlin recorder to skip re-uploading already-known files.
 */
@JsonClass(generateAdapter = true)
data class KnownHashesResponse(
    @Json(name = "sha256_list") val sha256List: List<String>,
    val count: Int
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

    // ── Known hashes (dedup) ──────────────────────────────────────────────────

    /**
     * Returns all SHA256s the server knows about:
     * files currently stored + files already downloaded by Flutter clients.
     * Call this before an upload batch to skip already-known files.
     */
    @GET("known-hashes")
    suspend fun getKnownHashes(
        @Header("Authorization") token: String
    ): Response<KnownHashesResponse>

    // ── Delete-at-source commands ─────────────────────────────────────────────

    /**
     * Poll pending deletion commands for this device.
     * The server marks all returned commands as done atomically.
     */
    @GET("delete-commands/{deviceId}")
    suspend fun getPendingCommands(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String
    ): Response<DeleteCommandsResponse>
}
