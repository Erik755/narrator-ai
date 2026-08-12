from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v26: expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")

if "private GeminiRemoteAgent geminiAgent;" not in service:
    service = replace_once(
        service,
        "    private LocalLanguageAgent languageAgent;\n",
        "    private LocalLanguageAgent languageAgent;\n    private GeminiRemoteAgent geminiAgent;\n",
        "Gemini remote agent field",
    )

# v2.3-v2.5 changed details inside this block several times. Replace the bounded
# LocalLanguageAgent initialization rather than relying on one exact interior shape.
start_marker = "        languageAgent = new LocalLanguageAgent"
end_marker = "        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);"
start = service.find(start_marker)
end = service.find(end_marker, start if start >= 0 else 0)
if start < 0 or end < 0 or end <= start:
    raise SystemExit("patch_v26: could not locate bounded LocalLanguageAgent initialization")
end += len(end_marker)
canonical_init = '''        languageAgent = new LocalLanguageAgent(this, new LocalLanguageAgent.StatusListener() {
            @Override public void onStatus(String value) {
                main.post(() -> {
                    if (!speaking && !listening) {
                        voiceStatus = value;
                        passiveOverlay();
                    }
                });
            }
        });
        geminiAgent = new GeminiRemoteAgent(this);
        // Do not download the large local model when Gemini is already configured.
        // If Gemini later fails, the provider chooser starts the local fallback on demand.
        if (!geminiAgent.isConfigured()) languageAgent.start();
        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);'''
service = service[:start] + canonical_init + service[end:]

# Route every existing voice/typed language-agent request through one provider chooser.
# Insert the chooser only after this replacement so its own local fallback is untouched.
call_count = service.count("languageAgent.interpret(")
if call_count < 2:
    raise SystemExit(f"patch_v26: expected at least two languageAgent.interpret calls, found {call_count}")
service = service.replace("languageAgent.interpret(", "interpretWithPreferredAgent(")

marker = "    private String currentUnderstandingContext() {"
if marker not in service:
    raise SystemExit("patch_v26: currentUnderstandingContext marker missing")
chooser = '''    private void interpretWithPreferredAgent(List<String> candidates, float[] confidences,
                                             String screenText, String activeSkill,
                                             LocalLanguageAgent.Callback callback) {
        final String richContext = currentUnderstandingContext();
        if (geminiAgent != null && geminiAgent.isConfigured()) {
            voiceStatus = "pensando con Gemini";
            passiveOverlay();
            geminiAgent.interpret(candidates, confidences, richContext, activeSkill,
                    new LocalLanguageAgent.Callback() {
                @Override public void onResult(LocalLanguageAgent.Result result) {
                    if (result != null && result.getUsedModel()) {
                        callback.onResult(result);
                        return;
                    }
                    // Invalid key, quota/network issue or remote parsing failure: preserve the
                    // request and fall back locally instead of dropping the user's turn.
                    if (languageAgent != null) {
                        languageAgent.start();
                        languageAgent.interpret(candidates, confidences, richContext, activeSkill, callback);
                    } else if (result != null) {
                        callback.onResult(result);
                    }
                }
            });
            return;
        }
        if (languageAgent != null) {
            languageAgent.start();
            languageAgent.interpret(candidates, confidences, richContext, activeSkill, callback);
            return;
        }
        IntentAgent.Result fallback = IntentAgent.interpret(candidates, confidences,
                activeSkill == null ? "" : activeSkill,
                richContext == null ? "" : richContext);
        callback.onResult(new LocalLanguageAgent.Result(
                fallback.type, fallback.argument, "", fallback.confidence, false));
    }

'''
service = service.replace(marker, chooser + marker, 1)

cleanup = "        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }"
if cleanup not in service:
    raise SystemExit("patch_v26: cleanup anchor missing")
if "if (geminiAgent != null) try { geminiAgent.close(); }" not in service:
    service = service.replace(
        cleanup,
        "        if (geminiAgent != null) try { geminiAgent.close(); } catch (Exception ignored) { }\n" + cleanup,
        1,
    )
service = service.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6")
service = service.replace("ScreenObserverPro/2.5", "ScreenObserverPro/2.6")
service = service.replace("Agente local · Android 15/16", "Gemini + respaldo local · Android 15/16")
service_path.write_text(service, encoding="utf-8")


# API key UI. The key is supplied only on the device and GeminiSecretStore encrypts
# it with Android Keystore; no user credential is bundled into the APK.
activity_path = Path("app/src/main/java/com/erik/screenobserver/MainActivityV22.java")
activity = activity_path.read_text(encoding="utf-8")
if "import android.text.InputType;" not in activity:
    activity = activity.replace("import android.provider.Settings;\n",
                                "import android.provider.Settings;\nimport android.text.InputType;\n", 1)
if "import android.widget.EditText;" not in activity:
    activity = activity.replace("import android.widget.Button;\n",
                                "import android.widget.Button;\nimport android.widget.EditText;\n", 1)

insert_after = "        root.addView(aiNote);\n"
if insert_after not in activity:
    raise SystemExit("patch_v26: aiNote insertion anchor missing")
if "GUARDAR CLAVE GEMINI" not in activity:
    gemini_ui = r'''
        TextView geminiLabel = new TextView(this);
        geminiLabel.setText("Gemini API · cerebro principal");
        geminiLabel.setTextSize(15);
        geminiLabel.setPadding(0, 18, 0, 4);
        root.addView(geminiLabel);

        EditText geminiKey = new EditText(this);
        geminiKey.setHint("Pega aquí tu clave de Gemini AI Studio");
        geminiKey.setSingleLine(true);
        geminiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(geminiKey, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView geminiStatus = new TextView(this);
        geminiStatus.setText(GeminiSecretStore.hasKey(this)
                ? "Gemini: configurado · clave cifrada en este teléfono."
                : "Gemini: sin configurar · pega una clave para activarlo.");
        geminiStatus.setTextSize(12);
        geminiStatus.setPadding(0, 5, 0, 8);
        root.addView(geminiStatus);

        Button saveGemini = new Button(this);
        saveGemini.setText("GUARDAR CLAVE GEMINI");
        saveGemini.setOnClickListener(v -> {
            String key = geminiKey.getText() == null ? "" : geminiKey.getText().toString().trim();
            try {
                GeminiSecretStore.save(this, key);
                geminiKey.setText("");
                geminiStatus.setText("Gemini: configurado · clave cifrada con Android Keystore.");
            } catch (Exception e) {
                geminiStatus.setText("No pude guardar la clave. Comprueba que esté completa.");
            }
        });
        root.addView(saveGemini);

        Button clearGemini = new Button(this);
        clearGemini.setText("BORRAR CLAVE GEMINI");
        clearGemini.setOnClickListener(v -> {
            GeminiSecretStore.clear(this);
            geminiKey.setText("");
            geminiStatus.setText("Gemini: sin configurar · se usará la IA local de respaldo.");
        });
        root.addView(clearGemini);
'''
    activity = activity.replace(insert_after, insert_after + gemini_ui, 1)

activity = re.sub(r'title\.setText\("Screen Observer Pro [^"]+"\);',
                  'title.setText("Screen Observer Pro 2.6");', activity, count=1)
activity = activity.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6")
activity = activity.replace("IA conversacional local.", "Gemini + IA local de respaldo.")
activity = activity.replace("Android 15/16 + IA local disponibles.", "Android 15/16 + Gemini disponibles.")
activity = activity.replace("Iniciando asistente e IA local…", "Iniciando asistente…")
activity_path.write_text(activity, encoding="utf-8")

print("patch_v26: GeminiRemoteAgent primary + encrypted on-device key UI applied")
