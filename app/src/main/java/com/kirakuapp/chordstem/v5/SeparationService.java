package com.kirakuapp.chordstem.v5;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;

/**
 * 前台服务 — 后台执行 AI 分轨，系统不会杀进程。
 * V5 修复：使用 FOREGROUND_SERVICE_IMMEDIATE 避免延迟通知问题，
 * 图标使用 mipmap/ic_launcher（应用图标）。
 */
public final class SeparationService extends Service {
    private static final String CHANNEL_ID = "separation_fg";
    private static final int NOTIFY_ID = 3001;

    private static final String ACTION_SEPARATE = "com.kirakuapp.chordstem.SEPARATE";
    private static final String EXTRA_SOURCE = "source";
    private static final String EXTRA_OUTDIR = "outDir";

    private NotificationManager nm;
    private Notification.Builder nb;

    // Activity 通过 static listener 接收结果
    public interface ProgressListener {
        void onProgress(String stage, float progress);
        void onComplete(File[] files);
        void onError(String msg);
    }
    private static ProgressListener listener;
    public static void setListener(ProgressListener l) { listener = l; }

    /** 启动前台分轨服务。 */
    public static void start(Context ctx, String source, File outDir) {
        Intent i = new Intent(ctx, SeparationService.class);
        i.setAction(ACTION_SEPARATE);
        i.putExtra(EXTRA_SOURCE, source);
        i.putExtra(EXTRA_OUTDIR, outDir.getAbsolutePath());
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "AI分轨",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("后台 AI 音频分轨进度");
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_SEPARATE.equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        String source = intent.getStringExtra(EXTRA_SOURCE);
        File outDir = new File(intent.getStringExtra(EXTRA_OUTDIR));

        // 立即发出前台通知（必须在 5s 内，否则 ANR）
        buildNotification("AI 分轨", "准备中…");
        startForeground(NOTIFY_ID, nb.build());

        // 在服务线程同步执行分轨（TasnetSeparator.separate 是同步方法）
        new Thread(() -> {
            try {
                File[] files = TasnetSeparator.separate(this, source, outDir, new TasnetSeparator.Callback() {
                    @Override public void onProgress(String stage, float p) {
                        buildNotification("AI 分轨 " + Math.round(p * 100) + "%", stage);
                        nm.notify(NOTIFY_ID, nb.build());
                        if (listener != null) listener.onProgress(stage, p);
                    }
                    @Override public void onComplete(File[] f) { /* 不使用，由 separate 返回 */ }
                    @Override public void onError(String msg) { /* 不使用，由 catch 处理 */ }
                });
                buildNotification("✅ 分轨完成", "4 条音轨已生成");
                nb.setAutoCancel(true).setOngoing(false);
                nm.notify(NOTIFY_ID, nb.build());
                if (listener != null) listener.onComplete(files);
            } catch (Exception e) {
                buildNotification("❌ 分轨失败", e.getMessage());
                nb.setAutoCancel(true).setOngoing(false);
                nm.notify(NOTIFY_ID, nb.build());
                if (listener != null) listener.onError(e.getMessage());
            }
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }, "sep-fg").start();

        return START_NOT_STICKY;
    }

    private void buildNotification(String title, String text) {
        if (nb == null) {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pi)
                    .setOngoing(true);
            if (Build.VERSION.SDK_INT >= 31) nb.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        nb.setContentTitle(title).setContentText(text);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
