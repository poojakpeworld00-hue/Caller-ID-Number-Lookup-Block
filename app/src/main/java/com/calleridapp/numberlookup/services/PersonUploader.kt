package com.calleridapp.numberlookup.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.calleridapp.numberlookup.BuildConfig
import androidx.core.content.ContextCompat
import com.calleridapp.numberlookup.data.PeopleSource
import com.calleridapp.numberlookup.data.PersonItem
import com.calleridapp.numberlookup.data.VaultRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Uploads the device contacts to the server exactly once (first time the
 * contacts permission is available). Guarded by [VaultRegistry.isContactsUploaded].
 *
 * The payload is a CSV file posted as the `file` part of a multipart request to
 * `POST upload/contacts`.
 */
object PersonUploader {

    private const val TAG = "PersonUploader"
    private const val FILE_NAME = "contacts_upload.csv"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var inProgress = false

    fun uploadOnceIfNeeded(context: Context) {
        // Only upload in release builds.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Skipping upload in debug build")
            return
        }
        val app = context.applicationContext
        val prefs = VaultRegistry(app)
        if (prefs.isContactsUploaded || inProgress) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // Without a key the request comes back 401, which would read as a server fault.
        if (!ServiceCredentials.isConfigured) {
            Log.w(TAG, "Contact upload skipped: no API key configured")
            return
        }

        inProgress = true
        scope.launch {
            var file: File? = null
            try {
                val contacts = PeopleSource(app).getContacts()
                if (contacts.isEmpty()) {
                    Log.w(TAG, "No contacts to upload")
                    return@launch
                }

                file = File(app.cacheDir, FILE_NAME).apply { writeText(toCsv(contacts)) }
                Log.d(TAG, "Uploading ${contacts.size} contacts (${file.length()} bytes)…")

                val part = MultipartBody.Part.createFormData(
                    "file", file.name, file.asRequestBody(CSV_MEDIA_TYPE)
                )
                val response = RetrofitClient.api.uploadContacts(part).execute()

                if (response.isSuccessful) {
                    prefs.isContactsUploaded = true
                    Log.i(TAG, "Upload SUCCESS (${response.code()}): ${response.body()}")
                } else {
                    val err = runCatching { response.errorBody()?.string() }.getOrNull()
                    Log.e(TAG, "Upload FAILED (${response.code()}): $err")
                }
            } catch (e: Exception) {
                // Network/IO failure — leave the flag unset so it retries next time.
                Log.e(TAG, "Upload ERROR: ${e.message}", e)
            } finally {
                // The whole address book sits in cacheDir as plain text; drop it now.
                runCatching { file?.delete() }
                inProgress = false
            }
        }
    }

    /**
     * The address book as CSV, header row first. RFC 4180 quoting, because names
     * routinely contain commas, quotes or newlines that would shift every column.
     */
    internal fun toCsv(contacts: List<PersonItem>): String = buildString {
        append("name,phone\n")
        contacts.forEach { contact ->
            append(csvField(contact.name)).append(',')
            append(csvField(contact.detail)).append('\n')
        }
    }

    private fun csvField(value: String?): String {
        val text = value.orEmpty()
        if (text.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return text
        return "\"" + text.replace("\"", "\"\"") + "\""
    }

    private val CSV_MEDIA_TYPE = "text/csv".toMediaTypeOrNull()
}
