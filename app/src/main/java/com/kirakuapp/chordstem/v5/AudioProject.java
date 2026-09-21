package com.kirakuapp.chordstem.v5;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 音频项目数据模型。 */
public final class AudioProject {
    public String id = UUID.randomUUID().toString();
    public String title = "未命名项目";
    public boolean demo;
    public float speed = 1.0f;
    public int pitchSemitones;
    public int masterVolume = 100;
    public long updatedAt = System.currentTimeMillis();
    public long durationMs;
    public boolean hasStems;
    public final List<TrackSpec> tracks = new ArrayList<>();
    public final List<ChordAnalyzerEngine.ChordSegment> chords = new ArrayList<>();
    public final List<Long> beatTimesMs = new ArrayList<>();

    // ── JSON ──

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("speed", (double) speed);
        o.put("pitchSemitones", pitchSemitones);
        o.put("masterVolume", masterVolume);
        o.put("updatedAt", updatedAt);
        o.put("durationMs", durationMs);
        o.put("hasStems", hasStems);
        JSONArray ta = new JSONArray();
        for (TrackSpec t : tracks) ta.put(t.toJson());
        o.put("tracks", ta);
        JSONArray ca = new JSONArray();
        for (ChordAnalyzerEngine.ChordSegment c : chords) {
            JSONObject co = new JSONObject();
            co.put("timeMs", c.timeMs);
            co.put("chordName", c.chordName);
            co.put("defaultChordName", c.defaultChordName);
            co.put("confidence", (double) c.confidence);
            ca.put(co);
        }
        o.put("chords", ca);
        JSONArray ba = new JSONArray();
        for (Long t : beatTimesMs) ba.put(t);
        o.put("beatTimesMs", ba);
        return o;
    }

    public static AudioProject fromJson(JSONObject o) throws JSONException {
        AudioProject p = new AudioProject();
        p.id = o.optString("id", UUID.randomUUID().toString());
        p.title = o.optString("title", "未命名项目");
        p.speed = (float) o.optDouble("speed", 1.0);
        p.pitchSemitones = o.optInt("pitchSemitones", 0);
        p.masterVolume = o.optInt("masterVolume", 100);
        p.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
        p.durationMs = o.optLong("durationMs", 0);
        p.hasStems = o.optBoolean("hasStems", false);
        JSONArray ta = o.optJSONArray("tracks");
        if (ta != null) for (int i = 0; i < ta.length(); i++) {
            JSONObject to = ta.optJSONObject(i);
            if (to != null) p.tracks.add(TrackSpec.fromJson(to));
        }
        JSONArray ca = o.optJSONArray("chords");
        if (ca != null) for (int i = 0; i < ca.length(); i++) {
            JSONObject co = ca.optJSONObject(i);
            if (co != null) {
                String name = co.optString("chordName", "C");
                p.chords.add(new ChordAnalyzerEngine.ChordSegment(
                        co.optLong("timeMs", 0), name, co.optString("defaultChordName", name),
                        (float) co.optDouble("confidence", 0.8)));
            }
        }
        JSONArray ba = o.optJSONArray("beatTimesMs");
        if (ba != null) for (int i = 0; i < ba.length(); i++) p.beatTimesMs.add(ba.optLong(i, 0));
        return p;
    }


    // ── TrackSpec ──

    public static final class TrackSpec {
        public static final String S_VOCALS = "vocals", S_DRUMS = "drums", S_BASS = "bass", S_OTHER = "other";

        public String name, uri;
        public int rawResId;
        public int volume = 100;
        public float pan;
        public boolean mute, solo;
        public String stemType;

        public TrackSpec(String name, String uri) { this.name = name; this.uri = uri; }
        public TrackSpec(String name, int rawResId) { this.name = name; this.rawResId = rawResId; }
        public TrackSpec(String name, String uri, String stemType) { this.name = name; this.uri = uri; this.stemType = stemType; }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name); o.put("uri", uri);
            o.put("volume", volume); o.put("pan", (double) pan);
            o.put("mute", mute); o.put("solo", solo);
            if (stemType != null) o.put("stemType", stemType);
            return o;
        }

        static TrackSpec fromJson(JSONObject o) {
            TrackSpec t = new TrackSpec(o.optString("name","音轨"), o.optString("uri",""));
            t.volume = o.optInt("volume", 100);
            t.pan = (float) o.optDouble("pan", 0);
            t.mute = o.optBoolean("mute", false);
            t.solo = o.optBoolean("solo", false);
            t.stemType = o.optString("stemType", null);
            if (t.stemType != null && t.stemType.isEmpty()) t.stemType = null;
            return t;
        }
    }
}
