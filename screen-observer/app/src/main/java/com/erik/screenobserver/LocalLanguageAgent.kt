package com.erik.screenobserver

import android.app.ActivityManager
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
import kotlin.math.max

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

    private data class Hypothesis(val text: String, val score: Float?)
    private data class ModelSpec(
        val fileName: String,
        val url: String,
        val minBytes: Long,
        val label: String,
    )

    companion object {
        private val STANDARD_MODEL = ModelSpec(
            "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
            "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm?download=true",
            300_000_000L,
            "Qwen3 0.6B",
        )
        // Apache-2.0 LiteRT-LM build published by litert-community. It is much larger
        // (~2.06 GB), so it is selected only on devices with enough physical RAM and storage.
        private val ENHANCED_MODEL = ModelSpec(
            "Qwen3_1.7B.litertlm",
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3_1.7B.litertlm?download=true",
            1_900_000_000L,
            "Qwen3 1.7B mejorada",
        )
        private const val ENHANCED_MIN_RAM_BYTES = 7_500_000_000L
        private const val ENHANCED_MIN_FREE_BYTES = 3_200_000_000L
        private const val MIN_ACTION_SPEECH_CONFIDENCE = 0.30f

        private val ACTIONS = IntentAgent.Type.entries.joinToString(",") { it.name }

        private val SYSTEM_PROMPT = """
            Eres el cerebro conversacional de un asistente Android privado que funciona en el teléfono del usuario. Recibes voz transcrita o instrucciones escritas.
            Entiende español natural, conversación de varios turnos, referencias como "eso", "ahora", "el de arriba" y sinónimos.
            No repitas ni parafrasees innecesariamente lo que acaba de decir el usuario. Responde de manera natural y breve.
            Cuando el usuario quiera una acción en el teléfono, clasifícala usando uno de estos tipos exactos: $ACTIONS.
            Casos importantes: ir, volver o abrir la pantalla principal del celular = HOME.
            "cierra WhatsApp", "sal de WhatsApp" o "cierra esta app" = CLOSE_APP, con el nombre de la app en argument cuando exista.
            "analiza este juego para aprender a usarlo", "aprende a usar este juego" o equivalentes = LEARN_CURRENT_APP.
            Buscar dentro de la app = SEARCH con la consulta en argument. Abrir Wi-Fi/Bluetooth/sonido/pantalla/batería/ubicación = OPEN_SETTINGS_SECTION.
            Deslizar horizontalmente = SWIPE_LEFT o SWIPE_RIGHT. Cambiar audio = VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE o VOLUME_UNMUTE.
            "reanuda la escucha" = RESUME_LISTENING. Una URL o dominio pedido explícitamente = OPEN_URL.
            Blackjack: pedir consejo = BLACKJACK_ADVICE; jugar una mano de práctica = BLACKJACK_PLAY. Nunca inventes cartas que no estén en la entrada o la pantalla.
            Para conversación o preguntas generales usa GENERAL y responde en reply.
            Para acciones coloca el objeto/control/app/texto en argument y deja reply vacío salvo que una aclaración sea necesaria.
            No inventes que una acción ya ocurrió: solo clasifica la intención. La app ejecutará la acción después.
            Las hipótesis de voz incluyen confianza. Ignora alternativas claramente menos probables que la mejor.
            Si una orden destructiva aparece (pagar, transferir, borrar, desinstalar, restablecer, formatear), clasifícala normalmente; la app pedirá confirmación por separado.
            Si no estás suficientemente seguro, usa GENERAL y pide una aclaración corta sin citar literalmente la frase del usuario.
            Devuelve SIEMPRE y SOLO un JSON en una línea con este formato:
            {"type":"GENERAL","argument":"","reply":"respuesta natural"}
        """.trimIndent()
    }

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val gemini = GeminiRemoteAgent(appContext)
    private val modelDir = File(appContext.filesDir, "models")
    @Volatile private var activeModelLabel = "IA local"

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var state = "IA local pendiente"

    fun start() {
        executor.execute {
            if (closed || ready) return@execute
            if (GeminiRemoteAgent.hasApiKey(appContext)) {
                updateStatus("Gemini 2.5 Flash configurado")
                return@execute
            }
            val preferred = if (supportsEnhancedModel()) ENHANCED_MODEL else STANDARD_MODEL
            val candidates = if (preferred === ENHANCED_MODEL)
                listOf(ENHANCED_MODEL, STANDARD_MODEL)
            else listOf(STANDARD_MODEL)
            var lastFailure: Throwable? = null

            for ((index, spec) in candidates.withIndex()) {
                if (closed) return@execute
                var newEngine: Engine? = null
                try {
                    val file = ensureModel(spec)
                    if (closed) return@execute
                    updateStatus("Cargando ${spec.label}…")
                    val cfg = EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = appContext.cacheDir.absolutePath,
                    )
                    newEngine = Engine(cfg)
                    newEngine.initialize()
                    if (closed) {
                        newEngine.close()
                        return@execute
                    }
                    val convoCfg = ConversationConfig(
                        systemInstruction = Contents.of(SYSTEM_PROMPT),
                        samplerConfig = SamplerConfig(topK = 20, topP = 0.90, temperature = 0.15),
                    )
                    val newConversation = newEngine.createConversation(convoCfg)
                    engine = newEngine
                    conversation = newConversation
                    activeModelLabel = spec.label
                    ready = true
                    updateStatus("IA local lista · ${spec.label}")
                    return@execute
                } catch (t: Throwable) {
                    lastFailure = t
                    try { newEngine?.close() } catch (_: Throwable) { }
                    if (index + 1 < candidates.size) {
                        updateStatus("IA mejorada no disponible; usando modelo ligero…")
                    }
                }
            }
            ready = false
            updateStatus("IA local no disponible; usando respaldo")
            if (lastFailure is InterruptedException && closed) return@execute
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
        val hypotheses = candidates.orEmpty().take(5).mapIndexedNotNull { index, text ->
            if (text.isBlank()) return@mapIndexedNotNull null
            val rawScore = confidences?.getOrNull(index)
            val score = rawScore?.takeIf { it in 0f..1f }
            Hypothesis(text, score)
        }
        if (hypotheses.isEmpty()) {
            callback.onResult(Result(IntentAgent.Type.GENERAL, "", "", 0.0, false))
            return
        }

        val fallbackTexts = hypotheses.map { it.text }
        val hasScores = hypotheses.any { it.score != null }
        val fallbackScores = if (hasScores) FloatArray(hypotheses.size) { hypotheses[it].score ?: -1f } else null
        val fallback = IntentAgent.interpret(fallbackTexts, fallbackScores, activeSkill ?: "", screenText ?: "")

        // Explicit, high-confidence commands remain deterministic and retain the
        // speech-reliability safety gate. Gemini handles paraphrases/conversation.
        if (fallback.type != IntentAgent.Type.GENERAL
            && fallback.confidence >= 0.80
            && (!isActionable(fallback.type) || hasReliableSpeech(hypotheses))
        ) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }

        val modelHypotheses = filterHypothesesForModel(hypotheses)
        if (GeminiRemoteAgent.hasApiKey(appContext)) {
            updateStatus("Pensando con Gemini…")
            gemini.interpret(
                modelHypotheses.map { it.text },
                screenText,
                activeSkill,
            ) geminiCallback@ { remote ->
                if (remote == null) {
                    updateStatus(if (isReady()) "Gemini no disponible · usando IA local" else "Gemini no disponible · usando respaldo")
                    if (isReady()) {
                        interpretLocalModel(modelHypotheses, screenText, activeSkill, fallback, callback)
                    } else {
                        callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
                    }
                    return@geminiCallback
                }
                var parsed = Result(remote.type, remote.argument, remote.reply, remote.confidence, true)
                if (isActionable(parsed.type) && !hasReliableSpeech(modelHypotheses)) {
                    parsed = Result(
                        IntentAgent.Type.GENERAL,
                        "",
                        "No estoy lo bastante seguro de la orden. Repítela después de la señal.",
                        0.40,
                        true,
                    )
                }
                updateStatus("Gemini 2.5 Flash listo")
                callback.onResult(parsed)
            }
            return
        }
        if (!isReady()) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }

        interpretLocalModel(modelHypotheses, screenText, activeSkill, fallback, callback)
    }

    private fun interpretLocalModel(
        modelHypotheses: List<Hypothesis>,
        screenText: String?,
        activeSkill: String?,
        fallback: IntentAgent.Result,
        callback: Callback,
    ) {
        executor.execute {
            if (closed || !isReady()) {
                callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
                return@execute
            }
            try {
                val prompt = buildPrompt(modelHypotheses, screenText, activeSkill)
                val raw = conversation!!.sendMessage(prompt).toString()
                var parsed = parseModelResult(raw, fallback)
                if (isActionable(parsed.type) && !hasReliableSpeech(modelHypotheses)) {
                    parsed = Result(
                        IntentAgent.Type.GENERAL,
                        "",
                        "No estoy lo bastante seguro de la orden. Repítela después de la señal.",
                        0.40,
                        true,
                    )
                }
                callback.onResult(parsed)
            } catch (t: Throwable) {
                callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            }
        }
    }

    /** Removes low-probability alternates so a distant hypothesis cannot become a device action. */
    private fun filterHypothesesForModel(all: List<Hypothesis>): List<Hypothesis> {
        val measured = all.mapNotNull { it.score }
        if (measured.isEmpty()) return all
        val best = measured.maxOrNull() ?: return all
        val floor = max(MIN_ACTION_SPEECH_CONFIDENCE, best - 0.20f)
        val filtered = all.filter { it.score == null || it.score >= floor }
        return if (filtered.isNotEmpty()) filtered else listOf(all.first())
    }

    private fun hasReliableSpeech(hypotheses: List<Hypothesis>): Boolean {
        val measured = hypotheses.mapNotNull { it.score }
        return measured.isEmpty() || (measured.maxOrNull() ?: 0f) >= MIN_ACTION_SPEECH_CONFIDENCE
    }

    private fun isActionable(type: IntentAgent.Type): Boolean = when (type) {
        IntentAgent.Type.LEARN_SKILL,
        IntentAgent.Type.USE_SKILL,
        IntentAgent.Type.HIDE_OVERLAY,
        IntentAgent.Type.SHOW_OVERLAY,
        IntentAgent.Type.STOP_ASSISTANT,
        IntentAgent.Type.PAUSE_LISTENING,
        IntentAgent.Type.CONFIRM_CLICK,
        IntentAgent.Type.CLICK,
        IntentAgent.Type.LONG_CLICK,
        IntentAgent.Type.TYPE_TEXT,
        IntentAgent.Type.SCROLL_DOWN,
        IntentAgent.Type.SCROLL_UP,
        IntentAgent.Type.BACK,
        IntentAgent.Type.HOME,
        IntentAgent.Type.RECENTS,
        IntentAgent.Type.NOTIFICATIONS,
        IntentAgent.Type.QUICK_SETTINGS,
        IntentAgent.Type.POWER_MENU,
        IntentAgent.Type.LOCK_SCREEN,
        IntentAgent.Type.SCREENSHOT,
        IntentAgent.Type.OPEN_SETTINGS,
        IntentAgent.Type.OPEN_SETTINGS_SECTION,
        IntentAgent.Type.OPEN_URL,
        IntentAgent.Type.OPEN_APP,
        IntentAgent.Type.CLOSE_APP,
        IntentAgent.Type.LEARN_CURRENT_APP,
        IntentAgent.Type.RESUME_LISTENING,
        IntentAgent.Type.CLICK_ORDINAL,
        IntentAgent.Type.SEARCH,
        IntentAgent.Type.SWIPE_LEFT,
        IntentAgent.Type.SWIPE_RIGHT,
        IntentAgent.Type.VOLUME_UP,
        IntentAgent.Type.VOLUME_DOWN,
        IntentAgent.Type.VOLUME_MUTE,
        IntentAgent.Type.VOLUME_UNMUTE,
        IntentAgent.Type.BLACKJACK_PLAY -> true
        else -> false
    }

    private fun buildPrompt(
        hypotheses: List<Hypothesis>,
        screenText: String?,
        activeSkill: String?,
    ): String {
        val hypothesisText = hypotheses.mapIndexed { index, hypothesis ->
            val score = hypothesis.score
            if (score != null) "${index + 1}) ${clip(hypothesis.text, 180)} [conf=${String.format(Locale.US, "%.2f", score)}]"
            else "${index + 1}) ${clip(hypothesis.text, 180)}"
        }.joinToString(" | ")
        return buildString {
            append("Entrada del usuario: ").append(hypothesisText)
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

    private fun supportsEnhancedModel(): Boolean {
        return try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            val ramOk = info.totalMem >= ENHANCED_MIN_RAM_BYTES
            val storageOk = modelDir.parentFile?.usableSpace?.let { it >= ENHANCED_MIN_FREE_BYTES } ?: false
            ramOk && storageOk
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureModel(spec: ModelSpec): File {
        val modelFile = File(modelDir, spec.fileName)
        if (modelFile.exists() && modelFile.length() >= spec.minBytes) return modelFile
        modelDir.mkdirs()
        val part = File(modelDir, "${spec.fileName}.part")
        if (part.exists()) part.delete()
        updateStatus("Descargando ${spec.label}…")

        var url = URL(spec.url)
        var connection: HttpURLConnection? = null
        for (hop in 0..5) {
            val current = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ScreenObserverPro/2.6")
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
                                updateStatus("Descargando ${spec.label}… ${pct.coerceAtMost(100)}%")
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
        if (part.length() < spec.minBytes) {
            part.delete()
            throw IllegalStateException("Modelo incompleto")
        }
        if (modelFile.exists()) modelFile.delete()
        if (!part.renameTo(modelFile)) {
            part.copyTo(modelFile, overwrite = true)
            part.delete()
        }
        return modelFile
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
        try { gemini.close() } catch (_: Throwable) { }
        try { conversation?.close() } catch (_: Throwable) { }
        try { engine?.close() } catch (_: Throwable) { }
        conversation = null
        engine = null
        executor.shutdownNow()
    }
}
