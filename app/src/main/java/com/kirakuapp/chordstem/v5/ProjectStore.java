package com.kirakuapp.chordstem.v5;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ProjectStore {
    private static final String PREFS = "audiojam_studio";
    private static final String KEY = "projects_v2";

    public static List<AudioProject> load(Context ctx) {
        List<AudioProject> list = new ArrayList<>();
        String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) { AudioProject p = AudioProject.fromJson(o); if (!p.tracks.isEmpty()) list.add(p); }
            }
        } catch (Exception ignored) {}
        list.sort(Comparator.comparingLong(p -> -p.updatedAt));
        return list;
    }

    public static void save(Context ctx, AudioProject p) {
        if (p.demo) return;
        List<AudioProject> list = load(ctx);
        list.removeIf(x -> x.id.equals(p.id));
        p.updatedAt = System.currentTimeMillis();
        list.add(0, p);
        while (list.size() > 30) list.remove(list.size() - 1);
        write(ctx, list);
    }

    public static void remove(Context ctx, String id) {
        List<AudioProject> list = load(ctx);
        list.removeIf(x -> x.id.equals(id));
        write(ctx, list);
    }

    private static void write(Context ctx, List<AudioProject> list) {
        JSONArray arr = new JSONArray();
        for (AudioProject p : list) { try { arr.put(p.toJson()); } catch (Exception ignored) {} }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply();
    }
}
