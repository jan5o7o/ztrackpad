package app.so7o.ztrackpad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.display.DeviceProductInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * ZTrackpad - a floating trackpad plus on-screen pointer, built on an
 * AccessibilityService.
 *
 * Overlay windows (all TYPE_ACCESSIBILITY_OVERLAY, all on the SURFACE display):
 *   bubble       - small draggable dot, tap to toggle the trackpad; snaps to the
 *                  nearer vertical edge on release
 *   keysBubble   - tap to toggle the keys panel; same drag + snap
 *   (the theme and display controls are NOT windows any more: they are dots docked
 *    inside the pad's title-bar area, see addDockedDot)
 *   pad          - the trackpad surface (handle bar + touch area + button row)
 *   keysPanel    - programmable keys
 *   pickerPanel  - choose which display the pointer controls
 *   cursor       - the pointer, drawn by us and moved with updateViewLayout
 *
 * Two independent displays are involved:
 *   SURFACE = where the panels live (the default display; where your fingers are)
 *   TARGET  = where the pointer and injected input go (default: same as surface;
 *             can be DeX, an HDMI/XREAL screen, or a virtual/overlay display)
 *
 * With no shizuku and no split, clicks are injected with dispatchGesture().
 * Everything else needs the Shizuku shell bridge, because dispatchGesture()
 * cannot address a display and cannot inject arbitrary keycodes.
 */
public class TrackpadService extends AccessibilityService {

    private static final String TAG = "ZTrackpad";

    /* ---- gesture tuning ---- */
    private static final int SLOP = 14;            // px before a touch counts as a move
    private static final long TAP_MS = 220;        // max duration for a tap
    private static final long LONGPRESS_MS = 520;  // hold this long without moving = long press
    private static final float SCROLL_STEP = 36f;  // px of finger travel per scroll flush
    private static final long SCROLL_THROTTLE = 40;// ms between scroll gestures
    /**
     * The edge strips scroll at this fraction of the two-finger gain, in flushes of this many
     * px of finger travel.
     *
     * Half the gain, and half the travel per flush, so a swipe down the side arrives in
     * smaller steps than a two-finger drag does. The flush is also *capped* at the same
     * amount: without that, a fast flick accumulates a lot of travel between two throttled
     * flushes and delivers it as one jump, which defeats the point of the smaller step.
     */
    private static final float EDGE_SCROLL_FACTOR = 0.5f;
    private static final float EDGE_FLUSH_PX = 12f;
    /** px of injected scroll per px of finger travel, for the two-finger drag. */
    private static final float SCROLL_GAIN = 1.4f;
    /**
     * Width of the pad's edge scroll strips, in dp.
     *
     * Fixed rather than a fraction of the pad: the pad can be resized down to a dp(240)
     * minimum, where a percentage would leave a sliver too narrow to hit.
     */
    private static final int EDGE_SCROLL_DP = 28;
    private static final long CLICK_MS = 45L;
    /**
     * How far one press of the split buttons moves the divider, as a percentage of the
     * screen height. A fixed step, not a rung to hit: the divider does not reliably land
     * where a drag asks (measured), so a nudge that visibly moves it is worth more than a
     * target it misses.
     */
    private static final float SPLIT_STEP_PERCENT = 8f;
    /** An injected divider drag: total duration, and how many MOVE steps get it there. */
    private static final int SPLIT_DRAG_MS = 240;
    private static final int SPLIT_DRAG_STEPS = 8;
    /** Keep this much of the split on each side, so a nudge cannot ask for a zero-height pane. */
    private static final float SPLIT_MIN_PERCENT = 12f;
    /**
     * Where to grab the divider relative to its centre, tried in order until the thing
     * actually moves.
     *
     * The grab region the divider window reports and the boundary between the two pane
     * frames do not agree: measured, a swipe level with the boundary did nothing while one
     * 25px above it moved the divider. So the grab point is searched rather than assumed.
     */
    private static final int[] SPLIT_GRAB_OFFSETS = {0, -25, 25, -50, 50};
    /**
     * How long to wait before injecting through the panels, and how long the panels then
     * stay non-touchable on top of the gesture itself. Two frames is enough for the window
     * manager to apply FLAG_NOT_TOUCHABLE; the margin covers the round trip back.
     */
    private static final long TOUCHABLE_SETTLE_MS = 40L;
    /** Nominal length of an injected tap, for holding the panels open long enough. */
    private static final long TAP_INJECT_MS = 80L;
    private static final long HOLD_MS = 650;
    private static final long DRAG_HOLD_MS = 260;   // hold still this long, then move = drag
    private static final long DRAG_BEAT_MS = 150;   // heartbeat that keeps the press alive
    private static final long CHUNK_MS = 400;       // stroke chunk length (too short reads as a tap)
    private static final long DRAG_STALL_MS = 2500; // no touch movement this long -> force release
    private static final long DOUBLE_TAP_MS = 300;  // tap, then touch again within this -> drag
    private static final long REPEAT_DELAY_MS = 420; // hold a key this long before it auto-repeats
    private static final long REPEAT_MS = 60;        // then repeat at this interval

    /**
     * Pointer z-order relative to the panels.
     * false = the trackpad / keys panel draw OVER the pointer (pointer is hidden
     *         whenever it sits on a panel).
     * true  = the pointer is raised above the panels and always visible.
     */
    private static final boolean CURSOR_ABOVE_PANELS = true;

    // pref-key prefixes: "" keeps the trackpad's original names so saved geometry survives
    private static final String PAD_KEY = "";
    private static final String KEYS_KEY = "k_";
    private static final String PICKER_KEY = "d_";
    private static final String SCREEN_KEY = "v_";
    private static final String THEME_KEY = "t_";

    /** Pref holding the *uniqueId* of the target display (ids are not stable). */
    private static final String PREF_TARGET_DISPLAY = "targetDisplay";

    /** Pref holding the chosen Theme id. */
    private static final String PREF_THEME = "theme";

    /** Pref holding the panel opacity multiplier. */
    private static final String PREF_OPACITY = "opacity";

    /** Pref holding a custom keys-panel layout. Absent means use DEFAULT_KEYS_SPEC. */
    private static final String PREF_KEYS = "keysSpec";

    /**
     * Pref holding whether the pad is locked against moving and resizing.
     *
     * Persisted, unlike bubble positions: a lock that forgot itself on restart would be
     * worse than no lock, because the pad would move on the next accidental brush.
     */
    private static final String PREF_LOCK = "padLocked";

    /** Opacity slider range, in percent. */
    private static final int OPACITY_MIN = 20;
    private static final int OPACITY_MAX = 100;

    /**
     * Started on a freshly created display so it has content of its own.
     *
     * A surface-backed display with nothing on it MIRRORS the default display - and our
     * floating window is drawn on the default display - so the window ends up showing
     * itself, recursively, until something covers the mirror. Starting the secondary
     * launcher gives the display a real desktop and stops the recursion.
     */
    private static final String SECONDARY_LAUNCHER =
            "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher";

    private WindowManager wm;
    private SharedPreferences prefs;
    private Handler ui;
    private DisplayManager displayManager;

    /** Size of the SURFACE display - what the panels are laid out against. */
    private int screenW = 1080, screenH = 1920;
    /** Size of the TARGET display - the coordinate space the pointer lives in. */
    private int outW = 1080, outH = 1920;
    /** The display the panels are on (always the default display). */
    private int surfaceDisplayId = Display.DEFAULT_DISPLAY;
    /** The display the pointer drives. Equal to surfaceDisplayId until the user picks. */
    private int targetDisplayId = Display.DEFAULT_DISPLAY;
    private float density = 3f;

    private View bubble, keysBubble, pad, keysPanel, pickerPanel;
    private LinearLayout pickerRows;
    private TextView virtualRow;
    private TextView screenRow;
    private View screenPanel;
    private WindowManager.LayoutParams screenPanelLp;
    private SurfaceView screenView;
    /** Height of the floating display's title bar; the surface starts below it. */
    private int screenHeaderH = 0;
    /** True when the current virtual display renders into our floating window's surface. */
    private boolean virtualDisplayHasSurface = false;
    /** True while a surface is actually attached (false after the window is hidden). */
    private boolean screenSurfaceAlive = false;
    /** Last surface size we pushed to the display, so resizes are not spammed. */
    private int screenSurfaceW = 0, screenSurfaceH = 0;
    /**
     * Id of a display we created via the shell, or -1. Created shell-side because a
     * PUBLIC task-hosting display needs CAPTURE_VIDEO_OUTPUT, which only shell holds.
     */
    private int ownVirtualDisplayId = -1;
    private CursorView cursor;
    private TextView moveChip;
    /** The pad's lock dot, so a theme rebuild can hand back a new one. */
    private LockDot lockDot;
    /** Set while a split nudge is in flight, so a held button cannot pile them up. */
    private boolean splitBusy = false;
    /** When true the pad ignores the move handle and every resize grip. */
    private boolean padLocked = false;
    private WindowManager.LayoutParams keysBubbleLp, keysPanelLp;
    private boolean keysVisible = false;
    private boolean pickerVisible = false;
    private int metaState = 0;   // sticky CTRL / ALT / SHIFT
    /** Visual preset. Never null; Theme.byId falls back to the default. */
    private Theme theme = Theme.byId(null);
    /** Panel opacity multiplier. Only panel fills scale - see fill(). */
    private float opacity = 1f;
    /** Custom keys-panel spec, or null/empty to use the built-in layout. */
    private String keysSpec = null;
    private Vibrator vib;
    private Boolean hapticsOn = null;
    private int[] shellIdsCache = null;
    private long shellIdsAt = 0L;
    private boolean refreshingList = false;
    private WindowManager.LayoutParams bubbleLp, padLp, cursorLp;
    private WindowManager.LayoutParams pickerPanelLp;
    private View themePanel;
    private WindowManager.LayoutParams themePanelLp;
    private LinearLayout themeRows;
    private LinearLayout opacityRows;
    private TextView opacityLabel;
    /** Slider value while a drag is in progress; -1 when idle. */
    private float pendingOpacity = -1f;
    private boolean themeVisible = false;

    /**
     * A second arrow, opened as an overlay ON the target display when the pointer is on a
     * display that isn't ours. A window we own cannot be composited into another display's
     * output from the phone screen, and the system pointer is not drawn on external or
     * virtual displays at all - so without this there is simply no visible pointer there.
     */
    private View targetCursor;
    private WindowManager targetWm;
    private WindowManager.LayoutParams targetCursorLp;
    private int targetCursorDisplayId = -1;

    private float cursorX, cursorY;
    private float sensitivity = 1.7f;

    private boolean padVisible = false;
    private boolean scrollMode = false;
    /** True while a touch that started in an edge strip owns the gesture - see PadTouch. */
    private boolean edgeScroll = false;
    private boolean dragArmed = false;
    private boolean dragging = false;
    private boolean holdReady = false;
    private boolean tapDrag = false;
    private long lastTapUpAt = 0L;
    private ShizukuInputHandler shizuku;
    private long dragDownAt = 0L;
    private boolean dragViaShizuku = false;   // which backend owns the current drag
    // where the last drag chunk actually ended - continueStroke() requires the next
    // path to START exactly here, or the continuation is rejected and the drag dies
    private float strokeEndX, strokeEndY;
    private long lastMoveAt;
    private Runnable holdRun;
    private Runnable beat;

    private float downX, downY, lastX, lastY;
    private long downTime;
    private boolean moved;
    private float scrollAccum;
    private long lastScrollAt;
    private GestureDescription.StrokeDescription stroke;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        prefs = getSharedPreferences("pad", MODE_PRIVATE);
        ui = new Handler(Looper.getMainLooper());
        readScreenMetrics();
        restoreTargetDisplay();
        // before anything is built: every panel reads its colours from here
        theme = Theme.byId(prefs.getString(PREF_THEME, Theme.DEFAULT_ID));
        opacity = prefs.getFloat(PREF_OPACITY, 1f);
        keysSpec = prefs.getString(PREF_KEYS, null);
        padLocked = prefs.getBoolean(PREF_LOCK, false);

        cursorX = outW / 2f;
        cursorY = outH / 2f;

        buildAll();
        setPadVisible(true);
        // The pad must win where it overlaps the keys panel, otherwise a tap on the
        // trackpad falls through to a key underneath it.
        raise(pad, padLp, "pad");
        if (CURSOR_ABOVE_PANELS) raise(cursor, cursorLp, "cursor");
        if (displayManager != null) displayManager.registerDisplayListener(displayListener, ui);
        VDisplayReceiver.service = this;
        startShizuku();
        Log.i(TAG, "connected surface=" + screenW + "x" + screenH
                + " target=display " + targetDisplayId + " " + outW + "x" + outH
                + " density=" + density);
    }

    /**
     * Overlay z-order follows add order, so raising = remove + re-add.
     * Two things depend on it:
     *  - the pad must sit above the keys panel, or taps on the trackpad in the overlap
     *    region fall through to a key underneath;
     *  - the pointer is raised last (see CURSOR_ABOVE_PANELS) so it stays visible.
     */
    private void raise(View v, WindowManager.LayoutParams lp, String what) {
        if (v == null || lp == null) return;
        try {
            wm.removeView(v);
            wm.addView(v, lp);
            Log.i(TAG, what + " raised");
        } catch (Exception e) {
            Log.w(TAG, "raise " + what + " failed: " + e);
        }
    }

    private void startShizuku() {
        shizuku = new ShizukuInputHandler(this, new ShizukuInputHandler.StateListener() {
            @Override public void onState(String text, boolean ready) {
                Log.i(TAG, "shizuku: " + text + " ready=" + ready);
                if (ready) {
                    // Shizuku has to know the target before we touch cursor visibility.
                    shizuku.setDisplayId(targetDisplayId);
                    // real mouse injection draws its own pointer - hide it, we draw ours
                    // (unless the pointer is aimed at a different display, where ours
                    // cannot represent it)
                    syncCursorMode();
                }
                updateModeUi();
                // the picker footer reads "needs Shizuku" until the bind lands, so it has
                // to be refreshed when Shizuku becomes ready (or dies)
                updateVirtualRow();
            }
        });
        shizuku.start();
    }

    /** True when we can inject real input events instead of accessibility gestures. */
    private boolean useShizuku() {
        return shizuku != null && shizuku.isReady();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* not needed */ }

    @Override
    public void onInterrupt() { /* not needed */ }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        removeAll();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        removeAll();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        int oldW = screenW, oldH = screenH;
        readScreenMetrics();
        if (oldW != screenW || oldH != screenH) {
            if (pad != null) {
                clampGeometryToScreen(padLp);
                try { wm.updateViewLayout(pad, padLp); } catch (Exception ignored) {}
                saveGeometry(padLp, PAD_KEY);
            }
            if (keysPanel != null) {
                clampGeometryToScreen(keysPanelLp);
                try { wm.updateViewLayout(keysPanel, keysPanelLp); } catch (Exception ignored) {}
                saveGeometry(keysPanelLp, KEYS_KEY);
            }
            // bubbles carry no persisted geometry, but a right-hand dot must not stay
            // parked past the new edge when the screen turns
            resnapBubble(bubble, bubbleLp, bubbleSide);
            resnapBubble(keysBubble, keysBubbleLp, keysBubbleSide);
        }
    }

    private void readScreenMetrics() {
        Rect b = wm.getCurrentWindowMetrics().getBounds();
        screenW = b.width();
        screenH = b.height();
        density = getResources().getDisplayMetrics().density;
        applyTargetMetrics();
    }

    /**
     * Apply the opacity multiplier to a panel fill.
     *
     * Only fills scale. Dimming the text and borders too would not make the panels look
     * more transparent, it would just make them unreadable - the point of turning the
     * opacity down is to see more of the app underneath while still reading the pad.
     */
    private int fill(int color) {
        int a = (color >>> 24) & 0xFF;
        int scaled = Math.round(a * opacity);
        if (scaled < 0) scaled = 0;
        if (scaled > 0xFF) scaled = 0xFF;
        return (scaled << 24) | (color & 0x00FFFFFF);
    }

    private int dp(float v) { return Math.round(v * density); }

    /**
     * Build every overlay. Order matters: it sets the z-order, so the virtual-display
     * window is first (bottom-most, under everything) and the cursor is raised last.
     */
    private void buildAll() {
        buildScreenPanel();
        buildBubble();
        buildKeysBubble();
        buildCursor();
        buildPad();
        buildKeysPanel();
        buildPickerPanel();
        buildThemePanel();
    }

    // =========================================================================
    // Theme
    // =========================================================================

    /**
     * Rebuild the panels under the current preset, keeping positions and visibility.
     *
     * A rebuild rather than a recolour, because every background is a generated
     * GradientDrawable or StateListDrawable built from theme values at construction time.
     * The four bubbles are the exception: they are restyled in place, so they do not jump
     * back to their default positions (bubble positions are not persisted).
     */
    private void applyTheme() {
        boolean keysWas = keysVisible;
        boolean pickerWas = pickerVisible;
        boolean themeWas = themeVisible;
        boolean screenWas = (screenPanel != null && screenPanel.getVisibility() == View.VISIBLE);

        // persist geometry first, or the rebuild would fall back to the defaults
        saveGeometry(padLp, PAD_KEY);
        saveGeometry(keysPanelLp, KEYS_KEY);
        saveGeometry(pickerPanelLp, PICKER_KEY);
        saveGeometry(screenPanelLp, SCREEN_KEY);

        removeViews();
        buildAll();

        restyleBubbles();
        setPadVisible(true);
        setKeysVisible(keysWas);
        if (screenWas && screenPanel != null) screenPanel.setVisibility(View.VISIBLE);
        setPickerVisible(pickerWas);
        // keep the theme panel open, so presets can be tried one after another
        setThemeVisible(themeWas);

        raise(pad, padLp, "pad");
        if (CURSOR_ABOVE_PANELS) raise(cursor, cursorLp, "cursor");
        syncCursorMode();
        updateModeUi();
        Log.i(TAG, "theme applied: " + theme.id);
    }

    private void setTheme(String id) {
        Theme next = Theme.byId(id);
        if (next == theme) return;
        theme = next;
        prefs.edit().putString(PREF_THEME, theme.id).apply();
        applyTheme();
    }

    /** Bubbles are plain TextViews, so they can be restyled without being rebuilt. */
    private void restyleBubbles() {
        restyleBubble(bubble, theme.bubbleTrack);
        restyleBubble(keysBubble, theme.bubbleKeys);
    }

    private void restyleBubble(View v, int textColor) {
        if (!(v instanceof TextView)) return;
        ((TextView) v).setTextColor(textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill(theme.bubbleFill));
        bg.setStroke(dp(1.5f), theme.bubbleStroke);
        v.setBackground(bg);
    }

    private void buildThemePanel() {
        FrameLayout container = new FrameLayout(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout handle = new LinearLayout(this);
        handle.setGravity(Gravity.CENTER);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setCornerRadii(new float[]{dp(theme.radius), dp(theme.radius),
                dp(theme.radius), dp(theme.radius), 0, 0, 0, 0});
        hbg.setColor(fill(theme.panelHead));
        handle.setBackground(hbg);
        handle.addView(makeChip("\u25D0  THEME \u2014 tap a preset"));
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - themePanelLp.x;
                        dy = e.getRawY() - themePanelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        themePanelLp.x = (int) (e.getRawX() - dx);
                        themePanelLp.y = (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(themePanel, themePanelLp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        saveGeometry(themePanelLp, THEME_KEY);
                        return true;
                }
                return false;
            }
        });
        content.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        themeRows = new LinearLayout(this);
        themeRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(themeRows, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        content.addView(sectionLabel("OPACITY"));
        opacityRows = new LinearLayout(this);
        opacityRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(opacityRows, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GradientDrawable rbg = new GradientDrawable();
        rbg.setCornerRadius(dp(theme.radius));
        rbg.setColor(fill(theme.panelSolid));
        rbg.setStroke(dp(1.5f), theme.panelStroke);
        container.setBackground(rbg);

        themePanelLp = overlayLp(dp(300), dp(300),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        themePanel = container;

        addResizeGrips(container, themePanelLp, THEME_KEY, true);

        restoreGeometry(themePanelLp, THEME_KEY, dp(300), dp(300));
        try { wm.addView(themePanel, themePanelLp); }
        catch (Exception ex) { Log.e(TAG, "themePanel", ex); }
        refreshThemeRows();
        setThemeVisible(false);
    }

    private void refreshThemeRows() {
        if (themeRows != null) {
            themeRows.removeAllViews();
            for (int i = 0; i < Theme.PRESETS.length; i++) {
                themeRows.addView(themeRow(Theme.PRESETS[i]));
            }
        }
        if (opacityRows != null) {
            opacityRows.removeAllViews();
            opacityRows.addView(opacitySlider());
        }
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(10f);
        t.setTextColor(theme.textDim);
        t.setPadding(dp(12), dp(12), dp(12), dp(4));
        return t;
    }

    /**
     * The opacity control: a slider, with a live percentage label.
     *
     * It deliberately does NOT apply on every tick. Applying means rebuilding the panels
     * (their backgrounds are generated drawables), and rebuilding inside a SeekBar's own
     * touch callback would remove the slider mid-drag. So the drag only updates the
     * label, and the release posts the rebuild - by which time the touch is over.
     */
    private View opacitySlider() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), 0, dp(12), dp(6));

        opacityLabel = new TextView(this);
        opacityLabel.setTextSize(11f);
        opacityLabel.setTextColor(theme.textSecondary);
        opacityLabel.setText(opacityText(opacity));
        box.addView(opacityLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pendingOpacity = -1f;
        SeekBar sb = new SeekBar(this);
        sb.setMax(OPACITY_MAX - OPACITY_MIN);
        sb.setProgress(Math.round(opacity * 100f) - OPACITY_MIN);
        sb.setProgressTintList(ColorStateList.valueOf(theme.accent));
        sb.setThumbTintList(ColorStateList.valueOf(theme.accent));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float v = (OPACITY_MIN + progress) / 100f;
                pendingOpacity = v;
                if (opacityLabel != null) opacityLabel.setText(opacityText(v));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                final float v = (pendingOpacity < 0f) ? opacity : pendingOpacity;
                pendingOpacity = -1f;
                ui.post(new Runnable() {
                    @Override public void run() { setOpacity(v); }
                });
            }
        });
        box.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private String opacityText(float v) {
        return "Panel opacity: " + Math.round(v * 100f) + "%";
    }

    private void setOpacity(float v) {
        if (Math.abs(v - opacity) < 0.01f) return;
        opacity = v;
        prefs.edit().putFloat(PREF_OPACITY, v).apply();
        applyTheme();
    }

    private TextView themeRow(final Theme preset) {
        boolean active = (preset == theme);
        TextView t = new TextView(this);
        t.setText((active ? "\u25C9  " : "\u25CB  ") + preset.label);
        t.setTextSize(12f);
        t.setTextColor(active ? theme.textPrimary : theme.textSecondary);
        t.setPadding(dp(12), dp(11), dp(12), dp(11));
        t.setBackground(keyBgState(active ? theme.selectedRow : 0x00000000, theme.accent));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); setTheme(preset.id); }
        });
        return t;
    }

    private void setThemeVisible(boolean visible) {
        themeVisible = visible;
        if (themePanel == null) return;
        if (visible) {
            refreshThemeRows();
            clampGeometryToScreen(themePanelLp);
            raise(themePanel, themePanelLp, "themePanel");
        }
        themePanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void removeViews() {
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) {}
        try { if (keysBubble != null) wm.removeView(keysBubble); } catch (Exception ignored) {}
        try { if (pad != null) wm.removeView(pad); } catch (Exception ignored) {}
        try { if (keysPanel != null) wm.removeView(keysPanel); } catch (Exception ignored) {}
        try { if (pickerPanel != null) wm.removeView(pickerPanel); } catch (Exception ignored) {}
        try { if (themePanel != null) wm.removeView(themePanel); } catch (Exception ignored) {}
        try { if (screenPanel != null) wm.removeView(screenPanel); } catch (Exception ignored) {}
        try { if (cursor != null) wm.removeView(cursor); } catch (Exception ignored) {}
        bubble = keysBubble = pad = keysPanel = null;
        pickerPanel = themePanel = screenPanel = null;
        cursor = null;
        themeRows = null;
        opacityRows = null;
    }

    private void removeAll() {
        VDisplayReceiver.service = null;
        try { if (displayManager != null) displayManager.unregisterDisplayListener(displayListener); }
        catch (Exception ignored) {}
        removeTargetCursor();
        // a virtual display we created must not outlive the service
        try { if (shizuku != null) shizuku.releaseVirtualDisplay(); }
        catch (Exception ignored) {}
        // and unbind cleanly, which is the path that actually reaps the shell process
        try { if (shizuku != null) shizuku.stop(); }
        catch (Exception ignored) {}
        ownVirtualDisplayId = -1;
        virtualDisplayHasSurface = false;
        screenSurfaceAlive = false;
        screenSurfaceW = 0;
        screenSurfaceH = 0;
        removeViews();
    }

    private WindowManager.LayoutParams overlayLp(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return lp;
    }

    // =========================================================================
    // Display selection
    //
    // The panels always live on the SURFACE display (the default display - the one
    // your fingers can reach). The pointer and every injected event are addressed
    // to the TARGET display, which the picker chooses. They are the same display
    // until you pick otherwise, in which case the pad becomes a remote control:
    // you drag on the phone and the cursor moves on the other screen.
    // =========================================================================

    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) { onDisplaysChanged(); }
                @Override public void onDisplayRemoved(int displayId) { onDisplaysChanged(); }
                @Override public void onDisplayChanged(int displayId) { onDisplaysChanged(); }
            };

    /**
     * Displays come and go constantly (DeX attach/detach, refresh-rate changes).
     * Re-resolve the target, but only rebuild the picker rows while it is open so a
     * chatty listener does not thrash the view tree.
     */
    private void onDisplaysChanged() {
        if (ui == null) return;
        ui.post(new Runnable() {
            @Override public void run() {
                int before = targetDisplayId;
                applyTargetMetrics();
                if (targetDisplayId != before) syncCursorMode();
                if (pickerVisible) refreshDisplayList();
                updateModeUi();
            }
        });
    }

    /** Read the target display's size, falling back to the surface if it vanished. */
    private void applyTargetMetrics() {
        Display d = (displayManager == null) ? null : displayManager.getDisplay(targetDisplayId);
        if (d == null || !d.isValid()) {
            targetDisplayId = surfaceDisplayId;
            d = (displayManager == null) ? null : displayManager.getDisplay(targetDisplayId);
        }
        if (d == null) {
            outW = screenW;
            outH = screenH;
            return;
        }
        Point p = new Point();
        displaySize(d, p);
        outW = (p.x > 0) ? p.x : screenW;
        outH = (p.y > 0) ? p.y : screenH;
    }

    /** Restore the saved target. Ids are not stable, so we match on uniqueId. */
    private void restoreTargetDisplay() {
        int id = displayIdForKey(prefs.getString(PREF_TARGET_DISPLAY, null));
        if (id >= 0) targetDisplayId = id;
        applyTargetMetrics();
    }

    /**
     * A stable key for a display, for persistence. Display ids get reused as displays
     * come and go ("Overlay #1" is id 7 on this device, not 2), so never persist the
     * id itself, and this SDK's Display has no getUniqueId().
     *
     * Key on name + *physical* mode size: physical rather than getWidth() so a
     * rotation does not change the key, and size because this device has two
     * displays both called "Built-in Screen".
     */
    private String displayKey(Display d) {
        int w = 0, h = 0;
        try {
            Display.Mode m = d.getMode();
            if (m != null) {
                w = m.getPhysicalWidth();
                h = m.getPhysicalHeight();
            }
        } catch (Throwable ignored) {}
        if (w <= 0 || h <= 0) {
            w = d.getWidth();
            h = d.getHeight();
        }
        return d.getName() + ":" + w + "x" + h;
    }

    /** -1 when the key matches nothing currently attached. */
    private int displayIdForKey(String key) {
        if (key == null || displayManager == null) return -1;
        Display[] all = displayManager.getDisplays();
        for (int i = 0; i < all.length; i++) {
            Display d = all[i];
            if (d != null && d.isValid() && key.equals(displayKey(d))) return d.getDisplayId();
        }
        return -1;
    }

    private void displaySize(Display d, Point p) {
        try {
            d.getRealSize(p);
        } catch (Throwable t) {
            p.set(d.getWidth(), d.getHeight());
        }
    }

    private String stateName(int state) {
        switch (state) {
            case Display.STATE_ON:           return "ON";
            case Display.STATE_OFF:          return "OFF";
            case Display.STATE_DOZE:         return "DOZE";
            case Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            case Display.STATE_ON_SUSPEND:   return "ON_SUSPEND";
            case Display.STATE_VR:           return "VR";
            case Display.STATE_UNKNOWN:      return "?";
            default:                         return "state " + state;
        }
    }

    /**
     * Valid displays, surface first, then by id.
     *
     * getDisplays() alone is not enough: on this device it returns ONLY the default
     * display, so the cover screen (and any virtual/overlay display) never shows up.
     * Those carry FLAG_PRESENTATION, so they do appear in the presentation category -
     * union the two and de-duplicate by id.
     */
    private List<Display> listDisplays() {
        List<Display> out = new ArrayList<Display>();
        if (displayManager == null) return out;
        Display[] plain = displayManager.getDisplays();
        Display[] present = null;
        try {
            present = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        } catch (Throwable t) {
            Log.w(TAG, "presentation displays: " + t);
        }
        addDisplays(out, plain);
        addDisplays(out, present);
        int viaPublic = out.size();

        // getDisplays() is filtered for apps: on this device it returns only the
        // default display, hiding the cover screen and any virtual/overlay display
        // (the presentation category is empty too). getDisplay(id) is NOT filtered,
        // so probing a small id range finds them with no Shizuku and no exec.
        addProbedDisplays(out);
        int probed = out.size() - viaPublic;

        // ...and sweep via the shell for anything the probe missed. Cached, so a
        // burst of display-change events cannot turn into a storm of shell execs.
        int swept = 0;
        int[] ids = shellDisplayIds();
        if (ids != null) {
            for (int i = 0; i < ids.length; i++) {
                if (containsId(out, ids[i])) continue;
                Display d = displayManager.getDisplay(ids[i]);
                if (d != null && d.isValid()) { out.add(d); swept++; }
            }
        }
        Log.i(TAG, "enumerate: public=" + viaPublic + " probed=" + probed
                + " swept=" + swept + " total=" + out.size());
        Collections.sort(out, new Comparator<Display>() {
            @Override public int compare(Display a, Display b) {
                if (a.getDisplayId() == surfaceDisplayId) return -1;
                if (b.getDisplayId() == surfaceDisplayId) return 1;
                return Integer.compare(a.getDisplayId(), b.getDisplayId());
            }
        });
        return out;
    }

    private void addDisplays(List<Display> out, Display[] in) {
        if (in == null) return;
        for (int i = 0; i < in.length; i++) {
            Display d = in[i];
            if (d == null || !d.isValid()) continue;
            if (!containsId(out, d.getDisplayId())) out.add(d);
        }
    }

    private boolean containsId(List<Display> l, int id) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).getDisplayId() == id) return true;
        }
        return false;
    }

    /**
     * Probe a small id range with the public getDisplay(id). Display ids are handed
     * out from a small counter, so this reliably finds the cover screen and virtual
     * displays, needs no Shizuku, and costs only local binder calls.
     */
    private void addProbedDisplays(List<Display> out) {
        for (int id = 0; id < 64; id++) {
            if (containsId(out, id)) continue;
            Display d = displayManager.getDisplay(id);
            if (d != null && d.isValid()) out.add(d);
        }
    }

    /**
     * Logical display ids as reported by `dumpsys display`, which sees displays the
     * public getDisplays() hides. Returns null when Shizuku is not available.
     *
     * `dumpsys display` prints lines like
     *   mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, displayGroupId 0, ..}
     * and "displayGroupId" does not contain the substring "displayId", so this only
     * matches the real thing. Duplicates (base + override info) are collapsed by sort -u.
     */
    private int[] shellDisplayIds() {
        long now = SystemClock.uptimeMillis();
        if (shellIdsCache != null && (now - shellIdsAt) < 2000L) return shellIdsCache;
        shellIdsAt = now;
        if (!useShizuku()) return shellIdsCache;
        String raw;
        try {
            raw = shizuku.run("dumpsys display | grep -oE 'displayId [0-9]+' | sort -u");
        } catch (Throwable t) {
            Log.w(TAG, "shellDisplayIds: " + t);
            return null;
        }
        if (raw == null) return null;
        List<Integer> ids = new ArrayList<Integer>();
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            int sp = s.lastIndexOf(' ');
            if (sp < 0) continue;
            try {
                int v = Integer.parseInt(s.substring(sp + 1).trim());
                if (!ids.contains(Integer.valueOf(v))) ids.add(Integer.valueOf(v));
            } catch (Throwable ignored) {}
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i).intValue();
        shellIdsCache = out;
        return out;
    }

    /** Switch the pointer to another display. No rebind, no window teardown. */
    private void setTargetDisplay(int id) {
        if (displayManager == null) return;
        Display d = displayManager.getDisplay(id);
        if (d == null || !d.isValid()) return;
        targetDisplayId = id;
        applyTargetMetrics();
        cursorX = clamp(cursorX, 0, outW - 1);
        cursorY = clamp(cursorY, 0, outH - 1);
        if (shizuku != null) shizuku.setDisplayId(id);
        if (prefs != null) prefs.edit().putString(PREF_TARGET_DISPLAY, displayKey(d)).apply();
        syncCursorMode();
        refreshDisplayList();
        updateModeUi();
        Log.i(TAG, "target display -> " + id + " (" + d.getName() + ") " + outW + "x" + outH);
    }

    /**
     * Our drawn arrow is an overlay on the SURFACE display, so it can only honestly
     * represent a pointer that is also there. Once the pointer has been sent to a
     * different screen we hide our arrow and leave the *system* pointer visible, so
     * the user can still see where they are pointing.
     */
    private void syncCursorMode() {
        updateCursorAlpha();
        // We draw our own arrow in every case now - on the phone screen, over the floating
        // window, or on the target display itself - so the system pointer is always hidden.
        // It has to be: it is not rendered on an external or virtual display at all, which
        // is exactly why there used to be no pointer there.
        if (useShizuku()) shizuku.hideSystemCursor(true);
        syncTargetCursor();
    }

    /**
     * Open (or close) a second arrow window on the target display, bound to it with
     * createDisplayContext(). This is the only way to show a pointer on a display whose
     * output we do not own.
     */
    private void syncTargetCursor() {
        boolean needed = (targetDisplayId != surfaceDisplayId) && !cursorOnScreenPanel();
        if (!needed) { removeTargetCursor(); return; }
        if (targetCursor != null && targetCursorDisplayId == targetDisplayId) return;

        removeTargetCursor();
        if (displayManager == null) return;
        Display d = displayManager.getDisplay(targetDisplayId);
        if (d == null || !d.isValid()) return;
        try {
            Context dc = createDisplayContext(d);
            WindowManager twm = (WindowManager) dc.getSystemService(WINDOW_SERVICE);
            if (twm == null) throw new IllegalStateException("no WindowManager");

            // size the arrow for the target's density, not the phone's
            DisplayMetrics dm = new DisplayMetrics();
            try { d.getRealMetrics(dm); } catch (Throwable ignored) {}
            float td = (dm.density > 0f) ? dm.density : density;
            WindowManager.LayoutParams lp = overlayLp(Math.round(28 * td), Math.round(34 * td),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            lp.x = (int) cursorX;
            lp.y = (int) cursorY;

            View v = new CursorView();
            twm.addView(v, lp);
            targetCursor = v;
            targetWm = twm;
            targetCursorLp = lp;
            targetCursorDisplayId = targetDisplayId;
            Log.i(TAG, "target cursor added on display " + targetDisplayId
                    + " at " + lp.x + "," + lp.y);
        } catch (Throwable t) {
            targetCursor = null;
            targetWm = null;
            targetCursorLp = null;
            targetCursorDisplayId = -1;
            Log.w(TAG, "target cursor FAILED on display " + targetDisplayId + ": " + t);
        }
    }

    private void removeTargetCursor() {
        if (targetCursor != null && targetWm != null) {
            try { targetWm.removeView(targetCursor); } catch (Throwable ignored) {}
        }
        targetCursor = null;
        targetWm = null;
        targetCursorLp = null;
        targetCursorDisplayId = -1;
    }

    private void updateCursorAlpha() {
        if (cursor == null) return;
        boolean sameDisplay = (targetDisplayId == surfaceDisplayId);
        cursor.setAlpha((padVisible && (sameDisplay || cursorOnScreenPanel())) ? 1f : 0f);
    }

    // ---- picker bubble + panel ---------------------------------------------

    private void buildPickerPanel() {
        FrameLayout container = new FrameLayout(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout handle = new LinearLayout(this);
        handle.setGravity(Gravity.CENTER);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setCornerRadii(new float[]{dp(theme.radius), dp(theme.radius), dp(theme.radius), dp(theme.radius), 0, 0, 0, 0});
        hbg.setColor(fill(theme.panelHead));
        handle.setBackground(hbg);
        handle.addView(makeChip("\u25A3  DISPLAY \u2014 tap to aim the pointer"));
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - pickerPanelLp.x;
                        dy = e.getRawY() - pickerPanelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        pickerPanelLp.x = (int) (e.getRawX() - dx);
                        pickerPanelLp.y = (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(pickerPanel, pickerPanelLp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        saveGeometry(pickerPanelLp, PICKER_KEY);
                        return true;
                }
                return false;
            }
        });
        content.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        pickerRows = new LinearLayout(this);
        pickerRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(pickerRows, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // footer: two ways to get a display that this app owns
        virtualRow = footerRow();
        virtualRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); toggleOwnVirtualDisplay(); }
        });
        content.addView(virtualRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        screenRow = footerRow();
        screenRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); onScreenFooterAction(); }
        });
        content.addView(screenRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateVirtualRow();

        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GradientDrawable rbg = new GradientDrawable();
        rbg.setCornerRadius(dp(theme.radius));
        rbg.setColor(fill(theme.panelSolid));
        rbg.setStroke(dp(1.5f), theme.panelStroke);
        container.setBackground(rbg);

        pickerPanelLp = overlayLp(dp(320), dp(320),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        pickerPanel = container;

        addResizeGrips(container, pickerPanelLp, PICKER_KEY, true);

        restoreGeometry(pickerPanelLp, PICKER_KEY, dp(320), dp(320));
        try { wm.addView(pickerPanel, pickerPanelLp); }
        catch (Exception ex) { Log.e(TAG, "pickerPanel", ex); }
        setPickerVisible(false);
    }

    private void setPickerVisible(boolean visible) {
        pickerVisible = visible;
        if (pickerPanel == null) return;
        if (visible) {
            refreshDisplayList();
            clampGeometryToScreen(pickerPanelLp);
            // rows change height and this panel must beat the pad for taps
            raise(pickerPanel, pickerPanelLp, "picker");
        }
        pickerPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void refreshDisplayList() {
        if (pickerRows == null || refreshingList) return;
        refreshingList = true;
        try {
            refreshDisplayListInner();
        } finally {
            refreshingList = false;
        }
    }

    private TextView footerRow() {
        TextView t = new TextView(this);
        t.setTextSize(12f);
        t.setPadding(dp(12), dp(11), dp(12), dp(11));
        t.setTextColor(theme.footerText);
        t.setBackground(keyBgState(theme.keyBg, theme.accent));
        return t;
    }

    private void updateVirtualRow() {
        if (virtualRow != null) {
            if (ownVirtualDisplayId >= 0 && !virtualDisplayHasSurface) {
                virtualRow.setText("\u2715  destroy virtual display  id " + ownVirtualDisplayId
                        + "  (headless)");
            } else {
                virtualRow.setText(useShizuku()
                        ? "\uFF0B  create virtual display    1920\u00D71080  (headless)"
                        : "\u26A0  virtual display needs Shizuku");
            }
        }
        if (screenRow != null) {
            boolean exists = virtualDisplayHasSurface && ownVirtualDisplayId >= 0;
            if (!useShizuku()) {
                screenRow.setText("\u26A0  floating display needs Shizuku");
            } else if (exists) {
                boolean showing = (screenPanel != null
                        && screenPanel.getVisibility() == View.VISIBLE);
                screenRow.setText("\u2715  destroy floating display  id " + ownVirtualDisplayId
                        + (showing ? "" : "  (hidden)"));
            } else {
                screenRow.setText("\uFF0B  create floating display  (top half)");
            }
        }
    }

    /**
     * Create a display this app owns, rather than writing the global
     * `overlay_display_devices` setting (which InnerDesk also manages - two owners of
     * one global would fight).
     *
     * The creation happens shell-side (see ShellUserService). Doing it in-app was
     * tried first and rejected with SecurityException ("...screen sharing virtual
     * display..."); the suggested OWN_CONTENT_ONLY alternative cannot host the apps we
     * want to control, so shell UID - which holds CAPTURE_VIDEO_OUTPUT - does it.
     */
    private void toggleOwnVirtualDisplay() {
        boolean headlessUp = (ownVirtualDisplayId >= 0 && !virtualDisplayHasSurface);
        if (headlessUp) { releaseOwnVirtualDisplay(); return; }
        // whichever display is up, it has to go first - the shell holds one slot
        if (screenPanel != null && screenPanel.getVisibility() == View.VISIBLE) {
            screenPanel.setVisibility(View.GONE);
        }
        if (ownVirtualDisplayId >= 0) releaseOwnVirtualDisplay();
        if (!useShizuku()) { Log.w(TAG, "virtual display needs Shizuku"); return; }
        ownVirtualDisplayId = shizuku.createVirtualDisplay("ztrackpad", 1920, 1080, 320);
        virtualDisplayHasSurface = false;
        Log.i(TAG, "virtual display -> id " + ownVirtualDisplayId);
        // surface = null means no render target: the display comes up OFF, its windows
        // are INVISIBLE, and injected clicks do NOT land. It hosts tasks, but it is not
        // a usable control target - use the floating (surface-backed) one for that.
        if (ownVirtualDisplayId >= 0) {
            Log.w(TAG, "virtual display is HEADLESS (no surface) - not a control target");
        }
        updateVirtualRow();
        refreshDisplayList();
    }

    private void releaseOwnVirtualDisplay() {
        ownVirtualDisplayId = -1;
        virtualDisplayHasSurface = false;
        screenSurfaceAlive = false;
        screenSurfaceW = 0;
        screenSurfaceH = 0;
        if (shizuku != null) shizuku.releaseVirtualDisplay();
        updateVirtualRow();
        refreshDisplayList();
    }

    // ---- floating virtual-display window (the non-headless option) ----------

    /**
     * A floating window showing the virtual display's output. Pinned to the top half by
     * default, but draggable by its header and resizable by the corner grips.
     *
     * Making it touchable costs something: the whole window now swallows touches, so it
     * can cover the keys panel or the pads if you move it over them. That is the price
     * of being able to drag and resize it.
     *
     * The display must be resized to match the surface when the window resizes, or the
     * content would be stretched to fit.
     */
    private void buildScreenPanel() {
        screenHeaderH = dp(30);
        final int headerH = screenHeaderH;
        FrameLayout container = new FrameLayout(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        final TextView header = new TextView(this);
        header.setText("\u2261  VIRTUAL DISPLAY  \u2014  drag to move");
        header.setTextColor(theme.textSecondary);
        header.setTextSize(10f);
        header.setGravity(Gravity.CENTER);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setCornerRadii(new float[]{dp(14), dp(14), dp(14), dp(14), 0, 0, 0, 0});
        hbg.setColor(fill(theme.screenHead));
        header.setBackground(hbg);
        header.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - screenPanelLp.x;
                        dy = e.getRawY() - screenPanelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        screenPanelLp.x = (int) (e.getRawX() - dx);
                        screenPanelLp.y = (int) (e.getRawY() - dy);
                        clampGeometryToScreen(screenPanelLp);
                        try { wm.updateViewLayout(screenPanel, screenPanelLp); } catch (Exception ignored) {}
                        // the arrow is drawn over this window, so it has to follow
                        moveCursor(0f, 0f);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveGeometry(screenPanelLp, SCREEN_KEY);
                        return true;
                }
                return false;
            }
        });
        content.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, headerH));

        screenView = new SurfaceView(this);
        screenView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                Log.i(TAG, "screen surface created " + h.getSurfaceFrame().width()
                        + "x" + h.getSurfaceFrame().height());
                startScreenDisplay(h.getSurface());
            }
            @Override public void surfaceChanged(SurfaceHolder h, int fmt, int w, int hh) {
                Log.i(TAG, "screen surface changed " + w + "x" + hh);
                screenSurfaceAlive = true;
                resizeScreenDisplay();
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                Log.i(TAG, "screen surface destroyed");
                screenSurfaceAlive = false;
                // Deliberately NOT releasing the display: hiding the window must not kill
                // the apps running on it. It survives off-screen, and showing the window
                // again just re-attaches a surface.
                updateCursorAlpha();
            }
        });
        content.addView(screenView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GradientDrawable rbg = new GradientDrawable();
        rbg.setCornerRadius(dp(14));
        rbg.setColor(fill(theme.panelSolid));
        rbg.setStroke(dp(1.5f), theme.panelStroke);
        container.setBackground(rbg);

        // touchable now, so the header and grips work - but NOT focusable, so it never
        // steals the IME or the back key
        screenPanelLp = overlayLp(screenW, screenH / 2,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        screenPanel = container;

        // default geometry is the top half (that is the whole point), so restore by hand
        // rather than via restoreGeometry(), whose defaults are bottom-centred
        screenPanelLp.width = prefs.getInt(SCREEN_KEY + "w", screenW);
        screenPanelLp.height = prefs.getInt(SCREEN_KEY + "h", screenH / 2);
        screenPanelLp.x = prefs.getInt(SCREEN_KEY + "x", 0);
        screenPanelLp.y = prefs.getInt(SCREEN_KEY + "y", 0);
        clampGeometryToScreen(screenPanelLp);

        addResizeGrips(container, screenPanelLp, SCREEN_KEY, true);

        try { wm.addView(screenPanel, screenPanelLp); }
        catch (Exception ex) { Log.e(TAG, "screenPanel", ex); }
        screenPanel.setVisibility(View.GONE);
    }

    /** Follow the surface whenever the window is resized, or the image would stretch. */
    private void resizeScreenDisplay() {
        if (!virtualDisplayHasSurface || ownVirtualDisplayId < 0) return;
        if (!screenSurfaceAlive) return;
        if (screenView == null || screenPanelLp == null) return;
        Rect sf = screenView.getHolder().getSurfaceFrame();
        if (sf.width() <= 0 || sf.height() <= 0) return;
        if (sf.width() == screenSurfaceW && sf.height() == screenSurfaceH) return;
        screenSurfaceW = sf.width();
        screenSurfaceH = sf.height();
        if (shizuku != null) {
            shizuku.resizeVirtualDisplay(screenSurfaceW, screenSurfaceH,
                    Math.round(density * 160f));
        }
    }

    /**
     * The footer is the lifecycle control: create the floating display, or destroy it.
     * Showing/hiding is the transient one and lives on the display's own row, because
     * hiding deliberately keeps the display and its apps alive.
     */
    private void onScreenFooterAction() {
        boolean exists = virtualDisplayHasSurface && ownVirtualDisplayId >= 0;
        if (!exists) {
            if (!useShizuku()) { Log.w(TAG, "floating display needs Shizuku"); return; }
            toggleVirtualScreen();      // shows the window; the surface creates the display
            return;
        }
        // destroy: drop the window first so the surface goes away cleanly, then release
        if (screenPanel != null) screenPanel.setVisibility(View.GONE);
        screenSurfaceAlive = false;
        Log.i(TAG, "destroying floating display " + ownVirtualDisplayId);
        releaseOwnVirtualDisplay();
        syncCursorMode();
    }

    /**
     * Entry point for VDisplayReceiver, and therefore for scripts. Runs on the main
     * thread, which the window work requires.
     *
     * Returns one machine-readable line so a script can parse it.
     */
    public String vdisplayCommand(String op, boolean headless, String spec, String arg) {
        if (op == null || op.length() == 0) op = "status";
        if ("status".equals(op)) return vdisplayStatus();

        // Pad lock, so a script can freeze the pad the same way the pad's own lock dot
        // does - both go through setPadLocked, so the dot and this can never disagree.
        if ("lock".equals(op)) {
            String what = (arg == null || arg.length() == 0) ? "toggle" : arg.trim();
            if ("on".equals(what)) setPadLocked(true);
            else if ("off".equals(what)) setPadLocked(false);
            else if ("toggle".equals(what)) setPadLocked(!padLocked);
            else return "error: lock wants on|off|toggle, not '" + what + "'";
            return "ok lock " + vdisplayStatus();
        }

        // Keys-panel layout. With no spec, hand one back to edit - the custom one if set,
        // otherwise the built-in one, which is just the same format as data.
        if ("keys".equals(op)) {
            if (spec == null) {
                return "spec=" + ((keysSpec == null || keysSpec.length() == 0)
                        ? DEFAULT_KEYS_SPEC : keysSpec);
            }
            keysSpec = spec;
            prefs.edit().putString(PREF_KEYS, spec).apply();
            applyTheme();
            return "ok keys " + keySpecSummary();
        }

        if ("keys-reset".equals(op)) {
            keysSpec = null;
            prefs.edit().remove(PREF_KEYS).apply();
            applyTheme();
            return "ok keys-reset " + keySpecSummary();
        }

        if ("destroy".equals(op)) {
            if (screenPanel != null) screenPanel.setVisibility(View.GONE);
            screenSurfaceAlive = false;
            releaseOwnVirtualDisplay();
            syncCursorMode();
            return "ok destroy " + vdisplayStatus();
        }

        if ("show".equals(op)) {
            if (screenPanel == null) return "error: no panel";
            if (screenPanel.getVisibility() != View.VISIBLE) toggleVirtualScreen();
            return "ok show " + vdisplayStatus();
        }

        if ("hide".equals(op)) {
            if (screenPanel != null && screenPanel.getVisibility() == View.VISIBLE) {
                toggleVirtualScreen();
            }
            return "ok hide " + vdisplayStatus();
        }

        if ("create".equals(op)) {
            if (headless) {
                boolean up = (ownVirtualDisplayId >= 0 && !virtualDisplayHasSurface);
                if (!up) toggleOwnVirtualDisplay();
                return "ok create-headless " + vdisplayStatus();
            }
            boolean floatingUp = (ownVirtualDisplayId >= 0 && virtualDisplayHasSurface);
            if (!floatingUp) {
                // Showing the window is all we can do here: the display is created from the
                // surface callback, so it will not exist yet. Poll status.
                if (screenPanel != null) screenPanel.setVisibility(View.VISIBLE);
                raise(cursor, cursorLp, "cursor");
                updateVirtualRow();
            } else if (screenPanel != null && screenPanel.getVisibility() != View.VISIBLE) {
                toggleVirtualScreen();
            }
            return "ok create-floating " + vdisplayStatus();
        }

        return "error: unknown op '" + op + "' (status|create|destroy|show|hide|lock)";
    }

    /** One line of key=value pairs, for scripts to parse. */
    public String vdisplayStatus() {
        boolean shown = (screenPanel != null && screenPanel.getVisibility() == View.VISIBLE);
        String kind = (ownVirtualDisplayId < 0) ? "none"
                : (virtualDisplayHasSurface ? "floating" : "headless");
        return "shizuku=" + (useShizuku() ? "ready" : "no")
                + " id=" + ownVirtualDisplayId
                + " kind=" + kind
                + " window=" + (shown ? "shown" : "hidden")
                + " surface=" + (screenSurfaceAlive ? "alive" : "detached")
                + " vsize=" + screenSurfaceW + "x" + screenSurfaceH
                + " target=" + targetDisplayId
                + " padlocked=" + padLocked
                + " keys=" + ((keysSpec == null || keysSpec.length() == 0) ? "default" : "custom");
    }

    /** A short summary of the active keys layout, for a script to log. */
    private String keySpecSummary() {
        String spec = (keysSpec == null || keysSpec.length() == 0) ? DEFAULT_KEYS_SPEC : keysSpec;
        List<String> rows = splitEscaped(spec, '|');
        int keys = 0;
        for (int i = 0; i < rows.size(); i++) {
            keys += splitEscaped(rows.get(i), ',').size();
        }
        return "keys=" + ((keysSpec == null || keysSpec.length() == 0) ? "default" : "custom")
                + " rows=" + rows.size() + " keys=" + keys;
    }

    private void toggleVirtualScreen() {
        if (screenPanel == null) return;
        boolean showing = (screenPanel.getVisibility() == View.VISIBLE);
        if (showing) {
            // just hide: the display and its apps keep running, only the surface goes away
            screenPanel.setVisibility(View.GONE);
            screenSurfaceAlive = false;
        } else if (!useShizuku()) {
            Log.w(TAG, "floating display needs Shizuku");
        } else {
            // only a headless display has to go - the shell holds a single slot
            if (ownVirtualDisplayId >= 0 && !virtualDisplayHasSurface) releaseOwnVirtualDisplay();
            // deliberately NOT raised: this window belongs at the bottom of the overlay
            // stack, under the pad / keys panel / picker
            screenPanel.setVisibility(View.VISIBLE);
        }
        // the arrow has to sit above the window we just showed
        if (cursor != null) raise(cursor, cursorLp, "cursor");
        updateVirtualRow();
        refreshDisplayList();
        // showing/hiding the floating window changes whether its own arrow applies
        syncCursorMode();
    }

    /**
     * Create the display now that the SurfaceView has a surface to render into. That
     * surface is what makes it non-headless: it is ON, its windows are visible, and
     * injected input lands.
     */
    private void startScreenDisplay(Surface surface) {
        if (!useShizuku()) { Log.w(TAG, "floating display needs Shizuku"); return; }

        // The display may already exist because the window was only hidden. Re-attach the
        // new surface rather than recreating the display, which would restart its apps.
        if (ownVirtualDisplayId >= 0 && virtualDisplayHasSurface) {
            screenSurfaceAlive = true;
            shizuku.setVirtualDisplaySurface(surface);
            Log.i(TAG, "screen surface re-attached to display " + ownVirtualDisplayId);
            updateVirtualRow();
            refreshDisplayList();
            updateCursorAlpha();
            return;
        }

        Rect sf = screenView.getHolder().getSurfaceFrame();
        int w = (sf.width() > 0) ? sf.width() : screenPanelLp.width;
        int h = (sf.height() > 0) ? sf.height() : (screenPanelLp.height - screenHeaderH);
        int dpi = Math.round(density * 160f);
        ownVirtualDisplayId = shizuku.createVirtualDisplay("ztrackpad", w, h, dpi, surface);
        virtualDisplayHasSurface = (ownVirtualDisplayId >= 0);
        screenSurfaceAlive = virtualDisplayHasSurface;
        screenSurfaceW = w;
        screenSurfaceH = h;
        Log.i(TAG, "floating virtual display -> id " + ownVirtualDisplayId
                + " " + w + "x" + h + " @" + dpi + "dpi");
        seedVirtualDisplay();
        updateVirtualRow();
        refreshDisplayList();
    }

    /**
     * Put something on the new display, off the UI thread (it is a shell round-trip).
     * Without this the display is empty, so it mirrors the default display and the
     * floating window shows itself - an infinite mirror.
     */
    private void seedVirtualDisplay() {
        if (ownVirtualDisplayId < 0 || !useShizuku()) return;
        final int id = ownVirtualDisplayId;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String out = shizuku.run("am start --display " + id
                            + " -f 0x10000000 -n " + SECONDARY_LAUNCHER);
                    Log.i(TAG, "seeded display " + id + ": " + out.trim());
                } catch (Throwable t) {
                    Log.w(TAG, "seed display " + id + " failed: " + t);
                }
            }
        }, "pi-seed-display").start();
    }

    private void refreshDisplayListInner() {
        pickerRows.removeAllViews();
        List<Display> ds = listDisplays();
        Log.i(TAG, "picker: " + ds.size() + " listed");
        for (int i = 0; i < ds.size(); i++) {
            Display d = ds.get(i);
            Log.i(TAG, "  display " + d.getDisplayId() + " '" + d.getName()
                    + "' valid=" + d.isValid() + " state=" + stateName(d.getState())
                    + " flags=0x" + Integer.toHexString(d.getFlags()));
            pickerRows.addView(pickerRow(d));
        }
        if (ds.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("no displays");
            t.setTextColor(theme.textFaint);
            t.setTextSize(12f);
            t.setPadding(dp(12), dp(10), dp(12), dp(10));
            pickerRows.addView(t);
        }
    }

    private View pickerRow(final Display d) {
        Point p = new Point();
        displaySize(d, p);
        DisplayMetrics dm = new DisplayMetrics();
        try { d.getRealMetrics(dm); } catch (Throwable ignored) {}
        boolean isTarget = (d.getDisplayId() == targetDisplayId);
        boolean isSurface = (d.getDisplayId() == surfaceDisplayId);
        int state = d.getState();
        boolean usable = (state == Display.STATE_ON);

        StringBuilder sb = new StringBuilder();
        sb.append(isTarget ? "\u25C9  " : "\u25CB  ");
        sb.append(d.getName());
        // the framework calls the XREAL "HDMI Screen"; the product name is what the user
        // recognises, so show it when it adds anything
        try {
            DeviceProductInfo info = d.getDeviceProductInfo();
            String product = (info == null) ? null : info.getName();
            if (product != null && product.length() > 0 && !product.equals(d.getName())) {
                sb.append("  \u00B7  ").append(product);
            }
        } catch (Throwable ignored) {}
        if (isSurface) sb.append("  (surface)");
        sb.append("\n     ").append(p.x).append("\u00D7").append(p.y);
        sb.append("  \u00B7  ").append(dm.densityDpi).append("dpi");
        sb.append("  \u00B7  ").append(stateName(state));
        sb.append("  \u00B7  id ").append(d.getDisplayId());

        TextView t = new TextView(this);
        t.setText(sb.toString());
        t.setTextSize(12f);
        t.setTextColor(usable ? (isTarget ? theme.textPrimary : theme.textSecondary) : theme.textDim);
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); setTargetDisplay(d.getDisplayId()); }
        });

        // Our own floating display gets its show/hide button right here, on its row.
        // Hiding only detaches the surface, so the entry stays listed and the apps on
        // that display keep running - which is why this is better than a "minimize" that
        // would have to shrink the surface, and with it the display's resolution.
        boolean isOurs = virtualDisplayHasSurface && d.getDisplayId() == ownVirtualDisplayId;
        if (!isOurs) {
            t.setBackground(keyBgState(isTarget ? theme.selectedRow : 0x00000000, theme.accent));
            return t;
        }

        boolean showing = (screenPanel != null && screenPanel.getVisibility() == View.VISIBLE);
        TextView b = new TextView(this);
        b.setText(showing ? "\u2715  hide" : "\u25B6  show");
        b.setTextSize(12f);
        b.setTextColor(theme.rowAction);
        b.setPadding(dp(10), dp(9), dp(14), dp(9));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); toggleVirtualScreen(); }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(keyBgState(isTarget ? theme.selectedRow : 0x00000000, theme.accent));
        row.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(b);
        return row;
    }

    // =========================================================================
    // Bubble
    // =========================================================================

    /**
     * Which vertical edge a bubble last snapped to. Held in memory only, like every other
     * bubble property: each dot starts on its default side at launch.
     */
    private static final class BubbleSide {
        boolean right;
        BubbleSide(boolean right) { this.right = right; }
    }

    private final BubbleSide bubbleSide = new BubbleSide(true);       // ● starts RIGHT
    private final BubbleSide keysBubbleSide = new BubbleSide(false);  // ⌨ starts LEFT

    /** The in-flight snap, so a new touch can cancel it instead of fighting it. */
    private ValueAnimator bubbleAnim;

    private void buildBubble() {
        final int size = dp(44);
        TextView v = new TextView(this);
        v.setText("\u25CF");
        v.setTextColor(theme.bubbleTrack);
        v.setTextSize(20f);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill(theme.bubbleFill));
        bg.setStroke(dp(1.5f), theme.bubbleStroke);
        v.setBackground(bg);

        bubbleLp = overlayLp(size, size,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        bubbleLp.x = screenW - size - dp(8);
        bubbleLp.y = (int) (screenH * 0.45f);

        attachBubbleDrag(v, bubbleLp, bubbleSide, new Runnable() {
            @Override public void run() { setPadVisible(!padVisible); }
        });

        bubble = v;
        try { wm.addView(bubble, bubbleLp); } catch (Exception ex) { Log.e(TAG, "bubble", ex); }
    }

    /**
     * Drag a floating dot anywhere, and let go: it flies to whichever vertical edge is
     * nearer, like a chat head.
     *
     * Nothing clamped these before, and the windows carry FLAG_LAYOUT_NO_LIMITS, so a drag
     * could park a dot half off the screen or past the bottom, with no way back except
     * groping for it blind. Snapping fixes that and also settles the tap/drag ambiguity:
     * a touch now counts as a drag only once it passes the touch slop, so a short fast
     * flick no longer toggles the panel on release as well.
     */
    private void attachBubbleDrag(final View v, final WindowManager.LayoutParams lp,
                                  final BubbleSide side, final Runnable onTap) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        v.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy, downX, downY;
            private long t0;
            private boolean dragged;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelSnap();
                        dx = e.getRawX() - lp.x;
                        dy = e.getRawY() - lp.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        t0 = SystemClock.uptimeMillis();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragged && Math.abs(e.getRawX() - downX)
                                + Math.abs(e.getRawY() - downY) > slop) dragged = true;
                        if (!dragged) return true;
                        lp.x = (int) (e.getRawX() - dx);
                        lp.y = (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragged) snapBubble(view, lp, side, e.getRawX() > screenW / 2f, true);
                        else if (SystemClock.uptimeMillis() - t0 < 250) onTap.run();
                        return true;
                }
                return false;
            }
        });
    }

    /** Put a bubble back on its recorded edge, without animating - used when the screen
     *  size changes, where the window is being re-laid out anyway. */
    private void resnapBubble(View v, WindowManager.LayoutParams lp, BubbleSide side) {
        if (v == null || lp == null) return;
        snapBubble(v, lp, side, side.right, false);
    }

    private void cancelSnap() {
        if (bubbleAnim == null) return;
        bubbleAnim.cancel();
        bubbleAnim = null;
    }

    /**
     * Send a bubble to the given vertical edge, keeping its height but clamping it inside
     * the screen - the dot must never end up half off it, which is the point of the snap.
     *
     * The animated x is written through the LayoutParams the touch listener reads, so an
     * interrupted snap leaves the dot where it actually is.
     */
    private void snapBubble(View v, WindowManager.LayoutParams lp, BubbleSide side,
                            boolean toRight, boolean animate) {
        if (v == null || lp == null) return;
        cancelSnap();
        side.right = toRight;
        final int margin = dp(8);
        lp.y = clampInt(lp.y, margin, Math.max(margin, screenH - lp.height - margin));
        final int to = toRight ? Math.max(margin, screenW - lp.width - margin) : margin;
        if (!animate || lp.x == to) {
            lp.x = to;
            try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(lp.x, to);
        a.setDuration(160);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator anim) {
                lp.x = (Integer) anim.getAnimatedValue();
                try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
            }
        });
        bubbleAnim = a;
        a.start();
    }

    // =========================================================================
    // Cursor
    // =========================================================================

    private class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean hot;

        CursorView() {
            super(TrackpadService.this);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.WHITE);
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(Color.BLACK);
            edge.setStrokeWidth(dp(1.5f));
            edge.setStrokeJoin(Paint.Join.ROUND);
        }

        void setHot(boolean h) {
            if (hot == h) return;
            hot = h;
            fill.setColor(h ? theme.cursorHot : theme.cursor);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            Path p = new Path();
            p.moveTo(0f, 0f);
            p.lineTo(0f, h * 0.70f);
            p.lineTo(w * 0.20f, h * 0.55f);
            p.lineTo(w * 0.33f, h);
            p.lineTo(w * 0.48f, h * 0.94f);
            p.lineTo(w * 0.35f, h * 0.49f);
            p.lineTo(w * 0.62f, h * 0.49f);
            p.close();
            c.drawPath(p, fill);
            c.drawPath(p, edge);
        }
    }

    private void buildCursor() {
        cursor = new CursorView();
        cursorLp = overlayLp(dp(28), dp(34),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        cursorLp.x = (int) cursorX;
        cursorLp.y = (int) cursorY;
        cursor.setAlpha(0f);
        try { wm.addView(cursor, cursorLp); } catch (Exception ex) { Log.e(TAG, "cursor", ex); }
    }

    // =========================================================================
    // Trackpad
    // =========================================================================

    /**
     * A control dot docked just under a panel's title bar - the floating bubbles moved
     * inside the pad so they cannot be lost behind another window or overlapped.
     *
     * It floats over the content, so the pad's proportions do not change, and it sits
     * below the 34dp handle so the whole title bar stays draggable. That is the whole
     * reason it is not *in* the handle: there it swallowed drag touches.
     *
     * `slot` counts inward from that side (0 = against the corner), so a second dot on the
     * same side is spaced off the first instead of landing on top of it.
     */
    private TextView addDockedDot(FrameLayout container, String glyph, int glyphColor,
                                  boolean alignRight, int slot, View.OnClickListener action) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(glyphColor);
        t.setTextSize(12f);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill(theme.bubbleFill));
        bg.setStroke(dp(1.5f), theme.bubbleStroke);
        t.setBackground(bg);
        t.setOnClickListener(action);
        dockDot(container, t, alignRight, slot);
        return t;
    }

    /** Seat a docked control dot: under the handle, inset clear of the panel's corner. */
    private void dockDot(FrameLayout container, View dot, boolean alignRight, int slot) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(26), dp(26),
                Gravity.TOP | (alignRight ? Gravity.RIGHT : Gravity.LEFT));
        // The side inset has to clear the panel's rounded corner (theme.radius, dp(18) by
        // default): at dp(6) the dot sat inside the curve and looked glued to the edge.
        lp.topMargin = dp(34) + dp(8);
        // NOTE: do NOT branch on `side & Gravity.RIGHT`. Gravity.LEFT is 3 and Gravity.RIGHT
        // is 5, so they share bit 0 and that test is true for BOTH - which is how the left
        // dot ended up flush against the edge while the right one was inset correctly.
        if (alignRight) {
            lp.rightMargin = dp(14) + slot * dp(32);
        } else {
            lp.leftMargin = dp(14) + slot * dp(32);
        }
        container.addView(dot, lp);
    }

    /**
     * The pad's lock dot, drawn rather than typed.
     *
     * It began as the emoji padlock, which ignores setTextColor: the icon rendered in the
     * font's own colours while every other dot followed the theme. A thin outline, the same
     * in both states - the pad's state shows on the handle (`≡ MOVE` / `≡ LOCKED`), so the
     * dot has no reason to shout about it, and no reason to change under the finger that
     * just tapped it.
     */
    private class LockDot extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();

        LockDot() {
            super(TrackpadService.this);
            ring.setStyle(Paint.Style.FILL);
            rim.setStyle(Paint.Style.STROKE);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            ring.setColor(fill(theme.bubbleFill));
            rim.setColor(theme.bubbleStroke);
            rim.setStrokeWidth(dp(1.5f));
            glyph.setColor(theme.textDim);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f;
            float sw = Math.max(1f, dp(1.5f));
            c.drawCircle(cx, cy, r - sw / 2f, ring);
            c.drawCircle(cx, cy, r - sw / 2f, rim);

            // Sized off the view so a theme change (different dp) cannot distort it.
            float line = Math.max(1f, dp(1.5f));
            glyph.setStrokeWidth(line);
            float bw = w * 0.28f;
            float bh = h * 0.27f;
            // body top such that body + shackle, not just the body, is centred in the ring
            float sr = bw * 0.36f;
            float top = cy - (bh - sr) / 2f;
            c.drawRoundRect(cx - bw / 2f, top, cx + bw / 2f, top + bh,
                    bw * 0.22f, bw * 0.22f, glyph);

            // the shackle: the top half of an oval whose middle sits on the body's top
            // edge, so its legs land there. Narrower than the body, like the real thing.
            arc.set(cx - sr, top - sr, cx + sr, top + sr);
            c.drawArc(arc, 180f, 180f, false, glyph);
        }
    }

    private void buildPad() {
        final int padW = (int) (screenW * 0.90f);
        final int padH = (int) (screenH * 0.30f);

        FrameLayout container = new FrameLayout(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // --- drag handle -------------------------------------------------
        LinearLayout handle = new LinearLayout(this);
        handle.setGravity(Gravity.CENTER);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setCornerRadii(new float[]{dp(theme.radius), dp(theme.radius), dp(theme.radius), dp(theme.radius), 0, 0, 0, 0});
        hbg.setColor(fill(theme.panelHead));
        handle.setBackground(hbg);
        moveChip = makeChip("\u2261  MOVE");
        handle.addView(moveChip);
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy, sx, sy;
            private boolean dragged;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                // Locked: swallow the whole gesture. Returning true at DOWN matters - the
                // MOVE branch below would otherwise run on a stale grab offset.
                if (padLocked) return true;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - padLp.x;
                        dy = e.getRawY() - padLp.y;
                        sx = e.getRawX();
                        sy = e.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - sx) > dp(6) || Math.abs(e.getRawY() - sy) > dp(6)) {
                            dragged = true;
                        }
                        padLp.x = (int) (e.getRawX() - dx);
                        padLp.y = (int) (e.getRawY() - dy);
                        clampGeometryToScreen(padLp);
                        try { wm.updateViewLayout(pad, padLp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragged) saveGeometry(padLp, PAD_KEY);
                        else resetPanelGeometry(pad, padLp, PAD_KEY, padDefW(), padDefH());
                        return true;
                }
                return false;
            }
        });

        // --- touch surface ------------------------------------------------
        View surface = new View(this);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(fill(theme.panelBody));
        surface.setBackground(sbg);
        LinearLayout.LayoutParams sp =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        surface.setLayoutParams(sp);
        surface.setOnTouchListener(new PadTouch());

        // --- button row ---------------------------------------------------
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setCornerRadii(new float[]{0, 0, 0, 0, dp(theme.radius), dp(theme.radius), dp(theme.radius), dp(theme.radius)});
        bbg.setColor(fill(theme.panelBar));
        bar.setBackground(bbg);
        bar.setPadding(dp(30), 0, dp(30), 0); // keep the corners clear for the resize grips

        // Action bar, per user spec: backspace / enter / right-click / pointer toggle
        bar.addView(makeRepeatButton("\u232B", new Runnable() {          // backspace (hold to repeat)
            @Override public void run() { sendKey(KeyEvent.KEYCODE_DEL, 0); }
        }));
        bar.addView(makeButton("\u23CE", new Runnable() {               // enter
            @Override public void run() { sendKey(KeyEvent.KEYCODE_ENTER, 0); }
        }));
        bar.addView(makeButton("\u22EE", new Runnable() {               // right click / context menu at pointer
            @Override public void run() { rightClick(); }
        }));
        bar.addView(makeButton("\u25CF", new Runnable() {               // toggle pointer visibility
            @Override public void run() { cursor.setAlpha(cursor.getAlpha() > 0f ? 0f : 1f); }
        }));

        // arrow keys - GLOBAL_ACTION_DPAD_* injects real DPAD key events
        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER);
        GradientDrawable kbg = new GradientDrawable();
        kbg.setColor(fill(theme.panelKeys));
        keys.setBackground(kbg);
        keys.setPadding(dp(30), 0, dp(30), 0);
        keys.addView(makeKey("\u2190", GLOBAL_ACTION_DPAD_LEFT));
        keys.addView(makeKey("\u2191", GLOBAL_ACTION_DPAD_UP));
        keys.addView(makeKey("\u2193", GLOBAL_ACTION_DPAD_DOWN));
        keys.addView(makeKey("\u2192", GLOBAL_ACTION_DPAD_RIGHT));

        content.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        content.addView(surface);
        content.addView(keys, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        content.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Three docked control dots flanking the pad, under the handle: the theme menu on
        // the left, the move/resize lock beside it, the display picker on the right. The
        // first two used to float on screen, and both were briefly tried inside the handle,
        // where they fought the drag gesture.
        addDockedDot(container, "\u25D0", theme.bubbleTheme, false, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        tick(); setThemeVisible(!themeVisible);
                    }
                });
        lockDot = new LockDot();
        lockDot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tick(); setPadLocked(!padLocked);
            }
        });
        dockDot(container, lockDot, false, 1);
        addDockedDot(container, "\u25A3", theme.bubbleDisplay, true, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        tick(); setPickerVisible(!pickerVisible);
                    }
                });
        // the handle's label is the only thing that reads the lock state, and updateModeUi
        // owns that label
        updateModeUi();

        // Two round buttons on the pad's left edge nudge the split divider. Added before
        // the resize grips on purpose: a FrameLayout dispatches touches to the newest child
        // first, so in the corner where they can overlap (a pad pinched down to its minimum
        // height) the grip still wins. They also sit on the left edge-scroll strip, whose
        // band they take over for their own dp(58) of height.
        addSplitButtons(container);

        GradientDrawable rbg = new GradientDrawable();
        rbg.setCornerRadius(dp(theme.radius));
        rbg.setColor(fill(theme.padContainer));
        rbg.setStroke(dp(1.5f), theme.padStroke);
        container.setBackground(rbg);

        padLp = overlayLp(padW, padH,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        pad = container;

        // resize grips: drag any corner to resize
        // one visible grip, all four corners. includeTopLeft is true: the theme button is
        // below the handle now, clear of that corner.
        addResizeGrips(container, padLp, PAD_KEY, true);

        restoreGeometry(padLp, PAD_KEY, padDefW(), padDefH());
        try { wm.addView(pad, padLp); } catch (Exception ex) { Log.e(TAG, "pad", ex); }
        setPadVisible(false);
    }

    // =========================================================================
    // Split-screen nudge buttons
    // =========================================================================

    /**
     * Two round buttons pinned to the pad's left edge, vertically centred: up moves the
     * divider up, which grows the BOTTOM pane, and down does the reverse.
     */
    private void addSplitButtons(FrameLayout container) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.addView(splitButton("\u2191", 1));
        col.addView(splitButton("\u2193", -1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        lp.leftMargin = dp(10);
        container.addView(col, lp);
    }

    private TextView splitButton(String glyph, final int dir) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(theme.textSecondary);
        t.setTextSize(15f);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill(theme.bubbleFill));
        bg.setStroke(dp(1.5f), theme.bubbleStroke);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
        lp.bottomMargin = dp(16);
        t.setLayoutParams(lp);
        // One nudge per press, deliberately not repeatable: a nudge reads the geometry over
        // the shell bridge, drags, then reads it back to check, so holding the button would
        // queue up work that finishes long after the finger is gone.
        attachRepeat(t, new Runnable() {
            @Override public void run() { splitNudge(dir); }
        }, false);
        return t;
    }

    /**
     * Move the split divider one step, from a button press.
     *
     * The geometry comes from `dumpsys window` over the existing Shizuku bridge rather than
     * from AccessibilityWindowInfo: window retrieval needs flagRetrieveInteractiveWindows,
     * and this service runs flagDefault, while the shell bridge is already here and needs
     * no permission. The cost is a round trip per press, which is why the work happens on
     * its own thread.
     */
    private void splitNudge(final int dir) {
        if (splitBusy) { Log.i(TAG, "split: still moving"); return; }
        if (!useShizuku()) { toast("split: needs Shizuku"); return; }
        if (targetDisplayId != surfaceDisplayId) { toast("split: not on the target display"); return; }
        splitBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    nudgeSplitInner(dir);
                } catch (Throwable t) {
                    Log.w(TAG, "split: " + t);
                } finally {
                    splitBusy = false;
                }
            }
        }, "pi-split").start();
    }

    private void nudgeSplitInner(int dir) {
        int[] s = parseSplit(shizuku.run("dumpsys window"));
        if (s == null) { toast("split: no divider found"); return; }
        if (s[4] != 1) {
            toast("split: only top/bottom splits are supported");
            return;
        }
        // Span is the screen, not the union of the two panes: the panes' windows shrink when
        // the lower app letterboxes (measured 1582 of 2176), which would make the step size
        // wobble. See parseSplit.
        final int span = screenH;
        if (span <= 0) { toast("split: odd geometry"); return; }
        final int divX = s[3];
        int divY = s[2];
        int step = Math.round(span * SPLIT_STEP_PERCENT / 100f);
        int lo = Math.round(span * SPLIT_MIN_PERCENT / 100f);
        int hi = span - Math.round(span * SPLIT_MIN_PERCENT / 100f);
        // up = the divider climbs = the bottom pane grows
        int target = clampInt(divY - dir * step, lo, hi);
        for (int i = 0; i < SPLIT_GRAB_OFFSETS.length; i++) {
            injectSplitDrag(divX, divY + SPLIT_GRAB_OFFSETS[i], divX, target);
            int[] after = parseSplit(shizuku.run("dumpsys window"));
            if (after == null) { toast("split: divider vanished"); return; }
            if (Math.abs(after[2] - divY) >= 4) {
                Log.i(TAG, "split nudge " + (dir > 0 ? "up" : "down") + ": divider " + divY
                        + " -> " + after[2] + " (wanted " + target + ", grab offset "
                        + SPLIT_GRAB_OFFSETS[i] + ")");
                return;
            }
        }
        toast("split: divider would not move");
    }

    /**
     * Drag the divider with a finger.
     *
     * Touchscreen, not the mouse drags that move app windows: the divider is moved with a
     * finger, and the measurement that proved an injected drag can move it was a touch. The
     * panels go non-touchable for the whole gesture, because the divider is often under the
     * pad - and the flag has to be in force before the DOWN, so the injection waits for it
     * (the same race that click-through had).
     */
    private void injectSplitDrag(final float x1, final float y1, final float x2, final float y2) {
        ui.post(new Runnable() {
            @Override public void run() { setPanelsTouchable(false); }
        });
        sleep(TOUCHABLE_SETTLE_MS + 20);
        long t = SystemClock.uptimeMillis();
        shizuku.touch(MotionEvent.ACTION_DOWN, x1, y1, t);
        for (int i = 1; i <= SPLIT_DRAG_STEPS; i++) {
            shizuku.touch(MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * i / (float) SPLIT_DRAG_STEPS,
                    y1 + (y2 - y1) * i / (float) SPLIT_DRAG_STEPS, t);
            sleep(SPLIT_DRAG_MS / SPLIT_DRAG_STEPS);
        }
        sleep(150);   // arrive without velocity, or the system flings the divider elsewhere
        shizuku.touch(MotionEvent.ACTION_UP, x2, y2, t);
        ui.post(new Runnable() {
            @Override public void run() { setPanelsTouchable(true); }
        });
        sleep(300);   // let the system settle before the caller reads the layout back
    }

    /**
     * Split geometry from a `dumpsys window` dump, as
     * `{0, screenH, dividerY, dividerX, 1 if the panes are stacked else 0}`, or null when
     * nothing looks like a two-pane split.
     *
     * The ruler for the ladder is the SCREEN, not the panes. After a resize the lower pane's
     * window often stops filling its pane - Termux keeps its old height at the top of the
     * space and leaves dead screen below - so a pane-union "area" shrinks and every rung
     * comes out wrong. Measured: `area 0..1582 divider 463 share 71%` when the truth was
     * 77%, which sent the next nudge the wrong way. The top pane and the divider window
     * agree exactly, so the divider is the only vertical value taken from the windows; the
     * two biggest app windows are still used to decide *whether* this is a stacked split.
     *
     * Windows rather than AccessibilityWindowInfo on purpose: retrieving them needs
     * flagRetrieveInteractiveWindows and this service runs flagDefault, while the shell
     * bridge is already here and needs no permission.
     */
    private int[] parseSplit(String dump) {
        if (dump == null) return null;
        int[] first = null, second = null;
        int divY = Integer.MIN_VALUE, divX = Integer.MIN_VALUE;
        for (String line : dump.split("\n")) {
            int at = line.indexOf("visible windows:");
            if (at < 0) continue;
            int br = line.indexOf('[', at);
            if (br < 0) continue;
            for (String w : line.substring(br + 1).split(", (?=[0-9a-f]{6,} \\S)")) {
                int[] r = frameOf(w);
                if (r == null) continue;
                String name = nameOf(w);
                if (name.indexOf("SplitDivider") >= 0) {
                    divY = (r[1] + r[3]) / 2;
                    divX = (r[0] + r[2]) / 2;
                    continue;
                }
                if (!isPaneWindow(name, r)) continue;
                if (first == null || paneArea(r) > paneArea(first)) { second = first; first = r; }
                else if (second == null || paneArea(r) > paneArea(second)) second = r;
            }
        }
        if (first == null || second == null) return null;
        // stacked, i.e. one above the other and overlapping sideways: only then does moving
        // the divider vertically mean anything
        boolean stacked = (first[3] <= second[1] + 80 || second[3] <= first[1] + 80)
                && first[0] < second[2] && second[0] < first[2];
        int left = Math.min(first[0], second[0]), right = Math.max(first[2], second[2]);
        if (divY == Integer.MIN_VALUE) {
            divY = (Math.min(first[3], second[3]) + Math.max(first[1], second[1])) / 2;
        }
        if (divX == Integer.MIN_VALUE) divX = (left + right) / 2;
        return new int[]{0, screenH, divY, divX, stacked ? 1 : 0};
    }

    /** Window frames read as `frame=[Rect(l, t - r, b)]`, or null if the entry has none. */
    private static final java.util.regex.Pattern FRAME_RE = java.util.regex.Pattern
            .compile("frame=\\[Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)\\]");

    private int[] frameOf(String w) {
        java.util.regex.Matcher g = FRAME_RE.matcher(w);
        return g.find() ? new int[]{Integer.parseInt(g.group(1)), Integer.parseInt(g.group(2)),
                Integer.parseInt(g.group(3)), Integer.parseInt(g.group(4))} : null;
    }

    private String nameOf(String w) {
        int sp = w.indexOf(' ');
        if (sp < 0) return w;
        String rest = w.substring(sp + 1);
        int comma = rest.indexOf(',');
        return (comma < 0) ? rest : rest.substring(0, comma);
    }

    /** True for a window that can plausibly be one pane of a split. */
    private boolean isPaneWindow(String name, int[] r) {
        String[] chrome = {"app.so7o.ztrackpad", "com.android.systemui", "ThumbsUpHandler",
                "FreeformContainer", "Taskbar", "InputMethod", "Embedded{", "StatusBar",
                "NavigationBar", "Wallpaper", "AssistPreview", "Splash", "Popup", "SplitDivider"};
        for (int i = 0; i < chrome.length; i++) if (name.indexOf(chrome[i]) >= 0) return false;
        if (name.indexOf('.') < 0) return false;
        int wpx = r[2] - r[0], hpx = r[3] - r[1];
        return wpx >= screenW * 0.35f && hpx >= screenH * 0.10f && hpx <= screenH * 0.92f;
    }

    private int paneArea(int[] r) { return (r[2] - r[0]) * (r[3] - r[1]); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // =========================================================================
    // Resize grips + geometry persistence
    // =========================================================================

    /**
     * Freeze the pad's geometry, or let it go again. The grips and the drag listener stay
     * attached and simply refuse - removing them instead would be invisible in the code and
     * indistinguishable from a broken grip on the device. Edge scrolling still works while
     * locked: the lock is about the pad's geometry, not about the pointer.
     */
    private void setPadLocked(boolean locked) {
        padLocked = locked;
        if (prefs != null) prefs.edit().putBoolean(PREF_LOCK, locked).apply();
        updateModeUi();
        toast(locked ? "pad locked" : "pad unlocked");
    }

    private int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private int padDefW() { return (int) (screenW * 0.90f); }
    private int padDefH() { return (int) (screenH * 0.36f); }
    private int keysDefW() { return (int) (screenW * 0.96f); }
    private int keysDefH() { return dp(34) + dp(304); }

    /** Short system "click" haptic, honouring the user's haptics setting. */
    private void tick() {
        try {
            if (hapticsOn == null) {
                hapticsOn = Settings.System.getInt(getContentResolver(),
                        Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1;
            }
            if (!hapticsOn) return;
            if (vib == null) vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return;
            vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        } catch (Throwable ignored) {}
    }

    /**
     * The classic resize handle: three diagonal strokes fanning out from the corner.
     *
     * No background, so only the strokes are drawn - and it still receives touches,
     * which is all a grip needs to be.
     */
    private class GripView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GripView() {
            super(TrackpadService.this);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            p.setColor(theme.grip);
            p.setStrokeWidth(Math.max(2f, dp(1.5f)));
            float[] f = { 0.80f, 0.56f, 0.32f };
            for (int i = 0; i < f.length; i++) {
                c.drawLine(w * f[i], h, w, h * f[i], p);
            }
        }
    }

    private View makeGrip() {
        return new GripView();
    }

    /**
     * All four corners resize; only one of them is drawn.
     *
     * A grip is nothing but a touch target - the drawing is cosmetic - so the invisible
     * ones cost nothing and mean a panel can still be resized from any corner without
     * four blobs on screen.
     *
     * includeTopLeft is false for the pad, where the hamburger button occupies that
     * corner and a grip there would swallow its taps.
     */
    private void addResizeGrips(FrameLayout container, WindowManager.LayoutParams lp,
                                String prefix, boolean includeTopLeft) {
        addGrip(container, container, lp, prefix, Gravity.BOTTOM | Gravity.RIGHT, 1, 1, true);
        if (includeTopLeft) {
            addGrip(container, container, lp, prefix, Gravity.TOP | Gravity.LEFT, -1, -1, false);
        }
        addGrip(container, container, lp, prefix, Gravity.TOP | Gravity.RIGHT, 1, -1, false);
        addGrip(container, container, lp, prefix, Gravity.BOTTOM | Gravity.LEFT, -1, 1, false);
    }

    /** dirX / dirY: -1 = this grip owns the left/top edge, +1 = right/bottom edge. */
    private void addGrip(FrameLayout parent, final View target,
                         final WindowManager.LayoutParams lp, final String prefix,
                         int gravity, final int dirX, final int dirY, boolean visible) {
        // invisible grips have no background but still receive touches, which is the
        // whole point of them
        View g = visible ? makeGrip() : new View(this);
        FrameLayout.LayoutParams flp =
                new FrameLayout.LayoutParams(dp(24), dp(24), gravity);
        flp.setMargins(dp(5), dp(5), dp(5), dp(5));
        g.setLayoutParams(flp);
        g.setOnTouchListener(new View.OnTouchListener() {
            private float sx, sy;
            private int sw, sh, ox, oy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                // A locked pad keeps its grips - an invisible grip with no listener cannot be
                // told apart from a broken one. Only the pad has a lock, so only pad
                // geometry (PAD_KEY is the empty prefix) is refused here.
                if (padLocked && PAD_KEY.equals(prefix)) return true;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        sw = lp.width;
                        sh = lp.height;
                        ox = lp.x;
                        oy = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - sx);
                        int dy = (int) (e.getRawY() - sy);
                        int nw = clampInt(sw + dirX * dx, dp(240), screenW);
                        int nh = clampInt(sh + dirY * dy, dp(120), screenH);
                        lp.width = nw;
                        lp.height = nh;
                        lp.x = (dirX < 0) ? ox + (sw - nw) : ox;
                        lp.y = (dirY < 0) ? oy + (sh - nh) : oy;
                        clampGeometryToScreen(lp);
                        try { wm.updateViewLayout(target, lp); } catch (Exception ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveGeometry(lp, prefix);
                        return true;
                }
                return false;
            }
        });
        parent.addView(g);
    }

    private void saveGeometry(WindowManager.LayoutParams lp, String prefix) {
        if (lp == null || prefs == null) return;
        prefs.edit()
                .putInt(prefix + "w", lp.width)
                .putInt(prefix + "h", lp.height)
                .putInt(prefix + "x", lp.x)
                .putInt(prefix + "y", lp.y)
                .apply();
    }

    private void restoreGeometry(WindowManager.LayoutParams lp, String prefix,
                                 int defW, int defH) {
        int w = prefs.getInt(prefix + "w", defW);
        int h = prefs.getInt(prefix + "h", defH);
        lp.width = w;
        lp.height = h;
        lp.x = prefs.getInt(prefix + "x", (screenW - w) / 2);
        lp.y = prefs.getInt(prefix + "y", screenH - h - dp(48));
        clampGeometryToScreen(lp);
    }

    /** Re-centre a panel at its default size. */
    private void resetPanelGeometry(View target, WindowManager.LayoutParams lp,
                                    String prefix, int defW, int defH) {
        if (lp == null) return;
        lp.width = defW;
        lp.height = defH;
        lp.x = (screenW - defW) / 2;
        lp.y = screenH - defH - dp(48);
        clampGeometryToScreen(lp);
        try { wm.updateViewLayout(target, lp); } catch (Exception ignored) {}
        saveGeometry(lp, prefix);
    }

    private void clampGeometryToScreen(WindowManager.LayoutParams lp) {
        if (lp == null) return;
        lp.width = clampInt(lp.width, dp(240), screenW);
        lp.height = clampInt(lp.height, dp(120), screenH);
        lp.x = clampInt(lp.x, 0, Math.max(0, screenW - lp.width));
        lp.y = clampInt(lp.y, 0, Math.max(0, screenH - lp.height));
    }

    private TextView makeChip(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(theme.textSecondary);
        t.setTextSize(10f);
        // every chip is a panel title bar, so it belongs centred in its space - without
        // this it sits left-aligned, which is noticeable on the pad once the theme button
        // and its matching spacer take a bite out of either end
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        return t;
    }

    /** Arrow keys: performGlobalAction(GLOBAL_ACTION_DPAD_*) injects a real DPAD key event.
     *  Hold to auto-repeat, same as the keys panel. */
    private TextView makeKey(String label, final int action) {
        return makeRepeatButton(label, new Runnable() {
            @Override public void run() {
                boolean ok = performGlobalAction(action);
                Log.i(TAG, "dpad action=" + action + " accepted=" + ok);
            }
        });
    }

    /** A trackpad-row button that auto-repeats while held. */
    private TextView makeRepeatButton(String label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(17f);
        t.setGravity(Gravity.CENTER);
        t.setBackground(keyBgState(0x00000000, theme.accent));
        t.setClickable(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        attachRepeat(t, action, true);
        return t;
    }

    /** Press and hold -> auto-repeat, exactly like a real keyboard key. */
    private void attachRepeat(final View v, final Runnable action, final boolean repeatable) {
        final Runnable[] rep = new Runnable[1];
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.setPressed(true);
                        tick();                     // haptic once, not per repeat
                        action.run();
                        cancelRepeat(rep);
                        if (repeatable) {
                            rep[0] = new Runnable() {
                                @Override public void run() {
                                    action.run();
                                    ui.postDelayed(this, REPEAT_MS);
                                }
                            };
                            ui.postDelayed(rep[0], REPEAT_DELAY_MS);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.setPressed(false);
                        cancelRepeat(rep);
                        view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.setPressed(false);
                        cancelRepeat(rep);
                        return true;
                }
                return false;
            }
        });
    }

    // =========================================================================
    // Special-keys bubble + panel (Termux-style extra keys)
    // =========================================================================

    /** Second floating dot, defaulting to the LEFT edge, toggling the keys panel. */
    private void buildKeysBubble() {
        final int size = dp(44);
        TextView v = new TextView(this);
        v.setText("\u2328");
        v.setTextColor(theme.bubbleKeys);
        v.setTextSize(20f);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill(theme.bubbleFill));
        bg.setStroke(dp(1.5f), theme.bubbleStroke);
        v.setBackground(bg);

        keysBubbleLp = overlayLp(size, size,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        keysBubbleLp.x = dp(8);                       // LEFT by default
        keysBubbleLp.y = (int) (screenH * 0.55f);

        attachBubbleDrag(v, keysBubbleLp, keysBubbleSide, new Runnable() {
            @Override public void run() { setKeysVisible(!keysVisible); }
        });

        keysBubble = v;
        try { wm.addView(keysBubble, keysBubbleLp); } catch (Exception ex) { Log.e(TAG, "keysBubble", ex); }
    }

    // Every row is 10 units wide and every key is exactly 1 unit, so all keys are
    // the same size across rows; shorter rows get centring spacers.
    private static final float ROW_UNITS = 13f;

    private LinearLayout newKeyRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return r;
    }

    private void addKey(LinearLayout row, String label, int keyCode) {
        row.addView(keyButton(label, keyCode, 0, 1f));
    }

    /** A wide key, e.g. the spacebar. */
    private void addKeyWide(LinearLayout row, String label, int keyCode, float units) {
        row.addView(keyButton(label, keyCode, 0, units));
    }

    private void addKeyMeta(LinearLayout row, String label, int keyCode, int extraMeta) {
        row.addView(keyButton(label, keyCode, extraMeta, 1f));
    }

    private void addModKey(LinearLayout row, String label, int metaBit) {
        row.addView(modButton(label, metaBit));
    }

    /** Centres a shorter row by padding both ends with weighted spacers. */
    private void padRow(LinearLayout row, float usedUnits) {
        float extra = (ROW_UNITS - usedUnits) / 2f;
        if (extra <= 0f) return;
        View left = new View(this);
        row.addView(left, 0, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, extra));
        View right = new View(this);
        row.addView(right, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, extra));
    }

    /**
     * The default key layout, as a spec.
     *
     * Kept as data rather than code so the built-in layout and a custom one take exactly
     * the same path - and so `op=keys` can hand the current layout back to you to edit.
     * (The built-in layout used to be hand-built in Java; it is the same keys.)
     *
     * Format: rows separated by '|', keys by ',', a key's parts by ':'.
     *   key   := label ':' keycode [ ';' attrs ]
     *   attrs := ';'-separated k=v:  m=ctrl+alt   n=2 (repeat)   w=6 (width in units)
     * keycode is any KeyEvent name (`escape`, `move_home`, `dpad_left`, `0`, `q`) or a
     * raw integer; `mod` makes it a sticky modifier instead of a key that sends.
     * A backslash escapes a separator - which is how the ':' ';' ',' and '|' keys manage
     * to label themselves.
     */
    private static final String DEFAULT_KEYS_SPEC =
            "ESC:escape,TAB:tab,CTRL:mod;m=ctrl,\uD83C\uDD71\uFE0F:b;m=ctrl,"
          + "SHIFT:mod;m=shift,\uD83C\uDD8E\uFE0F:a;m=ctrl,ALT:mod;m=alt,"
          + "HOME:move_home,END:move_end,\uD83C\uDD7F\uFE0F:p;m=ctrl,"
          + "*\uFE0F\u20E3\uD83C\uDD71\uFE0F:b;m=ctrl;n=2,\u23CE:enter,\u232B:del"
          + "|~:grave;m=shift,`:grave,\\;:semicolon,\\::semicolon;m=shift,?:slash;m=shift,"
          + "':apostrophe,\":apostrophe;m=shift,-:minus,_:minus;m=shift,/:slash,\\,:comma"
          + "|0:0,1:1,2:2,3:3,4:4,5:5,6:6,7:7,8:8,9:9"
          + "|q:q,w:w,e:e,r:r,t:t,y:y,u:u,i:i,o:o,p:p"
          + "|a:a,s:s,d:d,f:f,g:g,h:h,j:j,k:k,l:l"
          + "|SHIFT:mod;m=shift,z:z,x:x,c:c,v:v,b:b,n:n,m:m,\u23CE:enter"
          + "|HOME:move_home,END:move_end,PGUP:page_up,PGDN:page_down,DEL:forward_del,"
          + "\u2190:dpad_left,\u2191:dpad_up,\u2193:dpad_down,\u2192:dpad_right,"
          + "\\|:backslash;m=shift,\uD83C\uDFA4:v;m=alt"
          + "|space:space;w=6,\u232B back:del;w=3";

    /**
     * Split on unescaped `sep`, KEEPING the escapes in the output.
     *
     * The escapes have to survive every level: a row is split for rows, then keys, then
     * fields, and a ':' key is written '\:' throughout. Unescaping early would turn it into
     * a bare separator for the next level down.
     */
    private static List<String> splitEscaped(String s, char sep) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (esc) { cur.append(ch); esc = false; continue; }
            if (ch == '\\') { cur.append(ch); esc = true; continue; }
            if (ch == sep) { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    /** Remove backslash escapes. Only ever applied to a leaf value. */
    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (esc) { b.append(ch); esc = false; continue; }
            if (ch == '\\') { esc = true; continue; }
            b.append(ch);
        }
        if (esc) b.append('\\');   // trailing lone backslash
        return b.toString();
    }

    /**
     * Split on the FIRST unescaped `sep`, returning {before, after}, or null if absent.
     * Escapes are kept; unescape the pieces at the leaf. The parser needs this rather than
     * a full split: `label:keycode;attrs` is two fields, and splitting on every ':' would
     * swallow the keycode's attrs with it.
     */
    private static String[] splitFirstEscaped(String s, char sep) {
        boolean esc = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (esc) { cur.append(ch); esc = false; continue; }
            if (ch == '\\') { cur.append(ch); esc = true; continue; }
            if (ch == sep) return new String[]{ cur.toString(), s.substring(i + 1) };
            cur.append(ch);
        }
        return null;
    }

    /** Any KEYCODE_* on KeyEvent, by name, or a raw integer. -1 when unknown. */
    private static int keyCodeByName(String name) {
        String n = name.trim();
        if (n.length() == 0) return -1;
        if (n.matches("-?[0-9]+")) {
            try { return Integer.parseInt(n); } catch (Throwable ignored) { return -1; }
        }
        String u = n.toUpperCase();
        if (u.startsWith("KEYCODE_")) u = u.substring("KEYCODE_".length());
        try {
            return KeyEvent.class.getField("KEYCODE_" + u).getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** "ctrl+alt" -> META_CTRL_ON | META_ALT_ON. Unknown names are ignored. */
    private static int metaByName(String v) {
        int m = 0;
        String[] parts = v.toLowerCase().split("\\+");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if ("ctrl".equals(p)) m |= KeyEvent.META_CTRL_ON;
            else if ("alt".equals(p)) m |= KeyEvent.META_ALT_ON;
            else if ("shift".equals(p)) m |= KeyEvent.META_SHIFT_ON;
            else if ("meta".equals(p) || "super".equals(p)) m |= KeyEvent.META_META_ON;
            else if ("fn".equals(p)) m |= KeyEvent.META_FUNCTION_ON;
        }
        return m;
    }

    /** One key from its spec. Returns the width in row units, or 0 if it was unusable. */
    private float addKeyFromSpec(LinearLayout row, String keySpec) {
        // label ':' keycode [ ';' attrs ] - each split is on the FIRST unescaped separator,
        // so a label may itself be ':' or ';' as long as it is escaped
        String[] lp = splitFirstEscaped(keySpec, ':');
        if (lp == null) {
            Log.w(TAG, "keys: '" + keySpec + "' needs at least label:keycode");
            return 0f;
        }
        String label = unescape(lp[0]);
        String[] kcPart = splitFirstEscaped(lp[1], ';');
        String code = unescape((kcPart == null) ? lp[1] : kcPart[0]).trim();
        String attrs = (kcPart == null) ? "" : kcPart[1];

        int meta = 0, times = 1;
        float width = 1f;
        List<String> as = splitEscaped(attrs, ';');
        for (int i = 0; i < as.size(); i++) {
            String a = as.get(i);
            int eq = a.indexOf('=');
            if (eq < 0) continue;
            String k = unescape(a.substring(0, eq)).trim().toLowerCase();
            String v = unescape(a.substring(eq + 1)).trim();
            try {
                if ("m".equals(k)) meta = metaByName(v);
                else if ("n".equals(k)) times = Integer.parseInt(v);
                else if ("w".equals(k)) width = Float.parseFloat(v);
                else Log.w(TAG, "keys: unknown attr '" + k + "'");
            } catch (Throwable ignored) {}
        }

        // a sticky modifier, not a key that sends anything on its own
        if ("mod".equalsIgnoreCase(code)) {
            if (meta == 0) {
                Log.w(TAG, "keys: '" + label + "' is mod but has no m=");
                return 0f;
            }
            row.addView(modButton(label, meta));
            return 1f;
        }

        int kc = keyCodeByName(code);
        if (kc < 0) {
            Log.w(TAG, "keys: unknown keycode '" + code + "'");
            return 0f;
        }

        if (times > 1) {
            addMacro(row, label, meta, kc, times);
        } else {
            row.addView(keyButton(label, kc, meta, width));
        }
        return width;
    }

    /** One row per '|'-separated group. False if the spec produced no rows at all. */
    private boolean buildKeyRowsFromSpec(String spec, LinearLayout content) {
        int rows = 0;
        List<String> rowSpecs = splitEscaped(spec, '|');
        for (int i = 0; i < rowSpecs.size(); i++) {
            String rowSpec = rowSpecs.get(i).trim();
            if (rowSpec.length() == 0) continue;
            LinearLayout row = newKeyRow();
            float used = 0f;
            List<String> keySpecs = splitEscaped(rowSpec, ',');
            for (int j = 0; j < keySpecs.size(); j++) {
                String ks = keySpecs.get(j).trim();
                if (ks.length() == 0) continue;
                used += addKeyFromSpec(row, ks);
            }
            padRow(row, used);
            content.addView(row);
            rows++;
        }
        return rows > 0;
    }

    /**
     * The keys panel layout: the user's spec if one is set, otherwise the built-in one.
     * A custom spec that yields nothing falls back rather than leaving a blank panel.
     */
    private void buildKeyRows(LinearLayout content) {
        String spec = (keysSpec == null || keysSpec.trim().length() == 0)
                ? DEFAULT_KEYS_SPEC : keysSpec;
        if (!buildKeyRowsFromSpec(spec, content)) {
            Log.w(TAG, "keys: spec produced no rows, using the built-in layout");
            content.removeAllViews();
            buildKeyRowsFromSpec(DEFAULT_KEYS_SPEC, content);
        }
    }

    private void buildKeysPanel() {
        FrameLayout container = new FrameLayout(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // drag handle
        LinearLayout handle = new LinearLayout(this);
        handle.setGravity(Gravity.CENTER);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setCornerRadii(new float[]{dp(theme.radius), dp(theme.radius), dp(theme.radius), dp(theme.radius), 0, 0, 0, 0});
        hbg.setColor(fill(theme.panelHead));
        handle.setBackground(hbg);
        handle.addView(makeChip("\u2328  KEYS"));
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - keysPanelLp.x;
                        dy = e.getRawY() - keysPanelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        keysPanelLp.x = (int) (e.getRawX() - dx);
                        keysPanelLp.y = (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(keysPanel, keysPanelLp); } catch (Exception ignored) {}
                        return true;
                }
                return false;
            }
        });

        // handle first, so it stays at the TOP of the panel (drag + tap to re-centre)
        content.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        buildKeyRows(content);

        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GradientDrawable rbg = new GradientDrawable();
        rbg.setCornerRadius(dp(theme.radius));
        rbg.setColor(fill(theme.panelSolid));
        rbg.setStroke(dp(1.5f), theme.panelStroke);
        container.setBackground(rbg);

        keysPanelLp = overlayLp(keysDefW(), keysDefH(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        keysPanel = container;

        addResizeGrips(container, keysPanelLp, KEYS_KEY, true);

        restoreGeometry(keysPanelLp, KEYS_KEY, keysDefW(), keysDefH());
        try { wm.addView(keysPanel, keysPanelLp); } catch (Exception ex) { Log.e(TAG, "keysPanel", ex); }
        setKeysVisible(false);
    }

    private void setKeysVisible(boolean visible) {
        keysVisible = visible;
        if (keysPanel != null) keysPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private GradientDrawable keyBg(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(fill);
        g.setStroke(dp(1f), theme.keyStroke);
        return g;
    }

    private GradientDrawable keyShape(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(fill);
        g.setStroke(dp(1f), stroke);
        return g;
    }

    /** Key background that visibly changes colour while the key is held down. */
    private StateListDrawable keyBgState(int normal, int pressed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, keyShape(pressed, theme.keyStrokePressed));
        s.addState(new int[]{}, keyShape(normal, theme.keyStroke));
        return s;
    }

    private void setModLook(TextView t, boolean on) {
        t.setTextColor(on ? theme.modTextOn : theme.textPrimary);
        t.setBackground(keyBgState(on ? theme.modOnBg : theme.keyBg,
                on ? theme.modOnStroke : theme.accent));
    }

    private TextView keyButton(String label, final int keyCode, final int extraMeta) {
        return keyButton(label, keyCode, extraMeta, 1f);
    }

    /** tmux-style macro: send the same key `times` over (e.g. CTRL b b). Not repeatable. */
    private void addMacro(LinearLayout row, String label, final int meta,
                          final int keyCode, final int times) {
        row.addView(actionKey(label, 1f, new Runnable() {
            @Override public void run() {
                for (int i = 0; i < times; i++) sendKey(keyCode, meta);
            }
        }, false));
    }

    private TextView keyButton(String label, final int keyCode, final int extraMeta,
                               final float units) {
        return actionKey(label, units, new Runnable() {
            @Override public void run() { sendKey(keyCode, extraMeta); }
        }, true);
    }

    private TextView actionKey(String label, final float units,
                               final Runnable action, final boolean repeatable) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(12f);
        t.setGravity(Gravity.CENTER);
        t.setBackground(keyBgState(theme.keyBg, theme.accent));
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, units);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        t.setLayoutParams(lp);

        // Held keys auto-repeat like a real keyboard - hold backspace to wipe a line.
        final Runnable[] rep = new Runnable[1];
        t.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        tick();                     // haptic once, not on every repeat
                        action.run();
                        cancelRepeat(rep);
                        if (repeatable) {
                            rep[0] = new Runnable() {
                                @Override public void run() {
                                    action.run();
                                    ui.postDelayed(this, REPEAT_MS);
                                }
                            };
                            ui.postDelayed(rep[0], REPEAT_DELAY_MS);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        cancelRepeat(rep);
                        v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        cancelRepeat(rep);
                        return true;
                }
                return false;
            }
        });
        return t;
    }

    private void cancelRepeat(Runnable[] rep) {
        if (rep[0] != null) {
            ui.removeCallbacks(rep[0]);
            rep[0] = null;
        }
    }

    /** Sticky modifier: tap to arm, tap again to disarm. Applies to later keys. */
    private TextView modButton(String label, final int metaBit) {
        final TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(12f);
        t.setGravity(Gravity.CENTER);
        setModLook(t, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tick();
                metaState ^= metaBit;
                boolean on = (metaState & metaBit) != 0;
                setModLook(t, on);
                Log.i(TAG, "modifier " + label + " on=" + on + " metaState=" + metaState);
            }
        });
        return t;
    }

    /** Any keycode, via Shizuku's shell-side injectKey. Needs Shizuku - no fallback exists. */
    private void sendKey(int keyCode, int extraMeta) {
        int meta = metaState | extraMeta;
        Log.i(TAG, "key " + keyCode + " meta=" + meta);
        if (!useShizuku()) {
            Log.w(TAG, "key ignored - Shizuku not ready");
            return;
        }
        shizuku.key(keyCode, KeyEvent.ACTION_DOWN, meta);
        shizuku.key(keyCode, KeyEvent.ACTION_UP, meta);
    }

    private TextView makeButton(String label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(17f);
        t.setGravity(Gravity.CENTER);
        t.setBackground(keyBgState(0x00000000, theme.accent));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); action.run(); }
        });
        return t;
    }

    private void setPadVisible(boolean visible) {
        padVisible = visible;
        if (pad == null) return;
        pad.setVisibility(visible ? View.VISIBLE : View.GONE);
        updateCursorAlpha();
    }

    private void toast(final String msg) {
        Log.i(TAG, msg);
    }

    // =========================================================================
    // Pointer movement + gesture injection
    // =========================================================================

    private float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /**
     * True when the pointer is on the floating virtual display, whose pixels are 1:1 with
     * that display's coordinates. In that case our drawn arrow can be placed straight over
     * the window - which is the only way to see a pointer "inside" it, because a window we
     * own cannot be drawn into another display's composition.
     */
    private boolean cursorOnScreenPanel() {
        return virtualDisplayHasSurface && ownVirtualDisplayId >= 0
                && targetDisplayId == ownVirtualDisplayId
                && screenSurfaceAlive
                && screenPanel != null && screenPanel.getVisibility() == View.VISIBLE;
    }

    private void moveCursor(float dx, float dy) {
        // Finger deltas arrive in SURFACE pixels; the pointer lives in the TARGET
        // display's coordinate space. Scaling by the size ratio keeps "drag across the
        // pad" meaning the same fraction of the screen on any display - and it is a
        // no-op (ratio 1) when the two match, i.e. exactly the original behaviour.
        float kx = (screenW > 0) ? ((float) outW / (float) screenW) : 1f;
        float ky = (screenH > 0) ? ((float) outH / (float) screenH) : 1f;
        cursorX = clamp(cursorX + dx * sensitivity * kx, 0, outW - 1);
        cursorY = clamp(cursorY + dy * sensitivity * ky, 0, outH - 1);

        if (cursor != null) {
            int cx = -1, cy = -1;
            if (targetDisplayId == surfaceDisplayId) {
                // pointer is on this display: arrow at its own coordinates
                cx = (int) cursorX;
                cy = (int) cursorY;
            } else if (cursorOnScreenPanel()) {
                // pointer is on the floating display: the window's surface is the whole
                // display, so offset the arrow into the window (below its title bar)
                cx = screenPanelLp.x + (int) cursorX;
                cy = screenPanelLp.y + screenHeaderH + (int) cursorY;
            }
            if (cx >= 0) {
                cursorLp.x = cx;
                cursorLp.y = cy;
                try { wm.updateViewLayout(cursor, cursorLp); } catch (Exception ignored) {}
            }
        }
        // and the arrow on the target display, when the pointer is on one that isn't ours
        if (targetCursor != null && targetWm != null && targetCursorLp != null
                && targetDisplayId != surfaceDisplayId && !cursorOnScreenPanel()) {
            targetCursorLp.x = (int) cursorX;
            targetCursorLp.y = (int) cursorY;
            try { targetWm.updateViewLayout(targetCursor, targetCursorLp); } catch (Exception ignored) {}
        }
        // keep the real pointer in sync so hover effects and click targets are right
        if (useShizuku()) shizuku.hover(cursorX, cursorY);
    }

    private boolean sendStroke(GestureDescription.StrokeDescription s) {
        // dispatchGesture() has no display parameter, so with the pointer aimed at
        // another screen this would stroke the WRONG one. Refuse loudly instead of
        // silently clicking the wrong display.
        if (targetDisplayId != surfaceDisplayId && !useShizuku()) {
            Log.w(TAG, "stroke refused: display " + targetDisplayId + " needs Shizuku");
            return false;
        }
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(s);
            final boolean isDragChunk = (s.getDuration() == CHUNK_MS);
            boolean ok = dispatchGesture(b.build(), isDragChunk ? chunkCb : null, null);
            if (!ok && isDragChunk) Log.w(TAG, "drag: dispatch refused (gesture already in flight)");
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "stroke failed: " + e);
            return false;
        }
    }

    /** Reports whether the platform actually accepted our drag chunks or cancelled them. */
    private final AccessibilityService.GestureResultCallback chunkCb =
            new AccessibilityService.GestureResultCallback() {
        @Override public void onCompleted(GestureDescription g) {
            Log.d(TAG, "drag chunk: completed");
        }
        @Override public void onCancelled(GestureDescription g) {
            Log.w(TAG, "drag chunk: CANCELLED by platform - will restart");
            stroke = null;   // keep dragging; the heartbeat restarts the chain
        }
    };

    /**
     * The panels are touchable overlays, so an injected click aimed at a desktop icon
     * sitting under one would land on the panel instead. Briefly drop FLAG_NOT_TOUCHABLE
     * so the event dispatches to the app underneath, then restore it.
     *
     * Only used from the tap paths (click / long press / right click), which all fire on
     * finger-UP - so there is no in-flight gesture of ours to disturb. The drag path must
     * NOT do this, because there the finger is still down on the panel.
     */
    /**
     * Make the panels non-touchable, inject, then put them back.
     *
     * `updateViewLayout` only *queues* the flag change - it returns before the window
     * manager has applied it - so injecting straight afterwards raced it and the pad still
     * swallowed the tap whenever the pointer sat over one of our own panels. Measured in
     * split screen, where the pad covers most of a pane: the same click that worked with
     * the pointer clear of the pad did nothing underneath it.
     *
     * Two consequences: the injection is posted a couple of frames late, and the flag stays
     * up for as long as the gesture lasts plus a margin - a long press needs all of
     * HOLD_MS, or its UP arrives at a window that has gone touchable again.
     */
    private void injectThroughPanels(final Runnable inject, final long gestureMs) {
        setPanelsTouchable(false);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                inject.run();
                ui.postDelayed(new Runnable() {
                    @Override public void run() { setPanelsTouchable(true); }
                }, gestureMs + TOUCHABLE_SETTLE_MS);
            }
        }, TOUCHABLE_SETTLE_MS);
    }

    private void setPanelsTouchable(boolean touchable) {
        final int F = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (pad != null && padLp != null) {
            padLp.flags = touchable ? (padLp.flags & ~F) : (padLp.flags | F);
            try { wm.updateViewLayout(pad, padLp); } catch (Exception ignored) {}
        }
        if (keysPanel != null && keysPanelLp != null) {
            keysPanelLp.flags = touchable ? (keysPanelLp.flags & ~F) : (keysPanelLp.flags | F);
            try { wm.updateViewLayout(keysPanel, keysPanelLp); } catch (Exception ignored) {}
        }
        Log.i(TAG, "panels touchable=" + touchable);
    }

    private void click() {
        Log.i(TAG, "click at " + (int) cursorX + "," + (int) cursorY);
        if (useShizuku()) {
            injectThroughPanels(new Runnable() {
                @Override public void run() { shizuku.click(cursorX, cursorY); }
            }, TAP_INJECT_MS);
            return;
        }
        Path p = new Path();
        p.moveTo(cursorX, cursorY);
        sendStroke(new GestureDescription.StrokeDescription(p, 0, CLICK_MS));
    }

    private void longPress() {
        Log.i(TAG, "longpress at " + (int) cursorX + "," + (int) cursorY);
        if (useShizuku()) {
            final float px = cursorX, py = cursorY;
            injectThroughPanels(new Runnable() {
                @Override public void run() { shizuku.press(px, py, HOLD_MS); }
            }, HOLD_MS);
            return;
        }
        Path p = new Path();
        p.moveTo(cursorX, cursorY);
        sendStroke(new GestureDescription.StrokeDescription(p, 0, HOLD_MS));
    }

    private void rightClick() {
        Log.i(TAG, "right click at " + (int) cursorX + "," + (int) cursorY);
        if (useShizuku()) {
            injectThroughPanels(new Runnable() {
                @Override public void run() { shizuku.rightClick(cursorX, cursorY); }
            }, TAP_INJECT_MS);
            return;
        }
        longPress();   // best a touch gesture can do
    }

    /** The two-finger drag: `SCROLL_GAIN` per px of travel, one flush per `SCROLL_STEP`. */
    private void scrollBy(float dy) {
        scrollBy(dy, SCROLL_GAIN, SCROLL_STEP, false);
    }

    /** The edge strips: half the gain, half the travel per flush, and capped flushes. */
    private void scrollByEdge(float dy) {
        scrollBy(dy, SCROLL_GAIN * EDGE_SCROLL_FACTOR, EDGE_FLUSH_PX, true);
    }

    /**
     * `gain` scales how far the pointer's window scrolls per px of finger travel. `step` is how
     * much travel one flush consumes; with `capFlush` that is also the most a single flush may
     * inject, and the leftover stays on the accumulator for the next one.
     */
    private void scrollBy(float dy, float gain, float step, boolean capFlush) {
        scrollAccum += dy;
        long now = SystemClock.uptimeMillis();
        if (Math.abs(scrollAccum) < step) return;
        if (now - lastScrollAt < SCROLL_THROTTLE) return;
        float take = scrollAccum;
        if (capFlush && Math.abs(take) > step) {
            take = (scrollAccum < 0 ? -step : step);
        }
        if (useShizuku()) {
            // two-finger drag up should scroll content down -> negate
            shizuku.scroll(cursorX, cursorY, -take * gain, 0f);
            scrollAccum -= take;
            lastScrollAt = now;
            return;
        }
        Path p = new Path();
        p.moveTo(cursorX, cursorY);
        p.lineTo(cursorX, clamp(cursorY + take * gain, 0, screenH - 1));
        sendStroke(new GestureDescription.StrokeDescription(p, 0, 60));
        scrollAccum -= take;
        lastScrollAt = now;
    }

    // ------------------------------------------------------------------
    // Drag. With Shizuku this is a REAL mouse drag (SOURCE_MOUSE +
    // BUTTON_PRIMARY), which is what lets a window title bar be grabbed and
    // moved. The accessibility path can only approximate it, because
    // dispatchGesture() cancels the touch stream driving it.
    // ------------------------------------------------------------------

    /* ------------------------------------------------------------------
     * Press-and-hold drag, built from continued strokes.
     * A StrokeDescription is only valid for its declared duration, so a
     * heartbeat re-continues it every DRAG_BEAT_MS. That is what lets a
     * drag survive the user pausing mid-drag.
     * ------------------------------------------------------------------ */

    private boolean beginDrag() {
        dragViaShizuku = useShizuku();
        if (dragViaShizuku) {
            dragDownAt = SystemClock.uptimeMillis();
            shizuku.mouseDown(cursorX, cursorY, dragDownAt);
            lastMoveAt = dragDownAt;
            Log.i(TAG, "drag: shizuku mouse down at " + (int) cursorX + "," + (int) cursorY);
            return true;
        }
        Path p = new Path();
        p.moveTo(cursorX, cursorY);
        GestureDescription.StrokeDescription s =
                new GestureDescription.StrokeDescription(p, 0, CHUNK_MS, true);
        if (!sendStroke(s)) return false;
        stroke = s;
        strokeEndX = cursorX;
        strokeEndY = cursorY;
        lastMoveAt = SystemClock.uptimeMillis();
        startBeat();
        Log.i(TAG, "drag: begin at " + (int) cursorX + "," + (int) cursorY);
        return true;
    }

    private void dragTo() {
        if (dragViaShizuku) {
            shizuku.mouseMove(cursorX, cursorY, dragDownAt);
            return;
        }
        if (stroke == null) return;
        try {
            Path p = new Path();
            p.moveTo(strokeEndX, strokeEndY);   // MUST match where the last chunk ended
            p.lineTo(cursorX, cursorY);
            GestureDescription.StrokeDescription next = stroke.continueStroke(p, 0, CHUNK_MS, true);
            if (sendStroke(next)) {
                stroke = next;
                strokeEndX = cursorX;
                strokeEndY = cursorY;
            }
        } catch (Exception e) {
            Log.w(TAG, "drag: chain broke (" + e.getClass().getSimpleName() + ") - will restart");
            stroke = null;   // heartbeat / next move restarts the chain in place
        }
    }

    private void endDrag() {
        if (dragViaShizuku) {
            shizuku.mouseUp(cursorX, cursorY, dragDownAt);
            Log.i(TAG, "drag: shizuku mouse up at " + (int) cursorX + "," + (int) cursorY);
            dragViaShizuku = false;
            return;
        }
        stopBeat();
        if (stroke != null) {
            try {
                Path p = new Path();
                p.moveTo(strokeEndX, strokeEndY);
                p.lineTo(cursorX, cursorY);
                sendStroke(stroke.continueStroke(p, 0, 100, false));
            } catch (Exception ignored) {}
            stroke = null;
            Log.i(TAG, "drag: end at " + (int) cursorX + "," + (int) cursorY);
        }
    }

    private void startBeat() {
        if (beat == null) {
            beat = new Runnable() {
                @Override public void run() {
                    if (!dragging) return;
                    // safety: never leave an app holding a press if touches stop arriving
                    if (SystemClock.uptimeMillis() - lastMoveAt > DRAG_STALL_MS) {
                        Log.i(TAG, "drag: stalled -> auto-release");
                        endDrag();
                        dragging = false;
                        updateModeUi();
                        return;
                    }
                    if (stroke == null) beginDrag();   // restart a broken chain
                    else dragTo();
                    if (dragging) ui.postDelayed(this, DRAG_BEAT_MS);
                }
            };
        }
        ui.removeCallbacks(beat);
        ui.postDelayed(beat, DRAG_BEAT_MS);
    }

    private void stopBeat() {
        if (beat != null) ui.removeCallbacks(beat);
    }

    /** Hold still for DRAG_HOLD_MS, then move -> drag. No mode switch needed. */
    private void scheduleHold() {
        cancelHold();
        holdRun = new Runnable() {
            @Override public void run() {
                holdRun = null;
                holdReady = true;
            }
        };
        ui.postDelayed(holdRun, DRAG_HOLD_MS);
    }

    private void cancelHold() {
        holdReady = false;
        if (holdRun != null) { ui.removeCallbacks(holdRun); holdRun = null; }
    }

    private void updateModeUi() {
        if (cursor != null) cursor.setHot(dragging);
        if (moveChip != null) {
            // This is the only writer of the handle's label: a transient gesture wins, then
            // the lock, then the armed/move states. The lock used to set the text itself,
            // and the next drag promptly overwrote it back to MOVE.
            String mode = edgeScroll ? "\u2261  SCROLL"
                    : (dragging ? "\u2261  DRAGGING"
                            : (padLocked ? "\u2261  LOCKED"
                                    : (dragArmed ? "\u2261  DRAG ARMED" : "\u2261  MOVE")));
            if (!padLocked && useShizuku()) mode += "  \u00B7 SZ";
            // say when we are driving another screen, and warn if we cannot
            if (!padLocked && targetDisplayId != surfaceDisplayId) {
                mode += useShizuku()
                        ? ("  \u00B7 \u25B6 " + outW + "\u00D7" + outH)
                        : "  \u00B7 \u26A0 NO SZ";
            }
            moveChip.setText(mode);
        }
    }

    // =========================================================================
    // Trackpad touch handling
    // =========================================================================

    private class PadTouch implements View.OnTouchListener {

        private float centroidX(MotionEvent e) {
            float s = 0;
            for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
            return s / e.getPointerCount();
        }

        private float centroidY(MotionEvent e) {
            float s = 0;
            for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
            return s / e.getPointerCount();
        }

        // two-finger tap == right click (context menu), tracked separately from two-finger scroll
        private float twoX, twoY;
        private boolean twoMoved;
        private long twoAt;
        private boolean rightClickFired;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {

                case MotionEvent.ACTION_DOWN:
                    boolean quickReturn = (SystemClock.uptimeMillis() - lastTapUpAt) < DOUBLE_TAP_MS;
                    // A touch landing in an edge strip scrolls, and nothing else: no cursor,
                    // no click, no drag. Decided here at DOWN, because the hold timer and
                    // the tap-then-drag window would otherwise claim a slow edge swipe as a
                    // press-and-drag. The strip is scroll-only on purpose - the pointer is
                    // somewhere else entirely, so a click from here would land somewhere
                    // the finger never was.
                    edgeScroll = (e.getX() < dp(EDGE_SCROLL_DP)
                            || e.getX() > v.getWidth() - dp(EDGE_SCROLL_DP));
                    Log.i(TAG, "down pad=" + v.getWidth() + "x" + v.getHeight()
                            + " at " + (int) e.getX() + "," + (int) e.getY()
                            + " dragArmed=" + dragArmed + " tapDrag=" + quickReturn
                            + (edgeScroll ? " EDGE" : ""));
                    downX = lastX = e.getX();
                    downY = lastY = e.getY();
                    downTime = SystemClock.uptimeMillis();
                    moved = false;
                    scrollMode = false;
                    scrollAccum = 0f;
                    twoMoved = false;
                    rightClickFired = false;
                    cancelHold();
                    if (edgeScroll) {
                        updateModeUi();
                    } else if (dragArmed) {
                        if (beginDrag()) { dragging = true; updateModeUi(); }
                    } else if (quickReturn) {
                        // tapped a moment ago -> this touch drags as soon as it moves
                        tapDrag = true;
                    } else {
                        scheduleHold();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    scrollMode = true;
                    edgeScroll = false;
                    twoMoved = false;
                    twoX = centroidX(e);
                    twoY = centroidY(e);
                    twoAt = SystemClock.uptimeMillis();
                    lastX = twoX;
                    lastY = twoY;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    lastMoveAt = SystemClock.uptimeMillis();
                    if (e.getPointerCount() >= 2 || scrollMode) {
                        float cx = centroidX(e), cy = centroidY(e);
                        if (Math.abs(cx - twoX) > SLOP * 2 || Math.abs(cy - twoY) > SLOP * 2) {
                            twoMoved = true;
                        }
                        if (twoMoved && !dragging) {
                            // held both fingers still first, then moved -> press-and-drag
                            boolean held = holdReady;
                            cancelHold();
                            if (held && !dragArmed) {
                                if (beginDrag()) { dragging = true; updateModeUi(); }
                            }
                        }
                        if (dragging) {
                            moveCursor(cx - lastX, cy - lastY);
                            dragTo();
                        } else {
                            scrollBy(cy - lastY);   // immediate two-finger movement = scroll
                        }
                        lastX = cx;
                        lastY = cy;
                        return true;
                    }
                    float x = e.getX(), y = e.getY();
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    if (edgeScroll) {
                        // same sign convention as the two-finger drag: swipe up, content down
                        scrollByEdge(dy);
                        // pretend it moved, so ACTION_UP cannot turn this into a click
                        moved = true;
                        return true;
                    }
                    if (!moved && (Math.abs(x - downX) > SLOP || Math.abs(y - downY) > SLOP)) {
                        boolean wasHeld = holdReady;
                        boolean wasTapDrag = tapDrag;
                        moved = true;
                        cancelHold();
                        // held still, or tapped just before -> start a real press-and-drag
                        if (!dragging && !dragArmed && (wasHeld || wasTapDrag)) {
                            Log.i(TAG, "drag trigger (held=" + wasHeld + " tapDrag=" + wasTapDrag + ")");
                            if (beginDrag()) { dragging = true; updateModeUi(); }
                        }
                    }
                    if (moved) moveCursor(dx, dy);
                    if (dragging) {
                        // only the accessibility backend has a stroke chain to restart
                        if (!dragViaShizuku && stroke == null) beginDrag();
                        dragTo();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    if (e.getPointerCount() <= 2) {
                        scrollMode = false;
                        // two fingers down and never moved == right click (no duration limit,
                        // otherwise a slow two-finger tap falls into a dead zone)
                        if (!twoMoved) {
                            rightClick();
                            rightClickFired = true;
                        }
                        // remaining finger becomes a move origin again
                        int keep = (e.getActionIndex() == 0) ? 1 : 0;
                        lastX = e.getX(keep);
                        lastY = e.getY(keep);
                        moved = true;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (rightClickFired) { rightClickFired = false; cancelHold(); tapDrag = false; return true; }
                    if (edgeScroll) {
                        edgeScroll = false;
                        tapDrag = false;
                        // an edge tap must not arm the tap-then-drag window
                        lastTapUpAt = 0L;
                        updateModeUi();
                        return true;
                    }
                    if (dragging) {
                        endDrag();
                        dragging = false;
                        cancelHold();
                        tapDrag = false;
                        lastTapUpAt = 0L;
                        updateModeUi();
                        return true;
                    }
                    cancelHold();
                    if (scrollMode) { scrollMode = false; tapDrag = false; return true; }
                    long dt = SystemClock.uptimeMillis() - downTime;
                    if (!moved) {
                        // a second tap that never moved is still a double click
                        if (tapDrag || dt < LONGPRESS_MS) click();
                        else longPress();
                        lastTapUpAt = tapDrag ? 0L : SystemClock.uptimeMillis();
                    } else {
                        lastTapUpAt = 0L;
                    }
                    tapDrag = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    cancelHold();
                    tapDrag = false;
                    edgeScroll = false;
                    if (dragging) { endDrag(); dragging = false; updateModeUi(); }
                    scrollMode = false;
                    return true;
            }
            return false;
        }
    }
}
