import com.erik.screenobserver.BlackjackEngine;

public final class BlackjackEngineTest {
    private static int passed = 0;

    public static void main(String[] args) {
        // Hard totals.
        hard(20, 10, BlackjackEngine.Decision.STAND);
        hard(17, 11, BlackjackEngine.Decision.STAND);
        hard(16, 6, BlackjackEngine.Decision.STAND);
        hard(16, 7, BlackjackEngine.Decision.HIT);
        hard(15, 10, BlackjackEngine.Decision.HIT);
        hard(13, 2, BlackjackEngine.Decision.STAND);
        hard(13, 7, BlackjackEngine.Decision.HIT);
        hard(12, 4, BlackjackEngine.Decision.STAND);
        hard(12, 6, BlackjackEngine.Decision.STAND);
        hard(12, 2, BlackjackEngine.Decision.HIT);
        hard(12, 3, BlackjackEngine.Decision.HIT);
        hard(12, 10, BlackjackEngine.Decision.HIT);
        hard(11, 10, BlackjackEngine.Decision.DOUBLE);
        hardNoDouble(11, 10, BlackjackEngine.Decision.HIT);
        hard(11, 11, BlackjackEngine.Decision.HIT);
        hard(10, 9, BlackjackEngine.Decision.DOUBLE);
        hard(10, 10, BlackjackEngine.Decision.HIT);
        hard(9, 3, BlackjackEngine.Decision.DOUBLE);
        hard(9, 6, BlackjackEngine.Decision.DOUBLE);
        hard(9, 2, BlackjackEngine.Decision.HIT);
        hard(8, 6, BlackjackEngine.Decision.HIT);

        // Soft totals.
        soft(20, 10, BlackjackEngine.Decision.STAND);
        soft(19, 11, BlackjackEngine.Decision.STAND);
        soft(18, 2, BlackjackEngine.Decision.STAND);
        soft(18, 3, BlackjackEngine.Decision.DOUBLE);
        soft(18, 6, BlackjackEngine.Decision.DOUBLE);
        soft(18, 7, BlackjackEngine.Decision.STAND);
        soft(18, 8, BlackjackEngine.Decision.STAND);
        soft(18, 9, BlackjackEngine.Decision.HIT);
        soft(18, 10, BlackjackEngine.Decision.HIT);
        softNoDouble(18, 4, BlackjackEngine.Decision.STAND);
        soft(17, 3, BlackjackEngine.Decision.DOUBLE);
        soft(17, 2, BlackjackEngine.Decision.HIT);
        soft(16, 4, BlackjackEngine.Decision.DOUBLE);
        soft(16, 3, BlackjackEngine.Decision.HIT);
        soft(15, 6, BlackjackEngine.Decision.DOUBLE);
        soft(14, 5, BlackjackEngine.Decision.DOUBLE);
        soft(14, 4, BlackjackEngine.Decision.HIT);
        soft(13, 6, BlackjackEngine.Decision.DOUBLE);
        soft(13, 7, BlackjackEngine.Decision.HIT);

        // Pairs.
        pair(11, 10, BlackjackEngine.Decision.SPLIT);
        pair(8, 11, BlackjackEngine.Decision.SPLIT);
        pair(10, 6, BlackjackEngine.Decision.STAND);
        pair(9, 6, BlackjackEngine.Decision.SPLIT);
        pair(9, 7, BlackjackEngine.Decision.STAND);
        pair(9, 8, BlackjackEngine.Decision.SPLIT);
        pair(7, 7, BlackjackEngine.Decision.SPLIT);
        pair(7, 8, BlackjackEngine.Decision.HIT);
        pair(6, 2, BlackjackEngine.Decision.SPLIT);
        pair(6, 7, BlackjackEngine.Decision.HIT);
        pair(5, 9, BlackjackEngine.Decision.DOUBLE);
        pair(5, 10, BlackjackEngine.Decision.HIT);
        pair(4, 5, BlackjackEngine.Decision.SPLIT);
        pair(4, 4, BlackjackEngine.Decision.HIT);
        pair(3, 7, BlackjackEngine.Decision.SPLIT);
        pair(2, 8, BlackjackEngine.Decision.HIT);

        // Natural-language parsing.
        parsed("tengo 16 contra 10", BlackjackEngine.Decision.HIT);
        parsed("mano 12 vs 6", BlackjackEngine.Decision.STAND);
        parsed("tengo A 7 contra 9", BlackjackEngine.Decision.HIT);
        parsed("A + 7 vs 4", BlackjackEngine.Decision.DOUBLE);
        parsed("tengo 8 8 contra 10", BlackjackEngine.Decision.SPLIT);
        parsed("10 10 vs 6", BlackjackEngine.Decision.STAND);
        parsed("player 11 dealer 6", BlackjackEngine.Decision.DOUBLE);
        parsed("Your hand 16   Dealer 10", BlackjackEngine.Decision.HIT);
        parsed("Dealer 6   Your hand 13", BlackjackEngine.Decision.STAND);

        BlackjackEngine.Recommendation unknown = BlackjackEngine.recommendFromText("mesa bonita sin cartas legibles");
        require(!unknown.known(), "unknown hand must stay unknown");
        passed++;

        require(BlackjackEngine.isBlackjackContext("Blackjack Dealer Hit Stand"), "blackjack context");
        passed++;
        require(BlackjackEngine.isBlackjackContext("Crupier 10 Pedir Plantarse"), "Spanish blackjack context");
        passed++;
        require(!BlackjackEngine.isBlackjackContext("ajedrez tablero rey"), "chess is not blackjack");
        passed++;

        require(BlackjackEngine.isPracticeContext("Blackjack Practice Mode"), "practice context");
        passed++;
        require(BlackjackEngine.isPracticeContext("modo demo juego gratis"), "free demo context");
        passed++;
        require(!BlackjackEngine.isPracticeContext("casino normal"), "unknown casino should not be assumed practice");
        passed++;

        require(BlackjackEngine.isRealMoneyContext("Saldo disponible $25.00"), "currency should flag real money");
        passed++;
        require(BlackjackEngine.isRealMoneyContext("depositar fondos"), "deposit should flag real money");
        passed++;
        require(!BlackjackEngine.isRealMoneyContext("Practice Mode - Bet 10 points"), "points practice should not auto-flag by word bet alone");
        passed++;

        String[] hit = BlackjackEngine.labelsFor(BlackjackEngine.Decision.HIT);
        require(hit.length >= 2 && hit[0].equals("Pedir"), "hit labels");
        passed++;
        String[] split = BlackjackEngine.labelsFor(BlackjackEngine.Decision.SPLIT);
        require(split.length >= 2 && split[0].equals("Separar"), "split labels");
        passed++;

        System.out.println("PASS " + passed + " blackjack checks");
    }

    private static void hard(int total, int dealer, BlackjackEngine.Decision expected) {
        BlackjackEngine.Recommendation r = BlackjackEngine.recommendHard(total, dealer, true);
        require(r.decision == expected, "hard " + total + " vs " + dealer + " => " + r + " expected " + expected);
        passed++;
    }

    private static void hardNoDouble(int total, int dealer, BlackjackEngine.Decision expected) {
        BlackjackEngine.Recommendation r = BlackjackEngine.recommendHard(total, dealer, false);
        require(r.decision == expected, "hard(no double) " + total + " vs " + dealer + " => " + r);
        passed++;
    }

    private static void soft(int total, int dealer, BlackjackEngine.Decision expected) {
        BlackjackEngine.Recommendation r = BlackjackEngine.recommendSoft(total, dealer, true);
        require(r.decision == expected, "soft " + total + " vs " + dealer + " => " + r + " expected " + expected);
        passed++;
    }

    private static void softNoDouble(int total, int dealer, BlackjackEngine.Decision expected) {
        BlackjackEngine.Recommendation r = BlackjackEngine.recommendSoft(total, dealer, false);
        require(r.decision == expected, "soft(no double) " + total + " vs " + dealer + " => " + r);
        passed++;
    }

    private static void pair(int rank, int dealer, BlackjackEngine.Decision expected) {
        BlackjackEngine.Recommendation r = BlackjackEngine.recommendPair(rank, dealer, true);
        require(r.decision == expected, "pair " + rank + " vs " + dealer + " => " + r + " expected " + expected);
        passed++;
    }

    private static void parsed(String text, BlackjackEngine.Decision expected) {
        BlackjackEngine.Recommendation r = BlackjackEngine.recommendFromText(text);
        require(r.decision == expected, text + " => " + r + " expected " + expected);
        passed++;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
