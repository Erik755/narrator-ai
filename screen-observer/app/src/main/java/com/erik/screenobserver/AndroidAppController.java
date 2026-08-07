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

        if (best == null || bestScore < 45 || best.activityInfo == null) return false;
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

    private static int matchScore(String wanted, String label, String packageName) {
        if (wanted.equals(label)) return 100;
        if (!label.isEmpty() && label.contains(wanted)) return 85 - Math.abs(label.length() - wanted.length());
        if (!wanted.isEmpty() && wanted.contains(label) && label.length() >= 3) return 72;
        if (!packageName.isEmpty() && packageName.contains(wanted)) return 60;
        String[] w = wanted.split(" ");
        int hits = 0;
        for (String token : w) {
            if (token.length() >= 3 && (label.contains(token) || packageName.contains(token))) hits++;
        }
        return hits == 0 ? -1 : 40 + hits * 8;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
    }
}
