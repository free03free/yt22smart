package androidx.leanback.widget;

/** MOD: switch between the stock sidebar and the YouTube TV style icons rail. Set by the app from settings. */
public final class SidebarMode {
    public static volatile boolean rail = false;
    /** Rail item height in px (0 = layout default). Computed so that all sections fit on the screen. */
    public static volatile int itemHeightPx = 0;
    /** Rail icon size and start padding in px, measured from the real rail width (independent of dp/density quirks). */
    public static volatile int iconPx = 0;
    public static volatile int padStartPx = 0;
    /** Top padding of the sections list in px (aligned with the video cards level when the screen is tall enough). */
    public static volatile int topPadPx = 0;

    private SidebarMode() {
    }
}
