package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    // ── Réseau ────────────────────────────────────────────────────────────────

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net  = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    // ── Scan timestamp — index incrémentiel ────────────────────────────────────

    fun getLastScanTimestamp(): Long = prefs.getLong("last_scan_ts", 0L)
    private fun setLastScanTimestamp(ts: Long) { prefs.edit().putLong("last_scan_ts", ts).apply() }
    fun resetScanTimestamp() { prefs.edit().putLong("last_scan_ts", 0L).apply() }

    // ── Server SHA256 cache (dedup) ───────────────────────────────────────────

    fun isOnServer(sha256: String): Boolean {
        val set = prefs.getStringSet("server_sha256_cache", emptySet()) ?: emptySet()
        return set.contains(sha256)
    }

    suspend fun refreshServerSha256Cache() = withContext(Dispatchers.IO) {
        try {
            val resp = getApi().getRecords("Bearer ${getAuthToken()}")
            if (resp.isSuccessful) {
                val sha256Set = resp.body()?.map { it.sha256 }?.toSet() ?: emptySet()
                prefs.edit().putStringSet("server_sha256_cache", sha256Set).apply()
                addLog("Cache", "SHA256 cache: ${sha256Set.size} entrée(s)")
            }
        } catch (e: Exception) {
            addLog("Cache", "Refresh cache échoué: ${e.message}", true)
        }
    }

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
                addLog("Auth", "Connexion réussie")
                Pair(true, "")
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                addLog("Auth", "Connexion échouée: $err", true)
                Pair(false, "Login failed: $err")
            }
        } catch (e: Exception) {
            addLog("Auth", "Connexion exception: ${e.message}", true)
            Pair(false, e.message ?: "Unknown error")
        }
    }

    /** Connexion automatique + refresh du cache SHA256 (silencieux). */
    suspend fun autoConnectIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val (ok, _) = login()
            if (ok) refreshServerSha256Cache()
        } catch (e: Exception) {
            addLog("AutoConnect", "Auto-connexion échouée: ${e.message}", true)
        }
    }

    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val response = getApi().checkHealth()
            if (response.isSuccessful) {
                addLog("Auth", "Serveur joignable")
                Pair(true, "")
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                Pair(false, "HTTP ${response.code()}: $err")
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }

    // ── Upload (parallèle dynamique, dédup) ───────────────────────────────────

    suspend fun uploadPendingFiles(): Int = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext 0

        if (getAuthToken().isEmpty()) {
            val (ok, err) = login()
            if (!ok) {
                addLog("Uploader", "Upload impossible — auth échouée: $err", true)
                return@withContext 0
            }
        }

        val pending = uploadDao.getPendingUploads()
        if (pending.isEmpty()) return@withContext 0

        // Parallélisme dynamique : plus on a de fichiers, plus on parallélise
        val concurrency = when {
            pending.size >= 100 -> 16
            pending.size >= 30  -> 12
            pending.size >= 10  -> 8
            else                -> 4
        }
        addLog("Uploader", "Upload de ${pending.size} fichier(s) — $concurrency en parallèle")

        var uploaded = 0
        pending.chunked(concurrency).forEach { chunk ->
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
            addLog("Uploader", "Fichier manquant, marqué FAILED: ${upload.name}", true)
            uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = "File not found"))
            return false
        }
        return try {
            val sha256 = calculateSHA256(file)

            if (isOnServer(sha256)) {
                uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                addLog("Uploader", "Ignoré (déjà sur serveur): ${upload.name}")
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
                    addLog("Uploader", "Envoyé: ${upload.name}")
                    true
                }
                response.code() == 409 -> {
                    uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                    addToServerCache(sha256)
                    addLog("Uploader", "Ignoré (doublon serveur): ${upload.name}")
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
                    addLog("Uploader", "Échec upload (${response.code()}): ${upload.name}", true)
                    false
                }
            }
        } catch (e: Exception) {
            uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = e.message))
            addLog("Uploader", "Exception upload: ${upload.name} — ${e.message}", true)
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

    // ── Index incrémentiel ────────────────────────────────────────────────────

    /**
     * Scan incrémentiel : 1er appel = scan complet, suivants = seulement les fichiers
     * modifiés depuis le dernier scan. Le FileObserver gère le temps réel.
     * L'index Room est la source de vérité — on ne le recharge jamais en entier.
     */
    suspend fun scanFolderIncremental(): Int = withContext(Dispatchers.IO) {
        val folderPath = getMonitorFolderPath()
        val folder     = File(folderPath)

        if (!folder.exists() || !folder.isDirectory) {
            addLog("Scanner", "Dossier introuvable: $folderPath", true)
            return@withContext 0
        }

        val lastScanTs  = getLastScanTimestamp()
        val now         = System.currentTimeMillis()
        val isFirstScan = lastScanTs == 0L
        val cutoff      = if (isFirstScan) 0L else lastScanTs - 5_000L

        val filesToCheck = mutableListOf<File>()
        folder.walkTopDown().maxDepth(3).forEach { entry ->
            if (entry.isFile && isAudioFile(entry) && entry.lastModified() >= cutoff) {
                filesToCheck.add(entry)
            }
        }

        if (isFirstScan) {
            addLog("Scanner", "Premier scan: ${filesToCheck.size} fichier(s) audio")
        } else if (filesToCheck.isNotEmpty()) {
            addLog("Scanner", "Scan delta: ${filesToCheck.size} fichier(s) à vérifier")
        }

        var addedCount = 0
        for (file in filesToCheck) {
            if (uploadDao.getUploadByPath(file.absolutePath) != null) continue
            val sha256 = calculateSHA256(file)

            val existingBySha = uploadDao.getUploadBySha256(sha256)
            if (existingBySha != null && existingBySha.status == "COMPLETED") {
                uploadDao.insertUpload(
                    Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                        size = file.length(), status = "COMPLETED",
                        uploadedAt = existingBySha.uploadedAt)
                )
                continue
            }

            if (isOnServer(sha256)) {
                uploadDao.insertUpload(
                    Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                        size = file.length(), status = "COMPLETED",
                        uploadedAt = System.currentTimeMillis())
                )
                continue
            }

            val inserted = uploadDao.insertUpload(
                Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                    size = file.length(), status = "PENDING")
            )
            if (inserted > 0) {
                addedCount++
                addLog("Scanner", "Mis en queue: ${file.name}")
            }
        }

        setLastScanTimestamp(now)
        if (addedCount > 0) addLog("Scanner", "Scan: $addedCount nouveau(x) fichier(s) en queue")
        addedCount
    }

    /** Scan complet (bouton manuel dans l'UI). */
    suspend fun scanFolderManually(): Int = withContext(Dispatchers.IO) {
        val folderPath = getMonitorFolderPath()
        val folder = File(folderPath)
        addLog("Scanner", "Scan manuel: $folderPath")

        if (!folder.exists() || !folder.isDirectory) {
            addLog("Scanner", "Dossier introuvable: $folderPath", true)
            return@withContext 0
        }

        val allFiles = collectAudioFiles(folder)
        addLog("Scanner", "${allFiles.size} fichier(s) audio trouvé(s)")

        var addedCount = 0
        for (file in allFiles) {
            if (uploadDao.getUploadByPath(file.absolutePath) != null) continue
            val sha256 = calculateSHA256(file)

            val existingBySha = uploadDao.getUploadBySha256(sha256)
            if (existingBySha != null && existingBySha.status == "COMPLETED") {
                uploadDao.insertUpload(
                    Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                        size = file.length(), status = "COMPLETED",
                        uploadedAt = existingBySha.uploadedAt)
                )
                continue
            }

            if (isOnServer(sha256)) {
                uploadDao.insertUpload(
                    Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                        size = file.length(), status = "COMPLETED",
                        uploadedAt = System.currentTimeMillis())
                )
                addLog("Scanner", "Ignoré (serveur): ${file.name}")
                continue
            }

            val inserted = uploadDao.insertUpload(
                Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                    size = file.length(), status = "PENDING")
            )
            if (inserted > 0) {
                addedCount++
                addLog("Scanner", "Mis en queue: ${file.name}")
            }
        }
        addLog("Scanner", "Scan manuel terminé — $addedCount nouveau(x)")
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

    fun autoDetectCallRecordingsFolder(): String {
        val candidates = listOf(
            "/storage/emulated/0/Recordings/Call",
            "/storage/emulated/0/Recordings",
            "/storage/emulated/0/MIUI/sound_recorder/call_rec",
            "/storage/emulated/0/MIUI/sound_recorder",
            "/storage/emulated/0/Sounds/CallRecordings",
            "/storage/emulated/0/Sounds",
            "/storage/emulated/0/Sounds/CallRecord",
            "/storage/emulated/0/Recordings/CallRecording",
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
                if (count > bestCount) { bestCount = count; bestPath = path }
            }
        }

        if (bestPath.isEmpty()) bestPath = findCallRecordingsFolderViaMediaStore() ?: ""
        Log.d("CallSync/AutoDetect", "Dossier: $bestPath ($bestCount fichiers)")
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
            Log.w("CallSync/AutoDetect", "MediaStore query échoué: ${e.message}")
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
        if (stuck.isNotEmpty()) addLog("Uploader", "Reset ${stuck.size} upload(s) bloqué(s) → PENDING")
    }

    suspend fun retryFailedUploads() = withContext(Dispatchers.IO) {
        val failed = uploadDao.getFailedUploads()
        failed.forEach {
            uploadDao.updateUpload(it.copy(status = "PENDING", errorMessage = null,
                retryCount = it.retryCount + 1))
        }
        if (failed.isNotEmpty()) addLog("Uploader", "Retry ${failed.size} upload(s) en échec")
    }

    // ── Suppression locale totale ─────────────────────────────────────────────

    suspend fun deleteAllLocalFilesAndIndex(): Int = withContext(Dispatchers.IO) {
        val uploads = uploadDao.getAllUploadsList()
        var deleted = 0
        for (upload in uploads) {
            try {
                val file = File(upload.path)
                if (file.exists()) { file.delete(); deleted++ }
            } catch (e: Exception) {
                addLog("DeleteAll", "Erreur suppression ${upload.name}: ${e.message}", true)
            }
        }
        uploadDao.clearAllUploads()
        resetScanTimestamp()
        addLog("DeleteAll", "$deleted fichier(s) supprimé(s) + index vidé")
        deleted
    }

    // ── Delete-at-source polling ───────────────────────────────────────────────

    suspend fun pollAndExecuteDeleteCommands() = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) return@withContext
        try {
            val phoneId = getPhoneId()
            if (phoneId.isEmpty()) return@withContext
            if (getAuthToken().isEmpty()) login()
            val token = "Bearer ${getAuthToken()}"

            val response = getApi().getPendingCommands(token, phoneId)
            if (!response.isSuccessful) return@withContext

            val commands = response.body() ?: return@withContext
            if (commands.isEmpty()) return@withContext

            addLog("DeleteCmd", "${commands.size} ordre(s) de suppression reçu(s)")

            for (cmd in commands) {
                try {
                    val upload = uploadDao.getUploadBySha256(cmd.sha256)
                    if (upload != null) {
                        val file = File(upload.path)
                        if (file.exists()) { file.delete(); addLog("DeleteCmd", "Supprimé: ${upload.name}") }
                        uploadDao.deleteUploadById(upload.id)
                    }
                    getApi().acknowledgeDeleteCommand(token, cmd.id)
                } catch (e: Exception) {
                    addLog("DeleteCmd", "Échec SHA ${cmd.sha256}: ${e.message}", true)
                }
            }
        } catch (e: Exception) {
            addLog("DeleteCmd", "Polling échoué: ${e.message}", true)
        }
    }
}
