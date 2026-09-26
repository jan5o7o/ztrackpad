package app.so7o.ztrackpad;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Minimal launcher screen: status + a shortcut to the accessibility settings. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);
        root.setBackgroundColor(Color.parseColor("#101014"));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);

        TextView body = new TextView(this);
        body.setText("\nFloating trackpad, pointer and on-screen keys.\n\n"
                + "1. Tap the button below.\n"
                + "2. Enable \"" + getString(R.string.app_name) + "\" under Installed services.\n"
                + "3. Come back here - two small dots appear on screen.\n\n"
                + "The dots\n"
                + "  \u25CF  trackpad (right edge by default) - tap to show or hide\n"
                + "  \u2328  keys panel (left edge by default) - tap to show or hide\n"
                + "  drag either one and it snaps to the nearer edge\n\n"
                + "The trackpad\n"
                + "  \u2261 MOVE  drag bar at the top - move the pad, tap it to re-centre\n"
                + "  \u25D0         theme and opacity\n"
                + "  lock        freeze the pad's position and size\n"
                + "  \u25A3         display picker - drive another screen, or open a virtual one\n"
                + "  \u2191 \u2193         on the left edge - nudge the split-screen divider\n"
                + "  corners     drag any corner to resize\n\n"
                + "Gestures\n"
                + "  \u2022 drag one finger - move the pointer\n"
                + "  \u2022 tap - click at the pointer, even under the pad\n"
                + "  \u2022 hold still - long press\n"
                + "  \u2022 two-finger drag - scroll\n"
                + "  \u2022 swipe along the left or right edge - scroll\n"
                + "  \u2022 \u2934 - arm press-and-drag, tap again to disarm\n\n"
                + "Without Shizuku the pads still appear, but keys and drags do nothing.\n");
        body.setTextColor(0xFFDDDDDD);
        body.setTextSize(14f);

        Button go = new Button(this);
        go.setText("Open Accessibility Settings");
        go.setGravity(Gravity.CENTER);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        });

        root.addView(title);
        root.addView(body);
        root.addView(go);
        setContentView(root);
    }
}
