package com.erik.screenobserver;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ResearchEngine {
    public interface Callback {
        void onSuccess(String notes, JSONArray sources);
        void onError(String message);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void research(String topic, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                StringBuilder notes = new StringBuilder();
                JSONArray sources = new JSONArray();

                fetchSite("https://es.wikipedia.org", "Wikipedia", topic, notes, sources, 2);
                fetchSite("https://es.wikibooks.org", "Wikilibros", topic, notes, sources, 2);
                fetchSite("https://es.wikiversity.org", "Wikiversidad", topic, notes, sources, 2);

                if (notes.length() == 0) {
                    MAIN.post(() -> callback.onError("No encontré material suficiente en las fuentes gratuitas disponibles."));
                    return;
                }

                String result = notes.toString();
                if (result.length() > 7000) result = result.substring(0, 7000);
                String finalResult = result;
                MAIN.post(() -> callback.onSuccess(finalResult, sources));
            } catch (Exception e) {
                MAIN.post(() -> callback.onError("No pude completar la investigación: " + e.getMessage()));
            }
        });
    }

    private static void fetchSite(String baseUrl, String siteName, String topic,
                                  StringBuilder notes, JSONArray sources, int limit) {
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(topic, StandardCharsets.UTF_8.name());
            String urlString = baseUrl + "/w/api.php?action=query&format=json&generator=search"
                    + "&gsrsearch=" + encoded
                    + "&gsrlimit=" + limit
                    + "&prop=extracts&exintro=1&explaintext=1&exchars=1600&redirects=1";
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "ScreenObserverPro/1.3 (personal Android assistant)");
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return;

            String body = readAll(connection.getInputStream());
            JSONObject root = new JSONObject(body);
            JSONObject query = root.optJSONObject("query");
            if (query == null) return;
            JSONObject pages = query.optJSONObject("pages");
            if (pages == null) return;

            Iterator<String> keys = pages.keys();
            int count = 0;
            while (keys.hasNext() && count < limit) {
                JSONObject page = pages.optJSONObject(keys.next());
                if (page == null) continue;
                String title = page.optString("title", "").trim();
                String extract = page.optString("extract", "").trim();
                if (title.isEmpty() || extract.length() < 80) continue;

                if (notes.length() > 0) notes.append("\n\n");
                notes.append("[").append(siteName).append(" — ").append(title).append("]\n")
                        .append(extract);

                JSONObject source = new JSONObject();
                source.put("site", siteName);
                source.put("title", title);
                source.put("url", baseUrl + "/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"));
                sources.put(source);
                count++;
            }
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) builder.append(line);
        reader.close();
        return builder.toString();
    }
}
