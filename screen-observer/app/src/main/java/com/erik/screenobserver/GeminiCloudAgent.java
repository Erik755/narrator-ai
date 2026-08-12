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
import java.util.Locale;

/**
 * Optional cloud language layer. The API key is supplied by the device owner at runtime
 * and is never committed to source control. Device actions remain enforced locally.
 */
public final class GeminiCloudAgent {
    public static final String MODEL = "gemini-3.6-flash";
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions";

    public static final class Decision {
        public final IntentAgent.Type type;
        public final String argument;
        public final String reply;
        public final double confidence;

        Decision(IntentAgent.Type type, String argument, String reply, double confidence) {
            this.type = type;
            this.argument = argument == null ? "" : argument.trim();
            this.reply = reply == null ? "" : reply.trim();
            this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        }
    }

    private final Context context;
    private String previousInteractionId = "";

    public GeminiCloudAgent(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isConfigured() {
        return GeminiKeyStore.hasKey(context);
    }

    public synchronized void resetConversation() {
        previousInteractionId = "";
    }

    public synchronized Decision interpret(String userText, String screenText, String activeSkill,
                                           String controlsText) throws Exception {
        String key = GeminiKeyStore.load(context);
        if (key.isEmpty()) throw new IllegalStateException("Gemini no configurado");
        return call(key, userText, screenText, activeSkill, controlsText, true);
    }

    public static boolean testKey(Context context) {
        String key = GeminiKeyStore.load(context);
        if (key.isEmpty()) return false;
        try {
            GeminiCloudAgent agent = new GeminiCloudAgent(context);
            Decision d = agent.call(key, "Responde únicamente con una confirmación breve.", "", "", "", false);
            return d != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Decision call(String key, String userText, String screenText, String activeSkill,
                          String controlsText, boolean allowConversation) throws Exception {
        JSONObject request = new JSONObject();
        request.put("model", MODEL);
        request.put("input", buildInput(userText, screenText, activeSkill, controlsText));
        request.put("system_instruction", systemInstruction());
        request.put("store", true);
        if (allowConversation && !previousInteractionId.isEmpty()) {
            request.put("previous_interaction_id", previousInteractionId);
        }

        JSONObject generation = new JSONObject();
        generation.put("thinking_level", "low");
        generation.put("max_output_tokens", 700);
        request.put("generation_config", generation);
        request.put("response_format", responseFormat());

        JSONObject response;
        try {
            response = post(key, request);
        } catch (HttpFailure first) {
            // A stale server-side conversation must never make the assistant unusable.
            if (allowConversation && !previousInteractionId.isEmpty() && first.code == 400) {
                previousInteractionId = "";
                request.remove("previous_interaction_id");
                response = post(key, request);
            } else {
                throw first;
            }
        }

        String id = response.optString("id", "").trim();
        if (allowConversation && !id.isEmpty()) previousInteractionId = id;
        String output = extractOutputText(response);
        if (output.isEmpty()) throw new IllegalStateException("Gemini devolvió una respuesta vacía");
        JSONObject obj = new JSONObject(output);
        String typeName = obj.optString("type", "GENERAL").trim().toUpperCase(Locale.ROOT);
        IntentAgent.Type type;
        try { type = IntentAgent.Type.valueOf(typeName); }
        catch (Exception ignored) { type = IntentAgent.Type.GENERAL; }
        return new Decision(type,
                obj.optString("argument", ""),
                sanitizeReply(obj.optString("reply", "")),
                obj.optDouble("confidence", 0.82));
    }

    private JSONObject post(String key, JSONObject request) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(45_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("x-goog-api-key", key);
        conn.setRequestProperty("User-Agent", "ScreenObserverPro/2.6");
        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(payload);
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(stream);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new HttpFailure(code, body);
        return new JSONObject(body);
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static String extractOutputText(JSONObject response) {
        String direct = response.optString("output_text", "").trim();
        if (!direct.isEmpty()) return direct;
        JSONArray steps = response.optJSONArray("steps");
        if (steps == null) return "";
        String found = "";
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null || !"model_output".equals(step.optString("type"))) continue;
            JSONArray content = step.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null && "text".equals(part.optString("type"))) {
                    String text = part.optString("text", "").trim();
                    if (!text.isEmpty()) found = text;
                }
            }
        }
        return found;
    }

    private static JSONObject responseFormat() throws Exception {
        JSONArray actionNames = new JSONArray();
        for (IntentAgent.Type type : IntentAgent.Type.values()) actionNames.put(type.name());

        JSONObject properties = new JSONObject();
        properties.put("type", new JSONObject().put("type", "string").put("enum", actionNames)
                .put("description", "Tipo exacto de intención Android o GENERAL."));
        properties.put("argument", new JSONObject().put("type", "string")
                .put("description", "App, control, texto, consulta o parámetro requerido por la acción."));
        properties.put("reply", new JSONObject().put("type", "string")
                .put("description", "Respuesta natural breve; vacía cuando la intención es una acción clara."));
        properties.put("confidence", new JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1));

        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new JSONArray().put("type").put("argument").put("reply").put("confidence"));
        schema.put("additionalProperties", false);

        return new JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json")
                .put("schema", schema);
    }

    private static String buildInput(String userText, String screenText, String activeSkill, String controlsText) {
        return "PETICIÓN DEL USUARIO:\n" + clip(userText, 1800)
                + "\n\nCONTEXTO DEL TELÉFONO:\nApp/paquete activo: " + clip(AgentAccessibilityService.getActivePackageName(), 160)
                + "\nHabilidad activa: " + clip(activeSkill, 300)
                + "\nTexto visible/OCR: " + clip(screenText, 2600)
                + "\nControles accesibles: " + clip(controlsText, 1800)
                + "\n\nInterpreta la intención actual usando el contexto de conversación. No afirmes que una acción ya se ejecutó.";
    }

    private static String systemInstruction() {
        return "Eres el cerebro de comprensión de un asistente personal Android 15/16. "
                + "Hablas español natural y mantienes contexto entre turnos. No repitas lo que dijo el usuario. "
                + "Tu trabajo es decidir si quiere conversar o ejecutar una capacidad local. "
                + "Comprende paráfrasis: pantalla principal/inicio/home=HOME; cerrar/salir de una app=CLOSE_APP; "
                + "analizar/aprender a usar el juego o app actual=LEARN_CURRENT_APP; abrir app=OPEN_APP; "
                + "tocar/pulsar/elegir=CLICK; escribir/poner texto=TYPE_TEXT; volver=BACK; recientes=RECENTS; "
                + "deslizar, buscar, volumen, ajustes y demás acciones deben mapearse al tipo disponible más cercano. "
                + "Para una conversación, pregunta, explicación o petición que no requiera tocar el teléfono usa GENERAL y responde en reply. "
                + "Nunca inventes éxito de una acción. Nunca transformes una petición ambigua en una acción peligrosa. "
                + "No pidas ni manejes contraseñas, PIN, OTP o datos de pago. Las confirmaciones sensibles las impone la app local.";
    }

    private static String sanitizeReply(String value) {
        String out = value == null ? "" : value.trim();
        out = out.replaceFirst("(?i)^(entendi|entendí|te oi|te oí|dijiste)[: ,.-]+", "");
        if (out.length() > 900) out = out.substring(0, 900).trim() + "…";
        return out;
    }

    private static String clip(String value, int max) {
        String clean = value == null ? "" : value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    private static final class HttpFailure extends Exception {
        final int code;
        HttpFailure(int code, String body) {
            super("HTTP " + code + ": " + (body == null ? "" : clip(body, 240)));
            this.code = code;
        }
    }
}
