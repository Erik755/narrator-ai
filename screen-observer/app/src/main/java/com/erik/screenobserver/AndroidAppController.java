package com.erik.screenobserver;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Local helper for launching Android settings and installed launcher apps by spoken label. */
public final class AndroidAppController {
    private static final int MIN_LAUNCH_SCORE = 70;

    private AndroidAppController() { }

    public static boolean openSettings(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Finds a launcher app conservatively. A single shared vendor token is not enough for a
     * multi-word request, preventing e.g. an absent "Microsoft Teams" from opening Word.
     * Call this off the main thread because querying labels can involve package-manager IPC.
     */
    public static boolean launchAppByLabel(Context context, String requested) {
        if (context == null || requested == null || requested.trim().isEmpty()) return false;
        String wanted = normalize(requested);
        if (wanted.equals("ajustes") || wanted.equals("configuracion") || wanted.equals("settings")) {
            return openSettings(context);
        }

        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL);

        ResolveInfo best = null;
        int bestScore = -1;
        for (ResolveInfo info : apps) {
            CharSequence labelCs = info.loadLabel(pm);
            String label = labelCs == null ? "" : labelCs.toString();
            String packageName = info.activityInfo == null ? "" : info.activityInfo.packageName;
            String nl = normalize(label);
            String np = normalize(packageName.replace('.', ' '));
            int score = matchScore(wanted, nl, np);
            if (score > bestScore) {
                bestScore = score;
                best = info;
            }
        }

        if (best == null || bestScore < MIN_LAUNCH_SCORE || best.activityInfo == null) return false;
        try {
            Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static int matchScore(String wanted, String label, String packageName) {
        if (wanted.isEmpty()) return -1;
        if (wanted.equals(label)) return 100;
        if (!label.isEmpty() && containsWholePhrase(label, wanted))
            return 90 - Math.min(15, Math.abs(label.length() - wanted.length()));
        if (!label.isEmpty() && label.length() >= 4 && containsWholePhrase(wanted, label)) return 80;
        if (!packageName.isEmpty() && containsWholePhrase(packageName, wanted)) return 76;

        String[] rawTokens = wanted.split(" ");
        int meaningful = 0, hits = 0;
        for (String token : rawTokens) {
            if (token.length() < 3) continue;
            meaningful++;
            if (containsWord(label, token) || containsWord(packageName, token)) hits++;
        }
        if (meaningful == 0 || hits == 0) return -1;
        if (meaningful > 1 && hits < meaningful) return -1;
        return meaningful == 1 ? 68 : Math.min(88, 70 + hits * 6);
    }

    private static boolean containsWholePhrase(String haystack, String needle) {
        if (haystack.isEmpty() || needle.isEmpty()) return false;
        return (" " + haystack + " ").contains(" " + needle + " ");
    }

    private static boolean containsWord(String haystack, String word) {
        return !haystack.isEmpty() && !word.isEmpty()
                && (" " + haystack + " ").contains(" " + word + " ");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
    }
}
