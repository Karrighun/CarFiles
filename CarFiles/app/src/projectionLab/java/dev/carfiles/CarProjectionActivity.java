package dev.carfiles;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.apps.auto.sdk.CarActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import java.util.concurrent.Executors;

/** Minimal projection experiment, with no host hooks or driving-state overrides. */
@androidx.media3.common.util.UnstableApi
public class CarProjectionActivity extends CarActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private androidx.media3.exoplayer.ExoPlayer player;
    private WebView web;
    private ImageView mirror;
    private TextView state;
    private boolean visible;
    private long lastShownSeq;
    private long lastStamp;
    private final AtomicReference<Frame> pending = new AtomicReference<>();
    private final AtomicBoolean decoding = new AtomicBoolean();
    private static final int BG = android.graphics.Color.rgb(11, 18, 32);
    private static final int SURFACE = android.graphics.Color.rgb(27, 39, 64);
    private static final int ACCENT = android.graphics.Color.rgb(76, 141, 255);
    private static final int MUTED = android.graphics.Color.rgb(142, 160, 188);

    /** Кадры приходят из ProjectionService; декодируем только самый свежий, остальные пропускаем. */
    private final Function1<Frame, Unit> listener = frame -> {
        if (visible && mirror != null) { pending.set(frame); drain(); }
        return Unit.INSTANCE;
    };
    private void drain() {
        if (!decoding.compareAndSet(false, true)) return;
        images.execute(() -> {
            try {
                Frame f;
                while ((f = pending.getAndSet(null)) != null) {
                    final Frame frame = f;
                    Bitmap bitmap = BitmapFactory.decodeByteArray(frame.getJpeg(), 0, frame.getJpeg().length);
                    if (bitmap == null) continue;
                    main.post(() -> {
                        if (!visible || mirror == null) return;
                        mirror.setImageBitmap(bitmap);
                        lastShownSeq = frame.getSeq();
                        long now = System.currentTimeMillis();
                        if (now - lastStamp > 1000) {
                            lastStamp = now;
                            getSharedPreferences("car_diagnostics", MODE_PRIVATE).edit().putLong("car_last_frame", now).apply();
                            state.setText("Экран телефона · " + String.format(java.util.Locale.US, "%.1f", ProjectionState.INSTANCE.getFps()) + " к/с");
                        }
                    });
                }
            } finally {
                decoding.set(false);
                if (pending.get() != null) drain();
            }
        });
    }
    /** Подсказка, пока кадров нет (трансляция не запущена на телефоне). */
    private final Runnable hint = new Runnable() {
        @Override public void run() {
            if (!visible || mirror == null) return;
            if (!ProjectionState.INSTANCE.getRunning()) state.setText("Трансляция не запущена. Откройте CarFiles на телефоне → «Трансляция экрана» → «Начать».");
            else if (ProjectionState.INSTANCE.getLatest() == null) state.setText("Ждём первый кадр…");
            main.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle savedState) { super.onCreate(savedState); getSharedPreferences("car_diagnostics", MODE_PRIVATE).edit().putLong("projection_screen_created", System.currentTimeMillis()).apply(); CarLog.add(this, "Экран машины создан"); showMenu(); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 20, 24, 20);
        layout.setBackgroundColor(BG);
        return layout;
    }
    private void button(LinearLayout parent, String title, Runnable click) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(20);
        button.setTextColor(android.graphics.Color.WHITE); button.setMinHeight(96); button.setMinimumHeight(96);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(SURFACE); bg.setCornerRadius(28f); button.setBackground(bg);
        button.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 12;
        parent.addView(button, lp);
    }
    private void clear() {
        if (player != null) { player.release(); player = null; }
        stopMirror(); mirror = null;
        if (web != null) { web.stopLoading(); web.loadUrl("about:blank"); web.destroy(); web = null; }
    }
    private void showMenu() {
        clear();
        LinearLayout layout = column();
        TextView title = new TextView(this); title.setText("CarFiles"); title.setTextSize(28f); title.setTextColor(android.graphics.Color.WHITE); layout.addView(title);
        button(layout, "Медиатека", () -> showFiles(new FileRepository(this).root()));
        button(layout, "YouTube", this::showYouTube);
        button(layout, "Экран телефона", this::showMirror);
        TextView info = new TextView(this); info.setText("Захват экрана запускается на телефоне с системным разрешением."); info.setTextColor(MUTED); layout.addView(info);
        setContentView(layout);
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void showYouTube() {
        clear();
        LinearLayout layout = column(); button(layout, "Меню", this::showMenu);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return !YouTubeNavigation.INSTANCE.isAllowed(request.getUrl().getScheme(), request.getUrl().getHost());
            }
        });
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout); web.loadUrl("https://m.youtube.com/");
    }
    private void showFiles(android.net.Uri uri) {
        clear();
        LinearLayout layout = column(); button(layout, "Меню", this::showMenu);
        if (uri == null) { TextView t = new TextView(this); t.setText("Выберите папку в CarFiles Lab на телефоне"); layout.addView(t); setContentView(layout); return; }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout rows = column(); scroll.addView(rows); layout.addView(scroll); setContentView(layout);
        images.execute(() -> {
            try {
                java.util.List<FileEntry> entries = new FileRepository(this).children(uri);
                main.post(() -> { if (!visible) return; for (FileEntry entry : entries) button(rows, entry.getName(), () -> {
                    if (entry.getDirectory()) showFiles(entry.getUri());
                    else if (entry.getKind().equals("video") || entry.getKind().equals("audio")) showMedia(entry);
                }); });
            } catch (Exception e) { main.post(() -> { TextView t = new TextView(this); t.setText("Ошибка доступа: " + e.getMessage()); rows.addView(t); }); }
        });
    }
    @androidx.media3.common.util.UnstableApi
    private void showMedia(FileEntry entry) {
        clear();
        LinearLayout layout = column();
        button(layout, "Медиатека", () -> showFiles(new FileRepository(this).root()));
        androidx.media3.ui.PlayerView view = new androidx.media3.ui.PlayerView(this);
        player = new androidx.media3.exoplayer.ExoPlayer.Builder(this).build();
        view.setPlayer(player);
        layout.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(entry.getUri())); player.prepare(); player.play();
        button(layout, "Развернуть видео", () -> { layout.removeAllViews(); layout.addView(view, new LinearLayout.LayoutParams(-1,-1)); });
    }
    private void showMirror() {
        clear();
        LinearLayout layout = column(); button(layout, "Меню", this::showMenu);
        state = new TextView(this); state.setTextColor(MUTED); state.setTextSize(16f); state.setText("Ожидание кадров…"); layout.addView(state);
        mirror = new ImageView(this); mirror.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layout.addView(mirror, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(layout);
        CarLog.add(this, "Открыт раздел «Экран телефона»");
        startMirror();
    }
    private void startMirror() {
        ProjectionState.INSTANCE.addListener(listener);
        Frame current = ProjectionState.INSTANCE.getLatest();      // кадр, который уже был до открытия экрана
        if (current != null) { pending.set(current); drain(); }
        main.post(hint);
    }
    private void stopMirror() {
        ProjectionState.INSTANCE.removeListener(listener);
        main.removeCallbacks(hint); pending.set(null);
    }

    @Override public void onStart() { super.onStart(); visible = true; CarLog.add(this, "Экран машины виден"); if (mirror != null) startMirror(); }
    @Override public void onStop() { visible = false; stopMirror(); CarLog.add(this, "Экран машины скрыт"); if (player != null) player.pause(); if (web != null) { web.onPause(); web.loadUrl("about:blank"); } super.onStop(); }
    @Override public void onBackPressed() { showMenu(); }
    @Override public void onDestroy() { visible = false; clear(); images.shutdownNow(); super.onDestroy(); }
}
