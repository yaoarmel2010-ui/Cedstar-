package com.miniced.app

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Service qui affiche l'avatar de Mini Ced en superposition ET écoute en
 * permanence le mot de réveil ("Hey Jarvis" par défaut, via Porcupine).
 *
 * Flux complet, sans jamais ouvrir l'application ni toucher l'écran :
 *   mot de réveil détecté -> écoute de la commande (sans UI) -> envoi à Claude
 *   (avec recherche web) -> réponse parlée à voix haute -> ré-écoute du mot de
 *   réveil.
 *
 * Pour changer le mot de réveil ("Hey Mini Ced" au lieu de "Hey Jarvis") :
 * entraîne un mot-clé personnalisé sur console.picovoice.ai (gratuit), dépose
 * le fichier .ppn obtenu dans app/src/main/assets/, et remplace
 * Porcupine.BuiltInKeyword.JARVIS par le chemin de ce fichier ci-dessous.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isFaded = false

    private var porcupineManager: PorcupineManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Position / geste de glisser-déposer de l'avatar
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var hasMoved = false

    companion object {
        const val CHANNEL_ID = "mini_ced_overlay_channel"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        showOverlay()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.FRANCE
        }

        startWakeWordListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        porcupineManager?.stop()
        porcupineManager?.delete()
        speechRecognizer?.destroy()
        tts.stop()
        tts.shutdown()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
    }

    // ---------------------------------------------------------------------
    // Mot de réveil (Porcupine) — écoute en continu, très faible consommation
    // ---------------------------------------------------------------------

    private fun startWakeWordListening() {
        val accessKey = ApiKeyStore.getPicovoiceKey(this)
        if (accessKey.isNullOrBlank()) {
            setAvatarState(AvatarState.ERROR_NO_KEY)
            return
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS) // "Hey Jarvis"
                .build(applicationContext, object : PorcupineManagerCallback {
                    override fun invoke(keywordIndex: Int) {
                        onWakeWordDetected()
                    }
                })
            porcupineManager?.start()
            setAvatarState(AvatarState.IDLE_LISTENING_WAKEWORD)
        } catch (e: Exception) {
            setAvatarState(AvatarState.ERROR_NO_KEY)
        }
    }

    private fun onWakeWordDetected() {
        // On coupe temporairement Porcupine pendant qu'on écoute la vraie commande.
        porcupineManager?.stop()
        setAvatarState(AvatarState.LISTENING_COMMAND)
        listenForCommand()
    }

    // ---------------------------------------------------------------------
    // Reconnaissance de la commande (sans aucune UI système, invisible)
    // ---------------------------------------------------------------------

    private fun listenForCommand() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            resumeWakeWordListening()
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val spoken = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (spoken != null) handleCommand(spoken) else resumeWakeWordListening()
                }

                override fun onError(error: Int) = resumeWakeWordListening()
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        speechRecognizer?.startListening(intent)
    }

    // ---------------------------------------------------------------------
    // Envoi à Claude + réponse parlée
    // ---------------------------------------------------------------------

    private fun handleCommand(spoken: String) {
        setAvatarState(AvatarState.THINKING)
        val apiKey = ApiKeyStore.getAnthropicKey(this)

        if (apiKey.isNullOrBlank()) {
            speak("Configure ta clé API Claude pour que je puisse vraiment te répondre.")
            resumeWakeWordListening()
            return
        }

        serviceScope.launch {
            val reponse = try {
                ClaudeClient.ask(apiKey, spoken)
            } catch (e: Exception) {
                "Je n'ai pas réussi à contacter le serveur."
            }
            speak(reponse)
            resumeWakeWordListening()
        }
    }

    private fun speak(text: String) {
        setAvatarState(AvatarState.SPEAKING)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mini_ced_utterance")
    }

    private fun resumeWakeWordListening() {
        setAvatarState(AvatarState.IDLE_LISTENING_WAKEWORD)
        try {
            porcupineManager?.start()
        } catch (e: Exception) {
            // Déjà démarré ou moteur indisponible ; sans conséquence grave.
        }
    }

    // ---------------------------------------------------------------------
    // Notification (obligatoire pour un service en tâche de fond)
    // ---------------------------------------------------------------------

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notif_title))
            .setContentText("Dis \"Hey Jarvis\" pour lui parler")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    // ---------------------------------------------------------------------
    // Avatar flottant (overlay)
    // ---------------------------------------------------------------------

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_avatar, null)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        windowManager.addView(overlayView, params)

        overlayView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) hasMoved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasMoved) toggleFade(view)
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleFade(view: View) {
        isFaded = !isFaded
        view.animate().alpha(if (isFaded) 0.15f else 1f).setDuration(200).start()
    }

    /** Fait varier légèrement la transparence de l'avatar selon son état. */
    private fun setAvatarState(state: AvatarState) {
        val targetAlpha = when (state) {
            AvatarState.IDLE_LISTENING_WAKEWORD -> 1f
            AvatarState.LISTENING_COMMAND -> 1f
            AvatarState.THINKING -> 0.7f
            AvatarState.SPEAKING -> 1f
            AvatarState.ERROR_NO_KEY -> 0.3f
        }
        overlayView?.animate()?.alpha(targetAlpha)?.setDuration(150)?.start()
        // TODO Phase 4 : remplacer par une vraie animation (Lottie) selon l'état.
    }

    enum class AvatarState { IDLE_LISTENING_WAKEWORD, LISTENING_COMMAND, THINKING, SPEAKING, ERROR_NO_KEY }
}
