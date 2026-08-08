from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v24_review_fixes: expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


# Preserve the existing speech-confidence safety gate on the deterministic fast path.
llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")
llm = replace_once(
    llm,
    '''        if (fallback.type != IntentAgent.Type.GENERAL && fallback.confidence >= 0.80) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }''',
    '''        if (fallback.type != IntentAgent.Type.GENERAL
            && fallback.confidence >= 0.80
            && (!isActionable(fallback.type) || hasReliableSpeech(hypotheses))
        ) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }''',
    "reliable deterministic fast path",
)
llm_path.write_text(llm, encoding="utf-8")


# Expose a defensive copy of persisted source metadata so repeated learning does not erase it.
skills_path = Path("app/src/main/java/com/erik/screenobserver/SkillManager.java")
skills = skills_path.read_text(encoding="utf-8")
skills = replace_once(
    skills,
    "    public synchronized String listSkillNames() {",
    '''    public synchronized JSONArray getSkillSourcesArray(String name) {
        if (name == null || name.trim().isEmpty()) return new JSONArray();
        try {
            JSONObject skill = readAll().optJSONObject(key(name));
            if (skill == null) return new JSONArray();
            JSONArray sources = skill.optJSONArray("sources");
            return sources == null ? new JSONArray() : new JSONArray(sources.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public synchronized String listSkillNames() {''',
    "skill sources array accessor",
)
skills_path.write_text(skills, encoding="utf-8")


service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")

# Repeated requests to analyze the same game append a fresh observation instead of erasing history.
service = replace_once(
    service,
    '''        String initial = "[Análisis local de " + label + "]\\n"
                + "Paquete: " + pkg + "\\n"
                + "Pantalla inicial: " + compact(lastText, 1100) + "\\n"
                + "Controles iniciales: " + compact(controls, 900);
        skills.saveSkill(label, initial, new JSONArray());''',
    '''        String previous = skills.getSkillNotes(label);
        JSONArray previousSources = skills.getSkillSourcesArray(label);
        String initial = "[Análisis local de " + label + "]\\n"
                + "Paquete: " + pkg + "\\n"
                + "Pantalla inicial: " + compact(lastText, 1100) + "\\n"
                + "Controles iniciales: " + compact(controls, 900);
        String merged = previous == null || previous.trim().isEmpty()
                ? initial : previous + "\\n\\n" + initial;
        if (merged.length() > 7000) merged = merged.substring(merged.length() - 7000);
        skills.saveSkill(label, merged, previousSources);''',
    "preserve repeated learning history",
)

# Research completion and OCR observations now serialize their read-modify-write operations on main.
service = replace_once(
    service,
    '''            @Override public void onSuccess(String notes, JSONArray sources) {
                String observed = skills.getSkillNotes(skillName);
                String combined = observed + "\\n\\n[Investigación gratuita]\\n" + notes;
                if (combined.length() > 7000) combined = combined.substring(combined.length() - 7000);
                skills.saveSkill(skillName, combined, sources);
                activeSkillState = skills.getActiveSkillName();
                silent("Aprendizaje activo: " + compact(skillName, 34) + ".");
            }

            @Override public void onError(String message) {
                // Local observation is still useful even when public sources have no article.
                activeSkillState = skills.getActiveSkillName();
                silent("Aprendiendo " + compact(skillName, 34) + " desde la pantalla.");
            }''',
    '''            @Override public void onSuccess(String notes, JSONArray sources) {
                main.post(() -> {
                    String observed = skills.getSkillNotes(skillName);
                    String combined = observed + "\\n\\n[Investigación gratuita]\\n" + notes;
                    if (combined.length() > 7000) combined = combined.substring(combined.length() - 7000);
                    skills.saveSkill(skillName, combined, sources);
                    activeSkillState = skills.getActiveSkillName();
                    silent("Aprendizaje activo: " + compact(skillName, 34) + ".");
                });
            }

            @Override public void onError(String message) {
                main.post(() -> {
                    // Local observation remains useful even when public sources have no article.
                    activeSkillState = skills.getActiveSkillName();
                    silent("Aprendiendo " + compact(skillName, 34) + " desde la pantalla.");
                });
            }''',
    "serialize research learning callback",
)

service = replace_once(
    service,
    "        skills.saveSkill(learningSkillName, combined, new JSONArray());",
    "        skills.saveSkill(learningSkillName, combined, skills.getSkillSourcesArray(learningSkillName));",
    "preserve sources during screen observations",
)
service_path.write_text(service, encoding="utf-8")

print("patch_v24_review_fixes: speech safety and learning integrity fixes applied")
