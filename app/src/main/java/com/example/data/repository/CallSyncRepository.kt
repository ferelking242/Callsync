package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.data.api.CallSyncApi
import com.example.data.api.LoginRequest
import com.example.data.api.RecordingResponse
import com.example.data.database.AppDatabase
import com.example.data.model.LogEntry
import com.example.data.model.Upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val uploadDao           = database.uploadDao()
    val logDao              = database.logDao()

    val allUploads: Flow<List<Upload>>    = uploadDao.getAllUploads()
    val allLogs:    Flow<List<LogEntry>>  = logDao.getAllLogs()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("callsync_prefs", Context.MODE_PRIVATE)

    init {
        if (getPhoneId().isEmpty()) {
            prefs.edit().putString("phone_id", UUID.randomUUID().toString().take(8)).apply()
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun getServerUrl(): String {
        var url = prefs.getString("server_url", "https://sarcastic-wiry-lava--havek99178.replit.app/")
                  ?: "https://sarcastic-wiry-lava--havek99178.replit.app/"
        if (!url.endsWith("/")) url += "/"
        return url
    }
    fun setServerUrl(url: String) { prefs.edit().putString("server_url", url).apply(); resetApi() }

    fun getUsername(): String = prefs.getString("username", "admin") ?: "admin"
    fun setUsername(u: String) { prefs.edit().putString("username", u).apply() }

    fun getPassword(): String = prefs.getString("password", "admin123") ?: "admin123"
    fun setPassword(p: String) { prefs.edit().putString("password", p).apply() }

    fun getAuthToken(): String = prefs.getString("auth_token", "") ?: ""
    fun setAuthToken(t: String) { prefs.edit().putString("auth_token", t).apply() }

    fun getPhoneId(): String = prefs.getString("phone_id", "") ?: ""

    fun getDeviceName(): String {
        val mfr   = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(mfr, ignoreCase = true)) model.replaceFirstChar { it.uppercase() }
               else "${mfr.replaceFirstChar { it.uppercase() }} $model"
    }

    fun getAndroidVersion(): String = Build.VERSION.RELEASE

    fun getMonitorFolderPath(): String {
        val default = autoDetectCallRecordingsFolder().ifEmpty {
            "/storage/emulated/0/Recordings/Call"
        }
        return prefs.getString("monitor_folder", default) ?: default
    }
    fun setMonitorFolderPath(path: String) {
        prefs.edit().putString("monitor_folder", path).apply()
    }

    fun isOnboardingCompleted(): Boolean = prefs.getBoolean("onboarding_completed", false)
    fun setOnboardingCompleted(b: Boolean) { prefs.edit().putBoolean("onboarding_completed", b).apply() }

    // ── Server SHA256 cache (dedup) ───────────────────────────────────────────

    /** Returns true if this SHA256 is already known to be on the server. */
    fun isOnServer(sha256: String): Boolean {
        val set = prefs.getStringSet("server_sha256_cache", emptySet()) ?: emptySet()
        return set.contains(sha256)
    }

    /** Fetches the full records list and caches all SHA256 hashes locally. */
    suspend fun refreshServerSha256Cache() = withContext(Dispatchers.IO) {
        try {
            if (getAuthToken().isEmpty()) login()
            val resp = getApi().getRecords("Bearer ${getAuthToken()}")
            if (resp.isSuccessful) {
                val sha256Set = resp.body()?.map { it.sha256 }?.toSet() ?: emptySet()
                prefs.edit().putStringSet("server_sha256_cache", sha256Set).apply()
                addLog("Cache", "Refreshed server SHA256 cache: ${sha256Set.size} entries")
            }
        } catch (e: Exception) {
            addLog("Cache", "SHA256 cache refresh failed: ${e.message}", true)
        }
    }

    /** Adds a SHA256 to the local server cache (called after successful upload). */
    private fun addToServerCache(sha256: String) {
        val current = prefs.getStringSet("server_sha256_cache", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(sha256)
        prefs.edit().putStringSet("server_sha256_cache", current).apply()
    }

    // ── Retrofit / API ────────────────────────────────────────────────────────

    @Volatile private var cachedApi: CallSyncApi? = null
    @Volatile private var cachedUrl: String? = null

    private fun getApi(): CallSyncApi {
        val url = getServerUrl()
        synchronized(this) {
            if (cachedApi != null && cachedUrl == url) return cachedApi!!
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
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

    fun resetApi() { synchronized(this) { cachedApi = null; cachedUrl = null } }

    // ── Auth ──────────────────────────────────────────────────────────────────

    suspend fun login(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val response = getApi().login(LoginRequest(getUsername(), getPassword()))
            if (response.isSuccessful) {
                val token = response.body()?.token ?: ""
                setAuthToken(token)
                addLog("Auth", "Login successful")
                Pair(true, "")
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                addLog("Auth", "Login failed: $err", true)
                Pair(false, "Login failed: $err")
            }
        } catch (e: Exception) {
            addLog("Auth", "Login exception: ${e.message}", true)
            Pair(false, e.message ?: "Unknown error")
        }
    }

    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val response = getApi().checkHealth()
            if (response.isSuccessful) {
                addLog("Auth", "Server reachable")
                Pair(true, "")
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                Pair(false, "HTTP ${response.code()}: $err")
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }

    // ── Upload (parallel, dedup) ──────────────────────────────────────────────

    suspend fun uploadPendingFiles(): Int = withContext(Dispatchers.IO) {
        if (getAuthToken().isEmpty()) {
            val (ok, err) = login()
            if (!ok) {
                addLog("Uploader", "Cannot upload — auth failed: $err", true)
                return@withContext 0
            }
        }

        val pending = uploadDao.getPendingUploads()
        if (pending.isEmpty()) return@withContext 0

        val CONCURRENCY = 4
        var uploaded = 0
        pending.chunked(CONCURRENCY).forEach { chunk ->
            coroutineScope {
                val results = chunk.map { upload -> async { uploadSingle(upload) } }.awaitAll()
                uploaded += results.count { it }
            }
        }
        uploaded
    }

    private suspend fun uploadSingle(upload: Upload): Boolean {
        val file = File(upload.path)
        if (!file.exists()) {
            addLog("Uploader", "File missing, marking failed: ${upload.name}", true)
            uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = "File not found"))
            return false
        }
        return try {
            val sha256 = calculateSHA256(file)

            // Dedup: already on server?
            if (isOnServer(sha256)) {
                uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                addLog("Uploader", "Skipped (already on server): ${upload.name}")
                return true
            }

            val token        = "Bearer ${getAuthToken()}"
            val mediaType    = getMediaType(file).toMediaTypeOrNull()
            val fileBody     = file.asRequestBody(mediaType)
            val filePart     = MultipartBody.Part.createFormData("file", file.name, fileBody)
            val phoneIdBody  = getPhoneId().toRequestBody("text/plain".toMediaTypeOrNull())
            val deviceBody   = getDeviceName().toRequestBody("text/plain".toMediaTypeOrNull())
            val versionBody  = getAndroidVersion().toRequestBody("text/plain".toMediaTypeOrNull())
            val tsBody       = file.lastModified().toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val sha256Body   = sha256.toRequestBody("text/plain".toMediaTypeOrNull())

            uploadDao.updateUpload(upload.copy(status = "UPLOADING"))
            val response = getApi().uploadFile(token, filePart, phoneIdBody, deviceBody, versionBody, tsBody, sha256Body)

            when {
                response.isSuccessful -> {
                    uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                    addToServerCache(sha256)
                    addLog("Uploader", "Uploaded: ${upload.name}")
                    true
                }
                // 409 = sha256 conflict → already exists on server
                response.code() == 409 -> {
                    uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                    addToServerCache(sha256)
                    addLog("Uploader", "Skipped (server conflict/duplicate): ${upload.name}")
                    true
                }
                response.code() == 401 -> {
                    setAuthToken("")
                    val (ok, _) = login()
                    uploadDao.updateUpload(upload.copy(status = if (ok) "PENDING" else "FAILED",
                        errorMessage = if (ok) null else "Auth failed"))
                    false
                }
                else -> {
                    val errBody = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                    uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = errBody))
                    addLog("Uploader", "Upload failed (${response.code()}): ${upload.name}", true)
                    false
                }
            }
        } catch (e: Exception) {
            uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = e.message))
            addLog("Uploader", "Upload exception: ${upload.name} — ${e.message}", true)
            false
        }
    }

    // ── Server records ────────────────────────────────────────────────────────

    suspend fun getServerRecords(): Pair<List<RecordingResponse>, String> = withContext(Dispatchers.IO) {
        try {
            if (getAuthToken().isEmpty()) login()
            val response = getApi().getRecords("Bearer ${getAuthToken()}")
            if (response.isSuccessful) Pair(response.body() ?: emptyList(), "")
            else Pair(emptyList(), response.errorBody()?.string() ?: "HTTP ${response.code()}")
        } catch (e: Exception) {
            Pair(emptyList<RecordingResponse>(), e.message ?: "Unknown error")
        }
    }

    // ── Folder scanning — index-on-change only ─────────────────────────────────

    /**
     * Scans the monitored folder and adds only NEW files to the upload queue.
     * Files already in the DB (by path OR sha256=COMPLETED) are skipped — no redundant work.
     */
    suspend fun scanFolderManually(): Int = withContext(Dispatchers.IO) {
        val folderPath = getMonitorFolderPath()
        val folder = File(folderPath)
        addLog("Scanner", "Scanning: $folderPath")

        if (!folder.exists() || !folder.isDirectory) {
            addLog("Scanner", "Folder not found: $folderPath", true)
            return@withContext 0
        }

        val allFiles = collectAudioFiles(folder)
        addLog("Scanner", "Found ${allFiles.size} audio file(s)")

        var addedCount = 0
        for (file in allFiles) {
            // 1. Already indexed by path?
            val existingByPath = uploadDao.getUploadByPath(file.absolutePath)
            if (existingByPath != null) continue   // already in queue/completed — skip

            val sha256 = calculateSHA256(file)

            // 2. Already completed by sha256 (renamed/moved file)?
            val existingBySha = uploadDao.getUploadBySha256(sha256)
            if (existingBySha != null && existingBySha.status == "COMPLETED") {
                // Insert as COMPLETED so path-dedup catches it next time
                uploadDao.insertUpload(
                    Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                        size = file.length(), status = "COMPLETED",
                        uploadedAt = existingBySha.uploadedAt)
                )
                continue
            }

            // 3. Already on server (cached SHA256 set)?
            if (isOnServer(sha256)) {
                uploadDao.insertUpload(
                    Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                        size = file.length(), status = "COMPLETED",
                        uploadedAt = System.currentTimeMillis())
                )
                addLog("Scanner", "Skipped (on server): ${file.name}")
                continue
            }

            // 4. Truly new — queue for upload
            val inserted = uploadDao.insertUpload(
                Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                    size = file.length(), status = "PENDING")
            )
            if (inserted > 0) {
                addedCount++
                addLog("Scanner", "Queued: ${file.name}")
            }
        }
        addLog("Scanner", "Scan done — $addedCount new file(s) queued")
        addedCount
    }

    private fun collectAudioFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        root.walkTopDown().maxDepth(3).forEach { entry ->
            if (entry.isFile && isAudioFile(entry)) result.add(entry)
        }
        return result
    }

    fun isAudioFile(file: File): Boolean =
        file.extension.lowercase() in setOf("m4a", "mp3", "wav", "amr", "3gp", "ogg", "aac")

    private fun getMediaType(file: File): String = when (file.extension.lowercase()) {
        "m4a" -> "audio/mp4";  "wav" -> "audio/wav";  "ogg" -> "audio/ogg"
        "amr" -> "audio/amr";  "3gp" -> "video/3gpp"; "aac" -> "audio/aac"
        else  -> "audio/mpeg"
    }

    fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Auto-detect call recordings folder ───────────────────────────────────

    /**
     * Scans all known OEM call-recorder paths and returns the one with the most
     * audio files. Falls back to a MediaStore query if nothing is found on disk.
     * Returns empty string if nothing is found (caller should use a sensible default).
     */
    fun autoDetectCallRecordingsFolder(): String {
        val candidates = listOf(
            // AOSP / stock Android
            "/storage/emulated/0/Recordings/Call",
            "/storage/emulated/0/Recordings",
            // MIUI / Xiaomi
            "/storage/emulated/0/MIUI/sound_recorder/call_rec",
            "/storage/emulated/0/MIUI/sound_recorder",
            // Samsung One UI
            "/storage/emulated/0/Sounds/CallRecordings",
            "/storage/emulated/0/Sounds",
            // Huawei
            "/storage/emulated/0/Sounds/CallRecord",
            // OnePlus / OxygenOS
            "/storage/emulated/0/Recordings/CallRecording",
            // Generic
            "/storage/emulated/0/PhoneRecord",
            "/storage/emulated/0/CallRecordings",
            "/storage/emulated/0/CallRecording",
            "/storage/emulated/0/Download/CallRecordings",
            "/storage/emulated/0/Voice Recorder/call",
            "/storage/emulated/0/Record/Call",
            "/sdcard/Recordings/Call",
            "/sdcard/MIUI/sound_recorder/call_rec"
        )

        var bestPath  = ""
        var bestCount = 0

        for (path in candidates) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                val count = dir.walkTopDown().maxDepth(2).count { it.isFile && isAudioFile(it) }
                if (count > bestCount) {
                    bestCount = count
                    bestPath  = path
                }
            }
        }

        // If nothing found on disk, try MediaStore query
        if (bestPath.isEmpty()) {
            bestPath = findCallRecordingsFolderViaMediaStore() ?: ""
        }

        Log.d("CallSync/AutoDetect", "Best folder: $bestPath ($bestCount files)")
        return bestPath
    }

    private fun findCallRecordingsFolderViaMediaStore(): String? {
        return try {
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            val selection  = "${MediaStore.Audio.Media.DURATION} > ? AND (${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?)"
            val selArgs    = arrayOf("5000", "%call%", "%record%")

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            val cursor: Cursor? = context.contentResolver.query(uri, projection, selection, selArgs, null)
            val folders = mutableMapOf<String, Int>()

            cursor?.use { c ->
                val colIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val path = c.getString(colIdx) ?: continue
                    val dir  = File(path).parent ?: continue
                    folders[dir] = (folders[dir] ?: 0) + 1
                }
            }

            folders.maxByOrNull { it.value }?.key
        } catch (e: Exception) {
            Log.w("CallSync/AutoDetect", "MediaStore query failed: ${e.message}")
            null
        }
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    suspend fun addLog(tag: String, message: String, isError: Boolean = false) =
        withContext(Dispatchers.IO) {
            Log.d("CallSync/$tag", message)
            logDao.insertLog(LogEntry(tag = tag, message = message, isError = isError,
                timestamp = System.currentTimeMillis()))
        }

    suspend fun clearLogs()    = withContext(Dispatchers.IO) { logDao.clearAllLogs() }
    suspend fun clearUploads() = withContext(Dispatchers.IO) { uploadDao.clearAllUploads() }

    suspend fun resetStuckUploads() = withContext(Dispatchers.IO) {
        val stuck = uploadDao.getUploadingUploads()
        stuck.forEach { uploadDao.updateUpload(it.copy(status = "PENDING", errorMessage = null)) }
        if (stuck.isNotEmpty()) addLog("Uploader", "Reset ${stuck.size} stuck upload(s) → PENDING")
    }

    suspend fun retryFailedUploads() = withContext(Dispatchers.IO) {
        val failed = uploadDao.getFailedUploads()
        failed.forEach {
            uploadDao.updateUpload(it.copy(status = "PENDING", errorMessage = null,
                retryCount = it.retryCount + 1))
        }
        if (failed.isNotEmpty()) addLog("Uploader", "Retry ${failed.size} failed upload(s)")
    }
}
