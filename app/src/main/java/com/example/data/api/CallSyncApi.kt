package com.example.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

// Models
data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val token: String
)

data class RecordingResponse(
    val id: Long,
    val name: String,
    val size: Long,
    val sha256: String,
    val duration: Double,
    val uploadDate: String,
    val creationDate: String,
    val path: String,
    val deviceId: String
)

data class HealthResponse(
    val status: String,
    val time: String,
    val version: String
)

interface CallSyncApi {

    @POST("login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<LoginResponse>

    @GET("health")
    suspend fun checkHealth(): Response<HealthResponse>

    @Multipart
    @POST("upload")
    suspend fun uploadFile(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part,
        @Part("phone_id") phoneId: RequestBody,
        @Part("device_name") deviceName: RequestBody,
        @Part("android_version") androidVersion: RequestBody,
        @Part("timestamp") timestamp: RequestBody,
        @Part("sha256") sha256: RequestBody
    ): Response<ResponseBody>

    @GET("records")
    suspend fun getRecords(
        @Header("Authorization") token: String
    ): Response<List<RecordingResponse>>

    @GET("record/{id}")
    suspend fun getRecordDetails(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<RecordingResponse>

    @DELETE("record/{id}")
    suspend fun deleteRecord(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<ResponseBody>
}
