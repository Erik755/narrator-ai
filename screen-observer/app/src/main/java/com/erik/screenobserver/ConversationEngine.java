package com.erik.screenobserver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Small local conversational layer used when a turn is not a direct device command. */
public final class ConversationEngine {
    public static final class Memory {
        private String lastUser = "";
        private String lastReply = "";
        private String lastIntent = "";
        private String lastArgument = "";
        private int turns = 0;

        public void rememberUser(String user, String intent, String argument) {
            lastUser = user == null ? "" : user.trim();
            lastIntent = intent == null ? "" : intent;
            lastArgument = argument == null ? "" : argument.trim();
            turns++;
        }

        public void rememberReply(String reply) {
            lastReply = reply == null ? "" : reply.trim();
        }

        public String lastUser() { return lastUser; }
        public String lastReply() { return lastReply; }
        public String lastIntent() { return lastIntent; }
        public String lastArgument() { return lastArgument; }
        public int turns() { return turns; }
    }

    private ConversationEngine() { }

    /** Produces a concise reply without parroting the user's utterance. */
    public static String reply(String request, String activeSkill, String skillNotes,
                               String screenText, Memory memory) {
        String n = TextNormalizer.normalize(request);
        if (n.isEmpty()) return "No alcancé a entender la petición. Inténtalo otra vez cuando oigas la señal.";

        if (hasWord(n, "hola") || n.equals("buenas") || n.startsWith("buenos dias")
                || n.startsWith("buenas tardes") || n.startsWith("buenas noches")) {
            return "Hola. ¿Qué necesitas?";
        }
        if (n.equals("gracias") || n.startsWith("muchas gracias") || n.startsWith("te agradezco")) {
            return "De nada.";
        }
        if (n.equals("ok") || n.equals("okay") || n.equals("vale") || n.equals("de acuerdo")
                || n.equals("esta bien") || n.equals("perfecto")) {
            return "Perfecto.";
        }
        if (n.equals("espera") || n.equals("esperame") || n.equals("un momento") || n.equals("aguarda")) {
            return "Claro.";
        }
        if (n.equals("si") || n.equals("sí") || n.equals("claro") || n.equals("adelante")) {
            if (memory != null && !memory.lastReply().isEmpty() && looksLikeQuestion(memory.lastReply())) {
                return "De acuerdo. Continúa.";
            }
            return "De acuerdo.";
        }
        if (n.equals("no") || n.equals("no gracias")) return "De acuerdo.";

        if (isFollowUp(n) && memory != null && memory.turns() > 0) {
            if (!memory.lastArgument().isEmpty()) {
                return "Puedo continuar desde el último paso y comprobar cómo quedó la pantalla. Dime si quieres que siga o qué resultado buscas ahora.";
            }
            return "Sí, sigo el contexto. Dime qué quieres hacer a continuación.";
        }

        String selected = bestRelevantSentence(request, skillNotes);
        if (!selected.isEmpty() && looksLikeQuestion(n)) return selected;

        if (mentionsCurrentContext(n) && screenText != null && !screenText.trim().isEmpty()) {
            return "Tengo el contexto de la pantalla actual. Puedes preguntarme qué conviene hacer o pedirme una acción concreta.";
        }

        if (activeSkill != null && !activeSkill.trim().isEmpty() && looksLikeQuestion(n)) {
            return "Puedo responder usando la habilidad " + activeSkill.trim()
                    + ". Hazme la pregunta concreta y mantendré ese contexto en los siguientes turnos.";
        }

        return "Claro. Dime el resultado que quieres conseguir y puedo seguir la conversación o actuar sobre la pantalla cuando haga falta.";
    }

    public static boolean isFollowUp(String normalized) {
        String n = TextNormalizer.normalize(normalized);
        return n.equals("y ahora") || n.equals("ahora que") || n.equals("que sigue")
                || n.equals("y despues") || n.equals("despues que") || n.equals("continua con eso")
                || n.equals("sigue con eso") || n.equals("y luego") || n.equals("entonces que hago")
                || n.equals("que hago ahora");
    }

    public static boolean containsEcho(String reply, String request) {
        String r = TextNormalizer.normalize(reply);
        String q = TextNormalizer.normalize(request);
        if (q.length() < 12 || r.isEmpty()) return false;
        return r.contains(q);
    }

    private static boolean looksLikeQuestion(String value) {
        String n = TextNormalizer.normalize(value);
        return n.startsWith("que ") || n.startsWith("como ") || n.startsWith("cuando ")
                || n.startsWith("donde ") || n.startsWith("cual ") || n.startsWith("cuales ")
                || n.startsWith("por que ") || n.startsWith("para que ") || n.startsWith("puedo ")
                || n.startsWith("debo ") || n.startsWith("conviene ") || value.contains("?");
    }

    private static boolean mentionsCurrentContext(String n) {
        return hasWord(n, "esto") || hasWord(n, "aqui") || hasWord(n, "pantalla")
                || hasWord(n, "ahora") || n.contains("lo que veo") || n.contains("lo que hay");
    }

    private static String bestRelevantSentence(String request, String notes) {
        if (notes == null || notes.trim().isEmpty()) return "";
        Set<String> query = usefulWords(request);
        if (query.isEmpty()) return "";
        String[] sentences = notes.replace('\n', ' ').split("(?<=[.!?])\\s+");
        String best = "";
        int bestScore = 0;
        for (String sentence : sentences) {
            if (sentence.trim().length() < 12) continue;
            Set<String> words = usefulWords(sentence);
            int score = 0;
            for (String w : query) if (words.contains(w)) score++;
            if (score > bestScore) {
                bestScore = score;
                best = sentence.trim();
            }
        }
        if (bestScore == 0) return "";
        return best.length() > 360 ? best.substring(0, 360).trim() + "…" : best;
    }

    private static Set<String> usefulWords(String value) {
        String n = TextNormalizer.normalize(value);
        Set<String> stop = new HashSet<>();
        String[] common = {"que","como","cuando","donde","cual","cuales","para","por","una","uno","unos","unas",
                "del","las","los","con","sin","sobre","esto","esta","este","ese","esa","hay","quiero","puedo",
                "debo","seria","tengo","tienes","dime","haz","hacer","ahora","entonces"};
        for (String s : common) stop.add(s);
        Set<String> out = new HashSet<>();
        for (String w : n.split(" ")) if (w.length() >= 3 && !stop.contains(w)) out.add(w);
        return out;
    }

    private static boolean hasWord(String normalized, String word) {
        String n = " " + TextNormalizer.normalize(normalized) + " ";
        String w = " " + TextNormalizer.normalize(word) + " ";
        return n.contains(w);
    }
}
