package com.erik.screenobserver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Optional cloud language brain for Screen Observer Pro.
 * The API key is entered by the user at runtime and is never embedded in the APK.
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
    private val history = ArrayDeque<Pair<String, String>>()
    @Volatile private var closed = false

    companion object {
        private const val PREFS = "screen_observer_ai"
        private const val KEY_API = "gemini_api_key"
        private const val MODEL = "gemini-2.5-flash"
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

        @JvmStatic fun getApiKey(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API, "")?.trim().orEmpty()

        @JvmStatic fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

        @JvmStatic fun saveApiKey(context: Context, value: String) {
            val clean = value.trim()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (clean.isEmpty()) remove(KEY_API) else putString(KEY_API, clean)
            }.apply()
        }
    }

    fun interpret(
        candidates: List<String>,
        screenContext: String?,
        activeSkill: String?,
        callback: (Result?) -> Unit,
    ) {
        val apiKey = getApiKey(appContext)
        if (apiKey.isBlank() || candidates.isEmpty() || closed) {
            callback(null)
            return
        }
        val cleanCandidates = candidates.filter { it.isNotBlank() }.take(5)
        if (cleanCandidates.isEmpty()) {
            callback(null)
            return
        }
        executor.execute {
            if (closed) return@execute
            try {
                val userText = cleanCandidates.first()
                val request = buildRequest(cleanCandidates, screenContext, activeSkill)
                val raw = post(apiKey, request)
                val parsed = parseResponse(raw)
                if (parsed != null) remember(userText, parsed)
                callback(parsed)
            } catch (_: Throwable) {
                callback(null)
            }
        }
    }

    private fun buildRequest(
        candidates: List<String>,
        screenContext: String?,
        activeSkill: String?,
    ): JSONObject {
        val actionNames = IntentAgent.Type.entries.joinToString(",") { it.name }
        val system = """
            Eres el cerebro de un agente Android personal. Entiende español natural, referencias de turnos anteriores y órdenes indirectas.
            Nunca repitas al usuario lo que acaba de decir salvo que pida una transcripción.
            Si pide una acción del teléfono, devuelve un tipo de acción exacto de esta lista: $actionNames.
            Para conversación o preguntas usa GENERAL y escribe una respuesta natural y breve en reply.
            Para acciones, usa argument para el nombre de app, botón, texto, URL o dato necesario; reply normalmente vacío.
            No afirmes que ya ejecutaste acciones: solo decide la intención. La app ejecutará después.
            HOME significa ir a la pantalla principal. CLOSE_APP significa salir/cerrar visualmente una app, no forzar su proceso.
            LEARN_CURRENT_APP significa observar la app/juego actual para aprender su interfaz y comportamiento.
            BLACKJACK_ADVICE aconseja una jugada; BLACKJACK_PLAY solo debe clasificarse cuando el usuario pide jugar/actuar.
            Si la petición es ambigua, usa GENERAL y pide una aclaración corta.
            Devuelve únicamente JSON válido con type, argument y reply.
        """.trimIndent()

        val userPrompt = buildString {
            append("Entrada principal: ").append(clip(candidates.first(), 700)).append('\n')
            if (candidates.size > 1) {
                append("Otras hipótesis de voz: ")
                append(candidates.drop(1).joinToString(" | ") { clip(it, 260) }).append('\n')
            }
            append("Habilidad activa: ").append(clip(activeSkill.orEmpty(), 160)).append('\n')
            append("Contexto actual Android: ").append(clip(screenContext.orEmpty(), 4200)).append('\n')
            if (history.isNotEmpty()) {
                append("Contexto conversacional reciente:\n")
                history.forEach { (u, a) ->
                    append("Usuario: ").append(clip(u, 450)).append('\n')
                    append("Asistente/acción: ").append(clip(a, 450)).append('\n')
                }
            }
            append("Interpreta la petición actual.")
        }

        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("type", JSONObject().put("type", "string")
                    .put("enum", JSONArray(IntentAgent.Type.entries.map { it.name })))
                .put("argument", JSONObject().put("type", "string"))
                .put("reply", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("type", "argument", "reply")))

        return JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.10)
                .put("topP", 0.90)
                .put("maxOutputTokens", 700)
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema))
    }

    private fun post(apiKey: String, body: JSONObject): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("User-Agent", "ScreenObserverPro/2.6")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream ?: throw IllegalStateException("HTTP $code"))).use { reader ->
                reader.readText()
            }
            if (code !in 200..299) throw IllegalStateException("Gemini HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(raw: String): Result? {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts") ?: return null
        val text = parts.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()
        if (text.isBlank()) return null
        val cleaned = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = JSONObject(cleaned)
        val type = try {
            IntentAgent.Type.valueOf(obj.optString("type", "GENERAL").uppercase(Locale.ROOT))
        } catch (_: Throwable) {
            IntentAgent.Type.GENERAL
        }
        val argument = obj.optString("argument", "").trim()
        val reply = sanitizeReply(obj.optString("reply", ""))
        return Result(type, argument, reply, 0.94)
    }

    private fun sanitizeReply(value: String): String {
        var out = value.trim()
            .replace(Regex("(?i)^(entendi|entendí|te oi|te oí|dijiste)[: ,.-]+"), "")
            .replace(Regex("\\s+"), " ")
        if (out.length > 700) out = out.substring(0, 700).trim() + "…"
        return out
    }

    @Synchronized private fun remember(user: String, result: Result) {
        val assistant = if (result.type == IntentAgent.Type.GENERAL) result.reply
        else "${result.type.name}:${result.argument}"
        history.addLast(user to assistant)
        while (history.size > 6) history.removeFirst()
    }

    private fun clip(value: String, max: Int): String {
        val clean = value.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= max) clean else clean.substring(0, max) + "…"
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
        history.clear()
    }
}
