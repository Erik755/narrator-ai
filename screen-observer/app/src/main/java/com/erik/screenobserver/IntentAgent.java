package com.erik.screenobserver;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java local intent interpreter. No API key or paid service required. */
public final class IntentAgent {
    public enum Type {
        HEARING_CHECK, HIDE_OVERLAY, SHOW_OVERLAY, STOP_ASSISTANT, PAUSE_LISTENING,
        LEARN_SKILL, LIST_SKILLS, USE_SKILL, SKILL_INFO, DESCRIBE_CONTROLS,
        CONFIRM_CLICK, CLICK, LONG_CLICK, TYPE_TEXT, SCROLL_DOWN, SCROLL_UP,
        BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER_MENU, LOCK_SCREEN,
        SCREENSHOT, OPEN_SETTINGS, OPEN_APP,
        DESCRIBE_SCREEN, READ_SCREEN, ADVICE, GENERAL
    }

    public static final class Result {
        public final Type type;
        public final String argument;
        public final String raw;
        public final double confidence;

        Result(Type type, String argument, String raw, double confidence) {
            this.type = type;
            this.argument = argument == null ? "" : argument.trim();
            this.raw = raw == null ? "" : raw;
            this.confidence = confidence;
        }

        @Override public String toString() {
            return type + "(" + argument + ")@" + confidence;
        }
    }

    private static final double MIN_MEASURED_ACTION_CONFIDENCE = 0.30;

    private static final Pattern TYPE = Pattern.compile(
            "(?iu)^(?:por favor\\s+)?(?:escribe|escribeme|pon|coloca|introduce|ingresa|teclea)\\s+(?:el texto\\s+)?(.+)$");
    private static final Pattern LEARN = Pattern.compile(
            "(?iu)(?:necesito que (?:aprendas|sepas|conozcas)|quiero que (?:aprendas|sepas)|investiga(?: y aprende)?(?: sobre)?|aprende(?: sobre| la habilidad de| habilidad de)?|estudia)\\s+(.+)$");
    private static final Pattern USE = Pattern.compile(
            "(?iu)(?:usa|utiliza|aplica|activa)(?:\\s+la)?(?:\\s+habilidad|\\s+conocimientos)?(?:\\s+de)?\\s+(.+)$");
    private static final Pattern CLICK = Pattern.compile(
            "(?iu)(?:haz\\s+(?:clic|click)\\s+(?:en\\s+)?|pulsa(?:\\s+el\\s+boton)?\\s+|toca\\s+|presiona\\s+|oprime\\s+|aprieta\\s+|selecciona\\s+|elige\\s+|dale\\s+(?:clic\\s+)?(?:a\\s+)?)(.+)$");
    private static final Pattern LONG_CLICK = Pattern.compile(
            "(?iu)(?:manten\\s+presionado|mantén\\s+presionado|deja\\s+presionado|presiona\\s+y\\s+manten|presiona\\s+y\\s+mantén)\\s+(?:el\\s+boton\\s+)?(.+)$");
    private static final Pattern CONFIRM = Pattern.compile(
            "(?iu)(?:confirma(?:\\s+que)?\\s+)(?:pulsa|toca|presiona|oprime|selecciona|elige|manten\\s+presionado|mantén\\s+presionado)?\\s*(.+)$");
    private static final Pattern OPEN_APP = Pattern.compile(
            "(?iu)^(?:abre|inicia|lanza|ejecuta)\\s+(?:la\\s+)?(?:app|aplicacion|aplicación)?\\s*(.+)$");

    private IntentAgent() { }

    public static Result interpret(List<String> candidates, float[] confidences,
                                   String activeSkill, String screenText) {
        if (candidates == null || candidates.isEmpty()) {
            return new Result(Type.GENERAL, "", "", 0);
        }

        Result best = null;
        for (int i = 0; i < candidates.size(); i++) {
            String raw = candidates.get(i);
            boolean measured = confidences != null && i < confidences.length
                    && confidences[i] >= 0f && confidences[i] <= 1f;
            double speechConfidence = measured ? confidences[i] : rankConfidence(i);
            Result semantic = parse(raw, activeSkill, screenText);

            // Device actions and persistent state changes are never selected from a very
            // low-confidence speech hypothesis. This prevents accidental taps or learning.
            if (isActionable(semantic.type)) {
                if (measured && speechConfidence < MIN_MEASURED_ACTION_CONFIDENCE) continue;
                if (!measured && i >= 3) continue;
            }

            double combined = semantic.confidence * (0.20 + 0.80 * speechConfidence);
            Result weighted = new Result(semantic.type, semantic.argument, semantic.raw, combined);
            if (best == null || weighted.confidence > best.confidence) best = weighted;
        }

        if (best == null) {
            String raw = candidates.get(0) == null ? "" : candidates.get(0);
            return new Result(Type.GENERAL, "", raw, 0.20);
        }
        return best;
    }

    public static Result interpret(String raw, String activeSkill, String screenText) {
        List<String> candidates = new ArrayList<>();
        candidates.add(raw);
        return interpret(candidates, null, activeSkill, screenText);
    }

    private static double rankConfidence(int index) {
        switch (index) {
            case 0: return 0.95;
            case 1: return 0.75;
            case 2: return 0.55;
            case 3: return 0.35;
            default: return 0.20;
        }
    }

    private static boolean isActionable(Type type) {
        switch (type) {
            case LEARN_SKILL:
            case USE_SKILL:
            case HIDE_OVERLAY:
            case SHOW_OVERLAY:
            case STOP_ASSISTANT:
            case PAUSE_LISTENING:
            case CONFIRM_CLICK:
            case CLICK:
            case LONG_CLICK:
            case TYPE_TEXT:
            case SCROLL_DOWN:
            case SCROLL_UP:
            case BACK:
            case HOME:
            case RECENTS:
            case NOTIFICATIONS:
            case QUICK_SETTINGS:
            case POWER_MENU:
            case LOCK_SCREEN:
            case SCREENSHOT:
            case OPEN_SETTINGS:
            case OPEN_APP:
                return true;
            default:
                return false;
        }
    }

    private static Result parse(String raw, String activeSkill, String screenText) {
        String n = normalize(raw);
        if (n.isEmpty()) return new Result(Type.GENERAL, "", raw, .05);

        if (has(n, "me escuchas", "me oyes", "puedes oirme", "puedes escucharme", "estas escuchando"))
            return r(Type.HEARING_CHECK, "", raw, .94);
        if (has(n, "oculta la ventana", "oculta la burbuja", "esconde la ventana", "quita la ventana", "cierra la burbuja"))
            return r(Type.HIDE_OVERLAY, "", raw, .95);
        if (has(n, "muestra la ventana", "muestra la burbuja", "ensena la ventana", "abre la burbuja"))
            return r(Type.SHOW_OVERLAY, "", raw, .95);
        if (has(n, "deten el asistente", "para el asistente", "termina el asistente", "deten el monitoreo", "deja de monitorear"))
            return r(Type.STOP_ASSISTANT, "", raw, .96);
        if (has(n, "deja de escuchar", "no me escuches", "pausa la escucha", "pausa escucha", "desactiva el microfono", "apaga el microfono"))
            return r(Type.PAUSE_LISTENING, "", raw, .96);

        Matcher m = LEARN.matcher(raw.trim());
        if (m.find()) return r(Type.LEARN_SKILL, cleanup(m.group(1)), raw, .93);

        if (has(n, "que habilidades tienes", "cuales son tus habilidades", "que sabes hacer", "lista tus habilidades", "que has aprendido"))
            return r(Type.LIST_SKILLS, "", raw, .88);

        if (has(n, "que aprendiste de", "que sabes de la habilidad", "que sabes sobre", "dime lo que sabes de")) {
            String arg = afterAny(raw,
                    "que aprendiste de", "qué aprendiste de",
                    "que sabes de la habilidad", "qué sabes de la habilidad",
                    "que sabes sobre", "qué sabes sobre", "dime lo que sabes de");
            return r(Type.SKILL_INFO, arg, raw, .87);
        }

        m = USE.matcher(raw.trim());
        if (m.find() && (n.contains("habilidad") || n.contains("conocimiento")
                || n.startsWith("usa ") || n.startsWith("utiliza ")))
            return r(Type.USE_SKILL, cleanup(m.group(1)), raw, .78);

        if (has(n, "que botones ves", "que controles ves", "que puedo tocar", "que puedo pulsar",
                "que opciones puedo pulsar", "dime los botones", "que hay para tocar"))
            return r(Type.DESCRIBE_CONTROLS, "", raw, .94);

        if (has(n, "abre notificaciones", "muestra notificaciones", "panel de notificaciones", "baja las notificaciones"))
            return r(Type.NOTIFICATIONS, "", raw, .96);
        if (has(n, "abre ajustes rapidos", "muestra ajustes rapidos", "panel rapido", "quick settings", "controles rapidos"))
            return r(Type.QUICK_SETTINGS, "", raw, .96);
        if (has(n, "abre el menu de energia", "menu de energia", "menu de apagado", "opciones de apagado"))
            return r(Type.POWER_MENU, "", raw, .96);
        if (has(n, "bloquea el telefono", "bloquea la pantalla", "apaga y bloquea la pantalla"))
            return r(Type.LOCK_SCREEN, "", raw, .97);
        if (has(n, "toma una captura", "haz una captura", "captura de pantalla", "screenshot"))
            return r(Type.SCREENSHOT, "", raw, .96);
        if (has(n, "abre ajustes", "abre configuracion", "abre la configuracion", "ve a ajustes", "ve a configuracion"))
            return r(Type.OPEN_SETTINGS, "", raw, .96);

        m = CONFIRM.matcher(raw.trim());
        if (m.find() && n.startsWith("confirma"))
            return r(Type.CONFIRM_CLICK, cleanup(m.group(1)), raw, .94);

        m = LONG_CLICK.matcher(raw.trim());
        if (m.find()) return r(Type.LONG_CLICK, cleanupTarget(m.group(1)), raw, .96);

        m = TYPE.matcher(raw.trim());
        if (m.find()) return r(Type.TYPE_TEXT, m.group(1).trim(), raw, .96);

        m = CLICK.matcher(raw.trim());
        if (m.find()) return r(Type.CLICK, cleanupTarget(m.group(1)), raw, .95);

        String implicit = AndroidSkillPack.implicitControlTarget(raw);
        if (!implicit.isEmpty()) return r(Type.CLICK, implicit, raw, .86);

        if (has(n, "baja la pantalla", "desplazate abajo", "desplaza hacia abajo", "desliza hacia abajo", "scroll abajo",
                "baja un poco", "ve mas abajo", "mueve hacia abajo"))
            return r(Type.SCROLL_DOWN, "", raw, .92);
        if (has(n, "sube la pantalla", "desplazate arriba", "desplaza hacia arriba", "desliza hacia arriba", "scroll arriba",
                "sube un poco", "ve mas arriba", "mueve hacia arriba"))
            return r(Type.SCROLL_UP, "", raw, .92);
        if (has(n, "ve atras", "vuelve atras", "regresa", "retrocede", "boton atras"))
            return r(Type.BACK, "", raw, .91);
        if (has(n, "ve al inicio", "ve a inicio", "pantalla de inicio", "ve a home", "abre el inicio"))
            return r(Type.HOME, "", raw, .91);
        if (has(n, "abre recientes", "muestra recientes", "aplicaciones recientes", "abre las apps recientes"))
            return r(Type.RECENTS, "", raw, .91);

        if (has(n, "lee la pantalla", "lee esto", "leeme la pantalla", "leeme esto", "lee lo que dice"))
            return r(Type.READ_SCREEN, "", raw, .94);
        if (has(n, "que ves", "que hay en pantalla", "dime que ves", "describe la pantalla", "describe esto",
                "explicame la pantalla", "que aparece en pantalla"))
            return r(Type.DESCRIBE_SCREEN, "", raw, .94);

        boolean gameContext = normalize(activeSkill).contains("ajedrez")
                || normalize(screenText).contains("chess")
                || normalize(screenText).contains("ajedrez");
        if (has(n, "que hago", "que debo hacer", "que me recomiendas", "aconsejame", "cual elijo", "que opcion",
                "que conviene", "cual es mejor", "dime que hacer", "ayudame a decidir")
                || (gameContext && has(n, "que jugada", "cual jugada", "que movimiento", "cual movimiento", "como juego",
                        "que muevo", "cual muevo", "mi siguiente jugada", "mejor jugada", "mejor movimiento", "mi mejor movimiento")))
            return r(Type.ADVICE, "", raw, .90);

        m = OPEN_APP.matcher(raw.trim());
        if (m.find()) return r(Type.OPEN_APP, cleanup(m.group(1)), raw, .86);

        String approximateClick = wordsAfterApproxToken(raw,
                new String[]{"pulsa", "toca", "presiona", "oprime", "elige", "selecciona"}, 1);
        if (!approximateClick.isEmpty())
            return r(Type.CLICK, cleanupTarget(approximateClick), raw, .68);

        String approximateType = wordsAfterApproxToken(raw,
                new String[]{"escribe", "teclea", "ingresa"}, 1);
        if (!approximateType.isEmpty())
            return r(Type.TYPE_TEXT, approximateType, raw, .67);

        return r(Type.GENERAL, "", raw, .35);
    }

    private static Result r(Type t, String a, String raw, double c) {
        return new Result(t, a, raw, Math.min(.999, c));
    }

    public static String normalize(String value) {
        return TextNormalizer.normalize(value);
    }

    private static boolean has(String normalized, String... options) {
        for (String option : options) if (normalized.contains(normalize(option))) return true;
        return false;
    }

    private static String cleanup(String s) {
        return s == null ? "" : s.trim().replaceAll("[?.!,;:]+$", "").trim();
    }

    private static String cleanupTarget(String s) {
        String x = cleanup(s);
        x = x.replaceFirst("(?iu)^(?:el|la|los|las)\\s+(?:boton|opcion|casilla)?\\s*", "");
        return x.trim();
    }

    /** Extracts words after a prefix wherever that prefix occurs in the utterance. */
    private static String afterAny(String raw, String... prefixes) {
        if (raw == null) return "";
        String[] rawWords = raw.trim().split("\\s+");
        String[] normalizedWords = normalize(raw).split(" ");
        int usableWords = Math.min(rawWords.length, normalizedWords.length);

        for (String prefix : prefixes) {
            String normalizedPrefix = normalize(prefix);
            if (normalizedPrefix.isEmpty()) continue;
            String[] prefixWords = normalizedPrefix.split(" ");
            for (int start = 0; start + prefixWords.length <= usableWords; start++) {
                boolean matches = true;
                for (int j = 0; j < prefixWords.length; j++) {
                    if (!normalizedWords[start + j].equals(prefixWords[j])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return cleanup(joinWords(rawWords, start + prefixWords.length));
            }
        }
        return "";
    }

    /**
     * Fuzzy recovery is intentionally restricted to the first spoken token. Exact command
     * patterns above may occur later in a sentence, but a word inside ordinary conversation
     * (for example "poca") must never become an actionable "toca" command by edit distance.
     */
    private static String wordsAfterApproxToken(String raw, String[] targets, int maxDistance) {
        if (raw == null) return "";
        String[] rawWords = raw.trim().split("\\s+");
        String[] normalizedWords = normalize(raw).split(" ");
        int usableWords = Math.min(rawWords.length, normalizedWords.length);
        if (usableWords < 2) return "";
        int i = 0;
        for (String target : targets) {
            if (distance(normalizedWords[i], normalize(target)) <= maxDistance) {
                return cleanup(joinWords(rawWords, i + 1));
            }
        }
        return "";
    }

    private static String joinWords(String[] words, int start) {
        if (words == null || start >= words.length) return "";
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, start); i < words.length; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(words[i]);
        }
        return out.toString();
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(
                        Math.min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
