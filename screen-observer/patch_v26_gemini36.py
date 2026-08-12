from pathlib import Path

# This patch runs after patch_v26.py has generated the hybrid runtime.

llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")
llm = llm.replace("Gemini 2.5 Flash configurado", "Gemini 3.6 Flash configurado")
llm = llm.replace("Gemini 2.5 Flash listo", "Gemini 3.6 Flash listo")
llm_path.write_text(llm, encoding="utf-8")

main_path = Path("app/src/main/java/com/erik/screenobserver/MainActivity.java")
main = main_path.read_text(encoding="utf-8")
main = main.replace("IA principal: Gemini 2.5 Flash · clave configurada",
                    "IA principal: Gemini 3.6 Flash · clave cifrada y configurada")
main = main.replace("IA principal opcional: Gemini 2.5 Flash. Pega tu API key para mejorar comprensión y conversación.",
                    "IA principal opcional: Gemini 3.6 Flash. Pega tu API key para mejorar comprensión y conversación.")
main = main.replace("Gemini 2.5 Flash configurado. Reinicia el asistente para usarlo como IA principal.",
                    "Gemini 3.6 Flash configurado. Las próximas peticiones usarán Gemini como IA principal.")

anchor = '''        root.addView(saveGemini);

        accessibilityButton = new Button(this);'''
if anchor not in main:
    raise SystemExit("patch_v26_gemini36: Gemini save-button anchor missing")
replacement = '''        root.addView(saveGemini);

        Button testGemini = new Button(this);
        testGemini.setText("PROBAR CONEXIÓN GEMINI");
        testGemini.setOnClickListener(v -> {
            geminiInfo.setText("Probando Gemini 3.6 Flash…");
            GeminiRemoteAgent.testConfigured(this, (ok, message) -> runOnUiThread(() -> {
                geminiInfo.setText(message);
            }));
        });
        root.addView(testGemini);

        accessibilityButton = new Button(this);'''
main = main.replace(anchor, replacement, 1)
main_path.write_text(main, encoding="utf-8")

print("patch_v26_gemini36: Gemini 3.6 labels and live connection test applied")
