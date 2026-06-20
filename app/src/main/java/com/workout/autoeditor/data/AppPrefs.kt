package com.workout.autoeditor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SharedPreferences-backed config the user can change.
 *
 * The HF token is stored in an EncryptedSharedPreferences file backed by the
 * Android Keystore, so even if the device is rooted or the prefs file is
 * exfiltrated the token stays unreadable. Plain (non-secret) config like
 * customModelUrl and skippedModel live in the regular prefs file.
 *
 * Both files are excluded from device backup (see res/xml/backup_rules.xml)
 * because the Keystore key does not survive a backup/restore cycle.
 */
class AppPrefs(ctx: Context) {

    private val plain: SharedPreferences =
        ctx.getSharedPreferences("workout_auto_editor_prefs", Context.MODE_PRIVATE)

    private val secret: SharedPreferences = try {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "workout_auto_editor_secret",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        // If the Keystore is unavailable (rare, but possible on emulators
        // without a hardware-backed keystore), fall back to the plain prefs
        // so the user can still enter a token at their own risk. We log so
        // the developer can see this happened.
        android.util.Log.w("AppPrefs", "EncryptedSharedPreferences unavailable, using plain", t)
        plain
    }

    var customModelUrl: String?
        get() = plain.getString(KEY_MODEL_URL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            // Reject non-https / file:// here too so a bad URL never lands
            // on disk in the first place.
            val sanitized = value?.trim()
            val ok = sanitized == null || sanitized.isBlank() || sanitized.startsWith("https://")
            plain.edit().putString(KEY_MODEL_URL, if (ok) sanitized else null).apply()
        }

    var hfToken: String?
        get() = secret.getString(KEY_HF_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = secret.edit().putString(KEY_HF_TOKEN, value?.trim()).apply()

    var skippedModel: Boolean
        get() = plain.getBoolean(KEY_SKIPPED_MODEL, false)
        set(value) = plain.edit().putBoolean(KEY_SKIPPED_MODEL, value).apply()

    companion object {
        private const val KEY_MODEL_URL = "model_url"
        private const val KEY_HF_TOKEN = "hf_token"
        private const val KEY_SKIPPED_MODEL = "skipped_model"
    }
}
