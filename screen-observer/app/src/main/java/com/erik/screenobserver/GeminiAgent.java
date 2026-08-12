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
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Gemini 2.5 Flash remote reasoning layer. The local agent remains the fallback. */
public final class GeminiAgent {
    public interface Callback {
        void onResult(Result result);
        void onError(String message);
    }

    public static final class Result {
        public final IntentAgent.Type type;
        public final String argument;
        public final String reply;
        public final double confidence;

        Result(IntentAgent.Type type, String argument, String reply, double confidence) {
            this.type = type == null ? IntentAgent.Type.GENERAL : type;
            this.argument = argument == null ? "" : argument.trim();
            this.reply = reply == null ? "" : reply.trim();
            this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        }
    }

    private static final String MODEL = "gemini-2.5-flash";
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Deque<String> history = new ArrayDeque<>();

    public GeminiAgent(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isConfigured() {
        return GeminiKeyStore.has(context);
    }

    public void clearHistory() {
        synchronized (history) { history.clear(); }
    }

    public void interpret(String userText, String screenContext, boolean allowActions, Callback callback) {
        final String input = userText == null ? "" : userText.trim();
        if (input.isEmpty()) {
            callback.onError("Instrucción vacía");
            return;
        }
        executor.execute(() -> {
            String apiKey = GeminiKeyStore.get(context);
            if (apiKey.isEmpty()) {
                callback.onError("Gemini no está configurado");
                return;
            }
            HttpURLConnection conn = null;
            try {
                JSONObject request = buildRequest(input, screenContext, allowActions);
                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("x-goog-api-key", apiKey);
                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }

                int code = conn.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String response = readAll(stream);
                if (code < 200 || code >= 300) {
                    callback.onError("Gemini HTTP " + code + shortError(response));
                    return;
                }

                Result result = parseResponse(response, allowActions);
                remember("Usuario: " + input);
                if (result.type == IntentAgent.Type.GENERAL && !result.reply.isEmpty()) {
                    remember("Asistente: " + result.reply);
                } else {
                    remember("Acción: " + result.type.name() + (result.argument.isEmpty() ? "" : " " + result.argument));
                }
                callback.onResult(result);
            } catch (Exception e) {
                callback.onError("Gemini no disponible: " + e.getClass().getSimpleName());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private JSONObject buildRequest(String input, String screenContext, boolean allowActions) throws Exception {
        String allowed = "HEARING_CHECK,HIDE_OVERLAY,SHOW_OVERLAY,STOP_ASSISTANT,PAUSE_LISTENING,"
                + "RESUME_LISTENING,LEARN_SKILL,LIST_SKILLS,USE_SKILL,SKILL_INFO,DESCRIBE_CONTROLS,"
                + "CONFIRM_CLICK,CLICK,CLICK_ORDINAL,LONG_CLICK,TYPE_TEXT,SCROLL_DOWN,SCROLL_UP,"
                + "SWIPE_LEFT,SWIPE_RIGHT,BACK,HOME,RECENTS,NOTIFICATIONS,QUICK_SETTINGS,POWER_MENU,"
                + "LOCK_SCREEN,SCREENSHOT,OPEN_SETTINGS,OPEN_SETTINGS_SECTION,OPEN_APP,CLOSE_APP,"
                + "OPEN_URL,SEARCH,VOLUME_UP,VOLUME_DOWN,VOLUME_MUTE,VOLUME_UNMUTE,LEARN_CURRENT_APP,"
                + "DESCRIBE_SCREEN,READ_SCREEN,ADVICE,BLACKJACK_ADVICE,BLACKJACK_PLAY,GENERAL";

        StringBuilder system = new StringBuilder();
        system.append("Eres el cerebro de Screen Observer Pro, un agente Android personal. ")
                .append("Entiende español natural, conversación contextual y órdenes indirectas. ")
                .append("No repitas al usuario lo que acaba de decir. Responde natural y brevemente. ")
                .append("Si pide una acción del teléfono, devuelve el tipo exacto y el argumento necesario. ")
                .append("Tipos permitidos: ").append(allowed).append(". ")
                .append("Ejemplos: 'vete a la pantalla principal' => HOME; 'cierra WhatsApp' => CLOSE_APP WhatsApp; ")
                .append("'abre YouTube y busca música' requiere interpretar la intención principal actual; ")
                .append("'analiza este juego y aprende a usarlo' => LEARN_CURRENT_APP; ")
                .append("'pulsa continuar' => CLICK continuar; 'escribe hola' => TYPE_TEXT hola. ")
                .append("Para conversación, preguntas, explicaciones o cuando no sea una acción clara usa GENERAL y escribe reply. ")
                .append("No inventes controles que no estén en el contexto de pantalla. ")
                .append("Nunca propongas introducir contraseñas, PIN, OTP o datos de pago. ")
                .append("Las acciones destructivas requieren confirmación del runtime. ");
        if (!allowActions) {
            system.append("La transcripción de voz no tiene suficiente confianza: DEBES usar GENERAL y no ordenar ninguna acción. ");
        }
        String h = historyText();
        if (!h.isEmpty()) system.append("Contexto reciente de conversación:\n").append(h).append("\n");

        JSONObject sysPart = new JSONObject().put("text", system.toString());
        JSONObject systemInstruction = new JSONObject().put("parts", new JSONArray().put(sysPart));

        String prompt = "Contexto actual del teléfono:\n" + safe(screenContext, 6500)
                + "\n\nEntrada del usuario:\n" + input
                + "\n\nDevuelve SOLO JSON con: type, argument, reply, confidence."
                + " confidence debe estar entre 0 y 1.";
        JSONObject userPart = new JSONObject().put("text", prompt);
        JSONObject content = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(userPart));

        JSONObject generation = new JSONObject()
                .put("temperature", 0.15)
                .put("maxOutputTokens", 450)
                .put("responseMimeType", "application/json");

        return new JSONObject()
                .put("systemInstruction", systemInstruction)
                .put("contents", new JSONArray().put(content))
                .put("generationConfig", generation);
    }

    private Result parseResponse(String raw, boolean allowActions) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) throw new IllegalStateException("sin candidato");
        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) throw new IllegalStateException("sin contenido");
        String text = parts.getJSONObject(0).optString("text", "").trim();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (firstBreak >= 0 && end > firstBreak) text = text.substring(firstBreak + 1, end).trim();
        }
        JSONObject obj = new JSONObject(text);
        String typeName = obj.optString("type", "GENERAL").trim().toUpperCase(Locale.ROOT);
        IntentAgent.Type type;
        try { type = IntentAgent.Type.valueOf(typeName); }
        catch (Exception ignored) { type = IntentAgent.Type.GENERAL; }
        if (!allowActions && type != IntentAgent.Type.GENERAL) type = IntentAgent.Type.GENERAL;
        String argument = obj.optString("argument", "");
        String reply = obj.optString("reply", "");
        double confidence = obj.optDouble("confidence", type == IntentAgent.Type.GENERAL ? 0.75 : 0.85);
        if (type == IntentAgent.Type.GENERAL && reply.isEmpty()) {
            reply = "No estoy seguro de qué acción quieres. Dímelo de otra forma.";
        }
        return new Result(type, argument, reply, confidence);
    }

    private void remember(String entry) {
        synchronized (history) {
            history.addLast(safe(entry, 900));
            while (history.size() > 8) history.removeFirst();
        }
    }

    private String historyText() {
        StringBuilder out = new StringBuilder();
        synchronized (history) {
            for (String item : history) out.append(item).append('\n');
        }
        return out.toString();
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static String shortError(String value) {
        if (value == null || value.isEmpty()) return "";
        String flat = value.replace('\n', ' ').replace('\r', ' ');
        return ": " + safe(flat, 180);
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String s = value.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    public void close() {
        executor.shutdownNow();
    }
}
