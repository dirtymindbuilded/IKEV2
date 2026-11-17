package com.example.ikev2manager.vpn

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.example.ikev2manager.data.Credentials
import org.json.JSONObject
import java.util.UUID

/** Exports a standards-compatible strongSwan Android profile and offers it to the app for import. */
object StrongSwanFallback {
    private const val MIME_PROFILE = "application/vnd.strongswan.profile"

    fun exportAndOpen(context: Context, host: String, credentials: Credentials): Uri {
        val profile = JSONObject()
            .put("uuid", UUID.randomUUID().toString())
            .put("name", "IKEv2 $host")
            .put("type", "ikev2-eap")
            .put("remote", JSONObject().put("addr", host))
            .put("local", JSONObject()
                .put("eap_id", credentials.username)
                .put("shared_secret", credentials.password))
            .toString(2)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ikev2-${host.replace(Regex("[^A-Za-z0-9.-]"), "_")}.sswan")
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_PROFILE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IKEv2Manager")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the profile file in Downloads")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(profile.toByteArray(Charsets.UTF_8))
            } ?: error("Could not write the profile file")

            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_PROFILE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(openIntent, "Import profile with strongSwan"))
            return uri
        } catch (e: Exception) {
            // Keep the generated file in Downloads so it can still be imported manually.
            throw e
        }
    }
}
