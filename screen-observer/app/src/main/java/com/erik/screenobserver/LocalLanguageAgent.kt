package com.erik.screenobserver

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Real on-device language model layer. It downloads an Apache-2.0 Qwen3 0.6B
 * LiteRT-LM model once, keeps a multi-turn conversation, and maps natural Spanish
 * to either a supported Android action or a natural reply.
 */
class LocalLanguageAgent(
    context: Context,
    private val statusListener: StatusListener? = null,
) : AutoCloseable {

    interface StatusListener {
        fun onStatus(status: String)
    }

    interface Callback {
        fun onResult(result: Result)
    }

    data class Result(
        val type: IntentAgent.Type,
        val argument: String,
        val reply: String,
        val confidence: Double,
        val usedModel: Boolean,
    )

    companion object {
        private const val MODEL_FILE = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm?download=true"
        private const val MIN_MODEL_BYTES = 300_000_000L

        private val ACTIONS = IntentAgent.Type.entries.joinToString(",") { it.name }

        private val SYSTEM_PROMPT = """
            Eres el cerebro conversacional de un asistente Android privado que funciona en el teléfono del usuario.
            Entiende español natural, conversación de varios turnos, referencias como "eso", "ahora", "el de arriba" y sinónimos.
            No repitas ni parafrasees innecesariamente lo que acaba de decir el usuario. Responde de manera natural y breve.
            Cuando el usuario quiera una acción en el teléfono, clasifícala usando uno de estos tipos exactos: $ACTIONS.
            Para conversación o preguntas generales usa GENERAL y responde en reply.
            Para acciones coloca el objeto/control/app/texto en argument y deja reply vacío salvo que una aclaración sea necesaria.
            No inventes que una acción ya ocurrió: solo clasifica la intención. La app ejecutará la acción después.
            Si una orden destructiva aparece (pagar, transferir, borrar, desinstalar, restablecer, formatear), clasifícala normalmente; la app pedirá confirmación por separado.
            Si no estás suficientemente seguro, usa GENERAL y pide una aclaración corta sin citar literalmente la frase del usuario.
            Devuelve SIEMPRE y SOLO un JSON en una línea con este formato:
            {"type":"GENERAL","argument":"","reply":"respuesta natural"}
        """.trimIndent()
    }

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val modelDir = File(appContext.filesDir, "models")
    private val modelFile = File(modelDir, MODEL_FILE)

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var state = "IA local pendiente"

    fun start() {
        executor.execute {
            if (closed || ready) return@execute
            try {
                ensureModel()
                if (closed) return@execute
                updateStatus("Cargando IA local…")
                val cfg = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = appContext.cacheDir.absolutePath,
                )
                val newEngine = Engine(cfg)
                newEngine.initialize()
                if (closed) {
                    newEngine.close()
                    return@execute
                }
                val convoCfg = ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_PROMPT),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.92, temperature = 0.25),
                )
                val newConversation = newEngine.createConversation(convoCfg)
                engine = newEngine
                conversation = newConversation
                ready = true
                updateStatus("IA local lista")
            } catch (t: Throwable) {
                ready = false
                updateStatus("IA local no disponible; usando respaldo")
            }
        }
    }

    fun isReady(): Boolean = ready && conversation != null

    fun getState(): String = state

    fun interpret(
        candidates: List<String>?,
        confidences: FloatArray?,
        screenText: String?,
        activeSkill: String?,
        callback: Callback,
    ) {
        val safeCandidates = candidates?.filter { it.isNotBlank() }?.take(5).orEmpty()
        val fallback = IntentAgent.interpret(safeCandidates, confidences, activeSkill ?: "", screenText ?: "")
        if (!isReady() || safeCandidates.isEmpty()) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }

        executor.execute {
            if (closed || !isReady()) {
                callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
                return@execute
            }
            try {
                val prompt = buildPrompt(safeCandidates, confidences, screenText, activeSkill)
                // LiteRT-LM 0.14 exposes Message as a printable value; its Kotlin API docs
                // demonstrate consuming the returned Message through toString()/print.
                val raw = conversation!!.sendMessage(prompt).toString()
                val parsed = parseModelResult(raw, fallback)
                callback.onResult(parsed)
            } catch (t: Throwable) {
                callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            }
        }
    }

    private fun buildPrompt(
        candidates: List<String>,
        confidences: FloatArray?,
        screenText: String?,
        activeSkill: String?,
    ): String {
        val hypotheses = candidates.mapIndexed { index, s ->
            val score = confidences?.getOrNull(index)
            if (score != null && score >= 0f) "${index + 1}) ${clip(s, 180)} [conf=${String.format(Locale.US, "%.2f", score)}]"
            else "${index + 1}) ${clip(s, 180)}"
        }.joinToString(" | ")
        return buildString {
            append("Voz: ").append(hypotheses)
            append("\nHabilidad activa: ").append(clip(activeSkill ?: "", 80))
            append("\nApp activa: ").append(clip(AgentAccessibilityService.getActivePackageName(), 90))
            append("\nPantalla actual: ").append(clip(screenText ?: "", 520))
            append("\nInterpreta la intención actual usando también el contexto de turnos anteriores. Devuelve solo JSON.")
        }
    }

    private fun parseModelResult(raw: String, fallback: IntentAgent.Result): Result {
        val cleaned = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val obj = JSONObject(cleaned.substring(start, end + 1))
                val typeName = obj.optString("type", "GENERAL").uppercase(Locale.ROOT)
                val type = try { IntentAgent.Type.valueOf(typeName) } catch (_: Exception) { IntentAgent.Type.GENERAL }
                val argument = obj.optString("argument", "").trim()
                val reply = sanitizeReply(obj.optString("reply", ""))
                return Result(type, argument, reply, 0.90, true)
            } catch (_: Exception) {
                // Fall through to a natural chat response or deterministic fallback.
            }
        }
        val natural = sanitizeReply(cleaned)
        if (natural.isNotBlank() && fallback.type == IntentAgent.Type.GENERAL) {
            return Result(IntentAgent.Type.GENERAL, "", natural, 0.72, true)
        }
        return Result(fallback.type, fallback.argument, "", fallback.confidence, false)
    }

    private fun sanitizeReply(value: String): String {
        var out = value.trim()
            .replace(Regex("(?i)^(entendi|entendí|te oi|te oí|dijiste)[: ,.-]+"), "")
            .replace(Regex("\\s+"), " ")
        if (out.length > 520) out = out.substring(0, 520).trim() + "…"
        return out
    }

    private fun ensureModel() {
        if (modelFile.exists() && modelFile.length() >= MIN_MODEL_BYTES) return
        modelDir.mkdirs()
        val part = File(modelDir, "$MODEL_FILE.part")
        if (part.exists()) part.delete()
        updateStatus("Descargando IA local…")

        var url = URL(MODEL_URL)
        var connection: HttpURLConnection? = null
        for (hop in 0..5) {
            val current = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ScreenObserverPro/2.3")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = current.responseCode
            if (code in 300..399) {
                val location = current.getHeaderField("Location")
                    ?: run { current.disconnect(); throw IllegalStateException("Redirect sin destino") }
                current.disconnect()
                url = URL(url, location)
                continue
            }
            connection = current
            break
        }

        val conn = connection ?: throw IllegalStateException("Demasiadas redirecciones")
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw IllegalStateException("HTTP $code")
        }
        val total = conn.contentLengthLong
        var copied = 0L
        var lastPercent = -10
        try {
            conn.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (total > 0) {
                            val pct = ((copied * 100) / total).toInt()
                            if (pct >= lastPercent + 5) {
                                lastPercent = pct
                                updateStatus("Descargando IA local… ${pct.coerceAtMost(100)}%")
                            }
                        }
                        if (closed) throw InterruptedException("closed")
                    }
                    output.fd.sync()
                }
            }
        } finally {
            conn.disconnect()
        }
        if (part.length() < MIN_MODEL_BYTES) {
            part.delete()
            throw IllegalStateException("Modelo incompleto")
        }
        if (modelFile.exists()) modelFile.delete()
        if (!part.renameTo(modelFile)) {
            part.copyTo(modelFile, overwrite = true)
            part.delete()
        }
    }

    private fun updateStatus(value: String) {
        state = value
        statusListener?.onStatus(value)
    }

    private fun clip(value: String, max: Int): String {
        val clean = value.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= max) clean else clean.substring(0, max) + "…"
    }

    override fun close() {
        closed = true
        ready = false
        try { conversation?.close() } catch (_: Throwable) { }
        try { engine?.close() } catch (_: Throwable) { }
        conversation = null
        engine = null
        executor.shutdownNow()
    }
}
