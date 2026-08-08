from pathlib import Path

path = Path("patch_v25.py")
text = path.read_text(encoding="utf-8")
old = '    "            case OPEN_APP:\\n                silent(AndroidAppController.launchAppByLabel(this, r.argument)",'
new = '    "            case OPEN_APP:",'
count = text.count(old)
if count != 1:
    raise SystemExit(f"patch_v25_anchor_fix: expected one OPEN_APP patch anchor, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("patch_v25_anchor_fix: OPEN_APP anchor made stable")
