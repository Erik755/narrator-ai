from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v27: expected one {label}, found {count}")
    return text.replace(old, new, 1)

llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")
llm = llm.replace("Gemini 2.5 Flash configurado", "Gemini 3.6 Flash configurado")
llm = llm.replace("Gemini 2.5 Flash listo", "Gemini 3.6 Flash listo")
llm = replace_once(
    llm,
    '''                if (isActionable(parsed.type) && !hasReliableSpeech(modelHypotheses)) {
                    parsed = Result(
                        IntentAgent.Type.GENERAL,
                        "",
                        "No estoy lo bastante seguro de la orden. Repítela después de la señal.",
                        0.40,
                        true,
                    )
                }
                updateStatus("Gemini 3.6 Flash listo")''',
    '''                if (isActionable(parsed.type) && !hasReliableSpeech(modelHypotheses)) {
                    parsed = Result(
                        IntentAgent.Type.GENERAL,
                        "",
                        "No estoy lo bastante seguro de la orden. Repítela después de la señal.",
                        0.40,
                        true,
                    )
                } else if (isActionable(parsed.type) && parsed.confidence < 0.62) {
                    parsed = Result(
                        IntentAgent.Type.GENERAL,
                        "",
                        "No tengo suficiente certeza para ejecutar eso. Dime la acción de otra forma.",
                        parsed.confidence,
                        true,
                    )
                }
                updateStatus("Gemini 3.6 Flash listo")''',
    "Gemini actionable confidence gate",
)
llm = llm.replace("ScreenObserverPro/2.6", "ScreenObserverPro/2.7")
llm_path.write_text(llm, encoding="utf-8")

main_path = Path("app/src/main/java/com/erik/screenobserver/MainActivity.java")
main = main_path.read_text(encoding="utf-8")
main = main.replace("Gemini 2.5 Flash", "Gemini 3.6 Flash")
main = replace_once(
    main,
    '''        root.addView(saveGemini);

        accessibilityButton = new Button(this);''',
    '''        root.addView(saveGemini);

        TextView geminiSecurity = new TextView(this);
        geminiSecurity.setText("La clave se cifra con Android Keystore. El contenido sensible (PIN, OTP, contraseña, tarjeta o pago) se omite del contexto enviado a Gemini.");
        geminiSecurity.setTextSize(11);
        geminiSecurity.setPadding(0, 2, 0, 4);
        root.addView(geminiSecurity);

        LinearLayout geminiActions = new LinearLayout(this);
        geminiActions.setOrientation(LinearLayout.HORIZONTAL);

        Button testGemini = new Button(this);
        testGemini.setText("PROBAR GEMINI");
        testGemini.setOnClickListener(v -> {
            geminiInfo.setText("Probando Gemini 3.6 Flash…");
            new Thread(() -> {
                boolean ok = GeminiRemoteAgent.testApiKey(MainActivity.this);
                runOnUiThread(() -> geminiInfo.setText(ok
                        ? "Gemini 3.6 Flash: conexión correcta."
                        : "Gemini: la prueba falló. Revisa la clave y la conexión."));
            }, "GeminiKeyTest").start();
        });
        geminiActions.addView(testGemini, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button clearGemini = new Button(this);
        clearGemini.setText("BORRAR CLAVE");
        clearGemini.setOnClickListener(v -> {
            GeminiRemoteAgent.clearApiKey(this);
            geminiKey.setText("");
            geminiInfo.setText("Clave Gemini eliminada. Se usará la IA local/respaldo.");
        });
        geminiActions.addView(clearGemini, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(geminiActions);

        accessibilityButton = new Button(this);''',
    "Gemini test and clear controls",
)
main = main.replace("Screen Observer Pro 2.6", "Screen Observer Pro 2.7")
main_path.write_text(main, encoding="utf-8")

service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")
service = service.replace("Screen Observer Pro 2.6", "Screen Observer Pro 2.7")
service = service.replace("ScreenObserverPro/2.6", "ScreenObserverPro/2.7")
service_path.write_text(service, encoding="utf-8")

print("patch_v27: Gemini 3.6, cloud confidence safety and secure-key UI applied")
