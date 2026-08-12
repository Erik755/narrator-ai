from pathlib import Path

# Keep the workflow entrypoint stable while the implementation lives in patches
# written against the fully generated v2.5 runtime.
exec(Path("patch_v26_fixed.py").read_text(encoding="utf-8"), {"__name__": "__main__"})
exec(Path("patch_v26_secure_key.py").read_text(encoding="utf-8"), {"__name__": "__main__"})
