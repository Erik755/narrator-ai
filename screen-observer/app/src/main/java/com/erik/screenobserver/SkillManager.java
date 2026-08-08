package com.erik.screenobserver;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SkillManager {
    private static final String PREFS = "screen_observer_skills";
    private static final String KEY_SKILLS = "skills_json";
    private static final String KEY_ACTIVE = "active_skill";
    private static final String LEGACY_ANDROID_SKILL = "Android 15 y 16";

    private final SharedPreferences prefs;

    public SkillManager(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureBuiltInSkills();
    }

    /** Adds/upgrades the system skill while preserving any user-created skill with the old name. */
    private synchronized void ensureBuiltInSkills() {
        try {
            JSONObject all = readAll();
            String k = key(AndroidSkillPack.SKILL_NAME);
            String oldKey = key(LEGACY_ANDROID_SKILL);
            String active = prefs.getString(KEY_ACTIVE, "");

            // Previous app versions used a user-collidable name. Remove that legacy entry only
            // when it is positively marked as built-in. An unmarked user skill is never replaced.
            if (!oldKey.equals(k)) {
                JSONObject legacy = all.optJSONObject(oldKey);
                if (legacy != null && legacy.optBoolean("builtin", false)) {
                    all.remove(oldKey);
                    if (oldKey.equals(active)) active = k;
                }
            }

            JSONObject existing = all.optJSONObject(k);
            int storedVersion = existing == null ? -1 : existing.optInt("builtinVersion", -1);
            if (existing == null || !existing.optBoolean("builtin", false)
                    || storedVersion != AndroidSkillPack.BUILTIN_VERSION) {
                // If the new reserved display name somehow belongs to a user skill, preserve it
                // and do not overwrite. This is safer than losing user-authored knowledge.
                if (existing == null || existing.optBoolean("builtin", false)) {
                    JSONObject skill = new JSONObject();
                    skill.put("name", AndroidSkillPack.SKILL_NAME);
                    skill.put("notes", AndroidSkillPack.builtInNotes());
                    skill.put("sources", new JSONArray());
                    skill.put("updatedAt", System.currentTimeMillis());
                    skill.put("builtin", true);
                    skill.put("builtinVersion", AndroidSkillPack.BUILTIN_VERSION);
                    all.put(k, skill);
                }
            }

            SharedPreferences.Editor editor = prefs.edit().putString(KEY_SKILLS, all.toString());
            if (active != null && !active.isEmpty()) editor.putString(KEY_ACTIVE, active);
            editor.apply();
        } catch (Exception ignored) { }
    }

    public synchronized void saveSkill(String name, String notes, JSONArray sources) {
        if (name == null || name.trim().isEmpty()) return;
        try {
            JSONObject all = readAll();
            String k = key(name);
            JSONObject existing = all.optJSONObject(k);
            if (existing != null && existing.optBoolean("builtin", false)) return;

            JSONObject skill = new JSONObject();
            skill.put("name", name.trim());
            skill.put("notes", notes == null ? "" : notes);
            skill.put("sources", sources == null ? new JSONArray() : sources);
            skill.put("updatedAt", System.currentTimeMillis());
            all.put(k, skill);
            prefs.edit()
                    .putString(KEY_SKILLS, all.toString())
                    .putString(KEY_ACTIVE, k)
                    .apply();
        } catch (Exception ignored) { }
    }

    public synchronized boolean hasSkill(String name) {
        if (name == null) return false;
        return readAll().has(key(name));
    }

    public synchronized boolean setActiveSkill(String name) {
        String k = key(name);
        if (!readAll().has(k)) return false;
        prefs.edit().putString(KEY_ACTIVE, k).apply();
        return true;
    }

    public synchronized String getActiveSkillName() {
        try {
            String k = prefs.getString(KEY_ACTIVE, "");
            JSONObject skill = readAll().optJSONObject(k);
            return skill == null ? "" : skill.optString("name", "");
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized String getActiveSkillNotes() {
        return getSkillNotes(getActiveSkillName());
    }

    public synchronized String getSkillNotes(String name) {
        if (name == null || name.trim().isEmpty()) return "";
        JSONObject skill = readAll().optJSONObject(key(name));
        return skill == null ? "" : skill.optString("notes", "");
    }

    public synchronized String getSkillSources(String name) {
        if (name == null || name.trim().isEmpty()) return "";
        JSONObject skill = readAll().optJSONObject(key(name));
        if (skill == null) return "";
        JSONArray sources = skill.optJSONArray("sources");
        if (sources == null || sources.length() == 0) return "";
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            if (source != null) {
                String title = source.optString("title", "");
                String site = source.optString("site", "");
                if (!title.isEmpty()) out.add(title + (site.isEmpty() ? "" : " — " + site));
            }
        }
        return join(out, "; ");
    }

    public synchronized String listSkillNames() {
        JSONObject all = readAll();
        List<String> names = new ArrayList<>();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            JSONObject skill = all.optJSONObject(it.next());
            if (skill != null) {
                String name = skill.optString("name", "");
                if (!name.isEmpty()) names.add(name);
            }
        }
        return join(names, ", ");
    }

    public synchronized boolean deleteSkill(String name) {
        try {
            String k = key(name);
            JSONObject all = readAll();
            JSONObject existing = all.optJSONObject(k);
            if (existing == null || existing.optBoolean("builtin", false)) return false;
            all.remove(k);
            SharedPreferences.Editor editor = prefs.edit().putString(KEY_SKILLS, all.toString());
            if (k.equals(prefs.getString(KEY_ACTIVE, ""))) editor.remove(KEY_ACTIVE);
            editor.apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized String findRelevantSkill(String request) {
        String normalized = normalize(request);
        if (normalized.isEmpty()) return getActiveSkillName();
        JSONObject all = readAll();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            JSONObject skill = all.optJSONObject(it.next());
            if (skill == null) continue;
            String name = skill.optString("name", "");
            String n = normalize(name);
            if (!n.isEmpty() && (normalized.contains(n) || n.contains(normalized))) return name;
        }
        if (normalized.contains("android") || normalized.contains("telefono") || normalized.contains("celular")
                || normalized.contains("pantalla") || normalized.contains("aplicacion") || normalized.contains("app")) {
            return AndroidSkillPack.SKILL_NAME;
        }
        return getActiveSkillName();
    }

    private JSONObject readAll() {
        try {
            String raw = prefs.getString(KEY_SKILLS, "{}");
            return new JSONObject(raw == null ? "{}" : raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String key(String value) {
        return normalize(value).replace(' ', '_');
    }

    private static String normalize(String value) {
        return TextNormalizer.normalize(value);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder b = new StringBuilder();
        for (String value : values) {
            if (b.length() > 0) b.append(separator);
            b.append(value);
        }
        return b.toString();
    }
}
