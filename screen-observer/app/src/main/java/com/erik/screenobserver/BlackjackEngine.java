package com.erik.screenobserver;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java blackjack helper used by the local assistant and unit tests.
 * Strategy assumption: common 4-8 deck basic strategy, dealer stands on soft 17,
 * double after split allowed. It never chooses wager sizes and never places bets.
 */
public final class BlackjackEngine {
    public enum Decision { HIT, STAND, DOUBLE, SPLIT, UNKNOWN }

    public static final class Recommendation {
        public final Decision decision;
        public final int playerTotal;
        public final int dealerUp;
        public final boolean soft;
        public final boolean pair;
        public final String reason;

        Recommendation(Decision decision, int playerTotal, int dealerUp,
                       boolean soft, boolean pair, String reason) {
            this.decision = decision;
            this.playerTotal = playerTotal;
            this.dealerUp = dealerUp;
            this.soft = soft;
            this.pair = pair;
            this.reason = reason == null ? "" : reason;
        }

        public boolean known() { return decision != Decision.UNKNOWN; }

        public String actionLabelEs() {
            switch (decision) {
                case HIT: return "pedir";
                case STAND: return "plantarse";
                case DOUBLE: return "doblar";
                case SPLIT: return "separar";
                default: return "desconocida";
            }
        }

        @Override public String toString() {
            return decision + " player=" + playerTotal + " dealer=" + dealerUp
                    + (soft ? " soft" : "") + (pair ? " pair" : "");
        }
    }

    private static final Pattern SIMPLE_TOTAL = Pattern.compile(
            "(?iu)(?:tengo|mano|mi mano|total|jugador|player)?\\s*(\\d{1,2})\\s*(?:contra|vs|versus|frente a|dealer|crupier)\\s*(a|as|ace|[2-9]|10|j|q|k)");
    private static final Pattern SOFT_HAND = Pattern.compile(
            "(?iu)(?:tengo|mano|mi mano)?\\s*(?:a|as|ace)\\s*[,/+ -]*\\s*([2-9]|10)\\s*(?:contra|vs|versus|frente a)\\s*(a|as|ace|[2-9]|10|j|q|k)");
    private static final Pattern PAIR_HAND = Pattern.compile(
            "(?iu)(?:tengo|mano|mi mano)?\\s*(a|as|ace|[2-9]|10|j|q|k)\\s*[,/+ -]+\\s*(a|as|ace|[2-9]|10|j|q|k)\\s*(?:contra|vs|versus|frente a)\\s*(a|as|ace|[2-9]|10|j|q|k)");
    private static final Pattern DEALER = Pattern.compile(
            "(?iu)(?:dealer|crupier|casa)\\D{0,18}(a|as|ace|[2-9]|10|j|q|k)");
    private static final Pattern PLAYER_TOTAL = Pattern.compile(
            "(?iu)(?:tu mano|your hand|player|jugador|mano|total)\\D{0,18}(\\d{1,2})");

    private BlackjackEngine() { }

    public static Recommendation recommendHard(int total, int dealerUp, boolean canDouble) {
        if (!validDealer(dealerUp) || total < 4 || total > 21)
            return unknown("No pude interpretar la mano.");
        if (total >= 17) return r(Decision.STAND, total, dealerUp, false, false,
                "Con 17 o más, la estrategia básica indica plantarse.");
        if (total >= 13 && total <= 16) {
            if (dealerUp >= 2 && dealerUp <= 6)
                return r(Decision.STAND, total, dealerUp, false, false,
                        "La casa muestra una carta débil de 2 a 6.");
            return r(Decision.HIT, total, dealerUp, false, false,
                    "Contra 7 o más, este total duro normalmente debe pedir.");
        }
        if (total == 12) {
            if (dealerUp >= 4 && dealerUp <= 6)
                return r(Decision.STAND, total, dealerUp, false, false,
                        "Doce se planta contra 4, 5 o 6.");
            return r(Decision.HIT, total, dealerUp, false, false,
                    "Doce pide contra 2, 3 y cartas fuertes.");
        }
        if (total == 11) {
            if (canDouble && dealerUp >= 2 && dealerUp <= 10)
                return r(Decision.DOUBLE, total, dealerUp, false, false,
                        "Once dobla contra 2 a 10 cuando está permitido.");
            return r(Decision.HIT, total, dealerUp, false, false,
                    "Sin un doble favorable, once pide.");
        }
        if (total == 10) {
            if (canDouble && dealerUp >= 2 && dealerUp <= 9)
                return r(Decision.DOUBLE, total, dealerUp, false, false,
                        "Diez dobla contra 2 a 9.");
            return r(Decision.HIT, total, dealerUp, false, false,
                    "Diez pide contra 10 o as.");
        }
        if (total == 9) {
            if (canDouble && dealerUp >= 3 && dealerUp <= 6)
                return r(Decision.DOUBLE, total, dealerUp, false, false,
                        "Nueve dobla contra 3 a 6.");
            return r(Decision.HIT, total, dealerUp, false, false,
                    "Nueve pide fuera del rango de doble favorable.");
        }
        return r(Decision.HIT, total, dealerUp, false, false,
                "Con 8 o menos, la estrategia básica indica pedir.");
    }

    /** softTotal is the best total counting the ace as 11, e.g. A+7 = 18. */
    public static Recommendation recommendSoft(int softTotal, int dealerUp, boolean canDouble) {
        if (!validDealer(dealerUp) || softTotal < 13 || softTotal > 21)
            return unknown("No pude interpretar la mano suave.");
        if (softTotal >= 19)
            return r(Decision.STAND, softTotal, dealerUp, true, false,
                    "Con 19 suave o más, normalmente se planta.");
        if (softTotal == 18) {
            if (dealerUp >= 3 && dealerUp <= 6) {
                return r(canDouble ? Decision.DOUBLE : Decision.STAND,
                        softTotal, dealerUp, true, false,
                        canDouble ? "As-siete dobla contra 3 a 6." : "Sin doble, as-siete se planta contra 3 a 6.");
            }
            if (dealerUp == 2 || dealerUp == 7 || dealerUp == 8)
                return r(Decision.STAND, softTotal, dealerUp, true, false,
                        "As-siete se planta contra 2, 7 u 8.");
            return r(Decision.HIT, softTotal, dealerUp, true, false,
                    "As-siete pide contra 9, 10 o as.");
        }
        if (softTotal == 17) {
            if (canDouble && dealerUp >= 3 && dealerUp <= 6)
                return r(Decision.DOUBLE, softTotal, dealerUp, true, false,
                        "As-seis dobla contra 3 a 6.");
            return r(Decision.HIT, softTotal, dealerUp, true, false,
                    "As-seis pide cuando el doble no corresponde.");
        }
        if (softTotal == 16 || softTotal == 15) {
            if (canDouble && dealerUp >= 4 && dealerUp <= 6)
                return r(Decision.DOUBLE, softTotal, dealerUp, true, false,
                        "Esta mano suave dobla contra 4 a 6.");
            return r(Decision.HIT, softTotal, dealerUp, true, false,
                    "Esta mano suave pide fuera de 4 a 6.");
        }
        if (softTotal == 14 || softTotal == 13) {
            if (canDouble && dealerUp >= 5 && dealerUp <= 6)
                return r(Decision.DOUBLE, softTotal, dealerUp, true, false,
                        "As-dos o as-tres dobla contra 5 o 6.");
            return r(Decision.HIT, softTotal, dealerUp, true, false,
                    "As-dos o as-tres pide fuera de 5 o 6.");
        }
        return r(Decision.HIT, softTotal, dealerUp, true, false, "Pedir.");
    }

    public static Recommendation recommendPair(int pairRank, int dealerUp, boolean canDoubleAfterSplit) {
        if (!validDealer(dealerUp) || pairRank < 2 || pairRank > 11)
            return unknown("No pude interpretar la pareja.");
        int total = pairRank == 11 ? 12 : Math.min(20, pairRank * 2);
        if (pairRank == 11)
            return r(Decision.SPLIT, total, dealerUp, false, true,
                    "Los ases siempre se separan en estrategia básica.");
        if (pairRank == 8)
            return r(Decision.SPLIT, 16, dealerUp, false, true,
                    "Los ochos se separan para evitar jugar un 16 duro.");
        if (pairRank == 10)
            return r(Decision.STAND, 20, dealerUp, false, true,
                    "Veinte se conserva; no se separan dieces.");
        if (pairRank == 9) {
            if ((dealerUp >= 2 && dealerUp <= 6) || dealerUp == 8 || dealerUp == 9)
                return r(Decision.SPLIT, 18, dealerUp, false, true,
                        "Nueves se separan contra 2 a 6, 8 o 9.");
            return r(Decision.STAND, 18, dealerUp, false, true,
                    "Nueves se plantan contra 7, 10 o as.");
        }
        if (pairRank == 7) {
            if (dealerUp >= 2 && dealerUp <= 7)
                return r(Decision.SPLIT, 14, dealerUp, false, true,
                        "Sietes se separan contra 2 a 7.");
            return r(Decision.HIT, 14, dealerUp, false, true,
                    "Sietes piden contra 8 o más.");
        }
        if (pairRank == 6) {
            if (dealerUp >= 2 && dealerUp <= 6 && canDoubleAfterSplit)
                return r(Decision.SPLIT, 12, dealerUp, false, true,
                        "Con DAS, seises se separan contra 2 a 6.");
            if (dealerUp >= 3 && dealerUp <= 6)
                return r(Decision.SPLIT, 12, dealerUp, false, true,
                        "Seises se separan contra 3 a 6.");
            return r(Decision.HIT, 12, dealerUp, false, true, "Seises piden aquí.");
        }
        if (pairRank == 5)
            return recommendHard(10, dealerUp, true);
        if (pairRank == 4) {
            if (canDoubleAfterSplit && (dealerUp == 5 || dealerUp == 6))
                return r(Decision.SPLIT, 8, dealerUp, false, true,
                        "Con DAS, cuatros se separan contra 5 o 6.");
            return r(Decision.HIT, 8, dealerUp, false, true, "Cuatros normalmente piden.");
        }
        if (pairRank == 3 || pairRank == 2) {
            int low = canDoubleAfterSplit ? 2 : 4;
            if (dealerUp >= low && dealerUp <= 7)
                return r(Decision.SPLIT, pairRank * 2, dealerUp, false, true,
                        "Parejas bajas se separan contra cartas débiles cuando las reglas lo favorecen.");
            return r(Decision.HIT, pairRank * 2, dealerUp, false, true,
                    "Parejas bajas piden fuera del rango de separación.");
        }
        return recommendHard(total, dealerUp, true);
    }

    public static Recommendation recommendFromText(String text) {
        if (text == null || text.trim().isEmpty()) return unknown("No hay datos de la mano.");
        String raw = text.replace('–', '-').replace('—', '-');

        Matcher pair = PAIR_HAND.matcher(raw);
        if (pair.find()) {
            int a = rank(pair.group(1));
            int b = rank(pair.group(2));
            int d = rank(pair.group(3));
            if (a > 0 && a == b && d > 0) return recommendPair(a, d, true);
        }

        Matcher soft = SOFT_HAND.matcher(raw);
        if (soft.find()) {
            int second = rank(soft.group(1));
            int d = rank(soft.group(2));
            if (second >= 2 && second <= 10 && d > 0)
                return recommendSoft(11 + second, d, true);
        }

        Matcher simple = SIMPLE_TOTAL.matcher(raw);
        if (simple.find()) {
            int total = safeInt(simple.group(1));
            int d = rank(simple.group(2));
            if (total >= 4 && total <= 21 && d > 0) return recommendHard(total, d, true);
        }

        // OCR fallback: dealer label plus player total anywhere in the same screen text.
        Matcher dm = DEALER.matcher(raw);
        Matcher pm = PLAYER_TOTAL.matcher(raw);
        if (dm.find() && pm.find()) {
            int d = rank(dm.group(1));
            int total = safeInt(pm.group(1));
            if (d > 0 && total >= 4 && total <= 21) return recommendHard(total, d, true);
        }

        return unknown("No pude extraer el total del jugador y la carta visible del dealer.");
    }

    public static boolean isBlackjackContext(String text) {
        String n = normalize(text);
        return n.contains("blackjack") || n.contains("black jack")
                || (n.contains("dealer") && (n.contains("hit") || n.contains("stand") || n.contains("double") || n.contains("split")))
                || (n.contains("crupier") && (n.contains("pedir") || n.contains("plantarse") || n.contains("doblar") || n.contains("separar")));
    }

    public static boolean isPracticeContext(String text) {
        String n = normalize(text);
        return n.contains("practice") || n.contains("practica") || n.contains("entrenamiento")
                || n.contains("demo") || n.contains("free play") || n.contains("juego gratis")
                || n.contains("modo gratis") || n.contains("sin dinero real");
    }

    public static boolean isRealMoneyContext(String text) {
        if (text == null) return false;
        String n = normalize(text);
        boolean explicitCurrency = text.contains("$") || text.contains("€") || text.contains("£")
                || n.contains(" mxn") || n.contains(" usd") || n.contains(" eur");
        boolean moneyWords = n.contains("dinero real") || n.contains("real money")
                || n.contains("depositar") || n.contains("deposit") || n.contains("retirar")
                || n.contains("withdraw") || n.contains("cash balance") || n.contains("saldo disponible")
                || n.contains("wager real") || n.contains("apuesta real");
        return explicitCurrency || moneyWords;
    }

    public static String[] labelsFor(Decision decision) {
        switch (decision) {
            case HIT: return new String[]{"Pedir", "Hit", "Carta", "Otra carta"};
            case STAND: return new String[]{"Plantarse", "Stand", "Me planto", "Quedarse"};
            case DOUBLE: return new String[]{"Doblar", "Double", "Double down", "Doble"};
            case SPLIT: return new String[]{"Separar", "Split", "Dividir"};
            default: return new String[0];
        }
    }

    private static Recommendation r(Decision d, int total, int dealer, boolean soft, boolean pair, String reason) {
        return new Recommendation(d, total, dealer, soft, pair, reason);
    }

    private static Recommendation unknown(String reason) {
        return new Recommendation(Decision.UNKNOWN, 0, 0, false, false, reason);
    }

    private static boolean validDealer(int d) { return d >= 2 && d <= 11; }

    private static int rank(String value) {
        if (value == null) return 0;
        String n = normalize(value);
        if (n.equals("a") || n.equals("as") || n.equals("ace")) return 11;
        if (n.equals("j") || n.equals("q") || n.equals("k")) return 10;
        return safeInt(n);
    }

    private static int safeInt(String value) {
        try { return Integer.parseInt(value.trim()); }
        catch (Exception e) { return 0; }
    }

    private static String normalize(String value) {
        return TextNormalizer.normalize(value == null ? "" : value).toLowerCase(Locale.ROOT);
    }
}
