from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/BlackjackEngine.java")
text = path.read_text(encoding="utf-8")

old = '''    private static final Pattern DEALER = Pattern.compile(
            "(?iu)(?:^|[\\\\r\\\\n|])\\\\s*(?:dealer|crupier|casa)\\\\s*[:=\\\\-]?\\\\s*(a|as|ace|[2-9]|10|j|q|k)\\\\b");
    private static final Pattern PLAYER_TOTAL = Pattern.compile(
            "(?iu)(?:^|[\\\\r\\\\n|])\\\\s*(?:tu mano|your hand|player|jugador|mano|total)\\\\s*[:=\\\\-]?\\\\s*(\\\\d{1,2})\\\\b");'''
new = '''    private static final Pattern DEALER = Pattern.compile(
            "(?iu)(?:dealer|crupier|casa)\\\\s*[:=\\\\-]?\\\\s*(a|as|ace|[2-9]|10|j|q|k)\\\\b");
    private static final Pattern PLAYER_TOTAL = Pattern.compile(
            "(?iu)(?:tu mano|your hand|player|jugador|mano|total)\\\\s*[:=\\\\-]?\\\\s*(\\\\d{1,2})\\\\b");'''

count = text.count(old)
if count != 1:
    raise SystemExit(f"patch_v25_blackjack_ocr_fix: expected one OCR pattern block, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("patch_v25_blackjack_ocr_fix: safe same-line dealer/player labels supported")
