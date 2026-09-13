package com.miniced.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stocke les clés API (Anthropic + Picovoice) de façon chiffrée sur l'appareil
 * (jamais en clair, jamais codées en dur dans le code source).
 *
 * - Clé Anthropic : créée sur https://console.anthropic.com (facturée à l'usage).
 * - Clé Picovoice (AccessKey) : créée gratuitement sur https://console.picovoice.ai,
 *   nécessaire pour le mot de réveil ("Hey Jarvis" par défaut). Gratuite pour un
 *   usage personnel.
 */
object ApiKeyStore {
    private const val PREFS_NAME = "mini_ced_secure_prefs"
    private const val KEY_ANTHROPIC = "anthropic_api_key"
    private const val KEY_PICOVOICE = "picovoice_access_key"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveAnthropicKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_ANTHROPIC, key.trim()).apply()
    }

    fun getAnthropicKey(context: Context): String? = prefs(context).getString(KEY_ANTHROPIC, null)

    fun savePicovoiceKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_PICOVOICE, key.trim()).apply()
    }

    fun getPicovoiceKey(context: Context): String? = prefs(context).getString(KEY_PICOVOICE, null)
}
