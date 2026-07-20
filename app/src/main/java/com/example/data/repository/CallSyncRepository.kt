package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.example.data.api.CallSyncApi
import com.example.data.api.LoginRequest
import com.example.data.database.AppDatabase
import com.example.data.model.LogEntry
import com.example.data.model.Upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class CallSyncRepository(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    val uploadDao = database.uploadDao()
    val logDao = database.logDao()

    private val prefs: SharedPreferences = context.getSharedPreferences("callsync_prefs", Context.MODE_PRIVATE)

    init {
        // Initialize Phone ID if not present
        if (getPhoneId().isEmpty()) {
            val uniqueId = UUID.randomUUID().toString().take(8)
            prefs.edit().putString("phone_id", uniqueId).apply()
        }
    }

    // Settings Getters & Setters
    fun getServerUrl(): String {
        var url = prefs.getString("server_url", "http://10.0.2.2:8080/") ?: "http://10.0.2.2:8080/"
        if (!url.endsWith("/")) {
            url += "/"
        }
        return url
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString("server_url", url).apply()
        resetApi()
    }

    fun getUsername(): String = prefs.getString("username", "admin") ?: "admin"
    fun setUsername(user: String) = prefs.edit().putString("username", user).apply()

    fun getPassword(): String = prefs.getString("password", "admin123") ?: "admin123"
    fun setPassword(pass: String) = prefs.edit().putString("password", pass).apply()

    fun getAuthToken(): String = prefs.getString("auth_token", "") ?: ""
    fun setAuthToken(token: String) = prefs.edit().putString("auth_token", token).apply()

    fun getPhoneId(): String = prefs.getString("phone_id", "") ?: ""

    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            model.replaceFirstChar { it.uppercase() }
        } else {
            "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        }
    }

    fun getAndroidVersion(): String = Build.VERSION.RELEASE

    fun getMonitorFolderPath(): String {
        val defaultDir = File(context.getExternalFilesDir(null), "Recordings").absolutePath
        return prefs.getString("monitor_folder", defaultDir) ?: defaultDir
    }

    fun setMonitorFolderPath(path: String) {
        prefs.edit().putString("monitor_folder", path).apply()
    }

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean("onboarding_completed", false)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean("onboarding_completed", completed).apply()
    }

    // Dynamic Retrofit API Builder
    @Volatile
    private var cachedApi: CallSyncApi? = null
    private var cachedUrl: String? = null

    private fun getApi(): CallSyncApi {
        val url = getServerUrl()
        synchronized(this) {
            if (cachedApi != null && cachedUrl == url) {
                return cachedApi!!
            }
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(url)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()

            val api = retrofit.create(CallSyncApi::class.java)
            cachedApi = api
            cachedUrl = url
            return api
        }
    }

    private fun resetApi() {
        synchronized(this) {
            cachedApi = null
            cachedUrl = null
        }
    }

    // Logging helpers
    suspend fun addLog(tag: String, message: String, isError: Boolean = false) {
        Log.d(tag, message)
        withContext(Dispatchers.IO) {
            logDao.insertLog(LogEntry(tag = tag, message = message, isError = isError))
        }
    }

    val allLogs: Flow<List<LogEntry>> = logDao.getAllLogs()
    val allUploads: Flow<List<Upload>> = uploadDao.getAllUploads()

    suspend fun clearLogs() = logDao.clearAllLogs()
    suspend fun clearUploads() = uploadDao.clearAllUploads()

    // SHA-256 Calculation
    fun calculateSHA256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val inputStream = FileInputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            inputStream.close()
            val hashBytes = digest.digest()
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "ERROR_HASHING"
        }
    }

    // Login (obtain JWT token)
    suspend fun authenticate(): Boolean = withContext(Dispatchers.IO) {
        try {
            addLog("API", "Logging into server: ${getServerUrl()}")
            val response = getApi().login(LoginRequest(getUsername(), getPassword()))
            if (response.isSuccessful && response.body() != null) {
                val token = "Bearer " + response.body()!!.token
                setAuthToken(token)
                addLog("API", "Logged in successfully! Token stored.")
                true
            } else {
                addLog("API", "Login failed: Code ${response.code()} - ${response.errorBody()?.string()}", true)
                false
            }
        } catch (e: Exception) {
            addLog("API", "Login network exception: ${e.message}", true)
            false
        }
    }

    // Health Check
    suspend fun checkServerHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = getApi().checkHealth()
            if (response.isSuccessful) {
                addLog("API", "Connection check success: ${response.body()?.status}")
                true
            } else {
                addLog("API", "Connection check failed: Code ${response.code()}", true)
                false
            }
        } catch (e: Exception) {
            addLog("API", "Connection check exception: ${e.message}", true)
            false
        }
    }

    // Upload a specific file
    suspend fun uploadFile(upload: Upload): Boolean = withContext(Dispatchers.IO) {
        val file = File(upload.path)
        if (!file.exists()) {
            addLog("Uploader", "Upload failed: File does not exist: ${upload.path}", true)
            uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = "File not found"))
            return@withContext false
        }

        // Verify token, re-auth if missing
        var token = getAuthToken()
        if (token.isEmpty()) {
            if (!authenticate()) {
                uploadDao.updateUpload(upload.copy(status = "FAILED", retryCount = upload.retryCount + 1, errorMessage = "Authentication failure"))
                return@withContext false
            }
            token = getAuthToken()
        }

        try {
            addLog("Uploader", "Uploading: ${upload.name} (${upload.size} bytes)")
            val requestFile = file.asRequestBody("audio/*".toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)

            val phoneIdBody = getPhoneId().toRequestBody("text/plain".toMediaTypeOrNull())
            val deviceNameBody = getDeviceName().toRequestBody("text/plain".toMediaTypeOrNull())
            val androidVersionBody = getAndroidVersion().toRequestBody("text/plain".toMediaTypeOrNull())
            val timestampBody = System.currentTimeMillis().toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val sha256Body = upload.sha256.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = getApi().uploadFile(
                token = token,
                file = filePart,
                phoneId = phoneIdBody,
                deviceName = deviceNameBody,
                androidVersion = androidVersionBody,
                timestamp = timestampBody,
                sha256 = sha256Body
            )

            if (response.isSuccessful) {
                addLog("Uploader", "Upload success for ${upload.name}!")
                uploadDao.updateUpload(upload.copy(
                    status = "COMPLETED",
                    uploadedAt = System.currentTimeMillis(),
                    errorMessage = null
                ))
                true
            } else if (response.code() == 401) {
                // Retry auth once
                addLog("Uploader", "Token expired, re-authenticating...", true)
                if (authenticate()) {
                    val retryResponse = getApi().uploadFile(
                        token = getAuthToken(),
                        file = filePart,
                        phoneId = phoneIdBody,
                        deviceName = deviceNameBody,
                        androidVersion = androidVersionBody,
                        timestamp = timestampBody,
                        sha256 = sha256Body
                    )
                    if (retryResponse.isSuccessful) {
                        addLog("Uploader", "Upload success after retry for ${upload.name}!")
                        uploadDao.updateUpload(upload.copy(
                            status = "COMPLETED",
                            uploadedAt = System.currentTimeMillis(),
                            errorMessage = null
                        ))
                        return@withContext true
                    }
                }
                uploadDao.updateUpload(upload.copy(status = "FAILED", retryCount = upload.retryCount + 1, errorMessage = "Unauthorized: ${response.code()}"))
                false
            } else {
                addLog("Uploader", "Upload failed: Server code ${response.code()}", true)
                uploadDao.updateUpload(upload.copy(status = "FAILED", retryCount = upload.retryCount + 1, errorMessage = "Server error ${response.code()}"))
                false
            }
        } catch (e: Exception) {
            addLog("Uploader", "Upload exception for ${upload.name}: ${e.message}", true)
            uploadDao.updateUpload(upload.copy(status = "FAILED", retryCount = upload.retryCount + 1, errorMessage = e.message))
            false
        }
    }

    // Retrieve records list for Viewer
    suspend fun getServerRecords(): List<com.example.data.api.RecordingResponse>? = withContext(Dispatchers.IO) {
        var token = getAuthToken()
        if (token.isEmpty()) {
            if (!authenticate()) return@withContext null
            token = getAuthToken()
        }

        try {
            val response = getApi().getRecords(token)
            if (response.isSuccessful) {
                response.body()
            } else if (response.code() == 401) {
                // Retry authentication
                if (authenticate()) {
                    val retryResponse = getApi().getRecords(getAuthToken())
                    if (retryResponse.isSuccessful) return@withContext retryResponse.body()
                }
                addLog("Viewer", "Failed to retrieve records: Unauthorized", true)
                null
            } else {
                addLog("Viewer", "Failed to retrieve records: Server code ${response.code()}", true)
                null
            }
        } catch (e: Exception) {
            addLog("Viewer", "Records retrieval exception: ${e.message}", true)
            null
        }
    }

    // Delete a recording on server
    suspend fun deleteServerRecord(id: Long): Boolean = withContext(Dispatchers.IO) {
        var token = getAuthToken()
        if (token.isEmpty()) {
            if (!authenticate()) return@withContext false
            token = getAuthToken()
        }

        try {
            val response = getApi().deleteRecord(token, id)
            if (response.isSuccessful) {
                addLog("Viewer", "Record deleted from server: ID $id")
                true
            } else {
                addLog("Viewer", "Delete record failed: Code ${response.code()}", true)
                false
            }
        } catch (e: Exception) {
            addLog("Viewer", "Delete record exception: ${e.message}", true)
            false
        }
    }

    // Scan Folder manually to discover files
    suspend fun scanFolderManually(): Int = withContext(Dispatchers.IO) {
        val folderPath = getMonitorFolderPath()
        val folder = File(folderPath)
        addLog("Uploader", "Scanning folder: $folderPath")
        if (!folder.exists() || !folder.isDirectory) {
            addLog("Uploader", "Folder does not exist or is not a directory: $folderPath", true)
            return@withContext 0
        }

        val files = folder.listFiles { file ->
            file.isFile && (file.extension == "m4a" || file.extension == "mp3" || file.extension == "wav" || file.extension == "amr" || file.extension == "3gp" || file.extension == "ogg")
        } ?: emptyArray()

        var addedCount = 0
        for (file in files) {
            val existing = uploadDao.getUploadByPath(file.absolutePath)
            if (existing == null) {
                val sha256 = calculateSHA256(file)
                val upload = Upload(
                    sha256 = sha256,
                    path = file.absolutePath,
                    name = file.name,
                    size = file.length(),
                    status = "PENDING"
                )
                uploadDao.insertUpload(upload)
                addedCount++
                addLog("Uploader", "Discovered call recording: ${file.name}")
            }
        }
        addLog("Uploader", "Scan completed. Discovered $addedCount new call recording(s).")
        addedCount
    }
}
