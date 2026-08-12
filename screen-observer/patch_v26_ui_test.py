from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/MainActivityV22.java")
text = path.read_text(encoding="utf-8")

if "PROBAR CONEXIÓN GEMINI" in text:
    print("patch_v26_ui_test: connection-test UI already present")
else:
    anchor = '''        Button clearGemini = new Button(this);\n        clearGemini.setText("BORRAR CLAVE GEMINI");'''
    if anchor not in text:
        raise SystemExit("patch_v26_ui_test: clear-key UI anchor missing")

    block = '''        Button testGemini = new Button(this);\n        testGemini.setText("PROBAR CONEXIÓN GEMINI");\n        testGemini.setOnClickListener(v -> {\n            if (!GeminiSecretStore.hasKey(this)) {\n                geminiStatus.setText("Primero guarda una clave de Gemini.");\n                return;\n            }\n            geminiStatus.setText("Probando conexión real con Gemini…");\n            GeminiRemoteAgent testAgent = new GeminiRemoteAgent(this);\n            testAgent.testConnection(new GeminiRemoteAgent.ConnectionCallback() {\n                @Override public void onSuccess(String model) {\n                    runOnUiThread(() -> {\n                        geminiStatus.setText("Gemini conectado correctamente · " + model);\n                        testAgent.close();\n                    });\n                }\n\n                @Override public void onError(String message) {\n                    runOnUiThread(() -> {\n                        geminiStatus.setText("Falló Gemini: " + message);\n                        testAgent.close();\n                    });\n                }\n            });\n        });\n        root.addView(testGemini);\n\n'''
    text = text.replace(anchor, block + anchor, 1)
    path.write_text(text, encoding="utf-8")
    print("patch_v26_ui_test: real Gemini connection-test UI applied")
