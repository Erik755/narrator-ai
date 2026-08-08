from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v25_model_upgrade: expected one {label}, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.content.Context\n",
    "import android.app.ActivityManager\nimport android.content.Context\n",
    "ActivityManager import",
)

replace_once(
    '''    private data class Hypothesis(val text: String, val score: Float?)

    companion object {
        private const val MODEL_FILE = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm?download=true"
        private const val MIN_MODEL_BYTES = 300_000_000L
        private const val MIN_ACTION_SPEECH_CONFIDENCE = 0.30f''',
    '''    private data class Hypothesis(val text: String, val score: Float?)
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
        private const val MIN_ACTION_SPEECH_CONFIDENCE = 0.30f''',
    "model tier constants",
)

replace_once(
    '''    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val modelDir = File(appContext.filesDir, "models")
    private val modelFile = File(modelDir, MODEL_FILE)
''',
    '''    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val modelDir = File(appContext.filesDir, "models")
    @Volatile private var activeModelLabel = "IA local"
''',
    "model fields",
)

start_marker = "    fun start() {"
end_marker = "    fun isReady(): Boolean"
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0 or end <= start:
    raise SystemExit("patch_v25_model_upgrade: could not locate start() block")
new_start = '''    fun start() {
        executor.execute {
            if (closed || ready) return@execute
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

'''
text = text[:start] + new_start + text[end:]

ensure_marker = "    private fun ensureModel() {"
update_marker = "    private fun updateStatus(value: String) {"
start = text.find(ensure_marker)
end = text.find(update_marker, start)
if start < 0 or end < 0 or end <= start:
    raise SystemExit("patch_v25_model_upgrade: could not locate ensureModel() block")
new_ensure = '''    private fun supportsEnhancedModel(): Boolean {
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
                setRequestProperty("User-Agent", "ScreenObserverPro/2.5")
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

'''
text = text[:start] + new_ensure + text[end:]

if "MODEL_FILE" in text or "MODEL_URL" in text or "MIN_MODEL_BYTES" in text:
    raise SystemExit("patch_v25_model_upgrade: stale single-model constants remain")

text = text.replace("ScreenObserverPro/2.4", "ScreenObserverPro/2.5")
path.write_text(text, encoding="utf-8")
print("patch_v25_model_upgrade: automatic Qwen3 1.7B tier with 0.6B fallback applied")
