package com.erik.screenobserver;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java local intent interpreter. No API key or paid service required. */
public final class IntentAgent {
    public enum Type {
        HEARING_CHECK, HIDE_OVERLAY, SHOW_OVERLAY, STOP_ASSISTANT, PAUSE_LISTENING, RESUME_LISTENING,
        LEARN_SKILL, LIST_SKILLS, USE_SKILL, SKILL_INFO, DESCRIBE_CONTROLS,
        CONFIRM_CLICK, CLICK, CLICK_ORDINAL, LONG_CLICK, TYPE_TEXT, SEARCH, SCROLL_DOWN, SCROLL_UP,
        SWIPE_LEFT, SWIPE_RIGHT, VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE, VOLUME_UNMUTE,
        BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER_MENU, LOCK_SCREEN,
        SCREENSHOT, OPEN_SETTINGS, OPEN_SETTINGS_SECTION, OPEN_URL, OPEN_APP, CLOSE_APP, LEARN_CURRENT_APP,
        BLACKJACK_ADVICE, BLACKJACK_PLAY,
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
    private static final Pattern SEARCH = Pattern.compile(
            "(?iu)^(?:busca|buscar|encuentra|localiza)\\s+(.+)$");
    private static final Pattern OPEN_URL = Pattern.compile(
            "(?iu)^(?:abre|visita|ve a|navega a)\\s+((?:https?://|www\\.)[^\\s]+|[a-z0-9.-]+\\.(?:com|org|net|io|mx|es)(?:/[^\\s]*)?)$");
    private static final Pattern CLOSE_APP = Pattern.compile(
            "(?iu)^(?:cierra|cerrar|sal\\s+de|salte\\s+de)\\s+(?:la\\s+)?(?:app|aplicacion|aplicación)?\\s*(.+)$");

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
            case RESUME_LISTENING:
            case CONFIRM_CLICK:
            case CLICK:
            case CLICK_ORDINAL:
            case LONG_CLICK:
            case TYPE_TEXT:
            case SEARCH:
            case SCROLL_DOWN:
            case SCROLL_UP:
            case SWIPE_LEFT:
            case SWIPE_RIGHT:
            case VOLUME_UP:
            case VOLUME_DOWN:
            case VOLUME_MUTE:
            case VOLUME_UNMUTE:
            case BACK:
            case HOME:
            case RECENTS:
            case NOTIFICATIONS:
            case QUICK_SETTINGS:
            case POWER_MENU:
            case LOCK_SCREEN:
            case SCREENSHOT:
            case OPEN_SETTINGS:
            case OPEN_SETTINGS_SECTION:
            case OPEN_URL:
            case OPEN_APP:
            case CLOSE_APP:
            case LEARN_CURRENT_APP:
            case BLACKJACK_PLAY:
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
        if (startsCommand(n, "reanuda la escucha", "reanuda escucha", "vuelve a escuchar", "escuchame otra vez",
                "activa el microfono", "enciende el microfono", "reactiva la escucha"))
            return r(Type.RESUME_LISTENING, "", raw, .97);

        if (has(n,
                "analiza este juego", "analiza el juego", "analiza un juego", "analiza el juego actual", "analiza este juego y aprende",
                "aprende a usar este juego", "aprende a usar el juego", "aprende este juego", "estudia este juego",
                "observa este juego y aprende", "aprende como funciona este juego",
                "analiza esta app y aprende", "analiza esta aplicacion y aprende",
                "aprende a usar esta app", "aprende a usar esta aplicacion"))
            return r(Type.LEARN_CURRENT_APP, "", raw, .97);

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
        if (startsCommand(n, "abre ajustes de wifi", "abre configuracion de wifi", "ajustes de wifi", "configuracion de wifi", "abre wifi", "configura wifi", "redes wifi"))
            return r(Type.OPEN_SETTINGS_SECTION, "wifi", raw, .96);
        if (startsCommand(n, "abre ajustes de bluetooth", "abre configuracion de bluetooth", "ajustes de bluetooth", "configuracion de bluetooth", "abre bluetooth", "configura bluetooth"))
            return r(Type.OPEN_SETTINGS_SECTION, "bluetooth", raw, .96);
        if (startsCommand(n, "abre ajustes de sonido", "abre configuracion de sonido", "abre ajustes de audio", "ajustes de sonido", "configuracion de sonido", "ajustes de audio", "configuracion de audio"))
            return r(Type.OPEN_SETTINGS_SECTION, "sonido", raw, .95);
        if (startsCommand(n, "abre ajustes de pantalla", "abre configuracion de pantalla", "ajustes de pantalla", "configuracion de pantalla", "ajustes de display"))
            return r(Type.OPEN_SETTINGS_SECTION, "pantalla", raw, .95);
        if (startsCommand(n, "abre ajustes de bateria", "abre configuracion de bateria", "ajustes de bateria", "configuracion de bateria", "ahorro de bateria"))
            return r(Type.OPEN_SETTINGS_SECTION, "bateria", raw, .95);
        if (startsCommand(n, "abre ajustes de ubicacion", "abre configuracion de ubicacion", "ajustes de ubicacion", "configuracion de ubicacion", "ajustes de localizacion"))
            return r(Type.OPEN_SETTINGS_SECTION, "ubicacion", raw, .95);
        if (startsCommand(n, "abre ajustes de aplicaciones", "abre configuracion de aplicaciones", "ajustes de aplicaciones", "configuracion de aplicaciones", "lista de aplicaciones", "administrar aplicaciones"))
            return r(Type.OPEN_SETTINGS_SECTION, "aplicaciones", raw, .95);
        if (startsCommand(n, "abre ajustes de notificaciones", "abre configuracion de notificaciones", "ajustes de notificaciones", "configuracion de notificaciones"))
            return r(Type.OPEN_SETTINGS_SECTION, "notificaciones", raw, .95);
        if (startsCommand(n, "abre ajustes de seguridad", "abre configuracion de seguridad", "ajustes de seguridad", "configuracion de seguridad"))
            return r(Type.OPEN_SETTINGS_SECTION, "seguridad", raw, .95);
        if (startsCommand(n, "abre ajustes de accesibilidad", "abre configuracion de accesibilidad", "ajustes de accesibilidad", "configuracion de accesibilidad", "abre accesibilidad"))
            return r(Type.OPEN_SETTINGS_SECTION, "accesibilidad", raw, .95);
        if (startsCommand(n, "abre ajustes", "abre configuracion", "abre la configuracion", "ve a ajustes", "ve a configuracion"))
            return r(Type.OPEN_SETTINGS, "", raw, .96);

        if (startsCommand(n, "sube el volumen", "aumenta el volumen", "mas volumen", "volumen arriba"))
            return r(Type.VOLUME_UP, "", raw, .96);
        if (startsCommand(n, "baja el volumen", "reduce el volumen", "menos volumen", "volumen abajo"))
            return r(Type.VOLUME_DOWN, "", raw, .96);
        if (startsCommand(n, "silencia el telefono", "silencia el celular", "ponlo en silencio", "quita el sonido", "mute"))
            return r(Type.VOLUME_MUTE, "", raw, .96);
        if (startsCommand(n, "activa el sonido", "quita el silencio", "devuelve el sonido", "unmute"))
            return r(Type.VOLUME_UNMUTE, "", raw, .96);
        if (startsCommand(n, "desliza a la izquierda", "desliza izquierda", "swipe left", "pasa a la izquierda"))
            return r(Type.SWIPE_LEFT, "", raw, .95);
        if (startsCommand(n, "desliza a la derecha", "desliza derecha", "swipe right", "pasa a la derecha"))
            return r(Type.SWIPE_RIGHT, "", raw, .95);
        if (startsCommand(n, "pulsa el primero", "toca el primero", "elige el primero", "pulsa la primera opcion"))
            return r(Type.CLICK_ORDINAL, "1", raw, .94);
        if (startsCommand(n, "pulsa el segundo", "toca el segundo", "elige el segundo", "pulsa la segunda opcion"))
            return r(Type.CLICK_ORDINAL, "2", raw, .94);
        if (startsCommand(n, "pulsa el tercero", "toca el tercero", "elige el tercero", "pulsa la tercera opcion"))
            return r(Type.CLICK_ORDINAL, "3", raw, .94);
        if (startsCommand(n, "pulsa el ultimo", "toca el ultimo", "elige el ultimo", "ultima opcion"))
            return r(Type.CLICK_ORDINAL, "last", raw, .94);

        m = SEARCH.matcher(raw.trim());
        if (m.find()) return r(Type.SEARCH, cleanup(m.group(1)), raw, .92);

        m = OPEN_URL.matcher(raw.trim());
        if (m.find()) return r(Type.OPEN_URL, cleanup(m.group(1)), raw, .95);

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
        if (has(n, "ve al inicio", "ve a inicio", "pantalla de inicio", "ve a home", "abre el inicio",
                "abre la pantalla principal", "ve a la pantalla principal", "vete a la pantalla principal",
                "vuelve a la pantalla principal", "regresa a la pantalla principal",
                "muestra la pantalla principal", "llevame a la pantalla principal",
                "inicio del celular", "inicio del telefono", "sal al inicio"))
            return r(Type.HOME, "", raw, .97);
        if (has(n, "abre recientes", "muestra recientes", "aplicaciones recientes", "abre las apps recientes"))
            return r(Type.RECENTS, "", raw, .91);

        if (has(n, "lee la pantalla", "lee esto", "leeme la pantalla", "leeme esto", "lee lo que dice"))
            return r(Type.READ_SCREEN, "", raw, .94);
        if (has(n, "que ves", "que hay en pantalla", "dime que ves", "describe la pantalla", "describe esto",
                "explicame la pantalla", "que aparece en pantalla"))
            return r(Type.DESCRIBE_SCREEN, "", raw, .94);

        boolean blackjackContext = BlackjackEngine.isBlackjackContext(raw + " " + activeSkill + " " + screenText);
        if (startsCommand(n, "juega blackjack", "juega black jack", "juega esta mano de blackjack",
                "activa modo blackjack", "modo blackjack automatico", "juega esta mano") && blackjackContext)
            return r(Type.BLACKJACK_PLAY, raw, raw, .96);
        if (blackjackContext && (has(n, "que hago", "que jugada", "pido o me planto", "que conviene",
                "aconsejame", "recomiendame", "blackjack") || n.matches(".*\\b\\d{1,2}\\s+(?:contra|vs)\\s+(?:a|as|ace|[2-9]|10|j|q|k)\\b.*")))
            return r(Type.BLACKJACK_ADVICE, raw, raw, .94);

        boolean gameContext = normalize(activeSkill).contains("ajedrez")
                || normalize(screenText).contains("chess")
                || normalize(screenText).contains("ajedrez");
        if (has(n, "que hago", "que debo hacer", "que me recomiendas", "aconsejame", "cual elijo", "que opcion",
                "que conviene", "cual es mejor", "dime que hacer", "ayudame a decidir")
                || (gameContext && has(n, "que jugada", "cual jugada", "que movimiento", "cual movimiento", "como juego",
                        "que muevo", "cual muevo", "mi siguiente jugada", "mejor jugada", "mejor movimiento", "mi mejor movimiento")))
            return r(Type.ADVICE, "", raw, .90);

        m = CLOSE_APP.matcher(raw.trim());
        if (m.find()) return r(Type.CLOSE_APP, cleanupTarget(m.group(1)), raw, .96);

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

    private static boolean startsCommand(String normalized, String... options) {
        String value = normalize(normalized);
        for (String option : options) {
            String q = normalize(option);
            if (value.equals(q)) return true;
            if (!value.startsWith(q + " ")) continue;
            String rest = value.substring(q.length()).trim();
            // A command phrase can be mentioned rather than requested: e.g.
            // "desliza a la izquierda es una instruccion...". Keep those conversational.
            if (rest.equals("es") || rest.startsWith("es ")
                    || rest.equals("era") || rest.startsWith("era ")
                    || rest.equals("fue") || rest.startsWith("fue ")
                    || rest.equals("significa") || rest.startsWith("significa ")
                    || rest.equals("quiere decir") || rest.startsWith("quiere decir ")) continue;
            return true;
        }
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
