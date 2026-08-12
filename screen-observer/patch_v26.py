from pathlib import Path
import re


def must_replace(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v26: expected one {label}, found {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# Screen service: Gemini first, local Qwen fallback. Do not download local model
# eagerly when a Gemini key is already configured.
# -----------------------------------------------------------------------------
service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")

service = must_replace(
    service,
    "    private LocalLanguageAgent languageAgent;\n",
    "    private LocalLanguageAgent languageAgent;\n    private GeminiRemoteAgent geminiAgent;\n",
    "Gemini agent field",
)

service = must_replace(
    service,
    "        languageAgent.start();\n        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);",
    "        geminiAgent = new GeminiRemoteAgent(this);\n"
    "        if (!geminiAgent.isConfigured()) languageAgent.start();\n"
    "        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);",
    "Gemini initialization",
)

# Route both voice and typed LLM calls through the provider chooser. This is done
# before adding the chooser itself so its internal local fallback is not rewritten.
call_count = service.count("languageAgent.interpret(")
if call_count < 2:
    raise SystemExit(f"patch_v26: expected at least two languageAgent.interpret calls, found {call_count}")
service = service.replace("languageAgent.interpret(", "interpretWithPreferredAgent(")

marker = "    private String currentUnderstandingContext() {"
if marker not in service:
    raise SystemExit("patch_v26: currentUnderstandingContext marker missing")
chooser = r'''    private void interpretWithPreferredAgent(List<String> candidates, float[] confidences,
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
                    // Remote failure/quota/key issue: fall back without losing the request.
                    if (languageAgent != null) {
                        languageAgent.start();
                        languageAgent.interpret(candidates, confidences, richContext, activeSkill, callback);
                    } else {
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

# The global replacement above also rewrote calls inside the newly generated source
# only if an older patch inserted a helper before this point; guard against recursion.
chooser_start = service.find("private void interpretWithPreferredAgent")
chooser_end = service.find(marker, chooser_start)
chooser_text = service[chooser_start:chooser_end]
chooser_text = chooser_text.replace("interpretWithPreferredAgent(candidates, confidences, richContext, activeSkill, callback);",
                                    "languageAgent.interpret(candidates, confidences, richContext, activeSkill, callback);")
service = service[:chooser_start] + chooser_text + service[chooser_end:]

# Close the remote executor and update visible runtime wording.
service = must_replace(
    service,
    "        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }\n",
    "        if (geminiAgent != null) try { geminiAgent.close(); } catch (Exception ignored) { }\n"
    "        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }\n",
    "Gemini cleanup",
)
service = service.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6")
service = service.replace("ScreenObserverPro/2.5", "ScreenObserverPro/2.6")
service = service.replace("Agente local · Android 15/16", "Gemini + respaldo local · Android 15/16")
service_path.write_text(service, encoding="utf-8")


# -----------------------------------------------------------------------------
# Main activity: runtime API-key entry. Key is encrypted by GeminiSecretStore and
# never committed/bundled. This is intended for the user's own sideloaded phone.
# -----------------------------------------------------------------------------
activity_path = Path("app/src/main/java/com/erik/screenobserver/MainActivityV22.java")
activity = activity_path.read_text(encoding="utf-8")

if "import android.text.InputType;" not in activity:
    activity = activity.replace("import android.provider.Settings;\n",
                                "import android.provider.Settings;\nimport android.text.InputType;\n", 1)
if "import android.widget.EditText;" not in activity:
    activity = activity.replace("import android.widget.Button;\n",
                                "import android.widget.Button;\nimport android.widget.EditText;\n", 1)

activity = must_replace(
    activity,
    "    private TextView status, accessStatus, skillStatus;\n",
    "    private TextView status, accessStatus, skillStatus, geminiStatus;\n",
    "Gemini status field",
)

insert_after = "        root.addView(aiNote);\n"
if insert_after not in activity:
    raise SystemExit("patch_v26: aiNote insertion anchor missing")
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

        Button saveGemini = new Button(this);
        saveGemini.setText("GUARDAR CLAVE GEMINI");
        saveGemini.setOnClickListener(v -> {
            String key = geminiKey.getText() == null ? "" : geminiKey.getText().toString().trim();
            try {
                GeminiSecretStore.save(this, key);
                geminiKey.setText("");
                geminiStatus.setText("Gemini: configurado · la clave está cifrada en este teléfono.");
            } catch (Exception e) {
                geminiStatus.setText("Gemini: no pude guardar esa clave. Verifica que esté completa.");
            }
        });
        root.addView(saveGemini);

        Button clearGemini = new Button(this);
        clearGemini.setText("BORRAR CLAVE GEMINI");
        clearGemini.setOnClickListener(v -> {
            GeminiSecretStore.clear(this);
            geminiKey.setText("");
            geminiStatus.setText("Gemini: sin configurar · se usará la IA local.");
        });
        root.addView(clearGemini);

        geminiStatus = new TextView(this);
        geminiStatus.setText(GeminiSecretStore.hasKey(this)
                ? "Gemini: configurado · modelo principal Gemini 3.6 Flash."
                : "Gemini: sin configurar · pega una clave para activarlo.");
        geminiStatus.setTextSize(12);
        geminiStatus.setPadding(0, 5, 0, 10);
        root.addView(geminiStatus);
'''
# geminiStatus must exist before listeners reference it. Move creation before buttons.
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

        geminiStatus = new TextView(this);
        geminiStatus.setText(GeminiSecretStore.hasKey(this)
                ? "Gemini: configurado · modelo principal Gemini 3.6 Flash."
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
                geminiStatus.setText("Gemini: configurado · la clave está cifrada en este teléfono.");
            } catch (Exception e) {
                geminiStatus.setText("Gemini: no pude guardar esa clave. Verifica que esté completa.");
            }
        });
        root.addView(saveGemini);

        Button clearGemini = new Button(this);
        clearGemini.setText("BORRAR CLAVE GEMINI");
        clearGemini.setOnClickListener(v -> {
            GeminiSecretStore.clear(this);
            geminiKey.setText("");
            geminiStatus.setText("Gemini: sin configurar · se usará la IA local.");
        });
        root.addView(clearGemini);
'''
activity = activity.replace(insert_after, insert_after + gemini_ui, 1)

activity = re.sub(r'title\.setText\("Screen Observer Pro [^"]+"\);',
                  'title.setText("Screen Observer Pro 2.6");', activity, count=1)
activity = re.sub(r'aiNote\.setText\("IA:.*?"\);',
                  'aiNote.setText("IA: Gemini 3.6 Flash es el cerebro principal cuando guardas una clave. Si Gemini no está disponible, la app conserva el intérprete Android y la IA local como respaldo. La clave no se incluye en el APK.");',
                  activity, count=1, flags=re.S)
activity = activity.replace("IA conversacional local.", "Gemini + IA local de respaldo.")
activity = activity.replace("Android 15/16 + IA local disponibles.", "Android 15/16 + Gemini disponibles.")
activity = activity.replace("Iniciando asistente e IA local…", "Iniciando asistente…")
activity_path.write_text(activity, encoding="utf-8")

print("patch_v26: Gemini 3.6 Flash primary agent + encrypted on-device key UI applied")
