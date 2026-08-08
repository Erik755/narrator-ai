import com.erik.screenobserver.IntentAgent;
import com.erik.screenobserver.PhoneCommandPlanner;

import java.util.List;

/** Broad deterministic regression matrix for commands a user commonly gives an Android assistant. */
public final class PhoneCommandMatrixTest {
    private static int passed = 0;

    public static void main(String[] args) {
        // Listening / overlay lifecycle.
        type("pausa la escucha", IntentAgent.Type.PAUSE_LISTENING, "");
        type("deja de escuchar", IntentAgent.Type.PAUSE_LISTENING, "");
        type("apaga el micrófono", IntentAgent.Type.PAUSE_LISTENING, "");
        type("reanuda la escucha", IntentAgent.Type.RESUME_LISTENING, "");
        type("vuelve a escuchar", IntentAgent.Type.RESUME_LISTENING, "");
        type("activa el micrófono", IntentAgent.Type.RESUME_LISTENING, "");
        type("oculta la burbuja", IntentAgent.Type.HIDE_OVERLAY, "");
        type("muestra la ventana", IntentAgent.Type.SHOW_OVERLAY, "");
        type("detén el asistente", IntentAgent.Type.STOP_ASSISTANT, "");

        // Global Android navigation.
        type("abre la pantalla principal del celular", IntentAgent.Type.HOME, "");
        type("ve a la pantalla principal", IntentAgent.Type.HOME, "");
        type("vuelve a la pantalla principal", IntentAgent.Type.HOME, "");
        type("sal al inicio", IntentAgent.Type.HOME, "");
        type("ve atrás", IntentAgent.Type.BACK, "");
        type("retrocede", IntentAgent.Type.BACK, "");
        type("abre recientes", IntentAgent.Type.RECENTS, "");
        type("muestra aplicaciones recientes", IntentAgent.Type.RECENTS, "");
        type("abre notificaciones", IntentAgent.Type.NOTIFICATIONS, "");
        type("baja las notificaciones", IntentAgent.Type.NOTIFICATIONS, "");
        type("abre ajustes rápidos", IntentAgent.Type.QUICK_SETTINGS, "");
        type("abre el menú de energía", IntentAgent.Type.POWER_MENU, "");
        type("bloquea el teléfono", IntentAgent.Type.LOCK_SCREEN, "");
        type("toma una captura", IntentAgent.Type.SCREENSHOT, "");

        // Installed apps and safe close behavior.
        type("abre WhatsApp", IntentAgent.Type.OPEN_APP, "WhatsApp");
        type("inicia YouTube", IntentAgent.Type.OPEN_APP, "YouTube");
        type("lanza Spotify", IntentAgent.Type.OPEN_APP, "Spotify");
        type("cierra WhatsApp", IntentAgent.Type.CLOSE_APP, "WhatsApp");
        type("sal de WhatsApp", IntentAgent.Type.CLOSE_APP, "WhatsApp");
        type("cierra esta app", IntentAgent.Type.CLOSE_APP, "esta app");

        // Settings and sections.
        type("abre ajustes", IntentAgent.Type.OPEN_SETTINGS, "");
        type("ve a configuración", IntentAgent.Type.OPEN_SETTINGS, "");
        type("abre ajustes de wifi", IntentAgent.Type.OPEN_SETTINGS_SECTION, "wifi");
        type("configuración de wifi", IntentAgent.Type.OPEN_SETTINGS_SECTION, "wifi");
        type("abre bluetooth", IntentAgent.Type.OPEN_SETTINGS_SECTION, "bluetooth");
        type("ajustes de bluetooth", IntentAgent.Type.OPEN_SETTINGS_SECTION, "bluetooth");
        type("ajustes de sonido", IntentAgent.Type.OPEN_SETTINGS_SECTION, "sonido");
        type("configuración de audio", IntentAgent.Type.OPEN_SETTINGS_SECTION, "sonido");
        type("ajustes de pantalla", IntentAgent.Type.OPEN_SETTINGS_SECTION, "pantalla");
        type("ajustes de batería", IntentAgent.Type.OPEN_SETTINGS_SECTION, "bateria");
        type("ajustes de ubicación", IntentAgent.Type.OPEN_SETTINGS_SECTION, "ubicacion");
        type("administrar aplicaciones", IntentAgent.Type.OPEN_SETTINGS_SECTION, "aplicaciones");
        type("ajustes de notificaciones", IntentAgent.Type.OPEN_SETTINGS_SECTION, "notificaciones");
        type("ajustes de seguridad", IntentAgent.Type.OPEN_SETTINGS_SECTION, "seguridad");
        type("abre accesibilidad", IntentAgent.Type.OPEN_SETTINGS_SECTION, "accesibilidad");

        // URLs.
        type("abre https://example.com", IntentAgent.Type.OPEN_URL, "https://example.com");
        type("visita www.wikipedia.org", IntentAgent.Type.OPEN_URL, "www.wikipedia.org");
        type("ve a openai.com", IntentAgent.Type.OPEN_URL, "openai.com");

        // Controls and text entry.
        type("pulsa Continuar", IntentAgent.Type.CLICK, "Continuar");
        type("toca Aceptar", IntentAgent.Type.CLICK, "Aceptar");
        type("presiona Guardar", IntentAgent.Type.CLICK, "Guardar");
        type("mantén presionado Wi-Fi", IntentAgent.Type.LONG_CLICK, "Wi-Fi");
        type("escribe hola mundo", IntentAgent.Type.TYPE_TEXT, "hola mundo");
        type("pon Erik Sánchez", IntentAgent.Type.TYPE_TEXT, "Erik Sánchez");
        type("teclea 12345", IntentAgent.Type.TYPE_TEXT, "12345");
        type("busca restaurantes", IntentAgent.Type.SEARCH, "restaurantes");
        type("encuentra configuración", IntentAgent.Type.SEARCH, "configuración");
        type("localiza Erik", IntentAgent.Type.SEARCH, "Erik");
        type("pulsa el primero", IntentAgent.Type.CLICK_ORDINAL, "1");
        type("toca el segundo", IntentAgent.Type.CLICK_ORDINAL, "2");
        type("elige el tercero", IntentAgent.Type.CLICK_ORDINAL, "3");
        type("pulsa el último", IntentAgent.Type.CLICK_ORDINAL, "last");

        // Scrolling / gestures.
        type("baja un poco", IntentAgent.Type.SCROLL_DOWN, "");
        type("desplázate abajo", IntentAgent.Type.SCROLL_DOWN, "");
        type("sube la pantalla", IntentAgent.Type.SCROLL_UP, "");
        type("desplázate arriba", IntentAgent.Type.SCROLL_UP, "");
        type("desliza a la izquierda", IntentAgent.Type.SWIPE_LEFT, "");
        type("swipe left", IntentAgent.Type.SWIPE_LEFT, "");
        type("desliza a la derecha", IntentAgent.Type.SWIPE_RIGHT, "");
        type("swipe right", IntentAgent.Type.SWIPE_RIGHT, "");

        // Audio.
        type("sube el volumen", IntentAgent.Type.VOLUME_UP, "");
        type("aumenta el volumen", IntentAgent.Type.VOLUME_UP, "");
        type("baja el volumen", IntentAgent.Type.VOLUME_DOWN, "");
        type("reduce el volumen", IntentAgent.Type.VOLUME_DOWN, "");
        type("silencia el teléfono", IntentAgent.Type.VOLUME_MUTE, "");
        type("ponlo en silencio", IntentAgent.Type.VOLUME_MUTE, "");
        type("activa el sonido", IntentAgent.Type.VOLUME_UNMUTE, "");
        type("quita el silencio", IntentAgent.Type.VOLUME_UNMUTE, "");

        // Screen understanding and learning.
        type("lee la pantalla", IntentAgent.Type.READ_SCREEN, "");
        type("describe la pantalla", IntentAgent.Type.DESCRIBE_SCREEN, "");
        type("qué controles ves", IntentAgent.Type.DESCRIBE_CONTROLS, "");
        type("analiza este juego para que aprendas a usarlo", IntentAgent.Type.LEARN_CURRENT_APP, "");
        type("analiza el juego", IntentAgent.Type.LEARN_CURRENT_APP, "");
        type("aprende a usar esta app", IntentAgent.Type.LEARN_CURRENT_APP, "");
        type("necesito que aprendas fotografía", IntentAgent.Type.LEARN_SKILL, "fotografía");
        type("qué habilidades tienes", IntentAgent.Type.LIST_SKILLS, "");

        // Blackjack typed phrases; context-free phrases containing blackjack should be recognized.
        typeContext("juega blackjack", IntentAgent.Type.BLACKJACK_PLAY, "juega blackjack", "", "");
        typeContext("juega esta mano", IntentAgent.Type.BLACKJACK_PLAY, "juega esta mano", "blackjack", "Dealer 10 Hit Stand");
        typeContext("qué hago en blackjack", IntentAgent.Type.BLACKJACK_ADVICE, "qué hago en blackjack", "", "");
        typeContext("tengo 16 contra 10 en blackjack", IntentAgent.Type.BLACKJACK_ADVICE, "tengo 16 contra 10 en blackjack", "", "");
        typeContext("pido o me planto", IntentAgent.Type.BLACKJACK_ADVICE, "pido o me planto", "blackjack", "Dealer 9");

        // Negative cases: ordinary speech should not become device actions.
        type("me gusta el volumen de esta canción", IntentAgent.Type.GENERAL, "");
        type("quiero aprender por mi cuenta", IntentAgent.Type.GENERAL, "");
        type("la pantalla se ve bonita", IntentAgent.Type.GENERAL, "");
        type("WhatsApp es popular", IntentAgent.Type.GENERAL, "");
        type("el bluetooth consume batería", IntentAgent.Type.GENERAL, "");

        // Multi-step typed command planning.
        plan("abre WhatsApp y luego busca Erik", IntentAgent.Type.OPEN_APP, IntentAgent.Type.SEARCH);
        plan("abre ajustes; abre wifi", IntentAgent.Type.OPEN_SETTINGS, IntentAgent.Type.OPEN_SETTINGS_SECTION);
        plan("abre YouTube y luego busca música y luego sube el volumen",
                IntentAgent.Type.OPEN_APP, IntentAgent.Type.SEARCH, IntentAgent.Type.VOLUME_UP);
        plan("ve al inicio y luego abre Spotify", IntentAgent.Type.HOME, IntentAgent.Type.OPEN_APP);
        plan("abre ajustes y luego vuelve atrás", IntentAgent.Type.OPEN_SETTINGS, IntentAgent.Type.BACK);
        plan("abre WhatsApp y escribe hola", IntentAgent.Type.OPEN_APP, IntentAgent.Type.TYPE_TEXT);
        plan("abre example.com y luego vuelve atrás", IntentAgent.Type.OPEN_URL, IntentAgent.Type.BACK);
        plan("pausa la escucha y luego abre ajustes", IntentAgent.Type.PAUSE_LISTENING, IntentAgent.Type.OPEN_SETTINGS);
        plan("reanuda la escucha y luego muestra la ventana", IntentAgent.Type.RESUME_LISTENING, IntentAgent.Type.SHOW_OVERLAY);

        require(PhoneCommandPlanner.containsSensitive("abre banco y luego transferir dinero"),
                "sensitive multi-step command should be detected");
        passed++;
        require(PhoneCommandPlanner.isLikelyCommand("busca Erik"), "search should look like a command");
        passed++;
        require(!PhoneCommandPlanner.isLikelyCommand("hoy hace calor"), "statement should not look like a command");
        passed++;

        System.out.println("PASS " + passed + " phone-command checks");
    }

    private static void type(String text, IntentAgent.Type expected, String arg) {
        typeContext(text, expected, arg, "", "");
    }

    private static void typeContext(String text, IntentAgent.Type expected, String arg,
                                    String skill, String screen) {
        IntentAgent.Result r = IntentAgent.interpret(text, skill, screen);
        require(r.type == expected, text + " => " + r + " expected " + expected);
        if (arg != null && !arg.isEmpty()) {
            require(IntentAgent.normalize(r.argument).equals(IntentAgent.normalize(arg)),
                    text + " argument=" + r.argument + " expected=" + arg);
        }
        passed++;
    }

    private static void plan(String text, IntentAgent.Type... expected) {
        List<IntentAgent.Result> plan = PhoneCommandPlanner.plan(text, "", "");
        require(plan.size() == expected.length,
                text + " plan size=" + plan.size() + " expected=" + expected.length + " plan=" + plan);
        for (int i = 0; i < expected.length; i++) {
            require(plan.get(i).type == expected[i],
                    text + " step " + i + " => " + plan.get(i) + " expected=" + expected[i]);
        }
        passed++;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
