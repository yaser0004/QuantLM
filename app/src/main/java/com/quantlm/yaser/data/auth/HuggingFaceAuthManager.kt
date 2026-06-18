package com.quantlm.yaser.data.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HuggingFaceAuth"
private const val PREFS_NAME = "huggingface_auth"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val HF_API_URL = "https://huggingface.co/api/whoami-v2"

/**
 * Manages HuggingFace authentication for downloading gated models.
 * Tokens are stored securely using EncryptedSharedPreferences.
 */
@Singleton
class HuggingFaceAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Lazy + self-healing: EncryptedSharedPreferences.create touches the
     * Android Keystore and the prefs file, and it THROWS when the ciphertext
     * no longer matches the keystore key — the classic case being an
     * auto-backup restored onto a new device (the prefs file travels, the
     * keystore key does not). Eager creation in the constructor turned that
     * into a crash at injection time; here it degrades to a one-time token
     * reset instead.
     */
    private val encryptedPrefs: android.content.SharedPreferences? by lazy { createPrefs() }

    private fun createPrefs(): android.content.SharedPreferences? {
        return try {
            buildPrefs()
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted prefs unreadable (likely restored from backup); resetting", e)
            try {
                context.deleteSharedPreferences(PREFS_NAME)
                buildPrefs()
            } catch (e2: Exception) {
                Log.e(TAG, "Could not recreate encrypted prefs; token storage disabled", e2)
                null
            }
        }
    }

    private fun buildPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val httpClient = OkHttpClient.Builder().build()

    /**
     * Save access token securely
     */
    fun saveToken(token: String) {
        encryptedPrefs?.edit()?.putString(KEY_ACCESS_TOKEN, token)?.apply()
        Log.d(TAG, "Access token saved")
    }

    /**
     * Get saved access token
     */
    fun getToken(): String? {
        return try {
            encryptedPrefs?.getString(KEY_ACCESS_TOKEN, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read access token", e)
            null
        }
    }

    /**
     * Clear saved access token
     */
    fun clearToken() {
        encryptedPrefs?.edit()?.remove(KEY_ACCESS_TOKEN)?.apply()
        Log.d(TAG, "Access token cleared")
    }
    
    /**
     * Check if token is saved
     */
    fun hasToken(): Boolean {
        return getToken() != null
    }
    
    /**
     * Validate token against HuggingFace API
     * Returns username if valid, null if invalid
     */
    suspend fun validateToken(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(HF_API_URL)
                .addHeader("Authorization", "Bearer $token")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                // Extract username from response (simple JSON parsing)
                val usernameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body ?: "")
                val username = usernameMatch?.groupValues?.get(1)
                Log.d(TAG, "Token validated for user: $username")
                username
            } else {
                Log.w(TAG, "Token validation failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token validation error", e)
            null
        }
    }
    
    /**
     * Save and validate token
     * Returns username if valid, null if invalid (token not saved in this case)
     */
    suspend fun saveAndValidateToken(token: String): String? {
        val username = validateToken(token)
        if (username != null) {
            saveToken(token)
        }
        return username
    }
    
    /**
     * Check if a HuggingFace model URL is from a gated repository
     * Returns true if the model might require authentication
     */
    fun isGatedModelUrl(url: String): Boolean {
        // Common indicators of gated models
        return url.contains("meta-llama/") ||
               url.contains("mistralai/") ||
               url.contains("google/gemma") ||
               url.contains("-gated") ||
               url.contains("/gated/")
    }
}
