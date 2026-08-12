from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v27: expected one {label}, found {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# Local language router: Gemini 3.6 labels. Deterministic Android actions remain
# local and Gemini remains primary for natural/ambiguous language when configured.
# -----------------------------------------------------------------------------
llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")
llm = llm.replace("Gemini 2.5 Flash configurado", "Gemini 3.6 Flash configurado")
llm = llm.replace("Gemini 2.5 Flash listo", "Gemini 3.6 Flash listo")
llm = llm.replace("ScreenObserverPro/2.6", "ScreenObserverPro/2.7")
llm_path.write_text(llm, encoding="utf-8")


# -----------------------------------------------------------------------------
# Main activity: explicit Save/Test/Clear controls. The actual key bytes are stored
# by GeminiKeyStore through GeminiRemoteAgent.saveApiKey().
# -----------------------------------------------------------------------------
main_path = Path("app/src/main/java/com/erik/screenobserver/MainActivity.java")
main = main_path.read_text(encoding="utf-8")
main = main.replace("Gemini 2.5 Flash", "Gemini 3.6 Flash")

old_save = '''        Button saveGemini = new Button(this);
        saveGemini.setText("GUARDAR / CAMBIAR CLAVE GEMINI");
        saveGemini.setOnClickListener(v -> {
            String key = geminiKey.getText() == null ? "" : geminiKey.getText().toString().trim();
            if (key.isEmpty()) {
                GeminiRemoteAgent.saveApiKey(this, "");
                geminiInfo.setText("Clave Gemini eliminada. Se usará la IA local/respaldo.");
            } else {
                GeminiRemoteAgent.saveApiKey(this, key);
                geminiKey.setText("");
                geminiInfo.setText("Gemini 3.6 Flash configurado. Reinicia el asistente para usarlo como IA principal.");
            }
        });
        root.addView(saveGemini);
'''
new_save = '''        Button saveGemini = new Button(this);
        saveGemini.setText("GUARDAR CLAVE GEMINI");
        saveGemini.setOnClickListener(v -> {
            String key = geminiKey.getText() == null ? "" : geminiKey.getText().toString().trim();
            if (key.isEmpty()) {
                geminiInfo.setText("Pega una Gemini API key antes de guardarla.");
                return;
            }
            GeminiRemoteAgent.saveApiKey(this, key);
            geminiKey.setText("");
            geminiKey.setHint("Clave cifrada y guardada · pega otra para reemplazarla");
            geminiInfo.setText("Gemini 3.6 Flash configurado. Pulsa PROBAR GEMINI para verificar la conexión.");
        });
        root.addView(saveGemini);

        Button testGemini = new Button(this);
        testGemini.setText("PROBAR GEMINI 3.6 FLASH");
        testGemini.setOnClickListener(v -> {
            testGemini.setEnabled(false);
            geminiInfo.setText("Probando conexión con Gemini 3.6 Flash…");
            GeminiRemoteAgent.testConfigured(this, (ok, message) -> runOnUiThread(() -> {
                testGemini.setEnabled(true);
                geminiInfo.setText(message);
            }));
        });
        root.addView(testGemini);

        Button clearGemini = new Button(this);
        clearGemini.setText("BORRAR CLAVE GEMINI");
        clearGemini.setOnClickListener(v -> {
            GeminiRemoteAgent.saveApiKey(this, "");
            geminiKey.setText("");
            geminiKey.setHint("Gemini API key");
            geminiInfo.setText("Clave Gemini borrada. El asistente usará sus respaldos locales.");
        });
        root.addView(clearGemini);
'''
main = replace_once(main, old_save, new_save, "Gemini Save/Test/Clear UI")
main = main.replace("Screen Observer Pro 2.6", "Screen Observer Pro 2.7")
main_path.write_text(main, encoding="utf-8")


# Runtime branding.
service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")
service = service.replace("Screen Observer Pro 2.6", "Screen Observer Pro 2.7")
service = service.replace("ScreenObserverPro/2.6", "ScreenObserverPro/2.7")
service_path.write_text(service, encoding="utf-8")

print("patch_v27: Gemini 3.6 UI/test controls and v2.7 branding applied")
