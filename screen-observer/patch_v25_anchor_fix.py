from pathlib import Path

path = Path("patch_v25.py")
text = path.read_text(encoding="utf-8")

old_anchor = '    "            case OPEN_APP:\\n                silent(AndroidAppController.launchAppByLabel(this, r.argument)",'
new_anchor = '    "            case OPEN_APP:",'
count = text.count(old_anchor)
if count != 1:
    raise SystemExit(f"patch_v25_anchor_fix: expected one OPEN_APP patch anchor, found {count}")
text = text.replace(old_anchor, new_anchor, 1)

# Since the anchor now consists only of the switch label, the replacement must also stop
# at that label. The existing v2.4 OPEN_APP implementation remains immediately after it.
old_tail = '            case OPEN_APP:\n                silent(AndroidAppController.launchAppByLabel(this, r.argument)\'\'\','
new_tail = '            case OPEN_APP:\'\'\','
count = text.count(old_tail)
if count != 1:
    raise SystemExit(f"patch_v25_anchor_fix: expected one OPEN_APP replacement tail, found {count}")
text = text.replace(old_tail, new_tail, 1)

path.write_text(text, encoding="utf-8")
print("patch_v25_anchor_fix: OPEN_APP insertion anchor and replacement tail stabilized")
