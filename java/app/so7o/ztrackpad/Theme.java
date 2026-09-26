package app.so7o.ztrackpad;

/**
 * A named visual preset for the overlays.
 *
 * Values live in code rather than in res/values so a preset can be switched at runtime:
 * the build -> install -> bounce-the-service loop is roughly 40 seconds, which is far
 * too slow to try a colour out. Resources would also give automatic day/night; that is
 * a possible later addition, with a preset still able to override it.
 *
 * Field names describe roles, not positions, because the same literal is used for
 * different things in different panels - 0x66FFFFFF is a panel border in one place and
 * dimmed text in another, and a light preset has to move those in opposite directions.
 *
 * DEFAULT reproduces the original hardcoded values exactly, so selecting it changes
 * nothing.
 */
final class Theme {

    final String id;
    final String label;

    /** The preset used when nothing is saved. */
    static final String DEFAULT_ID = "default";

    // text
    int textPrimary;      // key labels, picker row name
    int textSecondary;    // chips, panel headers
    int textDim;          // picker rows that cannot be used
    int textFaint;        // "no displays"
    int footerText;       // the amber create/destroy rows
    int rowAction;        // show / hide button on a display row
    int modTextOn;        // label on an active modifier key

    // accents
    int accent;           // pressed keys, default key fill-out
    int selectedRow;      // picker row for the current target
    int modOnBg;          // active modifier key fill
    int modOnStroke;

    // bubbles
    int bubbleFill;
    int bubbleStroke;
    int bubbleTrack;      // the dot that toggles the pad
    int bubbleKeys;
    int bubbleDisplay;
    int bubbleTheme;      // the dot that opens the theme menu

    // panels
    int panelSolid;       // keys / picker / floating-window background
    int panelStroke;      // their border
    int panelHead;        // drag handles
    int panelBody;        // the pad's touch surface
    int panelBar;         // the pad's button row
    int panelKeys;        // the pad's arrow row
    int padContainer;     // the pad's outer fill
    int padStroke;
    int screenHead;       // floating-display title bar
    int grip;             // resize grips

    // keys
    int keyBg;            // resting key fill
    int keyStroke;
    int keyStrokePressed;

    // cursor
    int cursor;
    int cursorEdge;
    int cursorHot;

    // geometry
    int radius;           // panel corner radius, in dp

    private Theme(String id, String label) {
        this.id = id;
        this.label = label;
    }

    private static final Theme DEFAULT = new Theme("default", "Default");
    private static final Theme DARK = new Theme("dark", "Dark");
    private static final Theme LIGHT = new Theme("light", "Light");
    private static final Theme CONTRAST = new Theme("contrast", "High contrast");
    private static final Theme GLASS = new Theme("glass", "Glass");

    static final Theme[] PRESETS = { DEFAULT, DARK, LIGHT, CONTRAST, GLASS };

    static {
        // ---- Default: the original hardcoded values, byte for byte ----------------
        DEFAULT.textPrimary = 0xFFFFFFFF;
        DEFAULT.textSecondary = 0xCCFFFFFF;
        DEFAULT.textDim = 0x66FFFFFF;
        DEFAULT.textFaint = 0x88FFFFFF;
        DEFAULT.footerText = 0xFFFFD54F;
        DEFAULT.rowAction = 0xFF80D8FF;
        DEFAULT.modTextOn = 0xFF101014;
        DEFAULT.accent = 0xFF2A6FB0;
        DEFAULT.selectedRow = 0xFF1E5AA8;
        DEFAULT.modOnBg = 0xFF3DDC84;
        DEFAULT.modOnStroke = 0xFF2FA86A;
        DEFAULT.bubbleFill = 0x33FFFFFF;
        DEFAULT.bubbleStroke = 0x99FFFFFF;
        DEFAULT.bubbleTrack = 0xFF0A84FF;
        DEFAULT.bubbleKeys = 0xFF3DDC84;
        DEFAULT.bubbleDisplay = 0xFFFFB300;
        DEFAULT.bubbleTheme = 0xFFB388FF;
        DEFAULT.panelSolid = 0xF2000000;
        DEFAULT.panelStroke = 0x66FFFFFF;
        DEFAULT.panelHead = 0x33FFFFFF;
        DEFAULT.panelBody = 0x1AFFFFFF;
        DEFAULT.panelBar = 0x33FFFFFF;
        DEFAULT.panelKeys = 0x22FFFFFF;
        DEFAULT.padContainer = 0x66000000;
        DEFAULT.padStroke = 0x66FFFFFF;
        DEFAULT.screenHead = 0x66000000;
        DEFAULT.grip = 0x77FFFFFF;
        DEFAULT.keyBg = 0x22FFFFFF;
        DEFAULT.keyStroke = 0x55FFFFFF;
        DEFAULT.keyStrokePressed = 0xCCFFFFFF;
        DEFAULT.cursor = 0xFFFFFFFF;
        DEFAULT.cursorEdge = 0xFF000000;
        DEFAULT.cursorHot = 0xFFFFA000;
        DEFAULT.radius = 18;

        // ---- Dark: opaque, so the pads stay readable over a bright app ------------
        DARK.textPrimary = 0xFFFFFFFF;
        DARK.textSecondary = 0xD9FFFFFF;
        DARK.textDim = 0x80FFFFFF;
        DARK.textFaint = 0x99FFFFFF;
        DARK.footerText = 0xFFFFD54F;
        DARK.rowAction = 0xFF7FD4FF;
        DARK.modTextOn = 0xFF06121A;
        DARK.accent = 0xFF3D7EFF;
        DARK.selectedRow = 0xFF1B4F9E;
        DARK.modOnBg = 0xFF35C77A;
        DARK.modOnStroke = 0xFF1E9C59;
        DARK.bubbleFill = 0xE61C1C22;
        DARK.bubbleStroke = 0x66FFFFFF;
        DARK.bubbleTrack = 0xFF3D7EFF;
        DARK.bubbleKeys = 0xFF35C77A;
        DARK.bubbleDisplay = 0xFFE0A030;
        DARK.bubbleTheme = 0xFFC09BFF;
        DARK.panelSolid = 0xFF0B0B0F;
        DARK.panelStroke = 0x40FFFFFF;
        DARK.panelHead = 0xFF1C1C22;
        DARK.panelBody = 0xFF15151A;
        DARK.panelBar = 0xFF1C1C22;
        DARK.panelKeys = 0xFF191920;
        DARK.padContainer = 0xFF0B0B0F;
        DARK.padStroke = 0x40FFFFFF;
        DARK.screenHead = 0xFF1C1C22;
        DARK.grip = 0x88FFFFFF;
        DARK.keyBg = 0xFF1B1B22;
        DARK.keyStroke = 0x33FFFFFF;
        DARK.keyStrokePressed = 0x99FFFFFF;
        DARK.cursor = 0xFFFFFFFF;
        DARK.cursorEdge = 0xFF000000;
        DARK.cursorHot = 0xFFFFA000;
        DARK.radius = 14;

        // ---- Light: dark ink on pale panels, for a bright room or DeX on a monitor -
        LIGHT.textPrimary = 0xFF101014;
        LIGHT.textSecondary = 0xE0101014;
        LIGHT.textDim = 0x8A101014;
        LIGHT.textFaint = 0x99101014;
        LIGHT.footerText = 0xFF8A5A00;
        LIGHT.rowAction = 0xFF0B5FA5;
        LIGHT.modTextOn = 0xFF06210F;
        LIGHT.accent = 0xFF2A6FB0;
        LIGHT.selectedRow = 0xFFBBD9F5;
        LIGHT.modOnBg = 0xFF9BE3B4;
        LIGHT.modOnStroke = 0xFF3E9E63;
        LIGHT.bubbleFill = 0xE6FFFFFF;
        LIGHT.bubbleStroke = 0x33000000;
        LIGHT.bubbleTrack = 0xFF0A66FF;
        LIGHT.bubbleKeys = 0xFF0B8A4B;
        LIGHT.bubbleDisplay = 0xFFB26A00;
        LIGHT.bubbleTheme = 0xFF6A1FB0;
        LIGHT.panelSolid = 0xF2FFFFFF;
        LIGHT.panelStroke = 0x33000000;
        LIGHT.panelHead = 0xFFE4E4EA;
        LIGHT.panelBody = 0xFFFFFFFF;
        LIGHT.panelBar = 0xFFEDEDF2;
        LIGHT.panelKeys = 0xFFF2F2F6;
        LIGHT.padContainer = 0xF2FFFFFF;
        LIGHT.padStroke = 0x33000000;
        LIGHT.screenHead = 0xFFE4E4EA;
        LIGHT.grip = 0x66000000;
        LIGHT.keyBg = 0xFFFFFFFF;
        LIGHT.keyStroke = 0x22000000;
        LIGHT.keyStrokePressed = 0x88000000;
        // black arrow with a white edge, since a white arrow vanishes on a pale app
        LIGHT.cursor = 0xFF101014;
        LIGHT.cursorEdge = 0xFFFFFFFF;
        LIGHT.cursorHot = 0xFFE65100;
        LIGHT.radius = 18;

        // ---- High contrast: solid black, bright borders, squarer -------------------
        CONTRAST.textPrimary = 0xFFFFFFFF;
        CONTRAST.textSecondary = 0xFFFFFFFF;
        CONTRAST.textDim = 0xB3FFFFFF;
        CONTRAST.textFaint = 0xFFFFFFFF;
        CONTRAST.footerText = 0xFFFFE066;
        CONTRAST.rowAction = 0xFF66D9FF;
        CONTRAST.modTextOn = 0xFF000000;
        CONTRAST.accent = 0xFFFFCC00;
        CONTRAST.selectedRow = 0xFF6B5200;
        CONTRAST.modOnBg = 0xFF00E676;
        CONTRAST.modOnStroke = 0xFF00A152;
        CONTRAST.bubbleFill = 0xFF000000;
        CONTRAST.bubbleStroke = 0xFFFFFFFF;
        CONTRAST.bubbleTrack = 0xFF29B6F6;
        CONTRAST.bubbleKeys = 0xFF00E676;
        CONTRAST.bubbleDisplay = 0xFFFFC400;
        CONTRAST.bubbleTheme = 0xFFD0A0FF;
        CONTRAST.panelSolid = 0xFF000000;
        CONTRAST.panelStroke = 0xFFFFFFFF;
        CONTRAST.panelHead = 0xFF141414;
        CONTRAST.panelBody = 0xFF000000;
        CONTRAST.panelBar = 0xFF141414;
        CONTRAST.panelKeys = 0xFF141414;
        CONTRAST.padContainer = 0xFF000000;
        CONTRAST.padStroke = 0xFFFFFFFF;
        CONTRAST.screenHead = 0xFF141414;
        CONTRAST.grip = 0xFFFFFFFF;
        CONTRAST.keyBg = 0xFF141414;
        CONTRAST.keyStroke = 0x80FFFFFF;
        CONTRAST.keyStrokePressed = 0xFFFFFFFF;
        CONTRAST.cursor = 0xFFFFFFFF;
        CONTRAST.cursorEdge = 0xFF000000;
        CONTRAST.cursorHot = 0xFFFF3D00;
        CONTRAST.radius = 4;

        // ---- Glass: barely there. Panels all but vanish; text carries the layout ----
        GLASS.textPrimary = 0xF2FFFFFF;
        GLASS.textSecondary = 0xB3FFFFFF;
        GLASS.textDim = 0x59FFFFFF;
        GLASS.textFaint = 0x80FFFFFF;
        GLASS.footerText = 0xFFE8C56A;
        GLASS.rowAction = 0xCCFFFFFF;
        GLASS.modTextOn = 0xFF0A0A0A;
        GLASS.accent = 0xCCFFFFFF;
        GLASS.selectedRow = 0x33FFFFFF;
        GLASS.modOnBg = 0xCCFFFFFF;
        GLASS.modOnStroke = 0xD9FFFFFF;
        GLASS.bubbleFill = 0x1AFFFFFF;
        GLASS.bubbleStroke = 0x40FFFFFF;
        GLASS.bubbleTrack = 0xE6FFFFFF;
        GLASS.bubbleKeys = 0xE6FFFFFF;
        GLASS.bubbleDisplay = 0xE6FFFFFF;
        GLASS.bubbleTheme = 0xE6FFFFFF;
        GLASS.panelSolid = 0x14000000;
        GLASS.panelStroke = 0x26FFFFFF;
        GLASS.panelHead = 0x14FFFFFF;
        GLASS.panelBody = 0x0AFFFFFF;
        GLASS.panelBar = 0x14FFFFFF;
        GLASS.panelKeys = 0x0FFFFFFF;
        GLASS.padContainer = 0x14000000;
        GLASS.padStroke = 0x26FFFFFF;
        GLASS.screenHead = 0x14000000;
        GLASS.grip = 0x33FFFFFF;
        GLASS.keyBg = 0x14FFFFFF;
        GLASS.keyStroke = 0x1FFFFFFF;
        GLASS.keyStrokePressed = 0x80FFFFFF;
        GLASS.cursor = 0xFFFFFFFF;
        GLASS.cursorEdge = 0x66000000;
        GLASS.cursorHot = 0xFFFFC400;
        GLASS.radius = 20;
    }

    /** Never returns null: an unknown or missing id falls back to the default preset. */
    static Theme byId(String id) {
        if (id != null) {
            for (int i = 0; i < PRESETS.length; i++) {
                if (id.equals(PRESETS[i].id)) return PRESETS[i];
            }
        }
        return DEFAULT;
    }
}
