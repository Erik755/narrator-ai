import com.erik.screenobserver.BlackjackEngine;
import com.erik.screenobserver.IntentAgent;
import com.erik.screenobserver.PhoneCommandPlanner;

import java.util.List;

public final class V25ReviewRegressionTest {
    private static int passed = 0;

    public static void main(String[] args) {
        // Device-action words inside ordinary statements must never actuate the phone.
        general("los ajustes de wifi son confusos");
        general("cuando sube el volumen se oye mejor");
        general("los ajustes de bluetooth cambiaron mucho");
        general("creo que baja el volumen cuando llega un mensaje");
        general("desliza a la izquierda es una instrucción que conozco");

        // Imperative equivalents still work.
        type("ajustes de wifi", IntentAgent.Type.OPEN_SETTINGS_SECTION);
        type("abre bluetooth", IntentAgent.Type.OPEN_SETTINGS_SECTION);
        type("sube el volumen", IntentAgent.Type.VOLUME_UP);
        type("baja el volumen", IntentAgent.Type.VOLUME_DOWN);
        type("desliza a la izquierda", IntentAgent.Type.SWIPE_LEFT);

        // Conversational semicolon/luego input is allowed to remain conversational.
        List<IntentAgent.Result> conversation = PhoneCommandPlanner.plan(
                "Cuéntame sobre Roma; luego compárala con Madrid", "", "");
        require(conversation.size() == 2, "conversation should split into two segments");
        require(conversation.get(0).type == IntentAgent.Type.GENERAL,
                "first conversation segment must be GENERAL");
        require(conversation.get(1).type == IntentAgent.Type.GENERAL,
                "second conversation segment must be GENERAL");
        passed += 3;

        // A real command chain remains a command chain.
        List<IntentAgent.Result> commands = PhoneCommandPlanner.plan(
                "abre WhatsApp y luego busca Erik", "", "");
        require(commands.size() == 2, "command plan size");
        require(commands.get(0).type == IntentAgent.Type.OPEN_APP, "open-app first step");
        require(commands.get(1).type == IntentAgent.Type.SEARCH, "search second step");
        passed += 3;

        // Dealer OCR must never steal the player's total when dealer value is missing.
        BlackjackEngine.Recommendation missingDealer = BlackjackEngine.recommendFromText(
                "Blackjack Practice\nDealer\nYour hand 10\nHit Stand Double");
        require(!missingDealer.known(), "missing dealer card must remain UNKNOWN: " + missingDealer);
        passed++;

        // A legitimate labeled OCR screen still parses.
        BlackjackEngine.Recommendation labeled = BlackjackEngine.recommendFromText(
                "Blackjack Practice\nDealer 6\nYour hand 13\nHit Stand");
        require(labeled.decision == BlackjackEngine.Decision.STAND,
                "labeled OCR 13 vs 6 should STAND: " + labeled);
        passed++;

        // Explicit no-double state must be respected.
        BlackjackEngine.Recommendation noDouble = BlackjackEngine.recommendFromText(
                "no puedo doblar, tengo 11 contra 6");
        require(noDouble.decision == BlackjackEngine.Decision.HIT,
                "11 vs 6 with no double must HIT: " + noDouble);
        passed++;
        BlackjackEngine.Recommendation alreadyHit = BlackjackEngine.recommendFromText(
                "ya pedí, tengo 11 contra 6");
        require(alreadyHit.decision == BlackjackEngine.Decision.HIT,
                "already-hit 11 vs 6 must HIT: " + alreadyHit);
        passed++;

        // Negated real-money wording means practice, unless actual currency is visible.
        require(BlackjackEngine.isPracticeContext("Blackjack - sin dinero real - modo práctica"),
                "negated real-money screen should be practice");
        require(!BlackjackEngine.isRealMoneyContext("Blackjack - sin dinero real - modo práctica"),
                "negated real-money screen should not flag real money");
        require(BlackjackEngine.isRealMoneyContext("sin dinero real - saldo $10"),
                "visible currency must still flag real money");
        passed += 3;

        System.out.println("PASS " + passed + " v2.5 review regression checks");
    }

    private static void general(String text) {
        IntentAgent.Result r = IntentAgent.interpret(text, "", "");
        require(r.type == IntentAgent.Type.GENERAL, text + " => " + r.type + " expected GENERAL");
        passed++;
    }

    private static void type(String text, IntentAgent.Type expected) {
        IntentAgent.Result r = IntentAgent.interpret(text, "", "");
        require(r.type == expected, text + " => " + r.type + " expected " + expected);
        passed++;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
