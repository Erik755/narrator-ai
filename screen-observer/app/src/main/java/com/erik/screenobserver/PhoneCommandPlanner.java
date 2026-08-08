package com.erik.screenobserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small deterministic planner that complements the local LLM.
 * It is deliberately pure Java so broad command matrices can be tested in CI.
 */
public final class PhoneCommandPlanner {
    private static final String MARK = "\u001F";

    private PhoneCommandPlanner() { }

    public static List<IntentAgent.Result> plan(String raw, String activeSkill, String screenText) {
        List<IntentAgent.Result> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;

        String separated = raw.trim()
                .replaceAll("(?iu)\\s*;\\s*", MARK)
                .replaceAll("(?iu)\\s*,\\s*(?:y\\s+)?(?:luego|despues|después|entonces)\\s+", MARK)
                .replaceAll("(?iu)\\s+(?:y\\s+)?(?:luego|despues|después|entonces)\\s+", MARK)
                .replaceAll("(?iu)\\s+y\\s+(?=(?:abre|cierra|sal|ve|vuelve|regresa|pulsa|toca|presiona|oprime|escribe|pon|teclea|busca|desliza|sube|baja|silencia|quita|activa|desactiva|reanuda|pausa|juega|analiza|aprende|lee|describe|toma|haz|bloquea)\\b)", MARK);

        String[] segments = separated.split(MARK);
        for (String segment : segments) {
            if (out.size() >= 6) break;
            String s = segment == null ? "" : segment.trim();
            if (s.isEmpty()) continue;
            IntentAgent.Result result = IntentAgent.interpret(s, activeSkill, screenText);
            if (result.type == IntentAgent.Type.GENERAL) result = inferGeneral(s, screenText);
            out.add(result);
        }
        return out;
    }

    private static IntentAgent.Result inferGeneral(String raw, String screenText) {
        String n = IntentAgent.normalize(raw);
        if (n.isEmpty()) return r(IntentAgent.Type.GENERAL, "", raw, .20);

        if (startsAny(n, "busca ", "buscar ", "encuentra ", "localiza ")) {
            String q = afterFirstWord(raw);
            if (!q.isEmpty()) return r(IntentAgent.Type.SEARCH, q, raw, .86);
        }
        if (containsAny(n, "sube el volumen", "aumenta el volumen", "mas volumen", "volumen arriba"))
            return r(IntentAgent.Type.VOLUME_UP, "", raw, .90);
        if (containsAny(n, "baja el volumen", "reduce el volumen", "menos volumen", "volumen abajo"))
            return r(IntentAgent.Type.VOLUME_DOWN, "", raw, .90);
        if (containsAny(n, "silencia", "ponlo en silencio", "quita el sonido", "mute"))
            return r(IntentAgent.Type.VOLUME_MUTE, "", raw, .91);
        if (containsAny(n, "activa el sonido", "quita el silencio", "unmute", "devuelve el sonido"))
            return r(IntentAgent.Type.VOLUME_UNMUTE, "", raw, .91);
        if (containsAny(n, "desliza a la izquierda", "desliza izquierda", "swipe left", "pasa a la izquierda"))
            return r(IntentAgent.Type.SWIPE_LEFT, "", raw, .90);
        if (containsAny(n, "desliza a la derecha", "desliza derecha", "swipe right", "pasa a la derecha"))
            return r(IntentAgent.Type.SWIPE_RIGHT, "", raw, .90);
        if (containsAny(n, "reanuda la escucha", "vuelve a escuchar", "activa el microfono", "enciende el microfono"))
            return r(IntentAgent.Type.RESUME_LISTENING, "", raw, .94);

        if (n.equals("wifi") || n.equals("wi fi"))
            return r(IntentAgent.Type.OPEN_SETTINGS_SECTION, "wifi", raw, .82);
        if (n.equals("bluetooth"))
            return r(IntentAgent.Type.OPEN_SETTINGS_SECTION, "bluetooth", raw, .82);
        if (n.equals("sonido") || n.equals("audio"))
            return r(IntentAgent.Type.OPEN_SETTINGS_SECTION, "sonido", raw, .80);
        if (n.equals("pantalla") || n.equals("display"))
            return r(IntentAgent.Type.OPEN_SETTINGS_SECTION, "pantalla", raw, .78);

        if (BlackjackEngine.isBlackjackContext(raw + " " + screenText)) {
            if (containsAny(n, "que hago", "que jugada", "pido o me planto", "aconsejame", "recomiendame"))
                return r(IntentAgent.Type.BLACKJACK_ADVICE, raw, raw, .84);
        }

        return r(IntentAgent.Type.GENERAL, "", raw, .30);
    }

    public static boolean isLikelyCommand(String raw) {
        String n = IntentAgent.normalize(raw);
        if (n.isEmpty()) return false;
        return startsAny(n,
                "abre ", "cierra ", "sal ", "ve ", "vuelve ", "regresa ",
                "pulsa ", "toca ", "presiona ", "oprime ", "escribe ", "pon ",
                "teclea ", "busca ", "desliza ", "sube ", "baja ", "silencia ",
                "activa ", "desactiva ", "reanuda ", "pausa ", "juega ", "analiza ",
                "aprende ", "lee ", "describe ", "toma ", "haz ", "bloquea ");
    }

    public static boolean containsSensitive(String raw) {
        String n = IntentAgent.normalize(raw);
        return containsAny(n,
                "pagar", "pago", "comprar", "compra", "transferir", "transferencia",
                "enviar dinero", "borrar", "eliminar cuenta", "borrar datos", "desinstalar",
                "restablecer de fabrica", "formatear", "depositar", "retirar dinero");
    }

    public static long recommendedDelayMs(IntentAgent.Type type) {
        if (type == null) return 350;
        switch (type) {
            case OPEN_APP:
            case CLOSE_APP:
            case OPEN_SETTINGS:
            case OPEN_SETTINGS_SECTION:
            case HOME:
            case BACK:
            case RECENTS:
            case NOTIFICATIONS:
            case QUICK_SETTINGS:
                return 850;
            case SEARCH:
            case TYPE_TEXT:
                return 450;
            default:
                return 320;
        }
    }

    private static IntentAgent.Result r(IntentAgent.Type type, String argument, String raw, double confidence) {
        return new IntentAgent.Result(type, argument, raw, confidence);
    }

    private static String afterFirstWord(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int space = s.indexOf(' ');
        return space < 0 ? "" : s.substring(space + 1).trim().replaceAll("[?.!,;:]+$", "").trim();
    }

    private static boolean startsAny(String value, String... options) {
        for (String o : options) if (value.startsWith(IntentAgent.normalize(o))) return true;
        return false;
    }

    private static boolean containsAny(String value, String... options) {
        for (String o : options) if (value.contains(IntentAgent.normalize(o))) return true;
        return false;
    }
}
