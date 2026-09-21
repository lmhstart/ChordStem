package com.kirakuapp.chordstem.v5;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.animation.OvershootInterpolator;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;

/**
 * ChordStem — 暖白底 + 品牌黄的离线音频工作室。
 * <p>配色完全取自图标 Frame 1.svg：品牌黄 #FFD000、品红 #E900FF、粉 #FF0072、
 * 珊瑚 #F56B58、绿 #1EA500、蓝 #271CC4。UI 结构：顶栏 + 侧滑抽屉 + 底部导航 +
 * 悬浮按钮 + 卡片列表 + 骨架屏。</p>
 */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final int PICK_SINGLE = 1001;

    // ── 配色（取自 Frame 1.svg）──
    private static final int BRAND = Color.rgb(255, 208, 0);      // 品牌黄 #FFD000
    private static final int INK = Color.rgb(20, 16, 10);         // 主文字
    private static final int BG = Color.rgb(255, 253, 244);       // 暖白背景
    private static final int SURFACE = Color.rgb(255, 255, 255);  // 卡片表面
    private static final int SURFACE2 = Color.rgb(255, 246, 220); // 次级表面(淡黄)
    private static final int MUTED = Color.rgb(138, 131, 114);    // 次级文字
    private static final int DIVIDER = Color.rgb(241, 232, 207);  // 描边/分隔
    private static final int SKELETON = Color.rgb(236, 230, 213); // 骨架屏
    private static final int MAGENTA = Color.rgb(233, 0, 255);    // 品红
    private static final int PINK = Color.rgb(255, 0, 114);       // 粉红
    private static final int CORAL = Color.rgb(245, 107, 88);     // 珊瑚
    private static final int GREEN = Color.rgb(30, 165, 0);       // 绿
    private static final int BLUE = Color.rgb(39, 28, 196);       // 蓝

    private static final int ACCENT = MAGENTA;   // 交互强调（进度/激活）
    private static final int WARN = CORAL;       // 警示
    private static final int DANGER = PINK;      // 危险
    private static final int TEXT = INK;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MultiTrackPlayer player;
    private AudioProject currentProject;
    private boolean showingPlayer, userSeeking;
    private boolean splitting, analyzing, exporting;  // 重入保护
    private int loopA = -1, loopB = -1;
    private long lastSyncAt;

    // ── 界面骨架 ──
    private FrameLayout root;
    private View scrim;
    private LinearLayout drawer;
    private int drawerWidth;
    private boolean drawerOpen;
    private LinearLayout mainColumn, topBar, bottomNav;
    private FrameLayout notchHost;
    private View notchActive;
    private LinearLayout projectListView;
    private boolean selectingProjects;
    private final Set<String> selectedProjectIds = new LinkedHashSet<>();
    private FrameLayout contentFrame;
    private TextView fab;
    private int currentTab = 0;
    private final ArrayList<View> tiltCards = new ArrayList<>();
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private float edgeDownX, edgeDownY;
    private boolean edgeTracking;

    // ── 播放器控件 ──
    private SeekBar timeline;
    private WaveformView waveform;
    private TextView timeLabel, playButton, loadingLabel, loopLabel, chordBanner;
    private ChordTimelineView chordTimeline;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        configureWindow();
        buildChrome();
        showHome();
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
    }


    // ═══════════════════ 界面骨架（一次性构建）═══════════════════

    private void buildChrome() {
        root = new FrameLayout(this);
        root.setBackground(texture(BG, Color.rgb(255, 249, 226)));

        // 主列：顶栏 + 内容 + 底栏
        mainColumn = new LinearLayout(this);
        mainColumn.setOrientation(LinearLayout.VERTICAL);
        root.addView(mainColumn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyInsets(mainColumn);

        // 顶栏
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(6), dp(16), dp(6));
        topBar.setBackground(texture(SURFACE, Color.rgb(255, 252, 240)));
        mainColumn.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        buildTopBar();

        // 内容区
        contentFrame = new FrameLayout(this);
        mainColumn.addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 悬浮按钮
        fab = new TextView(this);
        fab.setText("导入");
        fab.setTextSize(12);
        fab.setTextColor(INK);
        fab.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(texture(BRAND, Color.rgb(255, 229, 92)));
        fab.setElevation(dp(6));
        fab.setOnClickListener(v -> openPicker());
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
        flp.rightMargin = dp(20);
        flp.bottomMargin = dp(20);
        contentFrame.addView(fab, flp);

        // 底栏
        notchHost = new FrameLayout(this);
        notchHost.setClipChildren(false);
        mainColumn.addView(notchHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));
        View notchShell = new View(this);
        notchShell.setBackground(notchBg());
        notchHost.addView(notchShell, new FrameLayout.LayoutParams(dp(252), dp(56), Gravity.CENTER));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER_VERTICAL);
        bottomNav.setPadding(dp(5), dp(5), dp(5), dp(5));
        bottomNav.setBackgroundColor(Color.TRANSPARENT);
        bottomNav.setElevation(dp(10));
        FrameLayout.LayoutParams notchParams = new FrameLayout.LayoutParams(dp(252), dp(56), Gravity.CENTER);
        notchHost.addView(bottomNav, notchParams);
        notchActive = new View(this);
        notchActive.setBackground(gradientRounded(BRAND, Color.rgb(255, 229, 92), 24));
        notchActive.setElevation(dp(1));
        FrameLayout.LayoutParams activeParams = new FrameLayout.LayoutParams(dp(121), dp(46));
        activeParams.gravity = Gravity.CENTER;
        activeParams.leftMargin = dp(2);
        notchHost.addView(notchActive, 1, activeParams);
        buildBottomNav();

        // 遮罩
        scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(80, 0, 0, 0));
        scrim.setVisibility(View.GONE);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 抽屉
        buildDrawer();

        setContentView(root);
    }

    private void buildTopBar() {
        TextView menu = navIcon("menu");
        menu.setOnClickListener(v -> openDrawer());
        topBar.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));

        // Logo：黄色唱片圆点
        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.icon);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        appIcon.setContentDescription("ChordStem 应用图标");
        topBar.addView(appIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView title = label("ChordStem", 19, INK, Typeface.BOLD);
        LinearLayout.LayoutParams ttp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ttp.leftMargin = dp(8);
        topBar.addView(title, ttp);

        topBar.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));
        topBar.addView(badge("离线", GREEN));
    }

    private void buildBottomNav() {
        navTab(0, "home", "首页");
        navTab(1, "info", "关于");
    }

    private void navTab(int idx, String icon, String title) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(9), dp(4), dp(9), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.TRANSPARENT);
        tab.setBackground(rippleFrom(bg));
        tab.setOnClickListener(v -> switchTab(idx));
        TextView ic = iconLabel(icon, 18, MUTED);
        ic.setGravity(Gravity.CENTER);
        tab.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(32)));
        TextView lb = label(title, 11, MUTED, Typeface.BOLD);
        lb.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(dp(42), dp(32));
        lbp.leftMargin = dp(3);
        tab.addView(lb, lbp);
        tab.setTag(new View[]{ic, lb});
        bottomNav.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void buildDrawer() {
        drawerWidth = dp(292);
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackground(texture(SURFACE, Color.rgb(255, 246, 220)));
        drawer.setElevation(dp(16));
        drawer.setTranslationX(-drawerWidth);
        root.addView(drawer, new FrameLayout.LayoutParams(drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.TOP));

        // 头部
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(28), dp(20), dp(24));
        GradientDrawable headBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{BRAND, Color.rgb(255, 228, 96)});
        head.setBackground(headBg);
        head.addView(label("ChordStem", 24, INK, Typeface.BOLD));
        TextView tag = label("和弦 · 分轨 · 离线混音", 12, Color.argb(200, 20, 16, 10), Typeface.NORMAL);
        LinearLayout.LayoutParams tagp = wrapWrap(); tagp.topMargin = dp(4);
        head.addView(tag, tagp);
        drawer.addView(head, matchWrap());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(10), 0, dp(10));
        drawerItem(body, "home", "首页", () -> switchTab(0));
        drawerItem(body, "upload", "导入一首歌曲（AI 分轨）", this::openPicker);
        drawerItem(body, "info", "关于 ChordStem", () -> switchTab(1));
        drawer.addView(body, matchWrap());

        drawer.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1f));
        TextView foot = label("无网络 · 无账号 · 无广告\n数据只保存在本机", 11, MUTED, Typeface.NORMAL);
        foot.setPadding(dp(20), dp(16), dp(20), dp(20));
        drawer.addView(foot, matchWrap());
    }

    private void drawerItem(LinearLayout parent, String icon, String title, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(20), dp(14), dp(20), dp(14));
        item.setBackground(ripple(Color.TRANSPARENT, 0));
        item.setOnClickListener(v -> { closeDrawer(); action.run(); });
        TextView ic = iconLabel(icon, 18, INK);
        ic.setGravity(Gravity.CENTER);
        item.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView tx = label(title, 14, INK, Typeface.NORMAL);
        LinearLayout.LayoutParams txp = weightedWrap(1f); txp.leftMargin = dp(12);
        item.addView(tx, txp);
        parent.addView(item, matchWrap());
    }

    private void showImportSheet() {
        new AlertDialog.Builder(this)
                .setTitle("导入音频")
                .setItems(new String[]{"导入一首歌曲（自动 AI 分轨）"}, (d, w) -> openPicker())
                .setNegativeButton("取消", null).show();
    }

    private void openDrawer() {
        drawerOpen = true;
        drawer.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-drawerWidth);
        drawer.animate().translationX(0f)
                .setInterpolator(new OvershootInterpolator(1.15f))
                .setDuration(460)
                .start();
        scrim.animate().alpha(1f).setDuration(220).start();
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.animate().translationX(-drawerWidth)
                .setDuration(220)
                .start();
        scrim.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> scrim.setVisibility(View.GONE))
                .start();
    }

    private void setChromeVisible(boolean visible) {
        topBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        notchHost.setVisibility(visible ? View.VISIBLE : View.GONE);
        bottomNav.setVisibility(visible ? View.VISIBLE : View.GONE);
        fab.setVisibility(visible && currentTab == 0 ? View.VISIBLE : View.GONE);
    }

    private void switchTab(int idx) {
        currentTab = idx;
        // 关于页是独立信息页，使用自己的返回导航，不叠加首页顶栏与底部导航。
        setChromeVisible(idx == 0 && !showingPlayer);
        if (notchActive != null && notchHost != null) {
            final float target = idx == 0 ? -dp(63) : dp(63);
            notchActive.animate().translationX(target).setInterpolator(new OvershootInterpolator(0.8f)).setDuration(420).start();
        }
        for (int i = 0; i < bottomNav.getChildCount(); i++) {
            View t = bottomNav.getChildAt(i);
            View[] v = (View[]) t.getTag();
            boolean sel = i == idx;
            ((TextView) v[0]).setTextColor(sel ? INK : MUTED);
            ((TextView) v[1]).setTextColor(sel ? INK : MUTED);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(Color.TRANSPARENT);
            t.setBackground(rippleFrom(bg));
        }
        fab.setVisibility(showingPlayer ? View.GONE : (idx == 0 ? View.VISIBLE : View.GONE));
        if (idx == 0) buildHomePage();
        else buildAboutPage();
    }

    // ═══════════════════ 首页 ═══════════════════

    private void showHome() {
        stopPlayer(true);
        showingPlayer = false;
        currentTab = 0;
        setChromeVisible(true);
        switchTab(0);
        if (drawerOpen) closeDrawer();
    }

    private void buildHomePage() {
        tiltCards.clear();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(texture(BG, Color.rgb(255, 248, 222)));
        LinearLayout content = vbox();
        content.setPadding(dp(18), dp(18), dp(18), dp(40));
        scroll.addView(content, matchWrap());

        // 欢迎语
        content.addView(label("欢迎回来", 24, INK, Typeface.BOLD));
        TextView sub = label("导入歌曲，AI 离线分轨，分析和弦并导出你的混音。", 13, MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams subp = wrapWrap(); subp.topMargin = dp(4);
        content.addView(sub, subp);

        content.addView(new Space(this), new LinearLayout.LayoutParams(1, dp(20)));

        // 导入卡片
        addHomeCard(content, "导入一首歌曲", "AI 自动分离人声/鼓/贝斯/其他", "♫", BRAND, v -> openPicker());

        addIllustrationCard(content);

        content.addView(new Space(this), new LinearLayout.LayoutParams(1, dp(22)));

        // 本地项目
        LinearLayout sectionHdr = hbox();
        sectionHdr.addView(label("本地项目", 16, INK, Typeface.BOLD), weightedWrap(1f));
        TextView manageProjects = label("⋮", 28, INK, Typeface.BOLD);
        manageProjects.setGravity(Gravity.CENTER);
        manageProjects.setContentDescription("管理本地项目");
        manageProjects.setOnClickListener(v -> {
            selectingProjects = true;
            selectedProjectIds.clear();
            fillProjectList(projectListView);
        });
        sectionHdr.addView(manageProjects, new LinearLayout.LayoutParams(dp(42), dp(38)));
        content.addView(sectionHdr, matchWrap());
        content.addView(new Space(this), new LinearLayout.LayoutParams(1, dp(12)));

        // 项目列表容器（先显示骨架屏，稍后填充）
        projectListView = vbox();
        content.addView(projectListView, matchWrap());
        showSkeleton(projectListView);
        handler.postDelayed(() -> fillProjectList(projectListView), 650);

        setContent(scroll);
    }

    private void showSkeleton(LinearLayout container) {
        container.removeAllViews();
        container.setAlpha(0.82f);
        for (int i = 0; i < 3; i++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackground(cardBg(SURFACE, 18));
            View bar = new View(this);
            bar.setBackgroundColor(SKELETON);
            card.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(40)));
            LinearLayout w = vbox();
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            wp.leftMargin = dp(14);
            card.addView(w, wp);
            w.addView(skelBar(dp(140), dp(14)));
            LinearLayout.LayoutParams sp = wrapWrap(); sp.topMargin = dp(8);
            w.addView(skelBar(dp(90), dp(10)), sp);
            LinearLayout.LayoutParams cp = matchWrap(); cp.bottomMargin = dp(10);
            container.addView(card, cp);
        }
    }

    private View skelBar(int w, int h) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(SKELETON);
        g.setCornerRadius(dp(h / 2));
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), dp(h)));
        return v;
    }

    private void fillProjectList(LinearLayout container) {
        container.setAlpha(1f);
        container.removeAllViews();
        List<AudioProject> projects = ProjectStore.load(this);
        if (selectingProjects && !projects.isEmpty()) addProjectSelectionBar(container, projects);
        if (projects.isEmpty()) {
            LinearLayout empty = vbox();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(30), dp(16), dp(30));
            empty.setBackground(cardBg(SURFACE, 18));
            empty.addView(label("还没有本地项目", 14, MUTED, Typeface.NORMAL));
            TextView hint = label("点右下角 ＋ 导入你的第一首歌", 12, MUTED, Typeface.NORMAL);
            LinearLayout.LayoutParams hp = wrapWrap(); hp.topMargin = dp(4);
            empty.addView(hint, hp);
            container.addView(empty, matchWrap());
        } else {
            for (AudioProject p : projects) projectCard(container, p);
        }
    }

    private void addProjectSelectionBar(LinearLayout container, List<AudioProject> projects) {
        LinearLayout bar = hbox();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView all = smallBtn("全选", BLUE);
        all.setOnClickListener(v -> {
            selectedProjectIds.clear();
            for (AudioProject p : projects) selectedProjectIds.add(p.id);
            fillProjectList(container);
        });
        bar.addView(all, new LinearLayout.LayoutParams(0, dp(42), 1f));
        TextView del = smallBtn("删除", DANGER);
        del.setOnClickListener(v -> deleteSelectedProjects(container));
        LinearLayout.LayoutParams delp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        delp.leftMargin = dp(8);
        bar.addView(del, delp);
        TextView cancel = smallBtn("完成", MUTED);
        cancel.setOnClickListener(v -> { selectingProjects = false; selectedProjectIds.clear(); fillProjectList(container); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        cp.leftMargin = dp(8);
        bar.addView(cancel, cp);
        LinearLayout.LayoutParams bp = matchWrap(); bp.bottomMargin = dp(10);
        container.addView(bar, bp);
    }

    private void deleteSelectedProjects(LinearLayout container) {
        if (selectedProjectIds.isEmpty()) { toast("请先选择项目"); return; }
        new AlertDialog.Builder(this).setTitle("删除所选项目？")
                .setMessage("只移除项目记录，不删除原音频文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    for (String id : new ArrayList<>(selectedProjectIds)) ProjectStore.remove(this, id);
                    selectedProjectIds.clear(); selectingProjects = false;
                    fillProjectList(container);
                }).show();
    }

    // ═══════════════════ 关于页 ═══════════════════

    private void buildAboutPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(texture(BG, Color.rgb(255, 248, 222)));
        LinearLayout content = vbox();
        content.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(content, matchWrap());

        LinearLayout top = hbox();
        TextView back = navIcon("back");
        back.setOnClickListener(v -> showHome());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        top.addView(label("关于 ChordStem", 20, INK, Typeface.BOLD), weightedWrap());
        content.addView(top, matchWrap());

        LinearLayout hero = aboutCard();
        LinearLayout heroHead = hbox();
        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.icon);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        appIcon.setPadding(dp(10), dp(10), dp(10), dp(10));
        appIcon.setBackground(roundBg(BRAND, 18));
        heroHead.addView(appIcon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout heroText = vbox();
        LinearLayout.LayoutParams htp = weightedWrap(); htp.leftMargin = dp(14);
        heroHead.addView(heroText, htp);
        heroText.addView(label("ChordStem", 23, INK, Typeface.BOLD));
        heroText.addView(label("离线音频工作台", 13, MUTED, Typeface.NORMAL));
        TextView version = badge("版本 6.1.0", MAGENTA);
        LinearLayout.LayoutParams vp = wrapWrap(); vp.topMargin = dp(7);
        heroText.addView(version, vp);
        hero.addView(heroHead, matchWrap());
        TextView intro = label("在本机完成分轨、和弦分析、BPM 识别和混音。", 14, INK, Typeface.NORMAL);
        intro.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams ip = matchWrap(); ip.topMargin = dp(16);
        hero.addView(intro, ip);
        addAboutBlock(content, hero, 12);

        aboutSectionTitle(content, "核心能力");
        LinearLayout abilities = vbox();
        LinearLayout abilityRow1 = hbox();
        abilityRow1.addView(aboutChip("AI 分轨", MAGENTA), weightedWithEnd(1f, 6));
        abilityRow1.addView(aboutChip("和弦分析", BLUE), weightedWrap());
        abilities.addView(abilityRow1, matchWrap());
        LinearLayout abilityRow2 = hbox();
        abilityRow2.addView(aboutChip("BPM 识别", CORAL), weightedWithEnd(1f, 6));
        abilityRow2.addView(aboutChip("本地导出", GREEN), weightedWrap());
        LinearLayout.LayoutParams ar2 = matchWrap(); ar2.topMargin = dp(6);
        abilities.addView(abilityRow2, ar2);
        addAboutBlock(content, abilities, 12);

        aboutSectionTitle(content, "开源与社区");
        LinearLayout github = aboutHorizontalCard();
        github.setOnClickListener(v -> openExternal("https://github.com/lmhstart/Chordstem"));
        LinearLayout ghIcon = vbox();
        ghIcon.setGravity(Gravity.CENTER);
        ghIcon.setBackground(roundBg(alpha(BLUE, 28), 15));
        ghIcon.addView(iconLabel("code", 24, BLUE), new LinearLayout.LayoutParams(dp(48), dp(48)));
        github.addView(ghIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout ghText = vbox();
        LinearLayout.LayoutParams ghp = weightedWrap(); ghp.leftMargin = dp(12);
        github.addView(ghText, ghp);
        ghText.addView(label("GitHub 开源项目", 15, INK, Typeface.BOLD));
        ghText.addView(label("查看源代码 · 提交 Issue · 欢迎 Star", 12, MUTED, Typeface.NORMAL));
        TextView arrow = label("↗", 22, BLUE, Typeface.BOLD);
        github.addView(arrow, new LinearLayout.LayoutParams(dp(30), dp(48)));
        addAboutBlock(content, github, 8);

        aboutSectionTitle(content, "隐私与运行方式");
        LinearLayout privacy = aboutCard();
        privacy.addView(label("完全离线", 15, INK, Typeface.BOLD));
        TextView privacyDesc = label("所有音频均在本机处理\n无需账号，不上传音频，不依赖云端服务", 13, MUTED, Typeface.NORMAL);
        privacyDesc.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams pdp = matchWrap(); pdp.topMargin = dp(6);
        privacy.addView(privacyDesc, pdp);
        addAboutBlock(content, privacy, 12);

        aboutSectionTitle(content, "技术信息");
        LinearLayout tech = aboutCard();
        aboutInfoRow(tech, "ONNX Runtime", "AI 分轨推理");
        aboutInfoRow(tech, "Android MediaCodec", "本地音频解码与播放");
        aboutInfoRow(tech, "本地音频分析引擎", "和弦、BPM 与调式估计");
        addAboutBlock(content, tech, 12);

        aboutSectionTitle(content, "版本信息");
        LinearLayout info = aboutCard();
        aboutInfoRow(info, "版本", "6.1.0");
        aboutInfoRow(info, "构建号", "61");
        aboutInfoRow(info, "最低支持", "Android 7.0+");
        aboutInfoRow(info, "作者", "lmhstart");
        addAboutBlock(content, info, 20);

        TextView footer = label("Made for music makers", 12, MUTED, Typeface.BOLD);
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, matchWrap());

        setContent(scroll);
    }

    private LinearLayout aboutCard() {
        LinearLayout card = vbox();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = gradientRounded(SURFACE, Color.rgb(255, 249, 231), 18);
        bg.setStroke(dp(1), DIVIDER);
        card.setBackground(bg);
        return card;
    }

    private LinearLayout aboutHorizontalCard() {
        LinearLayout card = hbox();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = gradientRounded(SURFACE, Color.rgb(255, 249, 231), 18);
        bg.setStroke(dp(1), DIVIDER);
        card.setBackground(bg);
        return card;
    }

    private TextView aboutChip(String text, int accent) {
        TextView chip = label(text, 13, INK, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), dp(12), dp(8), dp(12));
        chip.setBackground(rippleFrom(gradientRounded(SURFACE, alpha(accent, 24), 15)));
        return chip;
    }

    private void aboutSectionTitle(LinearLayout parent, String title) {
        TextView t = label(title, 14, INK, Typeface.BOLD);
        LinearLayout.LayoutParams p = matchWrap(); p.topMargin = dp(16); p.bottomMargin = dp(8);
        parent.addView(t, p);
    }

    private void addAboutBlock(LinearLayout parent, View block, int topMargin) {
        LinearLayout.LayoutParams p = matchWrap(); p.topMargin = dp(topMargin);
        parent.addView(block, p);
    }

    private void aboutInfoRow(LinearLayout parent, String name, String value) {
        LinearLayout row = hbox();
        TextView left = label(name, 13, INK, Typeface.BOLD);
        row.addView(left, weightedWrap());
        TextView right = label(value, 12, MUTED, Typeface.NORMAL);
        right.setGravity(Gravity.RIGHT);
        row.addView(right, wrapWrap());
        LinearLayout.LayoutParams p = matchWrap(); p.topMargin = dp(4); p.bottomMargin = dp(4);
        parent.addView(row, p);
    }

    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) { toast("暂时无法打开链接"); }
    }

    private void setContent(View v) {
        contentFrame.removeAllViews();
        contentFrame.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 悬浮按钮始终置于内容之上
        if (fab.getParent() == null) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
            flp.rightMargin = dp(20); flp.bottomMargin = dp(20);
            contentFrame.addView(fab, flp);
        } else {
            fab.bringToFront();
        }
    }

    // ═══════════════ 播放器页面 ═══════════════

    private void openProject(AudioProject project) {
        currentProject = project;
        if (!project.demo) ProjectStore.save(this, project);
        showingPlayer = true;
        loopA = -1; loopB = -1;
        setChromeVisible(false);
        if (drawerOpen) closeDrawer();

        LinearLayout page = vbox();
        page.setBackground(texture(BG, Color.rgb(255, 248, 222)));

        // 顶部栏
        LinearLayout topBar2 = hbox();
        topBar2.setGravity(Gravity.CENTER_VERTICAL);
        topBar2.setPadding(dp(12), dp(6), dp(16), dp(6));
        topBar2.setBackground(texture(SURFACE, Color.rgb(255, 252, 240)));
        TextView back = navIcon("back");
        back.setOnClickListener(v -> showHome());
        topBar2.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout tb = vbox();
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tbp.leftMargin = dp(8);
        topBar2.addView(tb, tbp);
        TextView pt = label(project.title, 16, INK, Typeface.BOLD);
        pt.setSingleLine(true);
        tb.addView(pt);
        tb.addView(label(project.demo ? "内置演示" : "本地项目 · 点击标题重命名", 11, MUTED, Typeface.NORMAL));
        if (!project.demo) pt.setOnClickListener(v -> renameProject(pt));
        topBar2.addView(badge("离线", GREEN));
        page.addView(topBar2, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = vbox();
        content.setPadding(dp(18), dp(10), dp(18), dp(40));
        scroll.addView(content, matchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 封面
        FrameLayout art = new FrameLayout(this);
        art.setBackground(cardBg(SURFACE2, 20));
        ImageView ai = new ImageView(this);
        ai.setImageResource(project.demo ? R.drawable.demo_cover : R.drawable.icon);
        ai.setScaleType(project.demo ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.CENTER_INSIDE);
        int ins = project.demo ? 0 : dp(40);
        ai.setPadding(ins, ins, ins, ins);
        art.addView(ai, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        content.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

        loadingLabel = label("正在读取本地音轨…", 12, MUTED, Typeface.NORMAL);
        loadingLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lop = matchWrap(); lop.topMargin = dp(10);
        content.addView(loadingLabel, lop);

        chordBanner = label("当前和弦  ·  --", 13, MAGENTA, Typeface.BOLD);
        chordBanner.setGravity(Gravity.CENTER);
        chordBanner.setPadding(dp(12), dp(8), dp(12), dp(8));
        chordBanner.setBackground(ripple(SURFACE2, 14));
        chordBanner.setOnClickListener(v -> showChordList());
        LinearLayout.LayoutParams cbp = matchWrap(); cbp.topMargin = dp(6);
        content.addView(chordBanner, cbp);

        chordTimeline = new ChordTimelineView(this);
        chordTimeline.setListener(this::editChordAt);
        LinearLayout.LayoutParams ctp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        ctp.topMargin = dp(6);
        content.addView(chordTimeline, ctp);

        waveform = new WaveformView(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        wp.topMargin = dp(6);
        content.addView(waveform, wp);

        timeLabel = label("00:00 / 00:00", 12, MUTED, Typeface.NORMAL);
        timeLabel.setGravity(Gravity.CENTER);
        content.addView(timeLabel, matchWrap());

        timeline = new SeekBar(this);
        timeline.setMax(1);
        timeline.setProgressTintList(ColorStateList.valueOf(ACCENT));
        timeline.setThumbTintList(ColorStateList.valueOf(ACCENT));
        timeline.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (player != null) player.seekTo(sb.getProgress());
                userSeeking = false;
            }
        });
        content.addView(timeline, matchWrap());

        // 传输控件
        LinearLayout transport = hbox();
        transport.setGravity(Gravity.CENTER);
        transport.setPadding(0, dp(6), 0, dp(6));
        TextView rw = transportBtn("−5s", false); rw.setOnClickListener(v -> seekRel(-5000));
        transport.addView(rw, weighted(dp(48), 1f));
        playButton = transportBtn("▶", true);
        playButton.setOnClickListener(v -> togglePlay());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(70), dp(70));
        plp.leftMargin = dp(14); plp.rightMargin = dp(14);
        transport.addView(playButton, plp);
        TextView ff = transportBtn("+5s", false); ff.setOnClickListener(v -> seekRel(5000));
        transport.addView(ff, weighted(dp(48), 1f));
        content.addView(transport, matchWrap());

        // 工具箱
        LinearLayout tools = sectionCard();
        tools.addView(label("工具箱", 14, INK, Typeface.BOLD));
        LinearLayout tr = hbox();
        tr.setPadding(0, dp(12), 0, 0);
        TextView btnC = smallBtn("分析和弦", MAGENTA);
        btnC.setOnClickListener(v -> startChord());
        tr.addView(btnC, weighted(dp(44), 1f));
        TextView btnS = smallBtn("AI 分轨", BLUE);
        LinearLayout.LayoutParams bsp = weighted(dp(44), 1f); bsp.leftMargin = dp(8);
        btnS.setOnClickListener(v -> startSplit());
        tr.addView(btnS, bsp);
        TextView btnE = smallBtn("导出", GREEN);
        LinearLayout.LayoutParams bep = weighted(dp(44), 1f); bep.leftMargin = dp(8);
        btnE.setOnClickListener(v -> startExport());
        tr.addView(btnE, bep);
        tools.addView(tr, matchWrap());
        addSection(content, tools);

        // A-B 循环
        LinearLayout loopCard = sectionCard();
        LinearLayout lh = hbox();
        lh.addView(label("A-B 循环", 14, INK, Typeface.BOLD), weightedWrap(1f));
        loopLabel = label("未设置", 11, MUTED, Typeface.NORMAL);
        lh.addView(loopLabel);
        loopCard.addView(lh, matchWrap());
        LinearLayout la = hbox();
        LinearLayout.LayoutParams lap = matchWrap(); lap.topMargin = dp(12);
        TextView ba = smallBtn("设为 A", CORAL);
        ba.setOnClickListener(v -> setLoopA());
        la.addView(ba, weighted(dp(44), 1f));
        TextView bb = smallBtn("设为 B", PINK);
        LinearLayout.LayoutParams bbp = weighted(dp(44), 1f); bbp.leftMargin = dp(8);
        bb.setOnClickListener(v -> setLoopB());
        la.addView(bb, bbp);
        TextView bc = smallBtn("清除", MUTED);
        bc.setOnClickListener(v -> clearLoop());
        LinearLayout.LayoutParams bcp = new LinearLayout.LayoutParams(dp(66), dp(42)); bcp.leftMargin = dp(8);
        la.addView(bc, bcp);
        loopCard.addView(la, lap);
        addSection(content, loopCard);

        // 播放调节
        LinearLayout tuneCard = sectionCard();
        tuneCard.addView(label("播放调节", 14, INK, Typeface.BOLD));
        addSlider(tuneCard, "速度", 175, Math.round((project.speed - 0.25f) * 100),
                p -> String.format(Locale.getDefault(), "%.2fx", 0.25f + p / 100f),
                p -> { if (player != null) player.setSpeed(0.25f + p / 100f); });
        addSlider(tuneCard, "音调", 24, project.pitchSemitones + 12,
                p -> { int v = p - 12; return v > 0 ? "+" + v + " 半音" : v + " 半音"; },
                p -> { if (player != null) player.setPitchSemitones(p - 12); });
        addSlider(tuneCard, "总音量", 100, project.masterVolume,
                p -> p + "%", p -> { if (player != null) player.setMasterVolume(p); });
        addSection(content, tuneCard);

        // 音轨列表
        TextView tt = label("音轨", 16, INK, Typeface.BOLD);
        LinearLayout.LayoutParams ttp = wrapWrap(); ttp.topMargin = dp(22); ttp.bottomMargin = dp(10);
        content.addView(tt, ttp);
        for (int i = 0; i < project.tracks.size(); i++) addTrackCard(content, project.tracks.get(i), i);

        TextView note = label(project.demo ? "演示分轨与和弦示例全程离线运行。" : "所有分析和分轨均在手机本地处理，完全无需联网。", 11, MUTED, Typeface.NORMAL);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = matchWrap(); np.topMargin = dp(22);
        content.addView(note, np);

        setContent(page);

        player = new MultiTrackPlayer(this, project, new MultiTrackPlayer.Listener() {
            @Override public void onPrepared(int dur) {
                project.durationMs = dur;
                if (!project.demo) ProjectStore.save(MainActivity.this, project);
                timeline.setMax(dur);
                loadingLabel.setText(project.tracks.size() + " 条音轨就绪 · " + fmt(dur));
                loadingLabel.setTextColor(GREEN);
                timeLabel.setText("00:00 / " + fmt(dur));
                refreshChordTimeline(0, dur);
                // 无和弦结果时自动启动分析
                if (project.chords.isEmpty()) {
                    startChord();
                } else {
                    updateChordBanner(0);
                }
                startBeatAnalysis(project);
            }
            @Override public void onPlaybackEnded() { playButton.setText("▶"); }
            @Override public void onError(String m) { loadingLabel.setText(m); loadingLabel.setTextColor(DANGER); toast(m); }
        });
        player.prepare();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    // ═══════════════ 功能实现 ═══════════════

    private void startChord() {
        if (analyzing) { toast("和弦分析进行中，请稍候"); return; }
        if (currentProject == null || currentProject.tracks.isEmpty()) { toast("无可分析的音轨"); return; }
        analyzing = true;
        chordBanner.setText("正在离线分析和弦…");
        AudioProject.TrackSpec t = currentProject.tracks.get(0);
        String src = t.uri != null && !t.uri.isEmpty() ? t.uri : "android.resource://" + getPackageName() + "/" + t.rawResId;
        ChordAnalyzerEngine.analyzeAsync(this, src, new ChordAnalyzerEngine.Callback() {
            @Override public void onProgress(float p) { handler.post(() -> chordBanner.setText("分析中  ·  " + Math.round(p * 100) + "%")); }
            @Override public void onComplete(List<ChordAnalyzerEngine.ChordSegment> chords) {
                handler.post(() -> {
                    analyzing = false;
                    if (currentProject != null) { currentProject.chords.clear(); currentProject.chords.addAll(chords); ProjectStore.save(MainActivity.this, currentProject); int p = player != null ? player.getCurrentPosition() : 0; updateChordBanner(p); refreshChordTimeline(p, player != null ? player.getDuration() : 1); toast("和弦分析完成！共 " + chords.size() + " 段"); }
                });
            }
            @Override public void onError(String m) { handler.post(() -> { analyzing = false; chordBanner.setText("当前和弦  ·  --"); toast(m); }); }
        });
    }

    private void startBeatAnalysis(AudioProject project) {
        if (!project.beatTimesMs.isEmpty() || project.tracks.isEmpty()) return;
        AudioProject.TrackSpec t = project.tracks.get(0);
        String src = t.uri != null && !t.uri.isEmpty() ? t.uri : "android.resource://" + getPackageName() + "/" + t.rawResId;
        BeatAnalyzerEngine.analyzeAsync(this, src, new BeatAnalyzerEngine.Callback() {
            @Override public void onComplete(List<Long> beats) {
                handler.post(() -> { if (currentProject == project) { project.beatTimesMs.clear(); project.beatTimesMs.addAll(beats); ProjectStore.save(MainActivity.this, project); refreshChordTimeline(player != null ? player.getCurrentPosition() : 0, player != null ? player.getDuration() : 1); } });
            }
            @Override public void onError(String message) { /* 没有拍点时仍可使用和弦时间线 */ }
        });
    }


    private void startSplit() {
        if (splitting) { toast("AI 分轨进行中，请稍候"); return; }
        if (currentProject == null || currentProject.tracks.isEmpty()) { toast("无可分轨的音频"); return; }
        splitting = true;
        AudioProject.TrackSpec t = currentProject.tracks.get(0);
        final String src = t.uri != null && !t.uri.isEmpty() ? t.uri : "android.resource://" + getPackageName() + "/" + t.rawResId;
        loadingLabel.setText("正在加载 AI 模型…");
        loadingLabel.setTextColor(BLUE);

        // 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2001);
            }
        }

        final File outDir = new File(getCacheDir(), "stems_" + System.currentTimeMillis());

        // 注册回调：ForegroundService 内的 TasnetSeparator 通过 listener 通知 UI
        SeparationService.setListener(new SeparationService.ProgressListener() {
            @Override public void onProgress(String stage, float p) {
                handler.post(() -> loadingLabel.setText(stage));
            }
            @Override public void onComplete(File[] files) {
                handler.post(() -> {
                    splitting = false;
                    getSharedPreferences("sep_state", MODE_PRIVATE).edit().remove("active_project").apply();
                    if (currentProject != null) {
                        currentProject.tracks.clear();
                        currentProject.hasStems = true;
                        for (int i = 0; i < files.length && i < 4; i++) {
                            currentProject.tracks.add(new AudioProject.TrackSpec(
                                    TasnetSeparator.STEM_LABELS[i],
                                    files[i].getAbsolutePath(),
                                    TasnetSeparator.STEM_NAMES[i]));
                        }
                        ProjectStore.save(MainActivity.this, currentProject);
                        toast("AI 分轨完成！已生成 4 条独立音轨");
                        openProject(currentProject);
                    }
                });
            }
            @Override public void onError(String m) {
                handler.post(() -> { splitting = false; getSharedPreferences("sep_state", MODE_PRIVATE).edit().remove("active_project").apply(); loadingLabel.setText(m); loadingLabel.setTextColor(DANGER); toast(m); });
            }
        });

        // 通过 ForegroundService 启动分轨，后台不被系统杀死
        SeparationService.start(this, src, outDir);
    }

    private void startExport() {
        if (exporting) { toast("导出进行中，请稍候"); return; }
        if (currentProject == null || currentProject.tracks.isEmpty()) { toast("无可导出的内容"); return; }
        exporting = true;
        loadingLabel.setText("正在渲染导出…");
        AudioExporter.exportMixAsync(this, currentProject, new AudioExporter.Callback() {
            @Override public void onProgress(float p) { handler.post(() -> loadingLabel.setText("导出中  ·  " + Math.round(p * 100) + "%")); }
            @Override public void onComplete(File f) {
                handler.post(() -> {
                    exporting = false;
                    loadingLabel.setText("导出成功: " + f.getName());
                    new AlertDialog.Builder(MainActivity.this).setTitle("导出成功").setMessage("文件已保存至：\n" + f.getAbsolutePath()).setPositiveButton("确定", null).show();
                });
            }
            @Override public void onError(String m) { handler.post(() -> { exporting = false; toast(m); }); }
        });
    }

    private void showChordList() {
        if (currentProject == null || currentProject.chords.isEmpty()) { toast("暂无和弦分析结果"); return; }
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (ChordAnalyzerEngine.ChordSegment c : currentProject.chords) {
            int ts = (int) (c.timeMs / 1000);
            sb.append(String.format(Locale.getDefault(), "%d.  %-6s  @ %d:%02d  (%.0f%%)\n", idx++, c.chordName, ts / 60, ts % 60, c.confidence * 100));
        }
        new AlertDialog.Builder(this).setTitle("和弦走向 (" + currentProject.chords.size() + " 段)").setMessage(sb.toString().trim()).setPositiveButton("关闭", null).show();
    }

    // ═══════════════ 播放控制 ═══════════════

    private final Runnable ticker = new Runnable() {
        @Override public void run() { updateProgress(); if (showingPlayer && player != null) handler.postDelayed(this, 40); }
    };

    private void updateProgress() {
        if (!showingPlayer || player == null || !player.isPrepared()) return;
        int pos = player.getCurrentPosition();
        int dur = Math.max(1, player.getDuration());
        if (loopA >= 0 && loopB > loopA && pos >= loopB - 35 && player.isPlaying()) { player.seekTo(loopA); pos = loopA; }
        if (!userSeeking) timeline.setProgress(pos);
        waveform.setProgress(pos / (float) dur);
        timeLabel.setText(fmt(pos) + " / " + fmt(dur));
        playButton.setText(player.isPlaying() ? "Ⅱ" : "▶");
        updateChordBanner(pos);
        refreshChordTimeline(pos, dur);
        if (player.isPlaying() && System.currentTimeMillis() - lastSyncAt >= 2000) { player.syncIfNeeded(); lastSyncAt = System.currentTimeMillis(); }
    }

    private void updateChordBanner(int ms) {
        if (chordBanner == null || currentProject == null || currentProject.chords.isEmpty()) return;
        String active = "--";
        for (ChordAnalyzerEngine.ChordSegment c : currentProject.chords) { if (ms >= c.timeMs) active = displayChordName(c.chordName); else break; }
        chordBanner.setText("当前和弦  ·  " + active);
    }

    private String displayChordName(String name) {
        if (name == null) return "—";
        if (name.startsWith("D#")) return "Eb" + name.substring(2);
        if (name.startsWith("A#")) return "Bb" + name.substring(2);
        return name;
    }

    private void refreshChordTimeline(int pos, int dur) {
        if (chordTimeline != null && currentProject != null)
            chordTimeline.setData(currentProject.chords, currentProject.beatTimesMs, dur, pos);
    }

    private void editChordAt(int index) {
        if (currentProject == null || index < 0 || index >= currentProject.chords.size()) return;
        final String[] choices = {"C", "C#", "D", "Eb", "E", "F", "F#", "G", "G#", "A", "Bb", "B"};
        final String[] types = {"", "m", "7", "m7", "maj7"};
        String current = currentProject.chords.get(index).chordName;
        int root = 0, type = 0;
        for (int i = 0; i < choices.length; i++) if (current.startsWith(choices[i])) { root = i; break; }
        if (current.startsWith("D#")) root = 3;
        if (current.startsWith("A#")) root = 10;
        String suffix = current.substring(Math.min(current.length(), choices[root].length()));
        for (int i = 1; i < types.length; i++) if (suffix.equals(types[i])) { type = i; break; }
        LinearLayout box = vbox();
        android.widget.Spinner roots = new android.widget.Spinner(this);
        roots.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices)); roots.setSelection(root);
        android.widget.Spinner qualities = new android.widget.Spinner(this);
        qualities.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"大三和弦", "小三和弦", "属七", "小七", "大七"})); qualities.setSelection(type);
        box.addView(roots); box.addView(qualities);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("修正和弦").setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    ChordAnalyzerEngine.ChordSegment old = currentProject.chords.get(index);
                    currentProject.chords.set(index, new ChordAnalyzerEngine.ChordSegment(old.timeMs, choices[roots.getSelectedItemPosition()] + types[qualities.getSelectedItemPosition()], old.defaultChordName, old.confidence));
                    ProjectStore.save(this, currentProject); refreshChordTimeline(player != null ? player.getCurrentPosition() : 0, player != null ? player.getDuration() : 1); updateChordBanner(player != null ? player.getCurrentPosition() : 0);
                }).setNeutralButton("恢复默认", (d, w) -> {
                    ChordAnalyzerEngine.ChordSegment old = currentProject.chords.get(index);
                    currentProject.chords.set(index, new ChordAnalyzerEngine.ChordSegment(old.timeMs, old.defaultChordName, old.confidence));
                    ProjectStore.save(this, currentProject); refreshChordTimeline(player != null ? player.getCurrentPosition() : 0, player != null ? player.getDuration() : 1); updateChordBanner(player != null ? player.getCurrentPosition() : 0);
                }).setNegativeButton("取消", null).create();
        dialog.show();
    }

    private void togglePlay() {
        if (player == null || !player.isPrepared()) { toast("音轨仍在读取"); return; }
        player.toggle();
        playButton.setText(player.isPlaying() ? "Ⅱ" : "▶");
    }

    private void seekRel(int d) {
        if (player == null || !player.isPrepared()) return;
        int t = Math.max(0, Math.min(player.getCurrentPosition() + d, player.getDuration()));
        player.seekTo(t); timeline.setProgress(t);
    }

    private void setLoopA() { if (player == null || !player.isPrepared()) return; loopA = player.getCurrentPosition(); if (loopB <= loopA) loopB = -1; refreshLoop(); }
    private void setLoopB() {
        if (player == null || !player.isPrepared()) return;
        int p = player.getCurrentPosition();
        if (loopA < 0) { toast("请先设置 A 点"); return; }
        if (p - loopA < 500) { toast("B 点需比 A 点晚至少 0.5 秒"); return; }
        loopB = p; refreshLoop();
    }
    private void clearLoop() { loopA = -1; loopB = -1; refreshLoop(); }

    private void refreshLoop() {
        if (loopLabel == null || waveform == null || player == null) return;
        if (loopA < 0) loopLabel.setText("未设置");
        else if (loopB < 0) loopLabel.setText("A " + fmt(loopA) + " · 等 B");
        else loopLabel.setText(fmt(loopA) + " — " + fmt(loopB));
        int d = Math.max(1, player.getDuration());
        waveform.setLoop(loopA < 0 ? -1f : loopA / (float) d, loopB < 0 ? -1f : loopB / (float) d);
    }

    private void stopPlayer(boolean save) {
        handler.removeCallbacks(ticker);
        if (save && currentProject != null && !currentProject.demo) ProjectStore.save(this, currentProject);
        if (player != null) { player.release(); player = null; }
        currentProject = null; userSeeking = false;
    }

    private void renameProject(TextView tv) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentProject.title);
        input.setSelectAllOnFocus(true);
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(dp(18), 0, dp(18), 0);
        holder.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle("项目名称").setView(holder).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> { String n = input.getText().toString().trim(); if (!n.isEmpty()) { currentProject.title = n; tv.setText(n); ProjectStore.save(this, currentProject); } }).show();
    }

    // ═══════════════ 音轨卡片 ═══════════════

    private void addTrackCard(LinearLayout parent, AudioProject.TrackSpec track, int idx) {
        LinearLayout card = sectionCard();
        LinearLayout hd = hbox();
        TextView dot = label("●", 16, trackColor(idx), Typeface.NORMAL);
        hd.addView(dot);
        TextView nm = label(track.name, 14, INK, Typeface.BOLD);
        LinearLayout.LayoutParams nmp = weightedWrap(1f); nmp.leftMargin = dp(8);
        hd.addView(nm, nmp);
        TextView mute = smallBtn("静音", MUTED);
        TextView solo = smallBtn("独奏", MUTED);
        updateToggle(mute, track.mute, CORAL);
        updateToggle(solo, track.solo, MAGENTA);
        mute.setOnClickListener(v -> { track.mute = !track.mute; updateToggle(mute, track.mute, CORAL); if (player != null) player.setTrackMute(idx, track.mute); });
        solo.setOnClickListener(v -> { track.solo = !track.solo; updateToggle(solo, track.solo, MAGENTA); if (player != null) player.setTrackSolo(idx, track.solo); });
        hd.addView(mute, new LinearLayout.LayoutParams(dp(58), dp(36)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(58), dp(36)); slp.leftMargin = dp(6);
        hd.addView(solo, slp);
        card.addView(hd, matchWrap());

        addSlider(card, "音量", 100, track.volume,
                p -> p + "%", p -> { track.volume = p; if (player != null) player.setTrackVolume(idx, p); });
        addSlider(card, "声像", 200, Math.round((track.pan + 1f) * 100),
                p -> { int v = p - 100; if (Math.abs(v) < 3) return "居中"; return v < 0 ? "左 " + Math.abs(v) + "%" : "右 " + v + "%"; },
                p -> { track.pan = (p - 100) / 100f; if (player != null) player.setTrackPan(idx, track.pan); });

        LinearLayout.LayoutParams cp = matchWrap(); cp.bottomMargin = dp(10);
        parent.addView(card, cp);
    }

    // ═══════════════ UI 组件工厂 ═══════════════

    private void addHomeCard(LinearLayout parent, String title, String desc, String icon, int accent, View.OnClickListener l) {
        LinearLayout card = hbox();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), DIVIDER);
        card.setBackground(rippleFrom(bg));
        card.setOnClickListener(l);
        TextView ic = label(icon, 24, accent, Typeface.BOLD);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(roundBg(alpha(accent, 40), 14));
        card.addView(ic, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout words = vbox();
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = dp(14);
        card.addView(words, wp);
        words.addView(label(title, 15, INK, Typeface.BOLD));
        TextView d = label(desc, 12, MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams dp2 = wrapWrap(); dp2.topMargin = dp(3);
        words.addView(d, dp2);
        card.addView(label("›", 24, MUTED, Typeface.NORMAL));
        LinearLayout.LayoutParams cp = matchWrap(); cp.bottomMargin = dp(12);
        parent.addView(card, cp);
    }

    private void addIllustrationCard(LinearLayout parent) {
        LinearLayout card = vbox();
        card.setClipToOutline(true);
        card.setBackground(rippleFrom(gradientRounded(SURFACE, Color.rgb(255, 244, 215), 22)));
        card.setElevation(dp(6));
        card.setOnClickListener(v -> openPicker());
        prepareTiltCard(card);

        ImageView cover = new ImageView(this);
        cover.setImageResource(R.drawable.demo_cover);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172)));

        LinearLayout copy = vbox();
        copy.setPadding(dp(16), dp(13), dp(16), dp(15));
        copy.addView(badge("ChordStem 视觉音色", MAGENTA));
        TextView title = label("把旋律拆开，再重新组合", 17, INK, Typeface.BOLD);
        LinearLayout.LayoutParams tp = wrapWrap(); tp.topMargin = dp(8);
        copy.addView(title, tp);
        TextView hint = label("导入一首歌，让每个声部都拥有自己的颜色。", 11, MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams hp = wrapWrap(); hp.topMargin = dp(3);
        copy.addView(hint, hp);
        card.addView(copy, matchWrap());

        LinearLayout.LayoutParams cp = matchWrap(); cp.topMargin = dp(12); cp.bottomMargin = dp(8);
        parent.addView(card, cp);
    }

    private void projectCard(LinearLayout parent, AudioProject p) {
        final int pIdx = parent.getChildCount();  // 用位置做颜色索引
        LinearLayout card = hbox();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cbg = new GradientDrawable();
        int accentColor = trackColor(pIdx);
        cbg = gradientRounded(SURFACE, alpha(accentColor, 24), 20);
        cbg.setCornerRadius(dp(18));
        cbg.setStroke(dp(1), alpha(accentColor, 90));
        card.setBackground(rippleFrom(cbg));
        prepareTiltCard(card);
        card.setOnClickListener(v -> {
            if (selectingProjects) {
                if (!selectedProjectIds.add(p.id)) selectedProjectIds.remove(p.id);
                fillProjectList(projectListView);
            } else openProject(p);
        });
        card.setOnLongClickListener(v -> { confirmDelete(p); return true; });

        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(trackColor(pIdx));
        barBg.setCornerRadius(dp(2));
        bar.setBackground(barBg);
        card.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(78)));

        CardArtworkView artwork = new CardArtworkView(this, accentColor);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        artParams.leftMargin = dp(10);
        artParams.rightMargin = dp(12);
        card.addView(artwork, artParams);

        LinearLayout w = vbox();
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = dp(14);
        card.addView(w, wp);
        w.addView(label(p.title, 14, INK, Typeface.BOLD));
        String date = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(p.updatedAt));
        w.addView(label("时长 " + projectDuration(p) + "  ·  " + p.tracks.size() + " 轨", 11, MUTED, Typeface.NORMAL));
        w.addView(label("BPM " + projectBpm(p) + "  ·  调式 " + projectMode(p), 11, MUTED, Typeface.NORMAL));
        w.addView(label("上次打开  " + date, 10, Color.rgb(166, 157, 137), Typeface.NORMAL));
        if (selectingProjects) {
            boolean selected = selectedProjectIds.contains(p.id);
            card.addView(label(selected ? "✓" : "□", 22, selected ? BLUE : MUTED, Typeface.BOLD));
        } else {
            card.addView(label("›", 22, MUTED, Typeface.NORMAL));
        }

        LinearLayout.LayoutParams cp = matchWrap(); cp.bottomMargin = dp(10);
        parent.addView(card, cp);
    }

    private String projectDuration(AudioProject p) {
        if (p.durationMs <= 0) return "待分析";
        return fmt((int) Math.min(Integer.MAX_VALUE, p.durationMs));
    }

    private String projectBpm(AudioProject p) {
        if (p.beatTimesMs.size() < 2) return "--";
        int count = Math.min(p.beatTimesMs.size() - 1, 48);
        long intervalSum = 0;
        for (int i = 1; i <= count; i++) {
            intervalSum += Math.max(1L, p.beatTimesMs.get(i) - p.beatTimesMs.get(i - 1));
        }
        if (intervalSum <= 0) return "--";
        return String.valueOf(Math.round(60000f * count / intervalSum));
    }

    private String projectMode(AudioProject p) {
        if (p.chords.size() < 2) return "待分析";
        final String[] notes = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        final double[] majorProfile = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
        final double[] minorProfile = {6.33, 2.68, 3.52, 5.38, 2.60, 2.54, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
        double[] chroma = new double[12];
        for (int i = 0; i < p.chords.size(); i++) {
            ChordAnalyzerEngine.ChordSegment segment = p.chords.get(i);
            int root = chordRootPc(segment.chordName);
            if (root < 0) continue;
            long end = i + 1 < p.chords.size() ? p.chords.get(i + 1).timeMs : p.durationMs;
            long duration = Math.max(1L, end - segment.timeMs);
            double weight = Math.max(0.25, segment.confidence) * Math.min(duration, 30_000L);
            boolean minor = isMinorChord(segment.chordName);
            addModeChord(chroma, root, minor, weight);
        }
        double total = 0;
        for (double value : chroma) total += value;
        if (total <= 0) return "待分析";

        int bestRoot = 0;
        boolean bestMinor = false;
        double bestScore = -Double.MAX_VALUE;
        for (int tonic = 0; tonic < 12; tonic++) {
            double majorScore = modeScore(chroma, majorProfile, tonic);
            if (majorScore > bestScore) { bestScore = majorScore; bestRoot = tonic; bestMinor = false; }
            double minorScore = modeScore(chroma, minorProfile, tonic);
            if (minorScore > bestScore) { bestScore = minorScore; bestRoot = tonic; bestMinor = true; }
        }
        return notes[bestRoot] + (bestMinor ? " 小调" : " 大调");
    }

    private static void addModeChord(double[] chroma, int root, boolean minor, double weight) {
        chroma[root] += weight * 1.25;
        chroma[(root + (minor ? 3 : 4)) % 12] += weight;
        chroma[(root + 7) % 12] += weight * 0.95;
        if (minor) chroma[(root + 10) % 12] += weight * 0.35;
    }

    private static double modeScore(double[] chroma, double[] profile, int tonic) {
        double score = 0;
        for (int pc = 0; pc < 12; pc++) score += chroma[pc] * profile[(pc - tonic + 12) % 12];
        return score;
    }

    private static int chordRootPc(String chord) {
        if (chord == null || chord.isEmpty()) return -1;
        char c = Character.toUpperCase(chord.charAt(0));
        String roots = "CDEFGAB";
        int base = roots.indexOf(c);
        if (base < 0) return -1;
        int[] pcs = {0, 2, 4, 5, 7, 9, 11};
        int pc = pcs[base];
        if (chord.length() > 1 && chord.charAt(1) == '#') pc = (pc + 1) % 12;
        return pc;
    }

    private static boolean isMinorChord(String chord) {
        int rootLength = chord != null && chord.length() > 1 && chord.charAt(1) == '#' ? 2 : 1;
        return chord != null && chord.length() > rootLength && chord.charAt(rootLength) == 'm';
    }

    private void confirmDelete(AudioProject p) {
        new AlertDialog.Builder(this).setTitle("删除项目？").setMessage("只移除记录，不删原文件。").setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> { ProjectStore.remove(this, p.id); showHome(); }).show();
    }

    // ═══════════════ 文件选择 ═══════════════

    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        try { startActivityForResult(i, PICK_SINGLE); } catch (Exception e) { toast("无可用文件选择器"); }
    }

    @Override
    protected void onActivityResult(int rc, int resultCode, Intent data) {
        super.onActivityResult(rc, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (rc != PICK_SINGLE) return;

        Set<Uri> uris = new LinkedHashSet<>();
        ClipData cd = data.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) {
            Uri u = cd.getItemAt(i).getUri(); if (u != null) uris.add(u);
        }
        if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) { toast("未选择文件"); return; }

        AudioProject p = new AudioProject();
        List<Uri> sel = new ArrayList<>(uris);
        if (sel.size() > 12) { sel = sel.subList(0, 12); toast("最多 12 轨，已保留前 12 条"); }
        for (Uri u : sel) {
            persistPerm(u, data.getFlags());
            p.tracks.add(new AudioProject.TrackSpec(stripExt(queryName(u)), u.toString()));
        }
        p.title = p.tracks.size() == 1 ? p.tracks.get(0).name : p.tracks.get(0).name + " 等 " + p.tracks.size() + " 轨";
        ProjectStore.save(this, p);
        openProject(p);
    }

    private void persistPerm(Uri u, int flags) {
        int f = flags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (f == 0) f = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try { getContentResolver().takePersistableUriPermission(u, f); } catch (Exception ignored) {}
    }

    private String queryName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (col >= 0) { String n = c.getString(col); if (n != null && !n.trim().isEmpty()) return n; } }
        } catch (Exception ignored) {}
        String last = u.getLastPathSegment();
        return last == null || last.isEmpty() ? "本地音频" : last;
    }

    // ═══════════════ 工具方法 ═══════════════

    private void addSlider(LinearLayout parent, String label, int max, int init, ValueFormatter fmt, ValueListener listen) {
        LinearLayout hd = hbox();
        TextView nm = label(label, 12, MUTED, Typeface.NORMAL);
        TextView val = label(fmt.format(Math.max(0, Math.min(init, max))), 12, INK, Typeface.BOLD);
        hd.addView(nm, weightedWrap(1f)); hd.addView(val);
        LinearLayout.LayoutParams hp = matchWrap(); hp.topMargin = dp(12);
        parent.addView(hd, hp);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max); sb.setProgress(Math.max(0, Math.min(init, max)));
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(ACCENT));
        sb.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) { val.setText(fmt.format(p)); if (fromUser) listen.onValue(p); }
        });
        parent.addView(sb, matchWrap());
    }

    private void addSection(LinearLayout parent, LinearLayout section) { LinearLayout.LayoutParams p = matchWrap(); p.topMargin = dp(12); parent.addView(section, p); }
    private LinearLayout sectionCard() {
        LinearLayout c = vbox();
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(SURFACE);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), DIVIDER);
        c.setBackground(g);
        return c;
    }
    private LinearLayout vbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout hbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }

    private TextView label(String v, float sp, int color, int style) { TextView t = new TextView(this); t.setText(v); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(Typeface.create("sans-serif", style)); return t; }
    private TextView badge(String v, int color) { TextView t = label(v, 10, color, Typeface.BOLD); t.setGravity(Gravity.CENTER); t.setPadding(dp(9), dp(5), dp(9), dp(5)); t.setBackground(roundBg(alpha(color, 40), 16)); return t; }
    private TextView iconLabel(String key, float sp, int color) {
        String glyph;
        if ("menu".equals(key)) glyph = "≡";
        else if ("home".equals(key)) glyph = "⌂";
        else if ("info".equals(key)) glyph = "ⓘ";
        else if ("upload".equals(key)) glyph = "↑";
        else if ("layers".equals(key)) glyph = "≋";
        else if ("play".equals(key)) glyph = "▶";
        else if ("back".equals(key)) glyph = "‹";
        else if ("code".equals(key)) glyph = "⌘";
        else glyph = key;
        TextView t = label(glyph, sp, color, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }
    private TextView navIcon(String v) { TextView t = iconLabel(v, 20, INK); t.setGravity(Gravity.CENTER); t.setBackground(ripple(Color.TRANSPARENT, 12)); return t; }
    private TextView btn(String v, int bg, int fg, float sp) { TextView t = label(v, sp, fg, Typeface.BOLD); t.setGravity(Gravity.CENTER); t.setBackground(ripple(bg, 12)); return t; }
    private TextView smallBtn(String v, int fg) { return btn(v, SURFACE2, fg, 12); }
    private TextView transportBtn(String v, boolean prim) { return btn(v, prim ? BRAND : SURFACE2, prim ? INK : MUTED, prim ? 25 : 13); }

    private void updateToggle(TextView btn, boolean active, int c) { btn.setTextColor(active ? Color.WHITE : MUTED); btn.setBackground(ripple(active ? c : SURFACE2, 12)); }

    private int trackColor(int idx) {
        if (currentProject != null && idx < currentProject.tracks.size()) {
            String s = currentProject.tracks.get(idx).stemType;
            if (AudioProject.TrackSpec.S_VOCALS.equals(s)) return MAGENTA;
            if (AudioProject.TrackSpec.S_DRUMS.equals(s)) return CORAL;
            if (AudioProject.TrackSpec.S_BASS.equals(s)) return BLUE;
            if (AudioProject.TrackSpec.S_OTHER.equals(s)) return GREEN;
        }
        int[] cs = {MAGENTA, CORAL, BLUE, GREEN, PINK, Color.rgb(0, 160, 140)};
        return cs[idx % cs.length];
    }

    private RippleDrawable rippleFrom(GradientDrawable content) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(26, 0, 0, 0)), content, null);
    }

    private GradientDrawable cardBg(int c, int r) { GradientDrawable g = new GradientDrawable(); g.setColor(c); g.setCornerRadius(dp(r)); return g; }
    private GradientDrawable gradientRounded(int start, int end, int r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        g.setCornerRadius(dp(r));
        return g;
    }
    private GradientDrawable notchBg() {
        return gradientRounded(Color.rgb(30, 25, 20), Color.rgb(72, 45, 38), 30);
    }

    private void prepareTiltCard(View card) {
        card.setCameraDistance(getResources().getDisplayMetrics().density * 15000f);
        card.setElevation(dp(8));
        tiltCards.add(card);
    }

    private static float clampTilt(float value) {
        return Math.max(-15f, Math.min(15f, value));
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR || tiltCards.isEmpty()) return;
        float[] rotation = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        SensorManager.getOrientation(rotation, orientation);
        float pitch = (float) Math.toDegrees(orientation[1]);
        float roll = (float) Math.toDegrees(orientation[2]);
        float rx = clampTilt(pitch);
        float ry = clampTilt(-roll);
        for (View card : tiltCards) {
            if (card.getWindowToken() != null) {
                card.setRotationX(rx);
                card.setRotationY(ry);
                card.setTranslationZ(Math.max(0f, 12f - (Math.abs(rx) + Math.abs(ry)) * 0.18f) * getResources().getDisplayMetrics().density);
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static final class CardArtworkView extends View {
        private final int accent;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        CardArtworkView(android.content.Context context, int accent) { super(context); this.accent = accent; setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            paint.setShader(new LinearGradient(0, 0, w, h, accent, Color.rgb(30, 24, 20), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(0, 0, w, h, 16, 16, paint);
            paint.setShader(null);
            paint.setColor(Color.argb(110, 255, 255, 255));
            canvas.drawCircle(w * .72f, h * .28f, w * .22f, paint);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f);
            canvas.drawCircle(w * .35f, h * .52f, w * .20f, paint);
            canvas.drawCircle(w * .35f, h * .52f, w * .08f, paint);
            paint.setStyle(Paint.Style.FILL);
            float[] bars = {0.22f, 0.48f, 0.72f, 0.38f, 0.60f};
            for (int i = 0; i < bars.length; i++) {
                float left = w * (.56f + i * .075f);
                float top = h * (0.70f - bars[i] * .30f);
                canvas.drawRoundRect(left, top, left + w * .045f, h * .70f, 3, 3, paint);
            }
        }
    }
    private GradientDrawable texture(int start, int end) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        g.setCornerRadius(0);
        return g;
    }
    private GradientDrawable roundBg(int c, int r) { return cardBg(c, r); }
    private RippleDrawable ripple(int c, int r) { return new RippleDrawable(ColorStateList.valueOf(Color.argb(24, 0, 0, 0)), cardBg(c, r), cardBg(c, r)); }

    private void applyInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, wi) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets insets = wi.getInsets(WindowInsets.Type.systemBars());
                top = insets.top; bottom = insets.bottom;
                left = insets.left; right = insets.right;
            } else {
                top = wi.getSystemWindowInsetTop();
                bottom = wi.getSystemWindowInsetBottom();
                left = wi.getSystemWindowInsetLeft();
                right = wi.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return wi;
        });
        root.requestApplyInsets();
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams wrapWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weighted(int h, float w) { return new LinearLayout.LayoutParams(0, h, w); }
    private LinearLayout.LayoutParams weightedWrap() { return weightedWrap(1f); }
    private LinearLayout.LayoutParams weightedWrap(float w) { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w); }
    private LinearLayout.LayoutParams weightedWithEnd(float w, int endMarginDp) {
        LinearLayout.LayoutParams p = weightedWrap(w);
        p.rightMargin = dp(endMarginDp);
        return p;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private static String stripExt(String v) { String c = v == null ? "音频" : v.trim(); int dot = c.lastIndexOf('.'); if (dot > 0 && dot >= c.length() - 6) c = c.substring(0, dot); return c.isEmpty() ? "音频" : c; }
    private static String fmt(int ms) { int s = Math.max(0, ms / 1000); int h = s / 3600, m = (s % 3600) / 60, sec = s % 60; return h > 0 ? String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, sec) : String.format(Locale.getDefault(), "%d:%02d", m, sec); }
    private static int alpha(int c, int a) { return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)); }

    private interface ValueFormatter { String format(int p); }
    private interface ValueListener { void onValue(int p); }
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar sb, int p, boolean f) {}
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }

    // ═══════════════ 生命周期 ═══════════════

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!showingPlayer && !drawerOpen) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                edgeDownX = event.getX();
                edgeDownY = event.getY();
                edgeTracking = edgeDownX <= dp(30);
            } else if (edgeTracking && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - edgeDownX;
                float dy = Math.abs(event.getY() - edgeDownY);
                if (dx >= dp(54) && dy <= dp(72)) {
                    edgeTracking = false;
                    openDrawer();
                    return true;
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                edgeTracking = false;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onResume() {
        super.onResume();
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override protected void onPause() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onPause();
        if (player != null && player.isPlaying()) { player.pause(); if (playButton != null) playButton.setText("▶"); }
        if (currentProject != null && !currentProject.demo) ProjectStore.save(this, currentProject);
        // 分轨进行中时保存当前项目 ID，以便从通知返回时恢复
        if (splitting && currentProject != null) {
            getSharedPreferences("sep_state", MODE_PRIVATE).edit()
                    .putString("active_project", currentProject.id).apply();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 从通知点击返回：如果当前不在项目页且正在分轨，恢复项目页
        if (!showingPlayer && splitting) {
            String projId = getSharedPreferences("sep_state", MODE_PRIVATE).getString("active_project", null);
            if (projId != null) {
                List<AudioProject> projects = ProjectStore.load(this);
                for (AudioProject p : projects) {
                    if (projId.equals(p.id)) { openProject(p); break; }
                }
            }
        }
    }
    @Override public void onBackPressed() {
        if (drawerOpen) { closeDrawer(); return; }
        if (showingPlayer) { showHome(); return; }
        if (currentTab != 0) { switchTab(0); return; }
        super.onBackPressed();
    }
    @Override protected void onDestroy() { stopPlayer(true); super.onDestroy(); }
}
