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

    val allUploads: Flow<List<Upload>> = uploadDao.getAllUploads()
    val allLogs: Flow<List<LogEntry>> = logDao.getAllLogs()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("callsync_prefs", Context.MODE_PRIVATE)

    init {
        if (getPhoneId().isEmpty()) {
            prefs.edit().putString("phone_id", UUID.randomUUID().toString().take(8)).apply()
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun getServerUrl(): String {
        var url = prefs.getString("server_url", "http://10.0.2.2:8080/") ?: "http://10.0.2.2:8080/"
        if (!url.endsWith("/")) url += "/"
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
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model.replaceFirstChar { it.uppercase() }
        } else {
            "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        }
    }

    fun getAndroidVersion(): String = Build.VERSION.RELEASE

    /** Default: /storage/emulated/0/Recordings/Call (standard call-recorder location) */
    fun getMonitorFolderPath(): String {
        val default = "/storage/emulated/0/Recordings/Call"
        return prefs.getString("monitor_folder", default) ?: default
    }

    fun setMonitorFolderPath(path: String) {
        prefs.edit().putString("monitor_folder", path).apply()
    }

    fun isOnboardingCompleted(): Boolean = prefs.getBoolean("onboarding_completed", false)
    fun setOnboardingCompleted(completed: Boolean) =
        prefs.edit().putBoolean("onboarding_completed", completed).apply()

    // ── Retrofit / API ────────────────────────────────────────────────────────

    @Volatile private var cachedApi: CallSyncApi? = null
    @Volatile private var cachedUrl: String? = null

    private fun getApi(): CallSyncApi {
        val url = getServerUrl()
        synchronized(this) {
            if (cachedApi != null && cachedUrl == url) return cachedApi!!
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
            cachedApi = retrofit.create(CallSyncApi::class.java)
            cachedUrl = url
            return cachedApi!!
        }
    }

    fun resetApi() {
        synchronized(this) {
            cachedApi = null
            cachedUrl = null
        }
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    /** Returns (success, errorMessage) */
    suspend fun login(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val response = getApi().login(LoginRequest(getUsername(), getPassword()))
            if (response.isSuccessful) {
                val token = response.body()?.token ?: ""
                setAuthToken(token)
                addLog("Auth", "Login successful")
                Pair(true, "")
            } else {
                val errBody = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                addLog("Auth", "Login failed: $errBody", true)
                Pair(false, "Login failed: $errBody")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            addLog("Auth", "Login exception: $msg", true)
            Pair(false, msg)
        }
    }

    /** Returns (success, errorMessage) */
    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val response = getApi().checkHealth()
            if (response.isSuccessful) {
                addLog("Auth", "Server reachable — ${response.body()?.status}")
                Pair(true, "")
            } else {
                val errBody = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                addLog("Auth", "Health check failed: $errBody", true)
                Pair(false, "Server returned ${response.code()}: $errBody")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            addLog("Auth", "Connection error: $msg", true)
            Pair(false, msg)
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    suspend fun uploadPendingFiles(): Int = withContext(Dispatchers.IO) {
        if (getAuthToken().isEmpty()) {
            val (ok, err) = login()
            if (!ok) {
                addLog("Uploader", "Cannot upload — auth failed: $err", true)
                return@withContext 0
            }
        }

        val pending = uploadDao.getPendingUploads()
        var uploaded = 0
        for (upload in pending) {
            val file = File(upload.path)
            if (!file.exists()) {
                addLog("Uploader", "File missing, marking failed: ${upload.name}", true)
                uploadDao.updateUploadStatus(upload.id, "FAILED")
                continue
            }
            try {
                val sha256 = calculateSHA256(file)
                val token = "Bearer ${getAuthToken()}"
                val mediaType = getMediaType(file).toMediaTypeOrNull()
                val fileBody = file.asRequestBody(mediaType)
                val filePart = MultipartBody.Part.createFormData("file", file.name, fileBody)
                val phoneIdBody = getPhoneId().toRequestBody("text/plain".toMediaTypeOrNull())
                val deviceNameBody = getDeviceName().toRequestBody("text/plain".toMediaTypeOrNull())
                val androidVersionBody = getAndroidVersion().toRequestBody("text/plain".toMediaTypeOrNull())
                val timestampBody = file.lastModified().toString().toRequestBody("text/plain".toMediaTypeOrNull())
                val sha256Body = sha256.toRequestBody("text/plain".toMediaTypeOrNull())

                uploadDao.updateUpload(upload.copy(status = "UPLOADING"))
                val response = getApi().uploadFile(
                    token, filePart, phoneIdBody, deviceNameBody,
                    androidVersionBody, timestampBody, sha256Body
                )

                if (response.isSuccessful) {
                    uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                    addLog("Uploader", "Uploaded: ${upload.name}")
                    uploaded++
                } else {
                    val errBody = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                    if (response.code() == 401) {
                        setAuthToken("")
                        val (ok, _) = login()
                        if (ok) {
                            uploadDao.updateUpload(upload.copy(status = "PENDING"))
                        } else {
                            uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = errBody))
                            addLog("Uploader", "Upload failed (auth): ${upload.name} — $errBody", true)
                        }
                    } else {
                        uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = errBody))
                        addLog("Uploader", "Upload failed (${response.code()}): ${upload.name} — $errBody", true)
                    }
                }
            } catch (e: Exception) {
                uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = e.message))
                addLog("Uploader", "Upload exception: ${upload.name} — ${e.message}", true)
            }
        }
        uploaded
    }

    suspend fun getServerRecords() = withContext(Dispatchers.IO) {
        try {
            if (getAuthToken().isEmpty()) login()
            val response = getApi().getRecords("Bearer ${getAuthToken()}")
            if (response.isSuccessful) {
                Pair(response.body() ?: emptyList(), "")
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                Pair(emptyList(), err)
            }
        } catch (e: Exception) {
            Pair(emptyList<com.example.data.api.RecordingResponse>(), e.message ?: "Unknown error")
        }
    }

    suspend fun deleteRecord(id: Long): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (getAuthToken().isEmpty()) login()
            val response = getApi().deleteRecord("Bearer ${getAuthToken()}", id)
            if (response.isSuccessful) {
                addLog("Viewer", "Deleted record ID $id from server")
                Pair(true, "")
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                addLog("Viewer", "Delete failed: $err", true)
                Pair(false, err)
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            addLog("Viewer", "Delete exception: $msg", true)
            Pair(false, msg)
        }
    }

    // ── Folder scanning (recursive) ───────────────────────────────────────────
    //
    // Supports two structures:
    //   /path/*.m4a                 (flat — recordings directly in root)
    //   /path/_065491040/*.m4a      (sub-folder per contact/number)

    suspend fun scanFolderManually(): Int = withContext(Dispatchers.IO) {
        val folderPath = getMonitorFolderPath()
        val folder = File(folderPath)
        addLog("Scanner", "Scanning: $folderPath")

        if (!folder.exists() || !folder.isDirectory) {
            addLog("Scanner", "Folder not found: $folderPath", true)
            return@withContext 0
        }

        val allFiles = collectAudioFiles(folder)
        addLog("Scanner", "Found ${allFiles.size} audio file(s) total")

        var addedCount = 0
        for (file in allFiles) {
            if (uploadDao.getUploadByPath(file.absolutePath) == null) {
                val sha256 = calculateSHA256(file)
                uploadDao.insertUpload(
                    Upload(
                        sha256 = sha256,
                        path = file.absolutePath,
                        name = file.name,
                        size = file.length(),
                        status = "PENDING"
                    )
                )
                addedCount++
                addLog("Scanner", "Queued: ${file.name}")
            }
        }
        addLog("Scanner", "Scan done — $addedCount new file(s) queued")
        addedCount
    }

    /**
     * Collects audio files from [root] and one level of sub-directories
     * (phone-number folders like _065491040, -064385183, etc.)
     */
    private fun collectAudioFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        root.listFiles()?.forEach { entry ->
            when {
                entry.isFile && isAudioFile(entry) -> result.add(entry)
                entry.isDirectory -> {
                    // One-level deep (phone number sub-folders)
                    entry.listFiles()?.forEach { sub ->
                        if (sub.isFile && isAudioFile(sub)) result.add(sub)
                    }
                }
            }
        }
        return result
    }

    fun isAudioFile(file: File): Boolean =
        file.extension.lowercase() in setOf("m4a", "mp3", "wav", "amr", "3gp", "ogg", "aac")

    private fun getMediaType(file: File): String = when (file.extension.lowercase()) {
        "m4a"       -> "audio/mp4"
        "wav"       -> "audio/wav"
        "ogg"       -> "audio/ogg"
        "amr"       -> "audio/amr"
        "3gp"       -> "video/3gpp"
        "aac"       -> "audio/aac"
        else        -> "audio/mpeg"
    }

    fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    suspend fun addLog(tag: String, message: String, isError: Boolean = false) =
        withContext(Dispatchers.IO) {
            Log.d("CallSync/$tag", message)
            logDao.insertLog(
                LogEntry(
                    tag = tag,
                    message = message,
                    isError = isError,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

    suspend fun clearLogs() = withContext(Dispatchers.IO) { logDao.clearAllLogs() }
    suspend fun clearUploads() = withContext(Dispatchers.IO) { uploadDao.clearAllUploads() }

    /** Reset any stuck UPLOADING → PENDING (e.g. after crash) */
    suspend fun resetStuckUploads() = withContext(Dispatchers.IO) {
        val stuck = uploadDao.getPendingUploads().filter { it.status == "UPLOADING" }
        stuck.forEach { uploadDao.updateUpload(it.copy(status = "PENDING")) }
    }

    suspend fun retryFailedUploads() = withContext(Dispatchers.IO) {
        val failed = uploadDao.getPendingUploads().filter { it.status == "FAILED" }
        failed.forEach { uploadDao.updateUpload(it.copy(status = "PENDING", errorMessage = null, retryCount = it.retryCount + 1)) }
    }
}
