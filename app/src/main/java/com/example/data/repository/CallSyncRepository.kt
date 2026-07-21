package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.example.data.api.CallSyncApi
import com.example.data.api.LoginRequest
import com.example.data.api.PurgeResponse
import com.example.data.api.RecordingResponse
import com.example.data.api.StorageStatsResponse
import com.example.data.database.AppDatabase
import com.example.data.model.DownloadedRecord
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
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class CallSyncRepository(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    val uploadDao            = database.uploadDao()
    val logDao               = database.logDao()
    val downloadedRecordDao  = database.downloadedRecordDao()

    val allUploads:          Flow<List<Upload>>          = uploadDao.getAllUploads()
    val allLogs:             Flow<List<LogEntry>>        = logDao.getAllLogs()
    val allDownloadedRecords: Flow<List<DownloadedRecord>> = downloadedRecordDao.getAllDownloaded()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("callsync_prefs", Context.MODE_PRIVATE)

    init {
        if (getPhoneId().isEmpty()) {
            prefs.edit().putString("phone_id", UUID.randomUUID().toString().take(8)).apply()
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun getServerUrl(): String {
        var url = prefs.getString("server_url", "https://sarcastic-wiry-lava--havek99178.replit.app/") ?: "https://sarcastic-wiry-lava--havek99178.replit.app/"
        if (!url.endsWith("/")) url += "/"
        return url
    }
    fun setServerUrl(url: String) { prefs.edit().putString("server_url", url).apply(); resetApi() }

    fun getUsername(): String = prefs.getString("username", "admin") ?: "admin"
    fun setUsername(user: String) = prefs.edit().putString("username", user).apply()

    fun getPassword(): String = prefs.getString("password", "admin123") ?: "admin123"
    fun setPassword(pass: String) = prefs.edit().putString("password", pass).apply()

    fun getAuthToken(): String = prefs.getString("auth_token", "") ?: ""
    fun setAuthToken(token: String) = prefs.edit().putString("auth_token", token).apply()

    fun getPhoneId(): String = prefs.getString("phone_id", "") ?: ""

    fun getDeviceName(): String {
        val mfr   = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(mfr, ignoreCase = true)) model.replaceFirstChar { it.uppercase() }
               else "${mfr.replaceFirstChar { it.uppercase() }} $model"
    }

    fun getAndroidVersion(): String = Build.VERSION.RELEASE

    fun getMonitorFolderPath(): String {
        val default = "/storage/emulated/0/Recordings/Call"
        return prefs.getString("monitor_folder", default) ?: default
    }
    fun setMonitorFolderPath(path: String) = prefs.edit().putString("monitor_folder", path).apply()

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
                addLog("Auth", "Server reachable — ${response.body()?.status}")
                Pair(true, "")
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                addLog("Auth", "Health check failed: $err", true)
                Pair(false, "Server returned ${response.code()}: $err")
            }
        } catch (e: Exception) {
            addLog("Auth", "Connection error: ${e.message}", true)
            Pair(false, e.message ?: "Unknown error")
        }
    }

    // ── Upload (parallel) ─────────────────────────────────────────────────────

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

        // Upload up to CONCURRENCY files simultaneously
        val CONCURRENCY = 4
        var uploaded = 0
        pending.chunked(CONCURRENCY).forEach { chunk ->
            coroutineScope {
                val results = chunk.map { upload ->
                    async { uploadSingle(upload) }
                }.awaitAll()
                uploaded += results.count { it }
            }
        }
        uploaded
    }

    private suspend fun uploadSingle(upload: Upload): Boolean {
        val file = File(upload.path)
        if (!file.exists()) {
            addLog("Uploader", "File missing, marking failed: ${upload.name}", true)
            uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = "File not found on disk"))
            return false
        }
        return try {
            val sha256       = calculateSHA256(file)
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

            if (response.isSuccessful) {
                uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                addLog("Uploader", "Uploaded: ${upload.name}")
                true
            } else {
                val errBody = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                if (response.code() == 401) {
                    setAuthToken("")
                    val (ok, _) = login()
                    uploadDao.updateUpload(upload.copy(status = if (ok) "PENDING" else "FAILED", errorMessage = if (ok) null else errBody))
                } else {
                    uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = errBody))
                    addLog("Uploader", "Upload failed (${response.code()}): ${upload.name} — $errBody", true)
                }
                false
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

    suspend fun deleteRecord(id: Long): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (getAuthToken().isEmpty()) login()
            val response = getApi().deleteRecord("Bearer ${getAuthToken()}", id)
            if (response.isSuccessful) {
                addLog("Viewer", "Deleted record ID $id from server")
                // Remove local download entry if present
                downloadedRecordDao.deleteByRecordId(id)
                Pair(true, "")
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                addLog("Viewer", "Delete failed: $err", true)
                Pair(false, err)
            }
        } catch (e: Exception) {
            addLog("Viewer", "Delete exception: ${e.message}", true)
            Pair(false, e.message ?: "Unknown error")
        }
    }

    // ── Download to local storage ─────────────────────────────────────────────

    /** Downloads a recording to app-internal storage. Returns (success, error). */
    suspend fun downloadRecordLocally(record: RecordingResponse): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            // Already downloaded?
            if (downloadedRecordDao.getByRecordId(record.id) != null) return@withContext Pair(true, "")

            try {
                if (getAuthToken().isEmpty()) login()
                val response = getApi().downloadRecord("Bearer ${getAuthToken()}", record.id)
                if (!response.isSuccessful) {
                    val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                    return@withContext Pair(false, err)
                }

                val body = response.body() ?: return@withContext Pair(false, "Empty response body")

                // Save to internal app storage (invisible to other apps)
                val dir = File(context.filesDir, "recordings").also { it.mkdirs() }
                val localFile = File(dir, record.name)

                body.byteStream().use { input ->
                    FileOutputStream(localFile).use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }

                downloadedRecordDao.insert(
                    DownloadedRecord(
                        recordId    = record.id,
                        sha256      = record.sha256,
                        name        = record.name,
                        size        = record.size,
                        localPath   = localFile.absolutePath
                    )
                )
                addLog("Receiver", "Downloaded locally: ${record.name}")
                Pair(true, "")
            } catch (e: Exception) {
                addLog("Receiver", "Download failed: ${record.name} — ${e.message}", true)
                Pair(false, e.message ?: "Unknown error")
            }
        }

    /** Auto-downloads all server records not yet stored locally (parallel). */
    suspend fun autoDownloadAll(
        records: List<RecordingResponse>,
        onProgress: suspend (downloaded: Int, total: Int) -> Unit = { _, _ -> }
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val missing = records.filter { r ->
            downloadedRecordDao.getByRecordId(r.id) == null
        }
        if (missing.isEmpty()) return@withContext Pair(0, 0)

        addLog("Receiver", "Auto-download: ${missing.size} file(s) to fetch")

        var done = 0
        var errors = 0
        missing.chunked(3).forEach { chunk ->
            coroutineScope {
                chunk.map { record ->
                    async {
                        val (ok, _) = downloadRecordLocally(record)
                        if (ok) done++ else errors++
                        onProgress(done, missing.size)
                    }
                }.awaitAll()
            }
        }
        Pair(done, errors)
    }

    // ── Purge remote ──────────────────────────────────────────────────────────

    /**
     * Verifies the receiver has all server records locally, then purges the server.
     * Returns (success, message).
     */
    suspend fun purgeRemoteRecords(serverRecords: List<RecordingResponse>): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                // 0 records on server → already clean
                if (serverRecords.isEmpty()) return@withContext Pair(true, "Serveur déjà vide")

                // Check which server records are missing locally
                val missing = serverRecords.filter { r ->
                    downloadedRecordDao.getByRecordId(r.id) == null
                }

                if (missing.isNotEmpty()) {
                    val msg = "${missing.size} fichier(s) pas encore téléchargé(s) localement. Lance le téléchargement auto d'abord."
                    addLog("Purge", msg, true)
                    return@withContext Pair(false, msg)
                }

                // All good — call purge-all
                if (getAuthToken().isEmpty()) login()
                val response = getApi().purgeAllRecords("Bearer ${getAuthToken()}")
                if (response.isSuccessful) {
                    val body = response.body()
                    val msg  = "Serveur purgé — ${body?.deleted ?: 0} fichier(s) supprimé(s)"
                    addLog("Purge", msg)
                    Pair(true, msg)
                } else {
                    val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                    addLog("Purge", "Purge échouée: $err", true)
                    Pair(false, err)
                }
            } catch (e: Exception) {
                addLog("Purge", "Purge exception: ${e.message}", true)
                Pair(false, e.message ?: "Unknown error")
            }
        }

    // ── Local downloads management ────────────────────────────────────────────

    /** Delete a single locally-downloaded file + its DB entry. */
    suspend fun deleteLocalDownload(recordId: Long) = withContext(Dispatchers.IO) {
        val entry = downloadedRecordDao.getByRecordId(recordId) ?: return@withContext
        try { File(entry.localPath).delete() } catch (_: Exception) {}
        downloadedRecordDao.deleteByRecordId(recordId)
        addLog("Receiver", "Deleted local copy: ${entry.name}")
    }

    /** Delete ALL locally-downloaded files + clear the table. */
    suspend fun purgeAllLocalDownloads() = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "recordings")
        try { dir.deleteRecursively() } catch (_: Exception) {}
        dir.mkdirs()
        downloadedRecordDao.clearAll()
        addLog("Receiver", "All local downloads purged")
    }

    /** Returns total bytes used by local recordings folder. */
    fun localDownloadsDirSize(): Long {
        val dir = File(context.filesDir, "recordings")
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ── Folder scanning (source phone) ────────────────────────────────────────

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
                    Upload(sha256 = sha256, path = file.absolutePath, name = file.name, size = file.length(), status = "PENDING")
                )
                addedCount++
                addLog("Scanner", "Queued: ${file.name}")
            }
        }
        addLog("Scanner", "Scan done — $addedCount new file(s) queued")
        addedCount
    }

    private fun collectAudioFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        root.listFiles()?.forEach { entry ->
            when {
                entry.isFile && isAudioFile(entry) -> result.add(entry)
                entry.isDirectory -> {
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

    // ── Logs ──────────────────────────────────────────────────────────────────

    suspend fun addLog(tag: String, message: String, isError: Boolean = false) =
        withContext(Dispatchers.IO) {
            Log.d("CallSync/$tag", message)
            logDao.insertLog(LogEntry(tag = tag, message = message, isError = isError, timestamp = System.currentTimeMillis()))
        }

    suspend fun clearLogs()    = withContext(Dispatchers.IO) { logDao.clearAllLogs() }
    suspend fun clearUploads() = withContext(Dispatchers.IO) { uploadDao.clearAllUploads() }

    suspend fun resetStuckUploads() = withContext(Dispatchers.IO) {
        val stuck = uploadDao.getUploadingUploads()
        stuck.forEach { uploadDao.updateUpload(it.copy(status = "PENDING", errorMessage = null)) }
        if (stuck.isNotEmpty()) addLog("Uploader", "Reset ${stuck.size} stuck upload(s) → PENDING")
    }

    suspend fun retryFailedUploads() = withContext(Dispatchers.IO) {
        val failed = uploadDao.getPendingUploads().filter { it.status == "FAILED" }
        failed.forEach { uploadDao.updateUpload(it.copy(status = "PENDING", errorMessage = null, retryCount = it.retryCount + 1)) }
    }
}
