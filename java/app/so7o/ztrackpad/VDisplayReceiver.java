package app.so7o.ztrackpad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Lets the virtual display be driven from a script instead of by tapping the picker:
 *
 *   adb shell am broadcast -a app.so7o.ztrackpad.VDISPLAY --es op show
 *   adb shell am broadcast -a app.so7o.ztrackpad.VDISPLAY --es op create
 *   adb shell am broadcast -a app.so7o.ztrackpad.VDISPLAY --es op create --ez headless true
 *   adb shell am broadcast -a app.so7o.ztrackpad.VDISPLAY --es op status
 *   adb shell am broadcast -a app.so7o.ztrackpad.VDISPLAY --es op lock --es arg on
 *
 * `arg` carries an op's argument (op=lock takes on|off|toggle; op=keys takes its layout
 * in `spec`).
 *
 * All the real work belongs to the running accessibility service, since it owns the
 * overlay windows and the Shizuku binding, so this is just a thin entry point. If the
 * service is not connected the broadcast fails with a clear message rather than
 * appearing to succeed.
 *
 * SECURITY: exported with no permission, so any app on the device can toggle the
 * display. Calling it destructive would be a stretch - the worst case is a display
 * appearing or disappearing - and gating it would make the adb one-liner above
 * awkward, but it is not a private channel.
 */
public class VDisplayReceiver extends BroadcastReceiver {

    public static final String ACTION = "app.so7o.ztrackpad.VDISPLAY";

    /** Set by TrackpadService while it is connected, cleared when it goes away. */
    static volatile TrackpadService service;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;

        String op = intent.getStringExtra("op");
        boolean headless = intent.getBooleanExtra("headless", false);
        // a custom keys-panel layout, for op=keys
        String spec = intent.getStringExtra("spec");
        // an op's argument, for op=lock (on|off|toggle)
        String arg = intent.getStringExtra("arg");

        // onReceive for a manifest receiver runs on the main thread, which is required:
        // vdisplayCommand adds and removes windows.
        TrackpadService s = service;
        if (s == null) {
            setResultCode(1);
            setResultData("error: accessibility service not connected");
            return;
        }

        String out;
        try {
            out = s.vdisplayCommand(op, headless, spec, arg);
        } catch (Throwable t) {
            out = "error: " + t;
        }
        setResultCode(out.startsWith("error") ? 1 : 0);
        setResultData(out);
    }
}
