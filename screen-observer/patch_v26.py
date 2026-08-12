from pathlib import Path

# Keep the workflow entrypoint stable while the implementation lives in the
# safety-compatible patch written against the fully generated v2.5 runtime.
# GeminiRemoteAgent now delegates key storage directly to GeminiSecretStore,
# so no second source-rewriting key patch is needed.
exec(Path("patch_v26_fixed.py").read_text(encoding="utf-8"), {"__name__": "__main__"})
