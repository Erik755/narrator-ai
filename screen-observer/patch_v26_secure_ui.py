from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/MainActivity.java")
text = path.read_text(encoding="utf-8")

if "import android.widget.ScrollView;" not in text:
    text = text.replace("import android.widget.LinearLayout;\n",
                        "import android.widget.LinearLayout;\nimport android.widget.ScrollView;\n", 1)

text = text.replace("Gemini 2.5 Flash", "Gemini 3.6 Flash")
text = text.replace("GUARDAR / CAMBIAR CLAVE GEMINI", "GUARDAR CLAVE GEMINI 3.6")

anchor = '''        root.addView(saveGemini);\n\n        accessibilityButton = new Button(this);'''
if anchor not in text:
    raise SystemExit("patch_v26_secure_ui: Gemini save-button anchor missing")

extra = '''        root.addView(saveGemini);\n\n        Button testGemini = new Button(this);\n        testGemini.setText("PROBAR CONEXIÓN GEMINI");\n        testGemini.setOnClickListener(v -> {\n            if (!GeminiRemoteAgent.hasApiKey(this)) {\n                geminiInfo.setText("Primero guarda una clave de Gemini.");\n                return;\n            }\n            geminiInfo.setText("Probando conexión real con Gemini…");\n            GeminiRemoteAgent.testConfigured(this, (ok, message) ->\n                    runOnUiThread(() -> geminiInfo.setText(message)));\n        });\n        root.addView(testGemini);\n\n        Button clearGemini = new Button(this);\n        clearGemini.setText("BORRAR CLAVE GEMINI");\n        clearGemini.setOnClickListener(v -> {\n            GeminiRemoteAgent.clearApiKey(this);\n            geminiKey.setText("");\n            geminiInfo.setText("Clave Gemini eliminada. Se usará la IA local de respaldo.");\n        });\n        root.addView(clearGemini);\n\n        accessibilityButton = new Button(this);'''
text = text.replace(anchor, extra, 1)

if "ScrollView scroll = new ScrollView(this);" not in text:
    old = "        setContentView(root);"
    new = '''        ScrollView scroll = new ScrollView(this);\n        scroll.addView(root);\n        setContentView(scroll);'''
    if old not in text:
        raise SystemExit("patch_v26_secure_ui: setContentView anchor missing")
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("patch_v26_secure_ui: Gemini 3.6 save/test/clear UI + scrolling applied")
