from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/IntentAgent.java")
text = path.read_text(encoding="utf-8")
old = '''    private static boolean startsCommand(String normalized, String... options) {
        String value = normalize(normalized);
        for (String option : options) {
            String q = normalize(option);
            if (value.equals(q) || value.startsWith(q + " ")) return true;
        }
        return false;
    }
'''
new = '''    private static boolean startsCommand(String normalized, String... options) {
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
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"patch_v25_intent_guard_fix: expected one startsCommand helper, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("patch_v25_intent_guard_fix: explanatory command mentions remain conversational")
