from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/MainActivityV22.java")
text = path.read_text(encoding="utf-8")

if "import android.text.InputType;" not in text:
    text = text.replace("import android.provider.Settings;\n",
                        "import android.provider.Settings;\nimport android.text.InputType;\n", 1)
if "import android.widget.EditText;" not in text:
    text = text.replace("import android.widget.Button;\n",
                        "import android.widget.Button;\nimport android.widget.EditText;\n", 1)

anchor = "        root.addView(aiNote);\n"
if text.count(anchor) != 1:
    raise SystemExit(f"patch_v261_launcher_fix: expected one aiNote anchor, found {text.count(anchor)}")

if "GUARDAR / CAMBIAR CLAVE GEMINI" not in text:
    ui = r'''

        TextView geminiInfo = new TextView(this);
        geminiInfo.setText(GeminiRemoteAgent.hasApiKey(this)
                ? "Gemini 3.6 Flash: configurado · clave cifrada en este teléfono."
                : "Gemini 3.6 Flash: pega tu API key para usarlo como cerebro principal.");
        geminiInfo.setTextSize(13);
        geminiInfo.setPadding(0, 14, 0, 4);
        root.addView(geminiInfo);

        EditText geminiKey = new EditText(this);
        geminiKey.setHint(GeminiRemoteAgent.hasApiKey(this)
                ? "Clave guardada · pega otra para reemplazarla" : "Gemini API key");
        geminiKey.setSingleLine(true);
        geminiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(geminiKey, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button saveGemini = new Button(this);
        saveGemini.setText("GUARDAR / CAMBIAR CLAVE GEMINI");
        saveGemini.setOnClickListener(v -> {
            String key = geminiKey.getText() == null ? "" : geminiKey.getText().toString().trim();
            if (key.isEmpty()) {
                geminiInfo.setText("Pega primero una API key de Gemini.");
                return;
            }
            GeminiRemoteAgent.saveApiKey(this, key);
            if (GeminiRemoteAgent.hasApiKey(this)) {
                geminiKey.setText("");
                geminiInfo.setText("Gemini 3.6 Flash configurado · clave cifrada con Android Keystore. Reinicia el asistente si ya estaba activo.");
            } else {
                geminiInfo.setText("No pude guardar la clave. Comprueba que esté completa.");
            }
        });
        root.addView(saveGemini);

        Button clearGemini = new Button(this);
        clearGemini.setText("BORRAR CLAVE GEMINI");
        clearGemini.setOnClickListener(v -> {
            GeminiRemoteAgent.clearApiKey(this);
            geminiKey.setText("");
            geminiInfo.setText("Gemini desactivado · se usará el intérprete/IA local de respaldo.");
        });
        root.addView(clearGemini);
'''
    text = text.replace(anchor, anchor + ui, 1)

for old in ["Screen Observer Pro 2.2", "Screen Observer Pro 2.3", "Screen Observer Pro 2.4", "Screen Observer Pro 2.5", "Screen Observer Pro 2.6"]:
    text = text.replace(old, "Screen Observer Pro 2.6.1")

old_note = "IA: Qwen3 0.6B se descarga automáticamente una sola vez y se ejecuta en el teléfono con LiteRT-LM. No requiere una clave ni una API de pago. Espera la señal y el indicador “🟢 listo · habla ahora” antes de empezar a hablar."
new_note = "IA: Gemini 3.6 Flash es el cerebro principal cuando configuras una clave. La clave se cifra con Android Keystore y no se incluye en el APK ni en GitHub. Sin Gemini, se conserva el respaldo local. Espera ‘🟢 listo · habla ahora’ antes de hablar."
text = text.replace(old_note, new_note)
text = text.replace("IA conversacional local.", "Gemini 3.6 Flash + respaldo local.")
text = text.replace("Android 15/16 + IA local disponibles.", "Android 15/16 + Gemini/respaldo local disponibles.")
text = text.replace("Iniciando asistente e IA local…", "Iniciando asistente…")

path.write_text(text, encoding="utf-8")
print("patch_v261_launcher_fix: Gemini controls added to actual launcher MainActivityV22")
