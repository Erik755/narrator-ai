package com.erik.screenobserver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gemini-backed understanding layer. The user's key is loaded at runtime from Android Keystore;
 * it is never embedded in the APK or repository. Gemini classifies natural language into the
 * same IntentAgent actions already protected by the local Android executor.
 */
public final class GeminiRemoteAgent implements AutoCloseable {
    // 3.5 Flash currently has an official Free Tier and is substantially stronger than the
    // on-device fallback. Flash-Lite is a lower-cost/lower-capacity fallback for quota spikes.
    private static final String[] MODELS = {"gemini-3.5-flash", "gemini-3.5-flash-lite"};
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final double MIN_ACTION_SPEECH_CONFIDENCE = 0.30;
    private static final int MAX_MEMORY_TURNS = 10;

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Deque<String> memory = new ArrayDeque<>();
    private volatile boolean closed = false;
    private volatile String state = "Gemini sin configurar";

    public GeminiRemoteAgent(Context context) {
        appContext = context.getApplicationContext();
        if (isConfigured()) state = "Gemini listo";
    }

    public boolean isConfigured() {
        return GeminiSecretStore.hasKey(appContext);
    }

    public String getState() { return state; }

    public void interpret(List<String> candidates, float[] confidences, String screenContext,
                          String activeSkill, LocalLanguageAgent.Callback callback) {
        final ArrayList<String> clean = new ArrayList<>();
        if (candidates != null) {
            for (String candidate : candidates) {
                if (candidate != null && !candidate.trim().isEmpty()) clean.add(candidate.trim());
                if (clean.size() >= 5) break;
            }
        }
        if (clean.isEmpty()) {
            callback.onResult(new LocalLanguageAgent.Result(
                    IntentAgent.Type.GENERAL, "", "", 0.0, false));
            return;
        }
        final String key = GeminiSecretStore.load(appContext);
        if (key.isEmpty() || closed) {
            callback.onResult(localFallback(clean, confidences, activeSkill, screenContext));
            return;
        }

        executor.execute(() -> {
            if (closed) return;
            LocalLanguageAgent.Result fallback = localFallback(clean, confidences, activeSkill, screenContext);
            try {
                state = "Gemini pensando";
                LocalLanguageAgent.Result result = null;
                Exception last = null;
                for (String model : MODELS) {
                    try {
                        result = request(model, key, clean, confidences, screenContext, activeSkill);
                        if (result != null) break;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (result == null) throw last == null ? new IllegalStateException("Sin respuesta") : last;

                if (isActionable(result.getType()) && !hasReliableSpeech(confidences, clean.size())) {
                    result = new LocalLanguageAgent.Result(
                            IntentAgent.Type.GENERAL,
                            "",
                            "No estoy suficientemente seguro de la orden. Repítela después de la señal.",
                            0.40,
                            true);
                }
                remember(clean.get(0), result);
                state = "Gemini listo";
                callback.onResult(result);
            } catch (Exception e) {
                state = "Gemini no disponible · respaldo local";
                callback.onResult(fallback);
            }
        });
    }

    private LocalLanguageAgent.Result request(String model, String key, List<String> candidates,
                                               float[] confidences, String screenContext,
                                               String activeSkill) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(String.format(Locale.US, ENDPOINT, model)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(40_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("x-goog-api-key", key);
        connection.setRequestProperty("User-Agent", "ScreenObserverPro/2.6");

        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", systemPrompt()))));
        body.put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text",
                        buildUserPrompt(candidates, confidences, screenContext, activeSkill))))));

        JSONObject generation = new JSONObject();
        generation.put("responseMimeType", "application/json");
        generation.put("maxOutputTokens", 900);
        generation.put("responseJsonSchema", responseSchema());
        body.put("generationConfig", generation);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Gemini HTTP " + code + ": " + clip(response, 180));
        }

        JSONObject root = new JSONObject(response);
        JSONArray candidatesJson = root.optJSONArray("candidates");
        if (candidatesJson == null || candidatesJson.length() == 0) throw new IllegalStateException("Sin candidatos");
        JSONObject content = candidatesJson.getJSONObject(0).optJSONObject("content");
        if (content == null) throw new IllegalStateException("Sin contenido");
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) throw new IllegalStateException("Sin texto");
        String jsonText = parts.getJSONObject(0).optString("text", "").trim();
        if (jsonText.isEmpty()) throw new IllegalStateException("Respuesta vacía");
        return parseDecision(jsonText);
    }

    private JSONObject responseSchema() throws Exception {
        JSONArray actionNames = new JSONArray();
        for (IntentAgent.Type type : IntentAgent.Type.values()) actionNames.put(type.name());
        JSONObject properties = new JSONObject();
        properties.put("type", new JSONObject().put("type", "string").put("enum", actionNames));
        properties.put("argument", new JSONObject().put("type", "string"));
        properties.put("reply", new JSONObject().put("type", "string"));
        properties.put("confidence", new JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1));
        return new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", new JSONArray().put("type").put("argument").put("reply").put("confidence"))
                .put("additionalProperties", false);
    }

    private String systemPrompt() {
        StringBuilder actions = new StringBuilder();
        for (IntentAgent.Type type : IntentAgent.Type.values()) {
            if (actions.length() > 0) actions.append(',');
            actions.append(type.name());
        }
        return "Eres el cerebro principal de Screen Observer Pro, un agente Android privado para Android 15 y 16. "
                + "Entiende español natural, conversación de varios turnos, referencias como eso/ahí/el anterior/esa app y órdenes indirectas. "
                + "No repitas ni parafrasees innecesariamente lo que dijo el usuario. Responde breve, natural y útil. "
                + "Si el usuario quiere actuar en el teléfono, clasifica UNA siguiente acción segura usando exactamente uno de estos tipos: "
                + actions + ". "
                + "Mapeo importante: pantalla principal/inicio/home = HOME; atrás = BACK; recientes = RECENTS; "
                + "cerrar o salir de WhatsApp u otra app = CLOSE_APP; analizar/estudiar este juego o app para aprenderlo = LEARN_CURRENT_APP; "
                + "abrir una app = OPEN_APP; buscar dentro de la app = SEARCH; escribir texto = TYPE_TEXT; tocar un control = CLICK; "
                + "desplazarse = SCROLL_DOWN/SCROLL_UP/SWIPE_LEFT/SWIPE_RIGHT; ajustes concretos = OPEN_SETTINGS_SECTION; "
                + "notificaciones = NOTIFICATIONS; ajustes rápidos = QUICK_SETTINGS; volumen = VOLUME_UP/VOLUME_DOWN/VOLUME_MUTE/VOLUME_UNMUTE; "
                + "conversación, explicaciones o preguntas que no requieren tocar Android = GENERAL. "
                + "Para GENERAL escribe la respuesta natural en reply. Para acciones deja reply vacío y coloca el objetivo exacto en argument. "
                + "Si el usuario da varios pasos, devuelve solo el PRIMER paso que corresponda al turno actual; el planificador local gestiona cadenas explícitas conocidas. "
                + "No afirmes que una acción ya ocurrió: solo decide la intención; Android la ejecuta después. "
                + "No inventes botones, cartas, resultados ni texto que no aparezcan en el contexto. Si falta información usa GENERAL y pregunta solo lo indispensable. "
                + "No solicites ni introduzcas contraseñas, PIN, OTP ni datos de pago. Las acciones sensibles quedan sujetas a confirmación local.";
    }

    private String buildUserPrompt(List<String> candidates, float[] confidences,
                                   String screenContext, String activeSkill) {
        StringBuilder b = new StringBuilder();
        b.append("Entrada del usuario (hipótesis de voz o texto):\n");
        for (int i = 0; i < candidates.size(); i++) {
            b.append(i + 1).append(") ").append(clip(candidates.get(i), 320));
            if (confidences != null && i < confidences.length && confidences[i] >= 0f && confidences[i] <= 1f) {
                b.append(" [conf=").append(String.format(Locale.US, "%.2f", confidences[i])).append(']');
            }
            b.append('\n');
        }
        synchronized (memory) {
            if (!memory.isEmpty()) {
                b.append("\nContexto reciente de conversación:\n");
                for (String item : memory) b.append("- ").append(clip(item, 420)).append('\n');
            }
        }
        b.append("\nHabilidad activa: ").append(clip(activeSkill, 160));
        b.append("\nContexto actual del teléfono (OCR, app y/o controles accesibles): ").append(clip(screenContext, 7000));
        b.append("\nDecide la intención actual. Para voz, prioriza la hipótesis con mayor confianza. Devuelve solo el objeto JSON solicitado.");
        return b.toString();
    }

    private LocalLanguageAgent.Result parseDecision(String jsonText) throws Exception {
        String clean = jsonText.trim();
        int first = clean.indexOf('{');
        int last = clean.lastIndexOf('}');
        if (first >= 0 && last >= first) clean = clean.substring(first, last + 1);
        JSONObject json = new JSONObject(clean);
        String rawType = json.optString("type", "GENERAL").trim().toUpperCase(Locale.ROOT);
        IntentAgent.Type type;
        try { type = IntentAgent.Type.valueOf(rawType); }
        catch (Exception ignored) { type = IntentAgent.Type.GENERAL; }
        String argument = json.optString("argument", "").trim();
        String reply = json.optString("reply", "").trim();
        double confidence = Math.max(0.0, Math.min(1.0, json.optDouble("confidence", 0.75)));
        if (type == IntentAgent.Type.GENERAL && reply.isEmpty()) {
            reply = "¿Qué quieres que haga exactamente?";
        }
        return new LocalLanguageAgent.Result(type, argument, reply, confidence, true);
    }

    private LocalLanguageAgent.Result localFallback(List<String> candidates, float[] confidences,
                                                     String activeSkill, String screenContext) {
        IntentAgent.Result r = IntentAgent.interpret(candidates, confidences, activeSkill == null ? "" : activeSkill,
                screenContext == null ? "" : screenContext);
        return new LocalLanguageAgent.Result(r.type, r.argument, "", r.confidence, false);
    }

    private boolean hasReliableSpeech(float[] confidences, int count) {
        if (confidences == null || confidences.length == 0) return true;
        double best = -1;
        for (int i = 0; i < Math.min(count, confidences.length); i++) {
            float c = confidences[i];
            if (c >= 0f && c <= 1f) best = Math.max(best, c);
        }
        return best < 0 || best >= MIN_ACTION_SPEECH_CONFIDENCE;
    }

    private boolean isActionable(IntentAgent.Type type) {
        String n = type.name();
        return !(n.equals("GENERAL") || n.equals("HEARING_CHECK") || n.equals("DESCRIBE_SCREEN")
                || n.equals("READ_SCREEN") || n.equals("ADVICE") || n.equals("LIST_SKILLS")
                || n.equals("SKILL_INFO") || n.equals("DESCRIBE_CONTROLS") || n.equals("BLACKJACK_ADVICE"));
    }

    private void remember(String user, LocalLanguageAgent.Result result) {
        String assistant = result.getType() == IntentAgent.Type.GENERAL
                ? result.getReply()
                : "acción " + result.getType().name() + (result.getArgument().isEmpty() ? "" : " → " + result.getArgument());
        synchronized (memory) {
            memory.addLast("Usuario: " + clip(user, 260) + " | Asistente: " + clip(assistant, 320));
            while (memory.size() > MAX_MEMORY_TURNS) memory.removeFirst();
        }
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString();
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    @Override public void close() {
        closed = true;
        executor.shutdownNow();
        synchronized (memory) { memory.clear(); }
    }
}
