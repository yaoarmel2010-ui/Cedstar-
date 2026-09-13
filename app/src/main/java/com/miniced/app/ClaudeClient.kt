package com.miniced.app

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Client réseau vers l'API Anthropic (Claude), avec l'outil de recherche web activé.
 *
 * Grâce à ça, Mini Ced peut :
 *  - répondre directement quand il connaît la réponse,
 *  - décider lui-même de lancer une recherche web quand la question le demande
 *    (actualité, info récente, "cherche-moi...", question dont il n'est pas sûr...),
 *  - te reprendre si ce que tu affirmes est inexact, plutôt que de te flatter
 *    (voir le system prompt ci-dessous — c'est le comportement "façon Jarvis"
 *    que tu as demandé).
 *
 * Le texte renvoyé est déjà prêt à être lu par le TextToSpeech (pas de markdown,
 * pas de listes à puces — c'est demandé explicitement dans le system prompt).
 */
object ClaudeClient {

    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-sonnet-5"

    private val client = OkHttpClient()

    private val SYSTEM_PROMPT = """
        Tu es Mini Ced, l'assistant IA personnel de l'utilisateur, dans l'esprit de
        Jarvis (Iron Man) : direct, compétent, un peu de répartie, mais toujours utile.

        Règles importantes :
        - Si l'utilisateur affirme quelque chose d'inexact, dis-le lui clairement et
          explique pourquoi. Ne valide jamais une erreur par politesse.
        - Si sa demande nécessite une information récente, précise, ou que tu n'es
          pas sûr à 100 %, utilise l'outil de recherche web plutôt que de deviner.
        - Cite brièvement ta source quand tu t'appuies sur une recherche web.
        - Tes réponses seront lues à voix haute par une synthèse vocale : reste
          concis, parle naturellement, sans markdown ni listes à puces.
    """.trimIndent()

    /**
     * Envoie le message de l'utilisateur à Claude et renvoie la réponse finale
     * (déjà "prête à être parlée"). Suspend function : à appeler depuis une
     * coroutine (ex. lifecycleScope.launch { ... }).
     */
    suspend fun ask(apiKey: String, userMessage: String): String =
        suspendCoroutine { continuation ->

            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 1024)
                put("system", SYSTEM_PROMPT)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put("content", userMessage)
                    )
                )
                put(
                    "tools",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "web_search_20250305")
                            .put("name", "web_search")
                    )
                )
            }

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val raw = resp.body?.string() ?: "{}"
                        val json = JSONObject(raw)

                        if (!resp.isSuccessful) {
                            val message = json.optJSONObject("error")
                                ?.optString("message", "Erreur inconnue de l'API")
                                ?: "Erreur réseau (${resp.code})"
                            continuation.resume("Erreur : $message")
                            return@use
                        }

                        // On assemble uniquement les blocs de type "text" : les blocs
                        // "server_tool_use" / "web_search_tool_result" (la recherche
                        // elle-même) sont gérés en interne par Claude, on n'a pas
                        // besoin de les traiter côté app.
                        val content = json.optJSONArray("content")
                        val text = StringBuilder()
                        if (content != null) {
                            for (i in 0 until content.length()) {
                                val block = content.getJSONObject(i)
                                if (block.optString("type") == "text") {
                                    text.append(block.optString("text"))
                                }
                            }
                        }

                        continuation.resume(
                            if (text.isNotEmpty()) text.toString()
                            else "Je n'ai pas réussi à obtenir de réponse exploitable."
                        )
                    }
                }
            })
        }
}
