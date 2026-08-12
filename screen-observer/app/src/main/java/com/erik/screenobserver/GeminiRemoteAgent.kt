package com.erik.screenobserver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Optional Gemini cloud language brain. It interprets language but never executes Android
 * actions itself; the existing local dispatcher and safety checks remain authoritative.
 */
class GeminiRemoteAgent(context: Context) : AutoCloseable {
    data class Result(
        val type: IntentAgent.Type,
        val argument: String,
        val reply: String,
        val confidence: Double,
    )

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var previousInteractionId = ""
    @Volatile private var conversationKeyHash = 0

    companion object {
        const val MODEL = "gemini-3.6-flash"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"

        @JvmStatic fun getApiKey(context: Context): String = GeminiKeyStore.load(context)
        @JvmStatic fun hasApiKey(context: Context): Boolean = GeminiKeyStore.hasKey(context)

        @JvmStatic fun saveApiKey(context: Context, value: String) {
            try {
                GeminiKeyStore.save(context, value)
            } catch (_: Throwable) {
                if (value.isBlank()) GeminiKeyStore.clear(context)
            }
        }

        @JvmStatic fun clearApiKey(context: Context) = GeminiKeyStore.clear(context)

        @JvmStatic fun testApiKey(context: Context): Boolean {
            val agent = GeminiRemoteAgent(context)
            return try {
                val key = getApiKey(context)
                if (key.isBlank()) false
                else agent.callBlocking(
                    key,
                    listOf("Devuelve una confirmación breve de que estás disponible."),
                    "",
                    "",
                    false,
                ) != null
            } catch (_: Throwable) {
                false
            } finally {
                agent.close()
            }
        }
    }

    fun interpret(
        candidates: List<String>,
        screenContext: String?,
        activeSkill: String?,
        callback: (Result?) -> Unit,
    ) {
        val apiKey = getApiKey(appContext)
        val cleanCandidates = candidates.filter { it.isNotBlank() }.take(5)
        if (apiKey.isBlank() || cleanCandidates.isEmpty() || closed) {
            callback(null)
            return
        }
        executor.execute {
            if (closed) return@execute
            try {
                val currentHash = apiKey.hashCode()
                if (conversationKeyHash != currentHash) {
                    previousInteractionId = ""
                    conversationKeyHash = currentHash
                }
                callback(callBlocking(apiKey, cleanCandidates, screenContext, activeSkill, true))
            } catch (_: Throwable) {
                callback(null)
            }
        }
    }

    @Synchronized
    private fun callBlocking(
        apiKey: String,
        candidates: List<String>,
        screenContext: String?,
        activeSkill: String?,
        continueConversation: Boolean,
    ): Result? {
        val request = buildRequest(candidates, screenContext, activeSkill, continueConversation)
        var response: JSONObject
        try {
            response = post(apiKey, request)
        } catch (first: HttpFailure) {
            // Server-side conversation IDs can expire. Retry once as a fresh turn.
            if (continueConversation && previousInteractionId.isNotBlank() && first.code == 400) {
                previousInteractionId = ""
                request.remove("previous_interaction_id")
                response = post(apiKey, request)
            } else throw first
        }

        val id = response.optString("id", "").trim()
        if (continueConversation && id.isNotEmpty()) previousInteractionId = id
        return parseResponse(response)
    }

    private fun buildRequest(
        candidates: List<String>,
        screenContext: String?,
        activeSkill: String?,
        continueConversation: Boolean,
    ): JSONObject {
        val safeContext = if (isSensitiveContext(screenContext.orEmpty())) {
            "Pantalla sensible detectada: OCR y contenido visual omitidos. " +
                    "Paquete activo: ${AgentAccessibilityService.getActivePackageName()}"
        } else clip(screenContext.orEmpty(), 5200)

        val input = buildString {
            append("Petición principal: ").append(clip(candidates.first(), 1100)).append('\n')
            if (candidates.size > 1) {
                append("Hipótesis alternativas de reconocimiento: ")
                append(candidates.drop(1).joinToString(" | ") { clip(it, 320) }).append('\n')
            }
            append("Habilidad activa: ").append(clip(activeSkill.orEmpty(), 280)).append('\n')
            append("Contexto Android actual: ").append(safeContext).append('\n')
            append("Interpreta la intención de este turno. No afirmes que una acción ya fue ejecutada.")
        }

        val request = JSONObject()
            .put("model", MODEL)
            .put("input", input)
            .put("system_instruction", systemInstruction())
            .put("store", true)
            .put("generation_config", JSONObject()
                .put("thinking_level", "low")
                .put("max_output_tokens", 700))
            .put("response_format", responseFormat())

        if (continueConversation && previousInteractionId.isNotBlank()) {
            request.put("previous_interaction_id", previousInteractionId)
        }
        return request
    }

    private fun post(apiKey: String, body: JSONObject): JSONObject {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("User-Agent", "ScreenObserverPro/2.7")
        }
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream ?: throw HttpFailure(code, ""))).use { it.readText() }
            if (code !in 200..299) throw HttpFailure(code, text)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(root: JSONObject): Result? {
        var text = root.optString("output_text", "").trim()
        if (text.isBlank()) {
            val steps = root.optJSONArray("steps") ?: return null
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "text") {
                        val candidate = part.optString("text", "").trim()
                        if (candidate.isNotBlank()) text = candidate
                    }
                }
            }
        }
        if (text.isBlank()) return null
        val obj = JSONObject(text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val type = try {
            IntentAgent.Type.valueOf(obj.optString("type", "GENERAL").uppercase(Locale.ROOT))
        } catch (_: Throwable) {
            IntentAgent.Type.GENERAL
        }
        return Result(
            type,
            obj.optString("argument", "").trim(),
            sanitizeReply(obj.optString("reply", "")),
            obj.optDouble("confidence", 0.82).coerceIn(0.0, 1.0),
        )
    }

    private fun responseFormat(): JSONObject {
        val types = JSONArray()
        IntentAgent.Type.entries.forEach { types.put(it.name) }
        val properties = JSONObject()
            .put("type", JSONObject().put("type", "string").put("enum", types))
            .put("argument", JSONObject().put("type", "string"))
            .put("reply", JSONObject().put("type", "string"))
            .put("confidence", JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1))
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray().put("type").put("argument").put("reply").put("confidence"))
            .put("additionalProperties", false)
        return JSONObject()
            .put("type", "text")
            .put("mime_type", "application/json")
            .put("schema", schema)
    }

    private fun systemInstruction(): String = """
        Eres el cerebro de comprensión de Screen Observer Pro, un asistente personal para Android 15 y 16.
        Habla español natural, conserva el contexto entre turnos y no repitas lo que el usuario acaba de decir.
        Para conversación, preguntas o explicaciones usa GENERAL y escribe una respuesta natural breve en reply.
        Para una acción del teléfono devuelve exactamente un IntentAgent.Type disponible y usa argument para el dato necesario.
        Interpreta paráfrasis: pantalla principal/inicio/home = HOME; cerrar o salir de una app = CLOSE_APP;
        analizar o aprender a usar el juego/app actual = LEARN_CURRENT_APP; abrir una app = OPEN_APP;
        tocar/pulsar/elegir = CLICK; escribir/poner texto = TYPE_TEXT; volver = BACK; recientes = RECENTS.
        También comprende búsquedas, desplazamientos, volumen, ajustes, URLs, aprendizaje y blackjack.
        No afirmes que ya ejecutaste una acción: solo clasifica la intención. La app local decide y ejecuta después.
        Si una orden es ambigua o peligrosa, usa GENERAL y pide una aclaración corta.
        No solicites ni manejes contraseñas, PIN, OTP, CVV ni datos de pago.
    """.trimIndent()

    private fun isSensitiveContext(value: String): Boolean {
        val n = value.lowercase(Locale.ROOT)
        val markers = arrayOf(
            "contraseña", "contrasena", "password", "pin", "otp", "código de verificación",
            "codigo de verificacion", "código de seguridad", "codigo de seguridad", "cvv", "cvc",
            "número de tarjeta", "numero de tarjeta", "tarjeta de crédito", "tarjeta de credito",
            "transferencia bancaria", "cuenta bancaria", "banca móvil", "banca movil", "pagar ahora",
            "confirmar pago", "saldo disponible", "clave de acceso"
        )
        return markers.any { n.contains(it) }
    }

    private fun sanitizeReply(value: String): String {
        var out = value.trim()
            .replace(Regex("(?i)^(entendi|entendí|te oi|te oí|dijiste)[: ,.-]+"), "")
            .replace(Regex("\\s+"), " ")
        if (out.length > 900) out = out.substring(0, 900).trim() + "…"
        return out
    }

    private fun clip(value: String, max: Int): String {
        val clean = value.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= max) clean else clean.substring(0, max) + "…"
    }

    private class HttpFailure(val code: Int, body: String) : Exception(
        "Gemini HTTP $code: ${body.take(240)}"
    )

    override fun close() {
        closed = true
        previousInteractionId = ""
        executor.shutdownNow()
    }
}
