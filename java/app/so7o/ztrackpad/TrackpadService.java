package app.so7o.ztrackpad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.display.DeviceProductInfo;
import android.net.Uri;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyCharacterMap;
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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
     * A quarter of the gain, and half the travel per flush, so a swipe down the side arrives
     * in smaller steps than a two-finger drag does. The flush is also *capped* at the same
     * amount: without that, a fast flick accumulates a lot of travel between two throttled
     * flushes and delivers it as one jump, which defeats the point of the smaller step.
     *
     * The factor scales only the injected distance, not the travel accounting - a flush
     * consumes EDGE_FLUSH_PX of finger travel whatever the gain - so this is the one knob
     * for "how far does the page move", and 0.25 puts it at ~0.35px of content per px of
     * finger, where 0.5 was ~0.7 and still felt fast.
     */
    private static final float EDGE_SCROLL_FACTOR = 0.25f;
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
    /**
     * Flick-to-scroll mode (CONTROLS > Flick to scroll): the edge strips stop scrolling
     * live and instead bank the whole gesture's travel, spending it as ONE jump on
     * release - ported from ZTrackpad Lite, where it is the only option because
     * dispatchGesture cannot inject mid-gesture. It is an option here because this build
     * CAN scroll live, which is the better feel, but Lite's was deliberately different:
     * slow to react, then a single flick.
     *
     * This is the flick's gain: banked distance per px of finger travel, spent at once.
     * It is a plain ratio in the same way Lite's was (2.0 there measured 2:1 and felt
     * right, so it is 2.0 here too).
     */
    private static final float EDGE_FLICK_GAIN = 2.0f;
    /** Ceiling on one flick, as a fraction of the screen height - a long fling stays a fling. */
    private static final float SCROLL_CAP_SCREEN = 0.6f;
    /**
     * Floor on one flick, in dp: below this much banked distance nothing is injected.
     * A sub-floor flick is a tap's worth of travel, which a stroke would read as a tap.
     */
    private static final float MIN_SCROLL_DP = 20f;
    /** How long the single stroke that carries a flick takes, on the no-Shizuku path. */
    private static final long SCROLL_MS = 260L;
    private static final long CLICK_MS = 45L;
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
    private static final String TASKS_KEY = "f_";
    private static final String CONTROLS_KEY = "c_";

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

    /** Pref: "full" builds the built-in keyboard, "favorites" a spec of your own. */
    private static final String PREF_KEYS_MODE = "keysMode";

    /**
     * Prefs for whether each optional dot exists at all.
     *
     * There is deliberately no pref for the pad's own dot: it is the only way to show the
     * pad (which has no close button) and it carries the docked dots that open this panel
     * and the theme menu, so hiding it would strand the way back to everything.
     */
    private static final String PREF_SHOW_KEYS_BUBBLE = "showKeysBubble";
    private static final String PREF_SHOW_TASKS_BUBBLE = "showTasksBubble";

    /** Pref: edge-strip scrolls bank the gesture and spend it on release - see flickScroll. */
    private static final String PREF_FLICK_SCROLL = "flickScroll";

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

    /**
     * The floating-window list, filtered shell-side.
     *
     * The `grep` is not cosmetic: the whole dump is 336 KB and this reply is 5 KB, for the
     * same ~110 ms, so the cut is free. `Display #` lines come along because a task header
     * does not name its display - the section header is the only place that information
     * exists. `mBounds=Rect` is anchored to the start of a line on purpose: the config
     * blocks print `winConfig={ mBounds=Rect(...) }` inline, and a looser pattern drags all
     * of those in too (which is most of the dump).
     */
    private static final String TASKS_CMD =
            "dumpsys activity activities | grep -E '^ *Display #|^ *\\* Task[{]|^ *mBounds=Rect'";

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
    /**
     * Size request from the last `vdisplay create --w/--h`, or -1 for the defaults
     * (1920x1080 headless, the floating window's own size). Applied to that create only.
     */
    private int pendingDisplayW = -1, pendingDisplayH = -1;
    private CursorView cursor;
    private TextView moveChip;
    /** The pad's lock dot, so a theme rebuild can hand back a new one. */
    private LockDot lockDot;
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

    // floating-window list (the "pop-up view" task switcher)
    private View tasksBubble, tasksPanel;
    private WindowManager.LayoutParams tasksBubbleLp, tasksPanelLp;
    private LinearLayout tasksRows;
    private boolean tasksVisible = false;
    /** True while a list fetch is in flight, so taps cannot pile up shell round trips. */
    private boolean tasksBusy = false;

    // controls panel - the gear dot docked on the pad
    private View controlsPanel;
    private WindowManager.LayoutParams controlsPanelLp;
    private LinearLayout controlsRows;
    private boolean controlsVisible = false;
    /** "full" or "favorites"; see PREF_KEYS_MODE. */
    private String keysMode = "full";
    private boolean showKeysBubble = true, showTasksBubble = true;
    /**
     * True = the edge strips scroll Lite-style: bank the gesture, spend it on release
     * ("flick to scroll"). False = the default live throttled scroll. See PREF_FLICK_SCROLL.
     */
    private boolean flickScroll = false;
    /** The list as last rendered, so a tap can act on rows it did not have to re-read. */
    private List<TaskRow> lastRows;
    /**
     * Bounds a parked window had before it was shrunk, keyed by task id.
     *
     * In memory only: task ids are reused, and a stale entry from a previous run could
     * point at somebody else's window. Entries are dropped as soon as a fetch stops
     * listing the task.
     */
    private final HashMap<Integer, int[]> parkedBounds = new HashMap<Integer, int[]>();

    /** Height of the strip floating windows are parked into, in dp. */
    private static final int PARK_H = 140;

    /**
     * Where a pop-up's own minimize button sits inside its window, in dp from its top-right
     * corner. Measured on this device (2026-09-27): with a window at `300,300 - 1500,1500`
     * the `-` is at `1220,350`, and tapping it takes the task to `visible=false` with the
     * task still alive and still freeform. Samsung lays its pop-up header out in dp, but
     * that is an assumption, which is why no minimize is trusted without checking.
     */
    private static final int MINIMIZE_FROM_RIGHT = 124;
    private static final int MINIMIZE_FROM_TOP = 22;
    /** How long the window manager gets to hide a minimized window before it is checked. */
    private static final long MINIMIZE_SETTLE_MS = 1200L;

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
    /** Scroll banked while a finger is down, spent on release - see flushPendingScroll. */
    private float pendingScrollY;
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
        keysMode = prefs.getString(PREF_KEYS_MODE, "full");
        showKeysBubble = prefs.getBoolean(PREF_SHOW_KEYS_BUBBLE, true);
        showTasksBubble = prefs.getBoolean(PREF_SHOW_TASKS_BUBBLE, true);
        flickScroll = prefs.getBoolean(PREF_FLICK_SCROLL, false);
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
            resnapBubble(tasksBubble, tasksBubbleLp, tasksBubbleSide);
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
        buildTasksBubble();
        buildCursor();
        buildPad();
        buildKeysPanel();
        buildPickerPanel();
        buildControlsPanel();
        buildTasksPanel();
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
        boolean tasksWas = tasksVisible;
        boolean controlsWas = controlsVisible;

        // persist geometry first, or the rebuild would fall back to the defaults
        saveGeometry(padLp, PAD_KEY);
        saveGeometry(keysPanelLp, KEYS_KEY);
        saveGeometry(pickerPanelLp, PICKER_KEY);
        saveGeometry(screenPanelLp, SCREEN_KEY);
        saveGeometry(tasksPanelLp, TASKS_KEY);
        saveGeometry(controlsPanelLp, CONTROLS_KEY);

        removeViews();
        buildAll();

        restyleBubbles();
        setPadVisible(true);
        setKeysVisible(keysWas);
        if (screenWas && screenPanel != null) screenPanel.setVisibility(View.VISIBLE);
        setPickerVisible(pickerWas);
        // keep the theme panel open, so presets can be tried one after another
        setThemeVisible(themeWas);
        setTasksVisible(tasksWas);
        setControlsVisible(controlsWas);

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
        restyleBubble(tasksBubble, theme.bubbleTasks);
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
        try { if (tasksBubble != null) wm.removeView(tasksBubble); } catch (Exception ignored) {}
        try { if (controlsPanel != null) wm.removeView(controlsPanel); } catch (Exception ignored) {}
        try { if (pad != null) wm.removeView(pad); } catch (Exception ignored) {}
        try { if (keysPanel != null) wm.removeView(keysPanel); } catch (Exception ignored) {}
        try { if (pickerPanel != null) wm.removeView(pickerPanel); } catch (Exception ignored) {}
        try { if (themePanel != null) wm.removeView(themePanel); } catch (Exception ignored) {}
        try { if (tasksPanel != null) wm.removeView(tasksPanel); } catch (Exception ignored) {}
        try { if (screenPanel != null) wm.removeView(screenPanel); } catch (Exception ignored) {}
        try { if (cursor != null) wm.removeView(cursor); } catch (Exception ignored) {}
        bubble = keysBubble = tasksBubble = pad = keysPanel = null;
        pickerPanel = themePanel = screenPanel = tasksPanel = null;
        controlsPanel = null;
        cursor = null;
        themeRows = null;
        opacityRows = null;
        tasksRows = null;
        controlsRows = null;
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
        int w = (pendingDisplayW > 0) ? pendingDisplayW : 1920;
        int h = (pendingDisplayH > 0) ? pendingDisplayH : 1080;
        ownVirtualDisplayId = shizuku.createVirtualDisplay("ztrackpad", w, h, 320);
        virtualDisplayHasSurface = false;
        Log.i(TAG, "virtual display -> id " + ownVirtualDisplayId + " " + w + "x" + h);
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
    public String vdisplayCommand(String op, boolean headless, String spec, String arg,
                                  int displayW, int displayH, String url) {
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
                // what is on screen, so it can be edited: the favorites spec when favorites
                // is selected, the built-in layout otherwise
                return "spec=" + activeKeysSpec();
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
            // Optional `--w/--h` size request from the broadcast extras. Applied to this
            // create only; the defaults return once the op finishes.
            if ((displayW > 0) != (displayH > 0)) {
                return "error: create wants both --w and --h, or neither (got "
                        + displayW + "x" + displayH + ")";
            }
            if (displayW > 0 && (displayW < 240 || displayH < 240
                    || displayW > 7680 || displayH > 7680)) {
                return "error: create dimensions out of range ("
                        + displayW + "x" + displayH + ")";
            }
            pendingDisplayW = (displayW > 0) ? displayW : -1;
            pendingDisplayH = (displayH > 0) ? displayH : -1;
            if (headless) {
                boolean up = (ownVirtualDisplayId >= 0 && !virtualDisplayHasSurface);
                if (up) {
                    if (pendingDisplayW > 0 && shizuku != null) {
                        shizuku.resizeVirtualDisplay(pendingDisplayW, pendingDisplayH, 320);
                    }
                } else {
                    toggleOwnVirtualDisplay();
                }
            } else {
                boolean floatingUp = (ownVirtualDisplayId >= 0 && virtualDisplayHasSurface);
                if (pendingDisplayW > 0) {
                    // a requested size sizes the floating window; the surface (and with it
                    // the display) follows it via the existing resize chain
                    resizeScreenPanelTo(pendingDisplayW, pendingDisplayH);
                }
                if (!floatingUp) {
                    // Showing the window is all we can do here: the display is created from
                    // the surface callback, so it will not exist yet. Poll status.
                    if (screenPanel != null) screenPanel.setVisibility(View.VISIBLE);
                    raise(cursor, cursorLp, "cursor");
                    updateVirtualRow();
                } else if (screenPanel != null && screenPanel.getVisibility() != View.VISIBLE) {
                    toggleVirtualScreen();
                }
            }
            pendingDisplayW = -1;
            pendingDisplayH = -1;
            return headless ? "ok create-headless " + vdisplayStatus()
                            : "ok create-floating " + vdisplayStatus();
        }

        // The controls panel, like the tasks panel: scriptable so it can be shown without a
        // finger, which is also how it gets verified.
        if ("controls".equals(op)) {
            String what = (arg == null || arg.length() == 0) ? "toggle" : arg.trim();
            if ("show".equals(what)) setControlsVisible(true);
            else if ("hide".equals(what)) setControlsVisible(false);
            else if ("toggle".equals(what)) setControlsVisible(!controlsVisible);
            else return "error: controls wants show|hide|toggle";
            return "ok controls " + (controlsVisible ? "shown" : "hidden") + " keys-mode=" + keysMode
                    + " bubbles=keys:" + onOff(showKeysBubble) + ",tasks:" + onOff(showTasksBubble)
                    + " flick=" + onOff(flickScroll);
        }

        // The edge strips' scroll feel, mirroring the CONTROLS row - same setter, so a
        // script and a finger cannot disagree, and a no-arg read for scripts.
        if ("flick".equals(op)) {
            String what = (arg == null) ? "" : arg.trim();
            if (what.length() == 0) return "ok flick " + onOff(flickScroll);
            if (!"on".equals(what) && !"off".equals(what)) {
                return "error: flick wants on|off, not '" + what + "'";
            }
            setFlickScroll("on".equals(what));
            return "ok flick " + onOff(flickScroll);
        }

        // Which layout the keys panel shows, and which of the optional dots exist. Both
        // mirror the CONTROLS panel's rows exactly - same setters, so a script and a finger
        // cannot disagree - and both report the current state when given no arg.
        if ("keys-mode".equals(op)) {            String what = (arg == null) ? "" : arg.trim();
            if (what.length() == 0) return "ok keys-mode " + keysMode;
            if (!"full".equals(what) && !"favorites".equals(what)) {
                return "error: keys-mode wants full|favorites";
            }
            setKeysMode(what);
            return "ok keys-mode " + keysMode + " " + keySpecSummary();
        }

        if ("bubbles".equals(op)) {            String what = (arg == null) ? "" : arg.trim();
            if (what.length() > 0) {
                String[] parts = what.split(",");
                for (int i = 0; i < parts.length; i++) {
                    String one = parts[i].trim();
                    int eq = one.indexOf('=');
                    if (eq < 0) return "error: bubbles wants keys=on|off,tasks=on|off";
                    String name = one.substring(0, eq).trim();
                    String val = one.substring(eq + 1).trim();
                    if (!"on".equals(val) && !"off".equals(val)) {
                        return "error: bubbles wants on|off, not '" + val + "'";
                    }
                    if ("keys".equals(name)) setBubbleShown(true, "on".equals(val));
                    else if ("tasks".equals(name)) setBubbleShown(false, "on".equals(val));
                    else return "error: unknown dot '" + name + "' (keys|tasks)";
                }
            }
            return "ok bubbles keys=" + onOff(showKeysBubble) + " tasks=" + onOff(showTasksBubble);
        }

        // Floating ("pop-up view") windows. With no arg, list them; with an arg, drive the
        // panel - so the UI can be exercised without tapping, like every other op here.
        if ("tasks".equals(op)) {
            if (arg != null && arg.length() > 0) {
                String what = arg.trim();
                if ("show".equals(what)) setTasksVisible(true);
                else if ("hide".equals(what)) setTasksVisible(false);
                else if ("toggle".equals(what)) setTasksVisible(!tasksVisible);
                else return "error: tasks wants show|hide|toggle, or no arg to list";
                return "ok tasks " + (tasksVisible ? "shown" : "hidden");
            }
            if (!useShizuku()) return "error: tasks needs Shizuku";
            List<TaskRow> rows = parseWindows(shizuku.run(TASKS_CMD), surfaceDisplayId);
            StringBuilder sb = new StringBuilder("ok tasks n=").append(rows.size())
                    .append(" display=").append(surfaceDisplayId);
            for (int i = 0; i < rows.size(); i++) {
                TaskRow r = rows.get(i);
                sb.append(' ').append(r.id).append(':').append(r.pkg)
                        .append(':').append(r.visible ? "visible" : "hidden")
                        .append(':').append(r.fullscreen ? "fullscreen" : "floating");
            }
            return sb.toString();
        }

        // arg is a task id from `tasks`. This is the same code path a row tap takes, so a
        // script cannot get a different result from a finger.
        if ("taskfocus".equals(op)) {
            String idArg = (arg == null) ? "" : arg.trim();
            int id = parseIntOr(idArg, -1);
            if (id < 0) return "error: taskfocus wants a task id in arg";
            if (!useShizuku()) return "error: taskfocus needs Shizuku";
            List<TaskRow> rows = parseWindows(shizuku.run(TASKS_CMD), surfaceDisplayId);
            TaskRow row = findRow(rows, id);
            if (row == null) return "error: task " + id + " is not in the list";
            // Exactly what a row tap does, and deliberately asynchronous for the same reason a
            // tap is: the minimize path is a tap, a settle and a verifying fetch, which is far
            // too long to hold the main thread - this runs on the main thread, and every
            // accessibility callback in this service does too. A caller that wants the result
            // re-reads `tasks` (which is what tests/smoke.sh does).
            activateRow(row, rows);
            return "ok taskfocus " + id + " role=" + (row.fullscreen ? "fullscreen" : "floating");
        }

        // Start an app on the display we own, shell-side. `am start --display` from adb
        // shell can be refused for displays like ours, and the shell process this app
        // already keeps is both the display's owner and holds INTERNAL_SYSTEM_WINDOW -
        // which the framework checks first, in ActivityTaskSupervisor
        // .isCallerAllowedToLaunchOnDisplay (AOSP 16). This is the same route the seed
        // uses, so it is the proven one.
        // Screen-space input aimed at the pad's current target display - the identical
        // routing the pad's own clicks and keys use (ShizukuInputHandler carries displayId
        // on every event), so a script types and taps exactly where the pointer is aimed,
        // which is what the tap-to-aim display list does for a finger.
        if ("tap".equals(op)) {
            if (!useShizuku()) return "error: tap needs Shizuku";
            String[] xy = (arg == null ? "" : arg.trim()).split("\\s+");
            if (xy.length < 2) return "error: tap wants '<x> <y>' in display px";
            int x = parseIntOr(xy[0], -1);
            int y = parseIntOr(xy[1], -1);
            if (x < 0 || y < 0) return "error: tap wants numeric '<x> <y>'";
            shizuku.click(x, y);
            return "ok tap " + x + " " + y;
        }

        if ("type".equals(op)) {
            if (!useShizuku()) return "error: type needs Shizuku";
            String text = (arg == null) ? "" : arg;
            if (text.length() == 0) return "ok type (empty)";
            // KeyCharacterMap turns chars into real DOWN/UP events including shift; short
            // texts only, this runs on the main thread (via the receiver) like the other ops.
            KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            for (int i = 0; i < text.length(); i++) {
                KeyEvent[] evts = kcm.getEvents(new char[]{text.charAt(i)});
                if (evts == null) {
                    continue;   // unmappable on this keyboard: skip
                }
                for (KeyEvent e : evts) {
                    shizuku.key(e.getKeyCode(), e.getAction(), e.getMetaState());
                }
                try {
                    Thread.sleep(18);
                } catch (InterruptedException ignored) {
                }
            }
            return "ok type " + text.length() + " chars";
        }

        if ("press".equals(op)) {
            if (!useShizuku()) return "error: press needs Shizuku";
            String name = (arg == null) ? "" : arg.trim();
            int kc = keyCodeByName(name);
            if (kc < 0) return "error: press wants a KEYCODE_* name (e.g. ENTER), got '" + name + "'";
            shizuku.key(kc, KeyEvent.ACTION_DOWN, 0);
            shizuku.key(kc, KeyEvent.ACTION_UP, 0);
            return "ok press " + name;
        }

        if ("launch".equals(op)) {
            String target = (arg == null) ? "" : arg.trim();
            if (target.length() == 0) {
                return "error: launch wants a package or component in arg"
                        + " (com.android.settings, or com.android.settings/.Settings)";
            }
            if (ownVirtualDisplayId < 0) return "error: launch needs a display - create one first";
            if (!useShizuku()) return "error: launch needs Shizuku";
            StringBuilder cmd = new StringBuilder("am start --display ")
                    .append(ownVirtualDisplayId).append(" -f 0x10000000");
            if (url != null && url.length() > 0) {
                cmd.append(" -a android.intent.action.VIEW -d ").append(shellQuote(url));
            }
            // a '/' makes it a component; without one am resolves the package itself
            if (target.indexOf('/') >= 0) cmd.append(" -n ").append(shellQuote(target));
            else cmd.append(' ').append(shellQuote(target));
            String out;
            try {
                out = String.valueOf(shizuku.run(cmd.toString()));
            } catch (Throwable t) {
                Log.w(TAG, "launch: " + t);
                return "error: launch " + t;
            }
            String trimmed = out.trim();
            String low = trimmed.toLowerCase();
            if (low.indexOf("error") >= 0 || low.indexOf("exception") >= 0
                    || low.indexOf("denied") >= 0 || low.indexOf("not found") >= 0) {
                return "error: launch " + trimmed.replace('\n', ' ');
            }
            int nl = trimmed.indexOf('\n');
            String line = (nl >= 0) ? trimmed.substring(0, nl).trim() : trimmed;
            return "ok launch display=" + ownVirtualDisplayId + " target=" + target
                    + (line.length() == 0 ? "" : " " + line);
        }

        // Point the pad's input at a display, exactly like tapping a row in the ▣ picker
        // - same setter, so a script cannot get a different result from a finger. A
        // numeric arg is a display id; anything else is a package whose current task
        // names its display.
        if ("target".equals(op)) {
            String what = (arg == null) ? "" : arg.trim();
            if (what.length() == 0) {
                Display cur = (displayManager == null) ? null
                        : displayManager.getDisplay(targetDisplayId);
                String name = (cur == null) ? "" : " " + cur.getName();
                return "ok target " + targetDisplayId + name;
            }
            int id = parseIntOr(what, -2);
            if (id < 0) {
                id = displayIdForPackage(what);
                if (id < 0) {
                    return "error: target: '" + what + "' is no display id and no running"
                            + " package";
                }
            }
            Display d = (displayManager == null) ? null : displayManager.getDisplay(id);
            if (d == null || !d.isValid()) {
                return "error: target: display " + id + " is not attached";
            }
            setTargetDisplay(id);
            return "ok target " + id + " " + d.getName() + " " + outW + "x" + outH;
        }

        // Capture the display's own pixels straight from SurfaceFlinger, so a script can
        // verify content without the composite (overlay) noise of a phone-screen capture.
        if ("shot".equals(op)) {
            if (ownVirtualDisplayId < 0) return "error: shot needs a display - create one first";
            if (!useShizuku()) return "error: shot needs Shizuku";
            String name = (arg == null) ? "" : arg.trim();
            if (name.length() == 0) name = "vdisplay-" + ownVirtualDisplayId;
            name = name.replaceAll("[^A-Za-z0-9._-]", "_");
            String path = "/data/local/tmp/" + name + ".png";
            String err = captureDisplayPng(ownVirtualDisplayId, path);
            if (err != null) return err;
            return "ok shot " + path;
        }

        return "error: unknown op '" + op + "' (status|create|destroy|show|hide|lock|keys"
                + "|keys-reset|keys-mode|flick|bubbles|controls|tasks|taskfocus|launch|target|shot)";
    }

    /**
     * The display a package's top task currently lives on, or -1. Reuses the same
     * filtered `dumpsys activity activities` the tasks list uses: each `Display #N`
     * section header is followed by that display's tasks, and a task names its package
     * in the `A=<uid>:<pkg>` token. This is how `vdisplay target <package>` works, and
     * it reads nothing.
     */
    private int displayIdForPackage(String pkg) {
        if (!useShizuku() || pkg == null || pkg.length() == 0) return -1;
        String dump = shizuku.run(TASKS_CMD);
        if (dump == null) return -1;
        int current = -1;
        String[] lines = dump.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int d = line.indexOf("Display #");
            if (d >= 0) {
                current = parseIntOr(nextToken(line, d + 9), -1);
                continue;
            }
            if (line.indexOf("* Task{") < 0) continue;
            int a = line.indexOf("A=");
            if (a < 0) continue;
            String spec = nextToken(line, a + 2);
            int colon = spec.indexOf(':');
            if (colon < 0) continue;
            String taskPkg = spec.substring(colon + 1);
            if (taskPkg.endsWith(".root")) {
                taskPkg = taskPkg.substring(0, taskPkg.length() - 5);
            }
            if (pkg.equals(taskPkg)) return current;
        }
        return -1;
    }

    /**
     * Scripted resize of the floating window, applied by `vdisplay create --w/--h`
     * before the surface exists. Clamped to the surface display (a window cannot be
     * bigger than the screen it is drawn on) with the aspect kept, and the result is
     * saved so a later restore does not fight it.
     */
    private void resizeScreenPanelTo(int w, int h) {
        if (screenPanel == null || screenPanelLp == null) return;
        if (w <= 0 || h <= 0) return;
        float aspect = (float) w / (float) h;
        int nw = w, nh = h;
        if (nw > screenW || nh > screenH) {
            if ((float) screenW / (float) screenH > aspect) {
                nh = screenH;
                nw = Math.round(nh * aspect);
            } else {
                nw = screenW;
                nh = Math.round(nw / aspect);
            }
        }
        screenPanelLp.width = nw;
        screenPanelLp.height = nh;
        screenPanelLp.x = Math.max(0, (screenW - nw) / 2);
        screenPanelLp.y = Math.max(0, (screenH - nh) / 2);
        clampGeometryToScreen(screenPanelLp);
        try { wm.updateViewLayout(screenPanel, screenPanelLp); } catch (Exception ignored) {}
        saveGeometry(screenPanelLp, SCREEN_KEY);
        Log.i(TAG, "screen panel resized to " + screenPanelLp.width + "x" + screenPanelLp.height);
    }

    /**
     * Single-quote a value for the device shell (`sh -c`), so '&' '?' or spaces in a
     * URL are not re-parsed as shell syntax inside ShellUserService.runCommand.
     */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Screencap one of our displays straight to a shell-writable path, bypassing the
     * phone-screen composite. screencap -d wants SurfaceFlinger's own display value
     * (raw Android display ids are rejected on this device), so the candidates tried
     * are the value from the `Display <value> (Virtual display)` line for a display
     * named "ztrackpad" in `dumpsys SurfaceFlinger --display-id`, then the Android id.
     * An OFF headless display may legitimately capture nothing: the result is verified
     * by file size, and failures are reported instead of assumed.
     */
    private String captureDisplayPng(int displayId, String path) {
        String[] candidates = { String.valueOf(displayId) };
        String sf;
        try {
            sf = String.valueOf(shizuku.run("dumpsys SurfaceFlinger --display-id"));
        } catch (Throwable t) {
            Log.w(TAG, "sf display-id: " + t);
            sf = "";
        }
        if (sf != null) {
            String[] lines = sf.split("\n");
            for (int i = 0; i < lines.length && candidates.length == 1; i++) {
                if (lines[i].indexOf("(Virtual display)") < 0) continue;
                if (lines[i].indexOf("displayName=\"ztrackpad\"") < 0) continue;
                int p = lines[i].indexOf("Display ");
                if (p >= 0) {
                    candidates = new String[] { nextToken(lines[i], p + 8),
                            String.valueOf(displayId) };
                }
            }
        }
        for (int i = 0; i < candidates.length; i++) {
            try {
                shizuku.run("timeout 5 screencap -d " + candidates[i] + " -p " + path);
            } catch (Throwable t) {
                Log.w(TAG, "screencap: " + t);
                continue;
            }
            if (pathBytes(path) > 100) return null;
        }
        return "error: shot: could not capture display " + displayId
                + " (is it headless/OFF?)";
    }

    /** Size of a shell-side file, or -1. `wc -c` prints just the byte count. */
    private long pathBytes(String path) {
        String out;
        try {
            out = String.valueOf(shizuku.run("wc -c < " + path));
        } catch (Throwable t) {
            Log.w(TAG, "wc: " + t);
            return -1;
        }
        String[] tokens = out.trim().split("\\s+");
        if (tokens.length == 0) return -1;
        try { return Long.parseLong(tokens[0]); } catch (Throwable t) { return -1; }
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
                + " flick=" + onOff(flickScroll)
                + " keys=" + ((keysSpec == null || keysSpec.length() == 0) ? "default" : "custom");
    }

    /** A short summary of the active keys layout, for a script to log. */
    private String keySpecSummary() {
        String spec = activeKeysSpec();
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
        // Our own display is shell-owned, so app-side display filtering keeps it invisible
        // to getDisplay() - the picker would never list it, and tap-to-control would stay
        // impossible. Show it from app state; targeting works by id, so no Display object
        // is needed.
        if (ownVirtualDisplayId >= 0 && !containsId(ds, ownVirtualDisplayId)) {
            final int id = ownVirtualDisplayId;
            Log.i(TAG, "  display " + id + " 'ztrackpad' (from app state)");
            boolean isTarget = (id == targetDisplayId);
            boolean isSurface = (id == surfaceDisplayId);
            TextView t = new TextView(this);
            StringBuilder sb = new StringBuilder();
            sb.append(isTarget ? "\u25C9  " : "\u25CB  ").append("ztrackpad");
            if (isSurface) sb.append("  (surface)");
            sb.append("\n     ").append(screenSurfaceW).append("\u00D7").append(screenSurfaceH)
                    .append("  \u00B7  id ").append(id);
            t.setText(sb.toString());
            t.setTextSize(12f);
            t.setTextColor(isTarget ? theme.textPrimary : theme.textSecondary);
            t.setPadding(dp(12), dp(9), dp(12), dp(9));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    tick();
                    setTargetDisplay(id);
                }
            });
            t.setBackground(keyBgState(isTarget ? theme.selectedRow : 0x00000000, theme.accent));
            pickerRows.addView(t);
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
    private final BubbleSide tasksBubbleSide = new BubbleSide(false); // ▤ starts LEFT

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
        addDockedDot(container, "\u25A3", theme.bubbleDisplay, true, 1,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        tick(); setPickerVisible(!pickerVisible);
                    }
                });
        // The gear is docked right-most, which is why the display dot moved inward a slot.
        addDockedDot(container, "\u2699", theme.bubbleControls, true, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        tick(); setControlsVisible(!controlsVisible);
                    }
                });

        // the handle's label is the only thing that reads the lock state, and updateModeUi
        // owns that label
        updateModeUi();

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

    /**
     * Drag the divider with a finger.
     *
     * Touchscreen, not the mouse drags that move app windows: the divider is moved with a
     * finger, and the measurement that proved an injected drag can move it was a touch. The
     * panels go non-touchable for the whole gesture, because the divider is often under the
     * pad - and the flag has to be in force before the DOWN, so the injection waits for it
     * (the same race that click-through had).
     */
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

    /**
     * As restoreGeometry, but with an explicit default position instead of the generic
     * "centred, near the bottom". Only applies when nothing is saved yet, so it decides
     * where the panel first appears rather than where it stays.
     */
    private void restoreGeometryAt(WindowManager.LayoutParams lp, String prefix,
                                   int defW, int defH, int defX, int defY) {
        restoreGeometry(lp, prefix, defW, defH);
        if (prefs.contains(prefix + "x")) return;   // the user has put it somewhere
        lp.x = defX;
        lp.y = defY;
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
        // Optional. This dot is the only way to open the keys panel, so hiding it is for
        // people who do not use that panel at all - and the way back is the CONTROLS panel
        // (whose own dot cannot be hidden) or `op=bubbles keys=on`.
        if (!showKeysBubble) { keysBubble = null; return; }
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
        String spec = activeKeysSpec();
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
        if (flickScroll) {
            // Flick mode banks the whole gesture and spends it on release - nothing is
            // injected here, on purpose: a live flush now would double the travel with
            // the release jump. See flushPendingScroll().
            pendingScrollY += dy * EDGE_FLICK_GAIN;
            return;
        }
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

    /**
     * Spend the scroll a flick-mode gesture banked, once the finger is up.
     *
     * This is ZTrackpad Lite's whole mechanism, ported as an option: bank the distance
     * during the gesture and inject it as ONE event on release. Lite had no choice - its
     * only backend is dispatchGesture, which cannot inject while a real touch is in
     * progress - but its feel was distinctive and worth offering here: the strip is slow
     * to react (nothing happens until you lift your finger) and then the page moves in a
     * single jump, like a flick.
     *
     * With Shizuku the distance goes out as one wheel event at the pointer. Without it,
     * it is one stroke, exactly as in Lite.
     */
    private void flushPendingScroll() {
        float move = pendingScrollY;
        pendingScrollY = 0f;
        if (move == 0f) return;
        // One event has to carry the whole gesture, so cap it: a long drag is a fling, and
        // a fling over a full screen carries on scrolling after the finger is long gone.
        float cap = screenH * SCROLL_CAP_SCREEN;
        if (move > cap) move = cap;
        if (move < -cap) move = -cap;
        // The banked distance is already proportional to the finger, so this is only a
        // floor for the whole gesture rather than something a fast swipe trips over.
        if (Math.abs(move) < dp(MIN_SCROLL_DP)) return;
        Log.i(TAG, "flick scroll on release move=" + (int) move);
        if (useShizuku()) {
            // Same sign as the live path: the banked move has the finger's sign, and the
            // wheel event negates it so content follows the touch.
            shizuku.scroll(cursorX, cursorY, -move, 0f);
            return;
        }
        Path p = new Path();
        p.moveTo(cursorX, cursorY);
        p.lineTo(cursorX, clamp(cursorY + move, 0, screenH - 1));
        sendStroke(new GestureDescription.StrokeDescription(p, 0, SCROLL_MS));
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
            String mode = edgeScroll ? (flickScroll ? "\u2261  FLICK" : "\u2261  SCROLL")
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
                    pendingScrollY = 0f;
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
                        // flick mode spent nothing while the finger was down; spend it now
                        flushPendingScroll();
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
                    // like Lite: a gesture cancelled mid-way still spends what it banked
                    flushPendingScroll();
                    if (dragging) { endDrag(); dragging = false; updateModeUi(); }
                    scrollMode = false;
                    return true;
            }
            return false;
        }
    }

    // =========================================================================
    // Floating windows ("pop-up view") - the task switcher
    //
    // Overlapping floating windows have no way to reach each other: the one at the back
    // is simply hidden, and there is no affordance that names it. This is the entry point
    // for one - a bubble that lists them and puts the chosen one in front.
    //
    // Scoped to the display the panels live on, which is the unfolded screen while the
    // phone is open. On the cover screen the list is simply empty: display 1 reports
    // canHostTasks=false, so nothing floats there to list. DeX and cross-display listing
    // are deliberately not attempted yet.
    // =========================================================================

    /**
     * One floating window, as read out of `dumpsys activity activities`.
     *
     * A plain holder rather than a TaskInfo because the list arrives as text. TaskInfo
     * would come from IActivityTaskManager, which this app can only reach by reflecting on
     * a hidden class - the shell bridge can run `dumpsys` today, so that is what v1 uses.
     */
    private static final class TaskRow {
        int id;
        String pkg;
        boolean visible;
        /** True for the fullscreen app the floating windows are sitting on top of. */
        boolean fullscreen;
        /** The task's own bounds as {left, top, right, bottom}, or null if it did not say. */
        int[] bounds;
    }

    /**
     * The floating entry point. A bubble rather than a docked dot on purpose: it has to be
     * reachable while a floating window covers the pad, which is exactly the situation
     * that makes the list worth having. It starts on the LEFT edge, level with the pad dot
     * (0.45 of the screen height, so 55% up from the bottom) and directly ABOVE the keys
     * dot at 0.55 - so the left edge reads top to bottom as windows, then keys.
     */
    private void buildTasksBubble() {
        if (!showTasksBubble) { tasksBubble = null; return; }
        final int size = dp(44);
        TextView v = new TextView(this);
        // U+25A4, from the same Geometric Shapes block as the other dots - an emoji here
        // would ignore setTextColor and render in the font's own colours
        v.setText("\u25A4");
        v.setTextColor(theme.bubbleTasks);
        v.setTextSize(19f);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill(theme.bubbleFill));
        bg.setStroke(dp(1.5f), theme.bubbleStroke);
        v.setBackground(bg);

        tasksBubbleLp = overlayLp(size, size,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        tasksBubbleLp.x = dp(8);                      // LEFT by default, above the ⌨ dot
        // 55% up from the bottom, i.e. level with the pad dot on the other edge, with the
        // keys dot below it - the three default heights are 0.45 (● and ▤) and 0.55 (⌨).
        tasksBubbleLp.y = (int) (screenH * 0.45f);

        attachBubbleDrag(v, tasksBubbleLp, tasksBubbleSide, new Runnable() {
            @Override public void run() { setTasksVisible(!tasksVisible); }
        });

        tasksBubble = v;
        try { wm.addView(tasksBubble, tasksBubbleLp); }
        catch (Exception ex) { Log.e(TAG, "tasksBubble", ex); }
    }

    private void buildTasksPanel() {
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
        handle.addView(makeChip("\u25A4  WINDOWS \u2014 tap to bring forward"));
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - tasksPanelLp.x;
                        dy = e.getRawY() - tasksPanelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        tasksPanelLp.x = (int) (e.getRawX() - dx);
                        tasksPanelLp.y = (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(tasksPanel, tasksPanelLp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        saveGeometry(tasksPanelLp, TASKS_KEY);
                        return true;
                }
                return false;
            }
        });
        content.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        // One row per floating window, and a window can be worth a long label - scroll
        // rather than let the list grow past the bottom of the panel.
        ScrollView scroll = new ScrollView(this);
        tasksRows = new LinearLayout(this);
        tasksRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(tasksRows, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GradientDrawable rbg = new GradientDrawable();
        rbg.setCornerRadius(dp(theme.radius));
        rbg.setColor(fill(theme.panelSolid));
        rbg.setStroke(dp(1.5f), theme.panelStroke);
        container.setBackground(rbg);

        tasksPanelLp = overlayLp(dp(320), dp(280),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        tasksPanel = container;

        addResizeGrips(container, tasksPanelLp, TASKS_KEY, true);

        restoreGeometryAt(tasksPanelLp, TASKS_KEY, dp(320), dp(280), dp(173), dp(509));
        try { wm.addView(tasksPanel, tasksPanelLp); }
        catch (Exception ex) { Log.e(TAG, "tasksPanel", ex); }
        setTasksVisible(false);
    }

    private void setTasksVisible(boolean visible) {
        tasksVisible = visible;
        if (tasksPanel == null) return;
        if (visible) {
            clampGeometryToScreen(tasksPanelLp);
            raise(tasksPanel, tasksPanelLp, "tasksPanel");
            // Refreshed on every open, because the list is a snapshot and focusing a
            // window - or closing it from its own header - changes the order.
            refreshTaskRowsAsync();
        }
        tasksPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Fetch the list off the main thread.
     *
     * A `dumpsys` round trip is ~100 ms measured through the same shell bridge. That is a
     * visible stall on the main thread, and every accessibility callback in this service
     * also runs there, so it would hold up input injection too.
     */
    private void refreshTaskRowsAsync() {
        if (tasksBusy) return;
        if (!useShizuku()) { renderTaskRows(null); return; }
        tasksBusy = true;
        final int displayId = surfaceDisplayId;
        new Thread(new Runnable() {
            @Override public void run() {
                List<TaskRow> rows = null;
                try {
                    rows = parseWindows(shizuku.run(TASKS_CMD), displayId);
                } catch (Throwable t) {
                    Log.w(TAG, "tasks: " + t);
                }
                final List<TaskRow> got = rows;
                ui.post(new Runnable() {
                    @Override public void run() {
                        tasksBusy = false;
                        renderTaskRows(got);
                    }
                });
            }
        }, "pi-tasks").start();
    }

    private void renderTaskRows(List<TaskRow> rows) {
        if (tasksRows == null) return;
        tasksRows.removeAllViews();
        lastRows = rows;
        if (rows == null) {
            tasksRows.addView(taskNote("\u26A0  needs Shizuku", theme.footerText));
            return;
        }
        forgetMissingParks(rows);
        if (rows.isEmpty()) {
            tasksRows.addView(taskNote("no floating windows on this screen", theme.textDim));
            return;
        }
        for (int i = 0; i < rows.size(); i++) tasksRows.addView(taskRow(rows.get(i)));
    }

    /**
     * Drop park records for tasks that are no longer listed.
     *
     * Task ids are reused, so a record kept past its task's death could later restore a
     * stranger's window to the bounds of a window that no longer exists.
     */
    private void forgetMissingParks(List<TaskRow> rows) {
        List<Integer> gone = null;
        for (Integer key : parkedBounds.keySet()) {
            if (!containsTask(rows, key.intValue())) {
                if (gone == null) gone = new ArrayList<Integer>();
                gone.add(key);
            }
        }
        if (gone == null) return;
        for (int i = 0; i < gone.size(); i++) parkedBounds.remove(gone.get(i));
    }

    private TextView taskNote(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11f);
        t.setTextColor(color);
        t.setPadding(dp(12), dp(14), dp(12), dp(14));
        return t;
    }

    private TextView taskRow(final TaskRow r) {
        TextView t = new TextView(this);
        String label = taskLabel(r.pkg);
        // A hidden window is the reason this list exists - it is behind another window,
        // or minimized, and there is no other way to name it. So mark it, do not filter it.
        // The fullscreen app gets a marker too, because tapping it is the one row that can
        // quietly do nothing: a fullscreen root task sits BELOW the floating windows, so
        // focusing it cannot lift it past them. It works whenever nothing is floating on
        // top, which is also when the row is least needed.
        String suffix = r.fullscreen ? "   \u00B7 full screen"
                : (parkedBounds.containsKey(Integer.valueOf(r.id)) ? "   \u00B7 parked"
                : (r.visible ? "" : "   \u00B7 hidden"));
        t.setText(label + suffix);
        t.setTextSize(12f);
        t.setTextColor(r.visible ? theme.textPrimary : theme.textDim);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setBackground(keyBgState(theme.keyBg, theme.accent));
        Drawable icon = taskIcon(r.pkg);
        if (icon != null) {
            // app icons have no useful intrinsic size here, so give them one
            icon.setBounds(0, 0, dp(20), dp(20));
            t.setCompoundDrawables(icon, null, null, null);
            t.setCompoundDrawablePadding(dp(9));
        }
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tick();
                activateRow(r, lastRows);
            }
        });
        return t;
    }

    /**
     * An app's label, or its package name if the lookup fails.
     *
     * Package visibility filtering applies to this process, so without the manifest's
     * <queries> entry this throws NameNotFound for anything we have not launched
     * ourselves. The fallback keeps a row readable instead of blank when it does.
     */
    private String taskLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private Drawable taskIcon(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); }
        catch (Throwable t) { return null; }
    }

    /**
     * Every switchable window on one display: the fullscreen app first, then the floating
     * ("pop-up view") windows in dump order, which is front-most first.
     *
     * Two kinds of line look like a floating window and are not one, and both are real on
     * this device:
     *   - freeform ROOT tasks and the per-desktop containers, which are `type=undefined`
     *     (the containers also report `sz=0`, and carry `dw=activatable` / `dw=minimized`)
     *   - anything with no `A=<uid>:<pkg>` at all, which has no app behind it
     *
     * A real window is `type=standard` with `sz>0`. Filtering on the affinity instead does
     * NOT work: an activity that ends up in a freeform root task reports its affinity as
     * `<pkg>.root` - Settings' window does - so that suffix has to be stripped, not read
     * as a container marker. `type=standard` also excludes the home and recents tasks for
     * free, which is why they need no special case.
     *
     * Only VISIBLE fullscreen tasks are worth a row: a hidden one is a stale recents entry,
     * and there are dozens of those (this device lists 25 at idle). Visible is also the
     * right test for "behind the pop-ups", because a task under floating windows is still
     * being drawn and still reports visible=true.
     *
     * The same task can also appear twice, once at top level and once nested under its
     * root task, so rows are de-duped by id. Display scoping comes from the `Display #N`
     * section headers: a task header line does not name its display.
     */
    private List<TaskRow> parseWindows(String dump, int displayId) {
        List<TaskRow> out = new ArrayList<TaskRow>();
        // Held aside and prepended at the end: the dump lists the fullscreen task first and
        // the floating ones last, which is the opposite of the order the panel wants.
        List<TaskRow> background = new ArrayList<TaskRow>();
        if (dump == null || dump.length() == 0) return out;
        int current = -1;
        // The row whose bounds we are waiting for; the task's own `mBounds=` line is the
        // first one after its header, and every other mBounds line in a task's block sits
        // inside a config dump on a line of its own, so only the first is taken.
        TaskRow pending = null;
        String[] lines = dump.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.indexOf("* Task{") < 0) {
                int d = line.indexOf("Display #");
                if (d >= 0) {
                    current = parseIntOr(nextToken(line, d + 9), -1);
                } else if (pending != null && pending.bounds == null) {
                    pending.bounds = parseRect(line);
                }
                continue;
            }
            if (current != displayId) continue;
            boolean floating = line.indexOf("mode=freeform") >= 0;
            boolean fullscreen = line.indexOf("mode=fullscreen") >= 0;
            if (!floating && !fullscreen) continue;
            if (line.indexOf("type=standard") < 0) continue;
            if (line.indexOf("sz=0") >= 0) continue;
            boolean visible = line.indexOf("visible=true") >= 0;
            if (fullscreen && !visible) continue;

            int hash = line.indexOf('#');
            if (hash < 0) continue;
            int id = parseIntOr(nextToken(line, hash + 1), -1);
            if (id < 0 || containsTask(out, id) || containsTask(background, id)) {
                pending = null;   // a duplicate: its bounds block belongs to the row we kept
                continue;
            }

            int a = line.indexOf("A=");
            if (a < 0) continue;
            String spec = nextToken(line, a + 2);
            int colon = spec.indexOf(':');
            if (colon < 0) continue;
            String pkg = spec.substring(colon + 1);
            if (pkg.endsWith(".root")) pkg = pkg.substring(0, pkg.length() - 5);
            if (pkg.length() == 0) continue;

            TaskRow r = new TaskRow();
            r.id = id;
            r.pkg = pkg;
            r.visible = visible;
            r.fullscreen = fullscreen;
            if (floating) out.add(r); else background.add(r);
            pending = r;
        }
        // The full-screen app goes FIRST even though it is drawn behind the floating windows.
        // Being behind is what makes it hard to reach, so it is the row you come here for, and
        // keeping it last moved it down the panel every time another window was opened.
        out.addAll(0, background);
        return out;
    }

    /** `mBounds=Rect(61, 143 - 1751, 1701)` -> `{61, 143, 1751, 1701}`. */
    private static int[] parseRect(String line) {
        int p = line.indexOf("Rect(");
        if (p < 0) return null;
        int end = line.indexOf(')', p);
        if (end < 0) return null;
        String[] parts = line.substring(p + 5, end).split("[\\s,-]+");
        if (parts.length < 4) return null;
        try {
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]) };
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean containsTask(List<TaskRow> rows, int id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id == id) return true;
        }
        return false;
    }

    /** The whitespace-delimited token that starts at `from`. */
    private static String nextToken(String line, int from) {
        int end = from;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
        return line.substring(from, end);
    }

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); }
        catch (Throwable t) { return fallback; }
    }

    /**
     * Bring a floating window to the front.
     *
     * `am task focus` is ActivityTaskManager.setFocusedTask: it moves the task to the
     * front of its own display and focuses it, which is both what a tap on the window does
     * and what restores one that is behind another or minimized. It needs
     * MANAGE_ACTIVITY_TASKS - shell holds it, a third-party app cannot - so it goes over
     * the existing bridge.
     *
     * moveTaskToFront would also work from shell (REORDER_TASKS, plus the exemption from
     * the background-activity-start check that shell's START_ACTIVITIES_FROM_BACKGROUND
     * buys), but this build has no `am task move-to-front`, and setFocusedTask clears the
     * calling identity internally, which sidesteps that check entirely.
     */
    /**
     * Shrink every visible floating window into a strip at the bottom edge, remembering
     * each one's bounds, and return the shell command that does it (empty if none).
     *
     * `am task resize` is the only way to move a floating window - it is refused for
     * fullscreen tasks, which is fine because only freeform ones are parked. One command
     * for all of them, so they land together instead of one shell round trip apart.
     */
    private String parkCommand(List<TaskRow> rows) {
        if (rows == null) return "";
        int[] size = taskDisplaySize();
        int bottom = size[1];
        int top = bottom - dp(PARK_H);
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            TaskRow r = rows.get(i);
            if (r.fullscreen || !r.visible || r.bounds == null) continue;
            if (parkedBounds.containsKey(Integer.valueOf(r.id))) continue;
            parkedBounds.put(Integer.valueOf(r.id), r.bounds);
            if (cmd.length() > 0) cmd.append("; ");
            cmd.append("am task resize ").append(r.id)
                    .append(" 0 ").append(top)
                    .append(' ').append(size[0]).append(' ').append(bottom);
        }
        return cmd.toString();
    }

    /**
     * The size of the display the tasks live on, which is the space their bounds are in.
     *
     * Deliberately neither screenW/screenH (captured once at connect, and on this device
     * they go stale as soon as the phone is folded or unfolded) nor
     * getCurrentWindowMetrics() (which reports the COVER screen, 904x2316, for this
     * service even while the unfolded display is the one on screen). A park built on either
     * came out as `0 2001 904 2316` and put the window off the bottom of a 1812x2176
     * display - measured 2026-09-27. getDisplay() is not filtered the way getDisplays()
     * is, so asking for the tasks' own display id gives its real size.
     */
    private int[] taskDisplaySize() {
        try {
            Display d = displayManager.getDisplay(surfaceDisplayId);
            if (d != null) {
                DisplayMetrics m = new DisplayMetrics();
                d.getRealMetrics(m);
                if (m.widthPixels > 0 && m.heightPixels > 0) {
                    return new int[] { m.widthPixels, m.heightPixels };
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "task display size: " + t);
        }
        return new int[] { screenW, screenH };
    }

    /**
     * Activate a row: raise that window, and for the fullscreen app, get the floating
     * windows out of its way first.
     *
     * A fullscreen row cannot be raised: it lives in the root task BELOW the floating
     * windows, so focusing it only reorders it within its own root (measured - and the one
     * API that did reparent it, `am stack move-task`, throws inside system_server and took
     * the framework down). So the obstruction is moved instead of the window manager being
     * fought. Any other row is a floating window, which is raisable - and if we parked it,
     * put it back where it was first.
     *
     * Off the main thread, because every branch is at least one shell round trip and the
     * fullscreen one is three.
     */
    private void activateRow(final TaskRow r, final List<TaskRow> rows) {
        // The window is the point of the tap, so get the panel out of its way first
        setTasksVisible(false);
        if (!useShizuku()) { Log.w(TAG, "activate " + r.id + ": shizuku not ready"); return; }
        final List<TaskRow> snapshot = rows;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (r.fullscreen) clearTheDecks(snapshot, r.id);
                    else {
                        String back = unparkCommand(r.id);
                        shellRun((back.length() == 0 ? "" : back + "; ") + "am task focus " + r.id);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "activate " + r.id + ": " + t);
                }
            }
        }, "pi-task-act").start();
    }

    /**
     * Get the floating windows off the fullscreen app, then focus it.
     *
     * Minimize is tried first, because it leaves nothing behind: no strip on screen, no
     * bounds to remember, and the state belongs to Samsung's window manager, so it outlives
     * a restart of this service. It is a pixel tap on Samsung's chrome, though, so the
     * result is CHECKED - whatever is still visible afterwards gets parked instead, which is
     * a pure `am task resize` and always works. A header that moves, or a decor that differs,
     * therefore degrades into the uglier path instead of into a tap that silently did nothing
     * - or one that lands on the close button.
     */
    private void clearTheDecks(List<TaskRow> rows, int focusId) {
        if (rows != null) {
            List<TaskRow> targets = new ArrayList<TaskRow>();
            StringBuilder taps = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                TaskRow r = rows.get(i);
                if (r.fullscreen || !r.visible || r.bounds == null) continue;
                if (parkedBounds.containsKey(Integer.valueOf(r.id))) continue;
                if (taps.length() > 0) taps.append("; ");
                taps.append("input tap ").append(r.bounds[2] - dp(MINIMIZE_FROM_RIGHT))
                        .append(' ').append(r.bounds[1] + dp(MINIMIZE_FROM_TOP));
                targets.add(r);
            }
            if (targets.size() > 0) {
                // Our own overlays swallow a tap that lands on one, and a pop-up's header can
                // sit under the pad - the same click-through the pad's own taps use, held for
                // the whole sequence rather than one gesture.
                ui.post(new Runnable() {
                    @Override public void run() { setPanelsTouchable(false); }
                });
                sleep(TOUCHABLE_SETTLE_MS + 20);
                shellRun(taps.toString());
                sleep(MINIMIZE_SETTLE_MS);
                ui.post(new Runnable() {
                    @Override public void run() { setPanelsTouchable(true); }
                });

                List<TaskRow> now = parseWindows(shellRun(TASKS_CMD), surfaceDisplayId);
                List<TaskRow> stubborn = new ArrayList<TaskRow>();
                for (int i = 0; i < targets.size(); i++) {
                    TaskRow fresh = findRow(now, targets.get(i).id);
                    if (fresh != null && fresh.visible && !fresh.fullscreen) stubborn.add(fresh);
                }
                if (stubborn.size() > 0) {
                    Log.i(TAG, "minimize missed " + stubborn.size() + " window(s) - parking");
                    String park = parkCommand(stubborn);
                    if (park.length() > 0) shellRun(park);
                }
            }
        }
        shellRun("am task focus " + focusId);
    }

    /** One shell command through the bridge, logged, never throwing. */
    private String shellRun(String cmd) {
        try {
            String out = shizuku.run(cmd).trim();
            Log.i(TAG, "sh: " + cmd + " -> " + out);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "sh: " + cmd + " failed: " + t);
            return "";
        }
    }

    private static TaskRow findRow(List<TaskRow> rows, int id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id == id) return rows.get(i);
        }
        return null;
    }

    /** The command that puts a parked window back, or empty if it was never parked. */
    private String unparkCommand(int id) {
        int[] b = parkedBounds.remove(Integer.valueOf(id));
        if (b == null) return "";
        return "am task resize " + id + " " + b[0] + " " + b[1] + " " + b[2] + " " + b[3];
    }

    // =========================================================================
    // CONTROLS panel (the pad's gear dot)
    // =========================================================================

    /**
     * What the app shows, and how much keyboard: the panel behind the pad's gear dot.
     *
     * The pad's own dot deliberately has no row here. It is the only way to show the pad
     * (the pad has no close button) and it carries the docked dots that open this panel and
     * the theme menu, so hiding it would strand the way back to everything. The other two
     * dots are optional, and the way back once they are hidden is this panel - its own dot
     * cannot be hidden - or `op=bubbles keys=on,tasks=on`.
     *
     * The row wording is the one a TypeSafe (Jev) judgment picked between candidates, glyph
     * plus what the dot opens: "Keys bubble" scored 0.71 of 2 and "Show the \u25A4 dot" 0.72
     * (the glyph alone is not enough - 0.36 probability a user cannot tell what it does),
     * while "Show the \u25A4 windows dot" scored 1.60 and "Favorite shortcuts" was preferred
     * to "Favorites only" at 0.99. The same judgment put the keys-layout rows here rather
     * than in the keys panel's own header (0.68 against 0.31, a soft call).
     */
    private void buildControlsPanel() {
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
        handle.addView(makeChip("\u2699  CONTROLS"));
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - controlsPanelLp.x;
                        dy = e.getRawY() - controlsPanelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        controlsPanelLp.x = (int) (e.getRawX() - dx);
                        controlsPanelLp.y = (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(controlsPanel, controlsPanelLp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        saveGeometry(controlsPanelLp, CONTROLS_KEY);
                        return true;
                }
                return false;
            }
        });
        content.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        ScrollView scroll = new ScrollView(this);
        controlsRows = new LinearLayout(this);
        controlsRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(controlsRows, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GradientDrawable rbg = new GradientDrawable();
        rbg.setCornerRadius(dp(theme.radius));
        rbg.setColor(fill(theme.panelSolid));
        rbg.setStroke(dp(1.5f), theme.panelStroke);
        container.setBackground(rbg);

        controlsPanelLp = overlayLp(dp(300), dp(400),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        controlsPanel = container;

        addResizeGrips(container, controlsPanelLp, CONTROLS_KEY, true);

        restoreGeometry(controlsPanelLp, CONTROLS_KEY, dp(300), dp(400));
        try { wm.addView(controlsPanel, controlsPanelLp); }
        catch (Exception ex) { Log.e(TAG, "controlsPanel", ex); }
        setControlsVisible(false);
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (controlsPanel == null) return;
        if (visible) {
            refreshControlsRows();
            clampGeometryToScreen(controlsPanelLp);
            raise(controlsPanel, controlsPanelLp, "controlsPanel");
        }
        controlsPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void refreshControlsRows() {
        if (controlsRows == null) return;
        controlsRows.removeAllViews();
        final boolean favorites = "favorites".equals(keysMode);

        controlsRows.addView(sectionLabel("KEYS PANEL"));
        controlsRows.addView(controlRow("Full keyboard", !favorites, new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); setKeysMode("full"); }
        }));
        controlsRows.addView(controlRow("Favorite shortcuts", favorites, new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); setKeysMode("favorites"); }
        }));

        controlsRows.addView(sectionLabel("DOTS"));
        controlsRows.addView(controlRow("Show the \u2328 keys dot", showKeysBubble,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { tick(); setBubbleShown(true, !showKeysBubble); }
                }));
        controlsRows.addView(controlRow("Show the \u25A4 windows dot", showTasksBubble,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { tick(); setBubbleShown(false, !showTasksBubble); }
                }));

        controlsRows.addView(sectionLabel("SCROLLING"));
        controlsRows.addView(controlRow("Flick to scroll", flickScroll, new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); setFlickScroll(!flickScroll); }
        }));

        // no section label here: the arrow rows read as links, and it is the difference
        // between the panel fitting its content and clipping the last row
        controlsRows.addView(linkRow("How to customize the keys", getString(R.string.help_guide_url)));
        controlsRows.addView(linkRow("Problems and questions", getString(R.string.help_issues_url)));
    }

    /** A selectable row, in the same shape as the theme panel's presets. */
    private TextView controlRow(String label, boolean on, View.OnClickListener action) {
        TextView t = new TextView(this);
        t.setText((on ? "\u25C9  " : "\u25CB  ") + label);
        t.setTextSize(12f);
        t.setTextColor(on ? theme.textPrimary : theme.textSecondary);
        t.setPadding(dp(12), dp(11), dp(12), dp(11));
        t.setBackground(keyBgState(on ? theme.selectedRow : 0x00000000, theme.accent));
        t.setOnClickListener(action);
        return t;
    }

    /** A row that opens something outside the app, which is why it carries no on/off marker. */
    private TextView linkRow(String label, final String url) {
        TextView t = new TextView(this);
        t.setText("\u2197  " + label);
        t.setTextSize(12f);
        t.setTextColor(theme.rowAction);
        t.setPadding(dp(12), dp(11), dp(12), dp(11));
        t.setBackground(keyBgState(0x00000000, theme.accent));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); openUrl(url); }
        });
        return t;
    }

    /**
     * Open a URL from an overlay.
     *
     * A service has no activity to start one from, and a background activity launch is
     * exactly what the platform blocks - so it goes over the shell bridge, the same route the
     * virtual display's seed app takes. Without Shizuku the direct Intent is tried anyway: it
     * works whenever this app happens to be foreground.
     */
    private void openUrl(final String url) {
        Log.i(TAG, "open " + url);
        if (useShizuku()) {
            new Thread(new Runnable() {
                @Override public void run() {
                    shellRun("am start -a android.intent.action.VIEW -d " + url);
                }
            }, "pi-open-url").start();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "open " + url + ": " + t);
        }
    }

    private void setKeysMode(String mode) {
        keysMode = "favorites".equals(mode) ? "favorites" : "full";
        prefs.edit().putString(PREF_KEYS_MODE, keysMode).apply();
        // Rebuilds every panel, which is what the keys panel needs to change layout.
        // applyTheme restores this panel's visibility, so its rows survive being tapped.
        applyTheme();
        Log.i(TAG, "keys mode -> " + keysMode + " " + keySpecSummary());
    }

    /**
     * Show or hide one of the optional dots.
     *
     * One setter per change, each rebuilding, so a panel row and the `bubbles` op cannot
     * drift apart - the op just calls this once per dot it changes.
     */
    private void setBubbleShown(boolean keys, boolean on) {
        if (keys) {
            showKeysBubble = on;
            prefs.edit().putBoolean(PREF_SHOW_KEYS_BUBBLE, on).apply();
        } else {
            showTasksBubble = on;
            prefs.edit().putBoolean(PREF_SHOW_TASKS_BUBBLE, on).apply();
        }
        applyTheme();
        Log.i(TAG, "bubbles -> keys=" + showKeysBubble + " windows=" + showTasksBubble);
    }

    /**
     * The edge strips' scroll behaviour: live as the finger drags (default), or banked
     * and spent as a single flick on release (Lite's feel). One setter, shared by the
     * CONTROLS row and the `flick` op, so a finger and a script cannot disagree.
     */
    private void setFlickScroll(boolean on) {
        flickScroll = on;
        prefs.edit().putBoolean(PREF_FLICK_SCROLL, on).apply();
        refreshControlsRows();
        Log.i(TAG, "flick to scroll -> " + (on ? "on" : "off"));
    }

    /**
     * The spec the keys panel is built from.
     *
     * Favorites with nothing saved yet falls back to the built-in keyboard rather than
     * showing an empty panel.
     */
    private String activeKeysSpec() {
        if (!"favorites".equals(keysMode)) return DEFAULT_KEYS_SPEC;
        return (keysSpec == null || keysSpec.trim().length() == 0) ? DEFAULT_KEYS_SPEC : keysSpec;
    }

    private static String onOff(boolean b) { return b ? "on" : "off"; }
}
