package com.resumekit.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.settingsDataStore by preferencesDataStore(name = "secure_settings")

class SecureStore(context: Context) {
    private val appContext = context.applicationContext
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        val encrypted = appContext.settingsDataStore.data.first()[stringPreferencesKey(key)]
            ?: return@withContext null
        try {
            String(
                aead.decrypt(
                    android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP),
                    key.toByteArray(),
                ),
                Charsets.UTF_8,
            )
        } catch (error: Exception) {
            throw IllegalStateException("Could not decrypt saved ResumeKit data for '$key'.", error)
        }
    }

    suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        val encrypted = android.util.Base64.encodeToString(
            aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP,
        )
        appContext.settingsDataStore.edit { preferences ->
            preferences[stringPreferencesKey(key)] = encrypted
        }
    }

    companion object {
        private const val KEYSET_NAME = "resumekit_keyset"
        private const val KEYSET_PREFS = "resumekit_tink_keyset"
        private const val MASTER_KEY_URI = "android-keystore://resumekit_master_key"
    }
}
