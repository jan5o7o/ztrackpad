package app.so7o.ztrackpad;

interface IShellService {
    void injectMouse(int action, float x, float y, int displayId, int source, int buttonState, long downTime);
    void injectScroll(float x, float y, float vDistance, float hDistance, int displayId);
    void injectKey(int keyCode, int action, int metaState, int displayId, int deviceId);
    void execClick(float x, float y, int displayId);
    void execRightClick(float x, float y, int displayId);
    void setSystemCursorVisibility(boolean visible);
    String runCommand(String cmd);

    // Virtual display creation must happen with shell UID: creating a PUBLIC,
    // task-hosting display needs CAPTURE_VIDEO_OUTPUT / ADD_TRUSTED_DISPLAY, which
    // shell holds and a normal app does not. Returns the display id, or -1.
    int createVirtualDisplay(String name, int w, int h, int dpi);
    // Same, but rendering into a Surface the app owns (a SurfaceView in a floating
    // window). That makes the display non-headless: it comes up ON, its windows are
    // visible, and injected input lands.
    int createVirtualDisplayWithSurface(String name, int w, int h, int dpi, in android.view.Surface surface);
    // The surface changes size when the floating window is resized; the display has to
    // follow it or the content would be stretched.
    void resizeVirtualDisplay(int w, int h, int dpi);
    // Hiding the window destroys its surface. Re-attaching the new one keeps the display
    // (and the apps on it) alive instead of tearing everything down.
    void setVirtualDisplaySurface(in android.view.Surface surface);
    void releaseVirtualDisplay();

    // Called once by the client right after binding. The shell service then stays alive
    // only as long as that process does. Without this, every app restart leaks another
    // shell process - and each of those holds a virtual display that can never be
    // released, which orphans it or (with two) makes the floating window mirror itself.
    void registerClient(int pid);
}
