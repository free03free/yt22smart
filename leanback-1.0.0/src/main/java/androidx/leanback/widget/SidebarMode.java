package androidx.leanback.widget;

/** MOD: switch between the stock sidebar and the YouTube TV style icons rail. Set by the app from settings. */
public final class SidebarMode {
    public static volatile boolean rail = false;
    /** Rail item height in px (0 = layout default). Computed so that all sections fit on the screen. */
    public static volatile int itemHeightPx = 0;

    private SidebarMode() {
    }
}
