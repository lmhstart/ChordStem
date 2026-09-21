package com.kirakuapp.chordstem.v5;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 多轨同步播放器。MediaPlayer 数组 + 手动同步。 */
public final class MultiTrackPlayer {
    public interface Listener {
        void onPrepared(int durationMs);
        void onPlaybackEnded();
        void onError(String msg);
    }

    private final Context ctx;
    private final AudioProject project;
    private final Listener listener;
    private final List<MediaPlayer> players = new ArrayList<>();
    private int preparedCount, durationMs, currentMs;
    private boolean prepared, playing, released, failed;

    public MultiTrackPlayer(Context ctx, AudioProject project, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.project = project;
        this.listener = listener;
    }

    public void prepare() {
        if (project.tracks.isEmpty()) { fail("无音轨"); return; }
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();

        for (int i = 0; i < project.tracks.size(); i++) {
            final int ti = i;
            AudioProject.TrackSpec t = project.tracks.get(i);
            MediaPlayer p = new MediaPlayer();
            players.add(p);
            p.setAudioAttributes(attrs);
            p.setOnPreparedListener(mp -> handlePrepared());
            p.setOnCompletionListener(mp -> { if (ti == 0 && playing) { playing = false; currentMs = durationMs; listener.onPlaybackEnded(); } });
            p.setOnErrorListener((mp, what, extra) -> { fail("解码失败: " + t.name); return true; });
            try {
                if (t.rawResId != 0) {
                    try (AssetFileDescriptor afd = ctx.getResources().openRawResourceFd(t.rawResId)) {
                        if (afd == null) throw new IOException("资源不存在");
                        p.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    }
                } else {
                    if (t.uri == null || t.uri.isEmpty()) throw new IOException("地址为空");
                    p.setDataSource(ctx, Uri.parse(t.uri));
                }
                p.prepareAsync();
            } catch (Exception e) { fail("无法打开: " + t.name); return; }
        }
    }

    private void handlePrepared() {
        if (released || failed) return;
        preparedCount++;
        if (preparedCount != players.size()) return;
        int min = Integer.MAX_VALUE;
        for (MediaPlayer p : players) { try { int d = p.getDuration(); if (d > 0) min = Math.min(min, d); } catch (Exception ignored) {} }
        durationMs = min == Integer.MAX_VALUE ? 0 : min;
        prepared = durationMs > 0;
        if (!prepared) { fail("无法读取时长"); return; }
        applyParams(); applyVolumes();
        listener.onPrepared(durationMs);
    }

    public void play() {
        if (!prepared || released) return;
        if (currentMs >= durationMs - 150) seekTo(0); else seekTo(currentMs);
        for (MediaPlayer p : players) { try { p.start(); } catch (Exception e) { fail("播放失败"); return; } }
        playing = true;
    }

    public void pause() {
        if (!prepared || released) return;
        currentMs = getCurrentPosition();
        for (MediaPlayer p : players) { try { if (p.isPlaying()) p.pause(); } catch (Exception ignored) {} }
        playing = false;
    }

    public void toggle() { if (playing) pause(); else play(); }

    public void seekTo(int ms) {
        if (!prepared || released) { currentMs = Math.max(0, ms); return; }
        currentMs = Math.max(0, Math.min(ms, durationMs));
        for (MediaPlayer p : players) { try { p.seekTo(currentMs); } catch (Exception ignored) {} }
    }

    public int getCurrentPosition() {
        if (!prepared || released || players.isEmpty()) return currentMs;
        try { currentMs = players.get(0).getCurrentPosition(); } catch (Exception ignored) {}
        return currentMs;
    }

    public int getDuration() { return durationMs; }
    public boolean isPrepared() { return prepared; }
    public boolean isPlaying() { return playing; }

    public void setSpeed(float v) { project.speed = Math.max(0.25f, Math.min(v, 2.0f)); applyParams(); }
    public void setPitchSemitones(int s) { project.pitchSemitones = Math.max(-12, Math.min(s, 12)); applyParams(); }
    public void setMasterVolume(int v) { project.masterVolume = clamp(v); applyVolumes(); }
    public void setTrackVolume(int idx, int v) { if (idx >= 0 && idx < project.tracks.size()) { project.tracks.get(idx).volume = clamp(v); applyVolumes(); } }
    public void setTrackMute(int idx, boolean m) { if (idx >= 0 && idx < project.tracks.size()) { project.tracks.get(idx).mute = m; applyVolumes(); } }
    public void setTrackSolo(int idx, boolean s) { if (idx >= 0 && idx < project.tracks.size()) { project.tracks.get(idx).solo = s; applyVolumes(); } }
    public void setTrackPan(int idx, float p) { if (idx >= 0 && idx < project.tracks.size()) { project.tracks.get(idx).pan = Math.max(-1, Math.min(p, 1)); applyVolumes(); } }

    public void syncIfNeeded() {
        if (!playing || players.size() < 2 || released) return;
        int ref = getCurrentPosition();
        for (int i = 1; i < players.size(); i++) { try { if (Math.abs(players.get(i).getCurrentPosition() - ref) > 180) players.get(i).seekTo(ref); } catch (Exception ignored) {} }
    }

    public void release() {
        released = true; prepared = false; playing = false;
        for (MediaPlayer p : players) { try { p.reset(); } catch (Exception ignored) {} try { p.release(); } catch (Exception ignored) {} }
        players.clear();
    }

    private void applyParams() {
        if (!prepared || released) return;
        float pitch = (float) Math.pow(2.0, project.pitchSemitones / 12.0);
        for (MediaPlayer p : players) {
            try {
                PlaybackParams pp = new PlaybackParams().allowDefaults().setSpeed(project.speed).setPitch(pitch);
                p.setPlaybackParams(pp);
                if (!playing && p.isPlaying()) p.pause();
            } catch (Exception ignored) {}
        }
    }

    private void applyVolumes() {
        if (released) return;
        boolean hasSolo = false;
        for (AudioProject.TrackSpec t : project.tracks) if (t.solo) { hasSolo = true; break; }
        float mg = project.masterVolume / 100f;
        int n = Math.min(players.size(), project.tracks.size());
        for (int i = 0; i < n; i++) {
            AudioProject.TrackSpec t = project.tracks.get(i);
            boolean aud = !t.mute && (!hasSolo || t.solo);
            float base = aud ? (t.volume / 100f) * mg : 0f;
            float l = base * (t.pan > 0 ? 1f - t.pan : 1f);
            float r = base * (t.pan < 0 ? 1f + t.pan : 1f);
            try { players.get(i).setVolume(l, r); } catch (Exception ignored) {}
        }
    }

    private void fail(String msg) { if (failed || released) return; failed = true; listener.onError(msg); }
    private static int clamp(int v) { return Math.max(0, Math.min(v, 100)); }
}
