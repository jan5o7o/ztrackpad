package app.so7o.ztrackpad;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;

import rikka.shizuku.Shizuku;

/**
 * Client side of the Shizuku shell bridge: requests permission, binds the user
 * service running as shell, and routes input injection through it.
 *
 * All calls are synchronous Binder transactions so DOWN/MOVE/UP stay ordered -
 * that ordering is what makes a drag work.
 */
public class ShizukuInputHandler {

    private static final String TAG = "ZShizuku";
    private static final int PERM_CODE = 4213;

    public interface StateListener {
        void onState(String text, boolean ready);
    }

    private final Context ctx;
    private final StateListener listener;
    private IShellService shell;
    private Shizuku.UserServiceArgs args;
    private boolean bound = false;
    private String state = "not started";

    /**
     * Which display the injected events are addressed to. DEFAULT_DISPLAY is the
     * normal phone screen; any other id sends the mouse to a second screen (DeX,
     * HDMI, a virtual/overlay display) while the panels stay where they are.
     */
    private int displayId = Display.DEFAULT_DISPLAY;

    private static final long REBIND_DELAY_MS = 1500L;
    private static final int MAX_REBIND_ATTEMPTS = 10;

    /** Main-thread handler for the rebind backoff and for the Shizuku listeners below. */
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Set by stop(), so a pending rebind cannot resurrect a service we tore down. */
    private boolean stopped = false;
    private int rebindAttempts = 0;
    private boolean binderListenersAdded = false;

    private final Runnable rebindTask = new Runnable() {
        @Override public void run() { bind(); }
    };

    /** Shizuku itself went away - after a reboot, or adb restarting it. */
    private final Shizuku.OnBinderDeadListener binderDeadListener =
            new Shizuku.OnBinderDeadListener() {
        @Override public void onBinderDead() {
            shell = null;
            bound = false;
            setState("Shizuku binder died", false);
        }
    };

    /** Shizuku came back. Deliberately the non-sticky variant, so this only fires for a
     *  NEW binder and cannot loop with start(). */
    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            new Shizuku.OnBinderReceivedListener() {
        @Override public void onBinderReceived() {
            setState("Shizuku binder received", false);
            rebindAttempts = 0;
            stopped = false;
            start();
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            shell = IShellService.Stub.asInterface(binder);
            bound = true;
            rebindAttempts = 0;
            setState("shell service bound", true);
            // Tell the shell side which process to outlive. Without this, a hard kill or an
            // app update leaks the shell process, and with it any virtual display it holds.
            try { shell.registerClient(android.os.Process.myPid()); }
            catch (Throwable t) { Log.w(TAG, "registerClient: " + t); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            shell = null;
            bound = false;
            setState("shell service disconnected", false);
            scheduleRebind();
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
        @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode != PERM_CODE) return;
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "permission granted");
                bind();
            } else {
                setState("permission DENIED", false);
            }
        }
    };

    public ShizukuInputHandler(Context ctx, StateListener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    private void setState(String s, boolean ready) {
        state = s;
        Log.i(TAG, s);
        if (listener != null) listener.onState(s, ready);
    }

    public String getState() { return state; }

    public boolean isReady() { return bound && shell != null; }

    /** Retarget every subsequent injection. Cheap - no rebind needed. */
    public void setDisplayId(int id) { this.displayId = id; }

    public int getDisplayId() { return displayId; }

    public void start() {
        try {
            stopped = false;
            if (!binderListenersAdded) {
                binderListenersAdded = true;
                // with the handler, so setState() and its UI work land on the main thread
                Shizuku.addBinderDeadListener(binderDeadListener, ui);
                Shizuku.addBinderReceivedListener(binderReceivedListener, ui);
            }
            if (!Shizuku.pingBinder()) {
                setState("Shizuku not running", false);
                return;
            }
            Log.i(TAG, "Shizuku version=" + Shizuku.getVersion());
            if (Shizuku.isPreV11()) {
                setState("Shizuku pre-v11, unsupported", false);
                return;
            }
            Shizuku.addRequestPermissionResultListener(permListener);

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bind();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                setState("permission denied previously - grant in Shizuku app", false);
            } else {
                setState("requesting permission", false);
                Shizuku.requestPermission(PERM_CODE);
            }
        } catch (Throwable t) {
            setState("start failed: " + t, false);
        }
    }

    private void bind() {
        if (stopped) return;
        try {
            args = new Shizuku.UserServiceArgs(
                    new ComponentName(ctx.getPackageName(), ShellUserService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("shell")
                    .debuggable(false)
                    .version(1);
            Shizuku.bindUserService(args, conn);
            setState("binding shell service", false);
        } catch (Throwable t) {
            setState("bind failed: " + t, false);
        }
    }

    /**
     * Retry with a growing delay, bounded so a permanently broken Shizuku cannot spin.
     *
     * A Shizuku user service can die on its own: the client-death watchdog kills it when a
     * stale client goes away, an app update replaces it, and Android may reclaim the
     * process. Nothing reconnects it automatically, so without this the app stays quietly
     * degraded - keys dropped, drag dead - until the accessibility service is restarted.
     */
    private void scheduleRebind() {
        if (stopped) return;
        if (rebindAttempts >= MAX_REBIND_ATTEMPTS) {
            setState("rebind gave up after " + rebindAttempts, false);
            return;
        }
        rebindAttempts++;
        ui.removeCallbacks(rebindTask);
        ui.postDelayed(rebindTask, REBIND_DELAY_MS * rebindAttempts);
        setState("rebinding shell service (attempt " + rebindAttempts + ")", false);
    }

    public void stop() {
        stopped = true;
        ui.removeCallbacks(rebindTask);
        // Hand the pointer back before the shell goes away. Otherwise setPointerIconType(0)
        // is left in force with nothing drawing an arrow, and there is no pointer at all.
        hideSystemCursor(false);
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
            if (binderListenersAdded) {
                binderListenersAdded = false;
                Shizuku.removeBinderDeadListener(binderDeadListener);
                Shizuku.removeBinderReceivedListener(binderReceivedListener);
            }
            if (args != null) Shizuku.unbindUserService(args, conn, true);
        } catch (Throwable ignored) {}
        shell = null;
        bound = false;
    }

    // =========================================================================
    // Input
    // =========================================================================

    public void hideSystemCursor(boolean hide) {
        if (!isReady()) return;
        try { shell.setSystemCursorVisibility(!hide); } catch (Throwable t) { Log.w(TAG, "cursor: " + t); }
    }

    /** action = MotionEvent.ACTION_*; buttonState = MotionEvent.BUTTON_*. */
    public void mouse(int action, float x, float y, int buttonState, long downTime) {
        if (!isReady()) return;
        try {
            shell.injectMouse(action, x, y, displayId, InputDevice.SOURCE_MOUSE, buttonState, downTime);
        } catch (Throwable t) { Log.w(TAG, "mouse: " + t); }
    }

    public void mouseDown(float x, float y, long downTime) {
        mouse(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, downTime);
    }

    public void mouseMove(float x, float y, long downTime) {
        mouse(MotionEvent.ACTION_MOVE, x, y, MotionEvent.BUTTON_PRIMARY, downTime);
    }

    public void mouseUp(float x, float y, long downTime) {
        mouse(MotionEvent.ACTION_UP, x, y, 0, downTime);
    }

    public void hover(float x, float y) {
        mouse(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0L);
    }

    /**
     * Touchscreen DOWN/MOVE/UP, unlike the mouse drags above.
     *
     * Some windows ignore a mouse drag - the split divider is one: it is moved with a
     * finger. Keep one downTime across the whole gesture, or the system reads each MOVE as
     * the start of a new touch and nothing drags.
     */
    public void touch(int action, float x, float y, long downTime) {
        if (!isReady()) return;
        try {
            shell.injectMouse(action, x, y, displayId,
                    InputDevice.SOURCE_TOUCHSCREEN, 0, downTime);
        } catch (Throwable t) { Log.w(TAG, "touch: " + t); }
    }

    /** Touch-style long press: DOWN now, UP after ms (non-blocking). */
    public void press(final float x, final float y, long ms) {
        if (!isReady()) return;
        final long t = SystemClock.uptimeMillis();
        try {
            shell.injectMouse(MotionEvent.ACTION_DOWN, x, y, displayId,
                    InputDevice.SOURCE_TOUCHSCREEN, 0, t);
        } catch (Throwable e) {
            Log.w(TAG, "press down: " + e);
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    shell.injectMouse(MotionEvent.ACTION_UP, x, y, displayId,
                            InputDevice.SOURCE_TOUCHSCREEN, 0, t);
                } catch (Throwable e) {
                    Log.w(TAG, "press up: " + e);
                }
            }
        }, ms);
    }

    public void scroll(float x, float y, float v, float h) {
        if (!isReady()) return;
        try { shell.injectScroll(x, y, v, h, displayId); } catch (Throwable t) { Log.w(TAG, "scroll: " + t); }
    }

    public void click(float x, float y) {
        if (!isReady()) return;
        try { shell.execClick(x, y, displayId); } catch (Throwable t) { Log.w(TAG, "click: " + t); }
    }

    public void rightClick(float x, float y) {
        if (!isReady()) return;
        try { shell.execRightClick(x, y, displayId); } catch (Throwable t) { Log.w(TAG, "rclick: " + t); }
    }

    public void key(int keyCode, int action, int metaState) {
        if (!isReady()) return;
        try { shell.injectKey(keyCode, action, metaState, displayId, 0); } catch (Throwable t) { Log.w(TAG, "key: " + t); }
    }

    public String run(String cmd) {
        if (!isReady()) return "not ready";
        try { return shell.runCommand(cmd); } catch (Throwable t) { return "ERR " + t; }
    }

    /** Shell-side virtual display: needs shell's CAPTURE_VIDEO_OUTPUT. -1 on failure. */
    public int createVirtualDisplay(String name, int w, int h, int dpi) {
        if (!isReady()) return -1;
        try { return shell.createVirtualDisplay(name, w, h, dpi); }
        catch (Throwable t) { Log.w(TAG, "createVirtualDisplay: " + t); return -1; }
    }

    /**
     * Non-headless variant: the display renders into a Surface we own (a SurfaceView in
     * a floating window), so it comes up ON and will accept injected input.
     */
    public int createVirtualDisplay(String name, int w, int h, int dpi, Surface surface) {
        if (!isReady()) return -1;
        try { return shell.createVirtualDisplayWithSurface(name, w, h, dpi, surface); }
        catch (Throwable t) { Log.w(TAG, "createVirtualDisplay(surface): " + t); return -1; }
    }

    public void releaseVirtualDisplay() {
        if (!isReady()) return;
        try { shell.releaseVirtualDisplay(); }
        catch (Throwable t) { Log.w(TAG, "releaseVirtualDisplay: " + t); }
    }

    /** Match the display to a new surface size after the floating window is resized. */
    public void resizeVirtualDisplay(int w, int h, int dpi) {
        if (!isReady()) return;
        try { shell.resizeVirtualDisplay(w, h, dpi); }
        catch (Throwable t) { Log.w(TAG, "resizeVirtualDisplay: " + t); }
    }

    /** Re-attach a new surface to the existing display (after the window was hidden). */
    public void setVirtualDisplaySurface(Surface s) {
        if (!isReady()) return;
        try { shell.setVirtualDisplaySurface(s); }
        catch (Throwable t) { Log.w(TAG, "setVirtualDisplaySurface: " + t); }
    }
}
