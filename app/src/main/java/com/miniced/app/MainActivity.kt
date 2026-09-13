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

    // Résultat de la reconnaissance vocale
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()

        if (spoken != null) handleUserSpeech(spoken)
    }

    // Résultat de la demande de permission micro
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

        findViewById<Button>(R.id.btnApiKey).setOnClickListener { showApiKeyDialog() }

        findViewById<Button>(R.id.btnPermission).setOnClickListener {
            requestOverlayPermission()
        }

        findViewById<Button>(R.id.btnStartOverlay).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, OverlayService::class.java))
            } else {
                Toast.makeText(
                    this,
                    "Autorise d'abord l'affichage par-dessus les autres apps (étape 1)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        findViewById<Button>(R.id.btnStopOverlay).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }

        findViewById<Button>(R.id.btnTalk).setOnClickListener {
            checkMicPermissionAndLaunch()
        }
    }

    /** Boîte de dialogue simple pour coller sa clé API Anthropic (console.anthropic.com). */
    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(ApiKeyStore.get(this@MainActivity) ?: "")
            hint = "sk-ant-..."
        }

        AlertDialog.Builder(this)
            .setTitle("Clé API Claude")
            .setMessage("Colle ta clé API Anthropic (créée sur console.anthropic.com). Elle est stockée chiffrée sur l'appareil uniquement.")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                ApiKeyStore.save(this, input.text.toString())
                Toast.makeText(this, "Clé enregistrée ✅", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
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

    /**
     * Envoie ce que l'utilisateur a dit soit à Claude (s'il y a une clé API,
     * avec recherche web + correction honnête activées), soit au repli local.
     */
    private fun handleUserSpeech(spoken: String) {
        val apiKey = ApiKeyStore.get(this)

        if (apiKey.isNullOrBlank()) {
            val reponse = Brain.process(spoken)
            afficherEtParler(spoken, reponse)
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
        speak(reponse)
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mini_ced_utterance")
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
