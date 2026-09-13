package com.miniced.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stocke la clé API Anthropic de façon chiffrée sur l'appareil (jamais en clair,
 * jamais codée en dur dans le code source).
 *
 * L'utilisateur doit créer sa propre clé sur https://console.anthropic.com puis
 * la saisir depuis l'écran principal. L'usage de l'API est facturé par Anthropic
 * à l'usage (au volume de texte échangé) — pense à surveiller ta consommation
 * sur la console si tu utilises Mini Ced intensivement.
 */
object ApiKeyStore {
    private const val PREFS_NAME = "mini_ced_secure_prefs"
    private const val KEY_API = "anthropic_api_key"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    fun get(context: Context): String? = prefs(context).getString(KEY_API, null)

    fun isConfigured(context: Context): Boolean = !get(context).isNullOrBlank()
}
