package com.miniced.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tts: TextToSpeech
    private lateinit var txtLastAnswer: TextView

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (spoken != null) handleUserSpeech(spoken)
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchSpeechRecognition()
        else Toast.makeText(this, "Permission micro refusée", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.FRANCE
        }

        txtLastAnswer = findViewById(R.id.txtLastAnswer)

        findViewById<Button>(R.id.btnApiKey).setOnClickListener { showAnthropicKeyDialog() }
        findViewById<Button>(R.id.btnWakeWordKey).setOnClickListener { showPicovoiceKeyDialog() }

        findViewById<Button>(R.id.btnPermission).setOnClickListener {
            requestOverlayPermission()
        }

        findViewById<Button>(R.id.btnStartOverlay).setOnClickListener {
            startMiniCed()
        }

        findViewById<Button>(R.id.btnStopOverlay).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }

        findViewById<Button>(R.id.btnTalk).setOnClickListener {
            checkMicPermissionAndLaunch()
        }
    }

    /** Vérifie les prérequis puis démarre le service overlay + écoute permanente. */
    private fun startMiniCed() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorise d'abord l'affichage par-dessus les autres apps (étape 1)", Toast.LENGTH_LONG).show()
            return
        }
        if (ApiKeyStore.getPicovoiceKey(this).isNullOrBlank()) {
            Toast.makeText(this, "Configure d'abord la clé Picovoice (mot de réveil)", Toast.LENGTH_LONG).show()
            return
        }

        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            Toast.makeText(this, "Autorise le micro puis appuie à nouveau sur ce bouton", Toast.LENGTH_LONG).show()
            return
        }

        startService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Mini Ced écoute — dis \"Hey Jarvis\" 🎙️", Toast.LENGTH_LONG).show()
    }

    private fun showAnthropicKeyDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(ApiKeyStore.getAnthropicKey(this@MainActivity) ?: "")
            hint = "sk-ant-..."
        }
        AlertDialog.Builder(this)
            .setTitle("Clé API Claude")
            .setMessage("Colle ta clé API Anthropic (console.anthropic.com). Stockée chiffrée sur l'appareil.")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                ApiKeyStore.saveAnthropicKey(this, input.text.toString())
                Toast.makeText(this, "Clé enregistrée ✅", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showPicovoiceKeyDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(ApiKeyStore.getPicovoiceKey(this@MainActivity) ?: "")
            hint = "AccessKey Picovoice"
        }
        AlertDialog.Builder(this)
            .setTitle("Clé Picovoice (mot de réveil)")
            .setMessage("Colle ton AccessKey, créé gratuitement sur console.picovoice.ai. Nécessaire pour que Mini Ced t'écoute en continu (\"Hey Jarvis\").")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                ApiKeyStore.savePicovoiceKey(this, input.text.toString())
                Toast.makeText(this, "Clé enregistrée ✅", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            Toast.makeText(this, "Permission déjà accordée ✅", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkMicPermissionAndLaunch() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (granted) launchSpeechRecognition()
        else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun launchSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Parle à Mini Ced...")
        }
        speechLauncher.launch(intent)
    }

    /** Utilisé uniquement pour le bouton manuel "Parler à Mini Ced" depuis l'appli. */
    private fun handleUserSpeech(spoken: String) {
        val apiKey = ApiKeyStore.getAnthropicKey(this)

        if (apiKey.isNullOrBlank()) {
            afficherEtParler(spoken, "Configure ta clé API Claude (bouton 0) pour que je puisse vraiment te répondre.")
            return
        }

        txtLastAnswer.text = "Toi : $spoken\n\nMini Ced réfléchit..."

        lifecycleScope.launch {
            val reponse = try {
                ClaudeClient.ask(apiKey, spoken)
            } catch (e: Exception) {
                "Je n'ai pas réussi à contacter le serveur : ${e.message}"
            }
            afficherEtParler(spoken, reponse)
        }
    }

    private fun afficherEtParler(spoken: String, reponse: String) {
        txtLastAnswer.text = "Toi : $spoken\n\nMini Ced : $reponse"
        tts.speak(reponse, TextToSpeech.QUEUE_FLUSH, null, "mini_ced_utterance")
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
