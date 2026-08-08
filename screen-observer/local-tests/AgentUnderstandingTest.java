import com.erik.screenobserver.AndroidSkillPack;
import com.erik.screenobserver.CaptureGeometry;
import com.erik.screenobserver.ConversationEngine;
import com.erik.screenobserver.IntentAgent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AgentUnderstandingTest {
    private static int passed = 0;

    public static void main(String[] args) {
        type("¿me escuchas?", IntentAgent.Type.HEARING_CHECK, "", "", "");
        type("oculta la burbuja", IntentAgent.Type.HIDE_OVERLAY, "", "", "");
        type("muestra la ventana", IntentAgent.Type.SHOW_OVERLAY, "", "", "");
        type("necesito que aprendas pintura al óleo", IntentAgent.Type.LEARN_SKILL, "pintura al óleo", "", "");
        type("qué habilidades tienes", IntentAgent.Type.LIST_SKILLS, "", "", "");
        type("dale a Continuar", IntentAgent.Type.CLICK, "Continuar", "", "");
        type("selecciona Aceptar", IntentAgent.Type.CLICK, "Aceptar", "", "");
        type("pon hola mundo", IntentAgent.Type.TYPE_TEXT, "hola mundo", "", "");
        type("baja un poco", IntentAgent.Type.SCROLL_DOWN, "", "", "");
        type("vuelve atrás", IntentAgent.Type.BACK, "", "", "");
        type("qué jugada hago", IntentAgent.Type.ADVICE, "", "ajedrez", "tablero");
        type("abre Ajustes", IntentAgent.Type.OPEN_SETTINGS, "", "", "");
        type("abre WhatsApp", IntentAgent.Type.OPEN_APP, "WhatsApp", "", "");
        type("abre notificaciones", IntentAgent.Type.NOTIFICATIONS, "", "", "");
        type("abre ajustes rápidos", IntentAgent.Type.QUICK_SETTINGS, "", "", "");
        type("bloquea la pantalla", IntentAgent.Type.LOCK_SCREEN, "", "", "");
        type("mantén presionado Wi-Fi", IntentAgent.Type.LONG_CLICK, "Wi-Fi", "", "");
        type("continúa", IntentAgent.Type.CLICK, "Continuar", "", "");
        type("toma una captura", IntentAgent.Type.SCREENSHOT, "", "", "");
        type("abre el menú de energía", IntentAgent.Type.POWER_MENU, "", "", "");
        type("hay poca privacidad", IntentAgent.Type.GENERAL, "", "", "");

        lowConfidenceActionDoesNotWin();
        lowConfidenceSoleActionIsRejected();
        lowConfidenceSkillMutationIsRejected();
        secondHypothesisCanWinWithoutScores();
        aliasesIncludeCommonAndroidLabels();
        androidContextIsDetected();
        ordinaryGoogleAppIsNotSystemContext();
        captureSizingPreservesAspectRatio();
        rotationMappingIsSafe();
        appWindowMappingIsRejected();
        nearAspectAppWindowIsRejected();
        conversationalReplyDoesNotEcho();
        conversationKeepsFollowUpContext();
        greetingIsNatural();

        System.out.println("PASS " + passed + " checks");
    }

    private static void type(String spoken, IntentAgent.Type expected, String arg,
                             String activeSkill, String screenText) {
        IntentAgent.Result r = IntentAgent.interpret(spoken, activeSkill, screenText);
        require(r.type == expected, spoken + " => " + r + " expected " + expected);
        if (arg != null && !arg.isEmpty()) {
            require(IntentAgent.normalize(r.argument).equals(IntentAgent.normalize(arg)),
                    spoken + " argument " + r.argument + " expected " + arg);
        }
        passed++;
    }

    private static void lowConfidenceActionDoesNotWin() {
        List<String> candidates = Arrays.asList("creo que esto está bien", "pulsa borrar");
        float[] conf = new float[]{0.99f, 0.01f};
        IntentAgent.Result r = IntentAgent.interpret(candidates, conf, "", "");
        require(r.type == IntentAgent.Type.GENERAL,
                "low confidence alternate action must not win: " + r);
        passed++;
    }

    private static void lowConfidenceSoleActionIsRejected() {
        List<String> candidates = Arrays.asList("pulsa desinstalar");
        float[] conf = new float[]{0.05f};
        IntentAgent.Result r = IntentAgent.interpret(candidates, conf, "", "");
        require(r.type == IntentAgent.Type.GENERAL,
                "low confidence sole action must be rejected: " + r);
        passed++;
    }

    private static void lowConfidenceSkillMutationIsRejected() {
        List<String> candidates = Arrays.asList("aprende banca");
        float[] conf = new float[]{0.01f};
        IntentAgent.Result r = IntentAgent.interpret(candidates, conf, "", "");
        require(r.type == IntentAgent.Type.GENERAL,
                "low confidence skill mutation must be rejected: " + r);
        passed++;
    }

    private static void secondHypothesisCanWinWithoutScores() {
        List<String> candidates = new ArrayList<>();
        candidates.add("tal vez necesito algo");
        candidates.add("pulsa Continuar");
        IntentAgent.Result r = IntentAgent.interpret(candidates, null, "", "");
        require(r.type == IntentAgent.Type.CLICK,
                "strong second hypothesis should be usable without scores: " + r);
        passed++;
    }

    private static void aliasesIncludeCommonAndroidLabels() {
        List<String> aliases = AndroidSkillPack.aliasesForTarget("continuar");
        boolean hasNext = false, hasContinue = false;
        for (String s : aliases) {
            String n = AndroidSkillPack.normalize(s);
            if (n.equals("next")) hasNext = true;
            if (n.equals("continue")) hasContinue = true;
        }
        require(hasNext && hasContinue, "Android aliases missing Next/Continue: " + aliases);
        passed++;
    }

    private static void androidContextIsDetected() {
        require(AndroidSkillPack.looksLikeAndroidContext("com.android.settings", "Permisos de aplicaciones"),
                "Android Settings context should be detected");
        passed++;
    }

    private static void ordinaryGoogleAppIsNotSystemContext() {
        require(!AndroidSkillPack.looksLikeAndroidContext("com.google.android.youtube", "Settings de video"),
                "ordinary Google apps must not force the Android system skill");
        passed++;
    }

    private static void captureSizingPreservesAspectRatio() {
        int[] portrait = CaptureGeometry.targetSize(1080, 2400);
        require(portrait[1] == 1280, "long edge must be capped at 1280");
        double inAspect = 1080.0 / 2400.0;
        double outAspect = portrait[0] / (double) portrait[1];
        require(Math.abs(inAspect - outAspect) < 0.002, "capture aspect ratio must be preserved");
        passed++;
    }

    private static void rotationMappingIsSafe() {
        require(CaptureGeometry.isDirectScreenMappingSafe(2400, 1080, 2400, 1080),
                "full-screen landscape rotation should remain directly mappable");
        passed++;
    }

    private static void appWindowMappingIsRejected() {
        require(!CaptureGeometry.isDirectScreenMappingSafe(1000, 1000, 1080, 2400),
                "square app-window capture must not be mapped to full-screen tap coordinates");
        passed++;
    }

    private static void nearAspectAppWindowIsRejected() {
        require(!CaptureGeometry.isDirectScreenMappingSafe(1080, 2200, 1080, 2400),
                "app-only capture missing system bars must not be mapped as the full display");
        passed++;
    }

    private static void conversationalReplyDoesNotEcho() {
        ConversationEngine.Memory m = new ConversationEngine.Memory();
        String request = "necesito ayuda para seguir con lo que estoy haciendo";
        m.rememberUser(request, "GENERAL", "");
        String reply = ConversationEngine.reply(request, "", "", "", m);
        require(!ConversationEngine.containsEcho(reply, request), "conversation must not parrot the user: " + reply);
        passed++;
    }

    private static void conversationKeepsFollowUpContext() {
        ConversationEngine.Memory m = new ConversationEngine.Memory();
        m.rememberUser("abre ajustes", "OPEN_SETTINGS", "Ajustes");
        m.rememberReply("Ajustes abiertos.");
        m.rememberUser("y ahora", "GENERAL", "");
        String reply = ConversationEngine.reply("y ahora", "", "", "Permisos y aplicaciones", m);
        require(reply.toLowerCase().contains("contexto") || reply.toLowerCase().contains("continu"),
                "follow-up should acknowledge prior context: " + reply);
        passed++;
    }

    private static void greetingIsNatural() {
        ConversationEngine.Memory m = new ConversationEngine.Memory();
        String reply = ConversationEngine.reply("hola", "", "", "", m);
        require(reply.startsWith("Hola"), "greeting should be natural: " + reply);
        require(!reply.toLowerCase().contains("entend"), "greeting should not say 'entendí': " + reply);
        passed++;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
