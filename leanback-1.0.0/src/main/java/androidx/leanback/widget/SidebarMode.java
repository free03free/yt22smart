package androidx.leanback.widget;

/** MOD: switch between the stock sidebar and the YouTube TV style icons rail. Set by the app from settings. */
public final class SidebarMode {
    public static volatile boolean rail = false;

    private SidebarMode() {
    }
}
