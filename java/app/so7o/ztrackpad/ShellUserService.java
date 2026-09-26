package app.so7o.ztrackpad;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Runs inside Shizuku's process with shell UID. That is the whole point: shell has
 * INJECT_EVENTS, so we can inject real MotionEvents via InputManager directly.
 *
 * This bypasses AccessibilityService.dispatchGesture() - and therefore bypasses the
 * "only one input stream at a time" arbitration that makes a finger-driven drag
 * impossible with accessibility gestures.
 *
 * Approach modelled on DroidOS's ShellUserService (source-available).
 */
public class ShellUserService extends IShellService.Stub {

    private static final String TAG = "ZShell";
    private static final int INJECT_MODE_ASYNC = 0;

    private Object inputManager;
    private Method injectInputEvent;
    private Method setDisplayId;
    private boolean reflectionOk = false;
    private boolean cursorHidden = false;

    public ShellUserService() {
        try {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            inputManager = im.getMethod("getInstance").invoke(null);
            injectInputEvent = im.getMethod("injectInputEvent",
                    InputEvent.class, int.class);
            setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
            reflectionOk = (inputManager != null);
        } catch (Throwable t) {
            log("reflection setup failed: " + t);
        }
        log("constructed, reflectionOk=" + reflectionOk);
    }

    private static void log(String m) {
        android.util.Log.i(TAG, m);
    }

    /**
     * Hide the system pointer so only our own drawn arrow shows. This build has no
     * setCursorVisibility() - the working lever is setPointerIconType, where
     * PointerIcon.TYPE_NULL (0) means "no icon" and TYPE_ARROW is 1.
     */
    @Override
    public void setSystemCursorVisibility(boolean visible) {
        if (!reflectionOk) return;
        long tok = Binder.clearCallingIdentity();
        try {
            java.lang.reflect.Method m =
                    inputManager.getClass().getMethod("setPointerIconType", int.class);
            m.invoke(inputManager, visible ? 1 : 0);
            log("setPointerIconType(" + (visible ? 1 : 0) + ") ok");
        } catch (Throwable t) {
            log("setPointerIconType failed: " + t);
        } finally {
            Binder.restoreCallingIdentity(tok);
        }
    }

    @Override
    public void injectMouse(int action, float x, float y, int displayId,
                            int source, int buttonState, long downTime) {
        inject(action, x, y, displayId, source, buttonState,
                downTime, SystemClock.uptimeMillis());
    }

    @Override
    public void injectScroll(float x, float y, float vDistance, float hDistance, int displayId) {
        if (!reflectionOk) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = 1.0f;
        c.size = 1.0f;
        c.setAxisValue(MotionEvent.AXIS_VSCROLL, vDistance);
        c.setAxisValue(MotionEvent.AXIS_HSCROLL, hDistance);
        try {
            MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                    new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            setDisplayId.invoke(ev, displayId);
            injectInputEvent.invoke(inputManager, ev, INJECT_MODE_ASYNC);
            ev.recycle();
        } catch (Throwable t) {
            log("injectScroll failed: " + t);
        }
    }

    @Override
    public void injectKey(int keyCode, int action, int metaState, int displayId, int deviceId) {
        if (!reflectionOk) return;
        long now = SystemClock.uptimeMillis();
        try {
            KeyEvent ev = new KeyEvent(now, now, action, keyCode, 0, metaState,
                    deviceId, 0, 8 /* FLAG_FROM_SYSTEM */, InputDevice.SOURCE_KEYBOARD);
            setDisplayId.invoke(ev, displayId);
            injectInputEvent.invoke(inputManager, ev, INJECT_MODE_ASYNC);
        } catch (Throwable t) {
            log("injectKey failed: " + t);
        }
    }

    /** Real finger-like tap: DroidOS notes SOURCE_TOUCHSCREEN is more reliable than mouse for UI. */
    @Override
    public void execClick(float x, float y, int displayId) {
        long now = SystemClock.uptimeMillis();
        inject(MotionEvent.ACTION_DOWN, x, y, displayId,
                InputDevice.SOURCE_TOUCHSCREEN, 0, now, now);
        sleep(60);
        inject(MotionEvent.ACTION_UP, x, y, displayId,
                InputDevice.SOURCE_TOUCHSCREEN, 0, now, now + 60);
    }

    /** Android has no right-click for touch, so this one must be a real mouse button. */
    @Override
    public void execRightClick(float x, float y, int displayId) {
        long now = SystemClock.uptimeMillis();
        inject(MotionEvent.ACTION_DOWN, x, y, displayId,
                InputDevice.SOURCE_MOUSE, MotionEvent.BUTTON_SECONDARY, now, now);
        sleep(60);
        inject(MotionEvent.ACTION_UP, x, y, displayId,
                InputDevice.SOURCE_MOUSE, 0, now, now + 60);
    }

    private void inject(int action, float x, float y, int displayId, int source,
                        int buttonState, long downTime, long eventTime) {
        if (!reflectionOk) return;
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = (source == InputDevice.SOURCE_MOUSE)
                ? MotionEvent.TOOL_TYPE_MOUSE : MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = (buttonState != 0 || action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_MOVE) ? 1.0f : 0.0f;
        c.size = 1.0f;
        try {
            MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, 1,
                    new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
                    0, buttonState, 1f, 1f, 0, 0, source, 0);
            setDisplayId.invoke(ev, displayId);
            injectInputEvent.invoke(inputManager, ev, INJECT_MODE_ASYNC);
            ev.recycle();
        } catch (Throwable t) {
            log("inject failed: " + t);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // =========================================================================
    // Virtual display
    //
    // Creating a PUBLIC, task-hosting display needs CAPTURE_VIDEO_OUTPUT or
    // ADD_TRUSTED_DISPLAY. A normal app calling DisplayManager.createVirtualDisplay
    // without OWN_CONTENT_ONLY is rejected with SecurityException ("...screen sharing
    // virtual display..."), and OWN_CONTENT_ONLY would defeat the purpose - such a
    // display can only ever show the owning app's own content, so it could not host
    // the apps we want to control. Shell holds both permissions (verified via
    // `dumpsys package com.android.shell`), so the display is created from here.
    // =========================================================================

    /** Static so the display is not released when the local reference is collected. */
    private static VirtualDisplay vdHolder;

    @Override
    public int createVirtualDisplay(String name, int w, int h, int dpi) {
        return createVd(name, w, h, dpi, null);
    }

    @Override
    public int createVirtualDisplayWithSurface(String name, int w, int h, int dpi, Surface surface) {
        return createVd(name, w, h, dpi, surface);
    }

    /**
     * surface == null  -> a headless display: it hosts tasks but has no render target,
     *                     so it comes up OFF and input does not land.
     * surface != null  -> the display renders into that Surface, so it is ON and
     *                     controllable. That is the screen-sharing shape, which is why
     *                     it needs shell's CAPTURE_VIDEO_OUTPUT.
     */
    private int createVd(String name, int w, int h, int dpi, Surface surface) {
        long tok = Binder.clearCallingIdentity();
        try {
            Context ctx = displayContext();
            if (ctx == null) { log("no system context"); return -1; }
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) { log("no DisplayManager"); return -1; }
            releaseVirtualDisplay();
            VirtualDisplay vd = dm.createVirtualDisplay(name, w, h, dpi, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            vdHolder = vd;
            Display d = vd.getDisplay();
            log("virtual display created: "
                    + (d == null ? "<none>" : String.valueOf(d.getDisplayId()))
                    + (surface == null ? " (headless)" : " (surface-backed)"));
            return (d == null) ? -1 : d.getDisplayId();
        } catch (Throwable t) {
            log("createVirtualDisplay failed: " + t);
            return -1;
        } finally {
            Binder.restoreCallingIdentity(tok);
        }
    }

    @Override
    public void resizeVirtualDisplay(int w, int h, int dpi) {
        VirtualDisplay vd = vdHolder;
        if (vd == null) { log("resize: no display"); return; }
        if (w <= 0 || h <= 0) { log("resize: bad size " + w + "x" + h); return; }
        try {
            vd.resize(w, h, dpi);
            log("virtual display resized to " + w + "x" + h);
        } catch (Throwable t) {
            log("virtual display resize failed: " + t);
        }
    }

    @Override
    public void setVirtualDisplaySurface(Surface surface) {
        VirtualDisplay vd = vdHolder;
        if (vd == null) { log("setSurface: no display"); return; }
        try {
            vd.setSurface(surface);
            log("virtual display surface re-attached");
        } catch (Throwable t) {
            log("setSurface failed: " + t);
        }
    }

    @Override
    public void releaseVirtualDisplay() {
        VirtualDisplay h = vdHolder;
        vdHolder = null;
        if (h == null) return;
        try {
            h.release();
            log("virtual display released");
        } catch (Throwable t) {
            log("virtual display release failed: " + t);
        }
    }

    /**
     * A Context whose package name matches our calling uid.
     *
     * DisplayManagerService rejects createVirtualDisplay with "packageName must match
     * the calling uid" when the context belongs to a different package. The system
     * context is package "android" (uid 1000), but we call as shell (uid 2000), so ask
     * for the shell package's own context instead - its uid is 2000.
     */
    private static Context displayContext() {
        Context sys = systemContext();
        if (sys == null) return null;
        try {
            Context c = sys.createPackageContext("com.android.shell", 0);
            if (c != null) {
                log("display context: com.android.shell");
                return c;
            }
        } catch (Throwable t) {
            log("createPackageContext(com.android.shell) failed: " + t);
        }
        log("display context: falling back to system context");
        return sys;
    }

    /**
     * A Context for a shell/app_process process - there is no Activity here.
     *
     * Prefer an existing ActivityThread: Shizuku's own process already has one, and
     * calling systemMain() a second time throws. Fall back to systemMain(), then to
     * the initial Application object as a last resort.
     */
    private static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method current = at.getMethod("currentActivityThread");
            java.lang.reflect.Method getCtx = at.getMethod("getSystemContext");

            Object thread = current.invoke(null);
            if (thread != null) {
                Context c = (Context) getCtx.invoke(thread);
                if (c != null) { log("context: currentActivityThread"); return c; }
            }

            try {
                thread = at.getMethod("systemMain").invoke(null);
                Context c = (Context) getCtx.invoke(thread);
                if (c != null) { log("context: systemMain"); return c; }
            } catch (Throwable t) {
                Throwable cause = (t.getCause() == null) ? t : t.getCause();
                log("systemMain failed: " + cause);
            }

            Context app = (Context) Class.forName("android.app.AppGlobals")
                    .getMethod("getInitialApplication").invoke(null);
            if (app != null) { log("context: AppGlobals"); return app; }
        } catch (Throwable t) {
            log("systemContext failed: " + t);
        }
        return null;
    }

    // =========================================================================
    // Client watchdog
    //
    // This process is spawned fresh on every app bind (daemon=false, process name
    // suffix "shell") and Shizuku only reaps it if the app unbinds cleanly. A hard kill
    // or an app update skips that, so processes accumulate - and each one holds its own
    // static vdHolder, meaning its virtual display is leaked too. A leaked display shows
    // up as an orphan (running but invisible) or, when two survive, as the floating
    // window mirroring itself. So: watch the client and die with it.
    // =========================================================================

    private volatile int clientPid = 0;
    private volatile boolean watchdogStarted = false;

    @Override
    public void registerClient(int pid) {
        if (pid <= 0) return;
        clientPid = pid;
        if (watchdogStarted) return;
        watchdogStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                log("watchdog: watching client pid " + clientPid);
                while (true) {
                    try { Thread.sleep(2000L); }
                    catch (InterruptedException e) { return; }
                    int p = clientPid;
                    if (p <= 0) continue;
                    if (!new File("/proc/" + p).exists()) {
                        log("watchdog: client " + p + " is gone - releasing display and exiting");
                        try { releaseVirtualDisplay(); } catch (Throwable ignored) {}
                        try { android.os.Process.killProcess(android.os.Process.myPid()); }
                        catch (Throwable ignored) {}
                        System.exit(0);
                        return;
                    }
                }
            }
        }, "pi-client-watchdog");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public String runCommand(String cmd) {
        long tok = Binder.clearCallingIdentity();
        StringBuilder out = new StringBuilder();
        try {
            Process pr = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
            r.close();
            pr.waitFor();
        } catch (Throwable t) {
            out.append("ERR: ").append(t);
        } finally {
            Binder.restoreCallingIdentity(tok);
        }
        return out.toString();
    }
}
