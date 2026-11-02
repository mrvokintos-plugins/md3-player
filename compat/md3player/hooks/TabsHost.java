package md3player.hooks;

import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import md3player.player.PlayerMiniView;

/**
 * Mini player on MainTabsActivity, which exteraless patches directly. Geometry is read from the live views
 * (tabs bar, update layout), so inugram's own tab-bar patches keep working.
 */
public final class TabsHost {
    private static final String TAG = "md3player";

    private TabsHost() {
    }

    private static final class Host {
        MainTabsActivity tabs;
        FrameLayout contentView;
        PlayerMiniView mini;
        ViewTreeObserver.OnPreDrawListener preDraw;
        View.OnAttachStateChangeListener attach;
    }

    private static final Map<MainTabsActivity, Host> hosts = new WeakHashMap<>();

    // ------------------------------------------------------------ reflection

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignore) {
            }
        }
        return null;
    }

    private static final Map<String, Field> fields = new java.util.HashMap<>();

    private static Object get(Object target, String name) {
        if (target == null) return null;
        String key = target.getClass().getName() + "#" + name;
        Field f;
        synchronized (fields) {
            f = fields.get(key);
            if (f == null && !fields.containsKey(key)) {
                f = field(target.getClass(), name);
                fields.put(key, f);
            }
        }
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void set(Object target, String name, int value) {
        get(target, name); // warms the cache
        Field f;
        synchronized (fields) {
            f = fields.get(target.getClass().getName() + "#" + name);
        }
        if (f == null) return;
        try {
            f.setInt(target, value);
        } catch (Throwable ignore) {
        }
    }

    private static int getInt(Object target, String name, int def) {
        Object v = get(target, name);
        return v instanceof Integer ? (Integer) v : def;
    }

    private static float getFloat(Object target, String name, float def) {
        Object v = get(target, name);
        return v instanceof Float ? (Float) v : def;
    }

    // ------------------------------------------------------------ lifecycle hooks

    /** after MainTabsActivity.createView(Context) */
    public static void attach(Object fragment) {
        if (!md3player.player.Md3Player.miniEnabled()) return;
        MainTabsActivity tabs = (MainTabsActivity) fragment;
        try {
            FrameLayout contentView = (FrameLayout) get(tabs, "contentView");
            if (contentView == null) {
                Log.w(TAG, "MainTabsActivity.contentView missing, no mini player");
                return;
            }
            Host old = hosts.remove(tabs);
            if (old != null) detach(old);

            Host host = new Host();
            host.tabs = tabs;
            host.contentView = contentView;
            host.mini = new PlayerMiniView(contentView.getContext(), tabs, tabs.getResourceProvider());
            host.mini.setOffsetListener(() -> applyInsets(host));
            int index = contentView.getChildCount();
            Object wrapper = get(tabs, "tabsViewWrapper");
            if (wrapper instanceof View && ((View) wrapper).getParent() == contentView) {
                index = contentView.indexOfChild((View) wrapper);
            }
            contentView.addView(host.mini, index, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, PlayerMiniView.HEIGHT_DP + 16, Gravity.BOTTOM));
            host.preDraw = () -> {
                position(host);
                return true;
            };
            host.attach = new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    v.getViewTreeObserver().addOnPreDrawListener(host.preDraw);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    v.getViewTreeObserver().removeOnPreDrawListener(host.preDraw);
                }
            };
            contentView.addOnAttachStateChangeListener(host.attach);
            if (contentView.isAttachedToWindow()) {
                contentView.getViewTreeObserver().addOnPreDrawListener(host.preDraw);
            }
            hosts.put(tabs, host);
            host.mini.update(false);
        } catch (Throwable t) {
            Log.e(TAG, "mini player attach failed", t);
        }
    }

    /** after MainTabsActivity.onResume() */
    public static void resume(Object fragment) {
        Host host = hosts.get(fragment);
        if (host != null) {
            host.mini.update(true);
        }
    }

    /** after MainTabsActivity.onFragmentDestroy() */
    public static void destroy(Object fragment) {
        Host host = hosts.remove(fragment);
        if (host != null) detach(host);
    }

    private static void detach(Host host) {
        try {
            host.contentView.removeOnAttachStateChangeListener(host.attach);
            host.contentView.getViewTreeObserver().removeOnPreDrawListener(host.preDraw);
            if (host.mini.getParent() instanceof ViewGroup) {
                ((ViewGroup) host.mini.getParent()).removeView(host.mini);
            }
            resetInsets(host);
        } catch (Throwable t) {
            Log.w(TAG, "mini player detach", t);
        }
    }

    public static void shutdown() {
        for (Host host : new ArrayList<>(hosts.values())) {
            detach(host);
        }
        hosts.clear();
    }

    /** Settings changed (mini player toggled): re-attach or remove. */
    public static void refresh() {
        boolean enabled = md3player.player.Md3Player.miniEnabled();
        if (enabled && hosts.isEmpty()) {
            org.telegram.ui.LaunchActivity activity = org.telegram.ui.LaunchActivity.instance;
            if (activity != null && activity.getActionBarLayout() != null) {
                for (BaseFragment f : activity.getActionBarLayout().getFragmentStack()) {
                    if (f instanceof MainTabsActivity && f.getFragmentView() != null) attach(f);
                }
            }
            return;
        }
        for (Host host : new ArrayList<>(hosts.values())) {
            if (!enabled) {
                hosts.remove(host.tabs);
                detach(host);
            } else {
                host.mini.update(true);
            }
        }
    }

    // ------------------------------------------------------------ geometry

    private static void position(Host host) {
        MainTabsActivity tabs = host.tabs;
        int navigationBarHeight = getInt(tabs, "navigationBarHeight", AndroidUtilities.navigationBarHeight);
        int updateLayoutHeight = 0;
        Object updateWrapper = get(tabs, "updateLayoutWrapper");
        if (updateWrapper != null) {
            try {
                Method visible = updateWrapper.getClass().getMethod("isUpdateLayoutVisible");
                if ((Boolean) visible.invoke(updateWrapper)) {
                    updateLayoutHeight = AndroidUtilities.dp(getStaticInt(updateWrapper.getClass(), "HEIGHT", 0));
                }
            } catch (Throwable ignore) {
            }
        }
        View wrapper = (View) get(tabs, "tabsViewWrapper");
        View tabsView = (View) get(tabs, "tabsView");
        boolean hidden = wrapper == null || tabsView == null || wrapper.getVisibility() != View.VISIBLE;
        float factor = 1f;
        int tabsHeight = 0;
        if (!hidden) {
            Object animator = get(tabs, "animatorTabsVisible");
            if (animator != null) {
                try {
                    factor = (Float) animator.getClass().getMethod("getFloatValue").invoke(animator);
                } catch (Throwable ignore) {
                }
            }
            // distance from the bottom of the wrapper (which sits above the navigation bar) to the top of the tabs
            tabsHeight = Math.max(0, wrapper.getHeight() - tabsView.getTop());
        }
        int gap = AndroidUtilities.dp(hidden ? 12 : 8);
        host.mini.setHostPosition(-(navigationBarHeight + updateLayoutHeight + tabsHeight + gap - AndroidUtilities.dp(12)), hidden ? 1f : factor);
        updateTopAnchor(host);
    }

    private static int getStaticInt(Class<?> c, String name, int def) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void updateTopAnchor(Host host) {
        View viewPager = (View) get(host.tabs, "viewPager");
        DialogsActivity chats = host.tabs.getDialogsActivity();
        View view = chats != null ? chats.getFragmentView() : null;
        View page = view != null && view.getParent() instanceof View ? (View) view.getParent() : null;
        float shift = 0f;
        float zone = 0f;
        if (viewPager != null && page != null && page.getParent() == viewPager && page.getVisibility() == View.VISIBLE && viewPager.getMeasuredWidth() > 0) {
            shift = page.getTranslationX();
            zone = Math.max(0f, Math.min(1f, 1f - Math.abs(shift) / viewPager.getMeasuredWidth()));
        }
        float anchor = -1;
        float visibility = 1f;
        if (chats != null) {
            Object pages = get(chats, "viewPages");
            if (pages instanceof Object[] && ((Object[]) pages).length > 0 && ((Object[]) pages)[0] != null) {
                View page0 = (View) ((Object[]) pages)[0];
                View list = (View) get(page0, "listView");
                if (list != null) {
                    anchor = list.getPaddingTop() + getFloat(chats, "scrollYOffset", 0f) + getFloat(chats, "storiesOverscroll", 0f) + page0.getTranslationY();
                }
            }
            visibility = 1f - clamp01(getFloat(chats, "searchAnimationProgress", 0f));
            Object sliding = get(chats, "rightSlidingDialogContainer");
            if (sliding != null) {
                try {
                    if ((Boolean) sliding.getClass().getMethod("hasFragment").invoke(sliding)) {
                        visibility *= 1f - clamp01(getFloat(sliding, "openedProgress", 0f));
                    }
                } catch (Throwable ignore) {
                }
            }
            Object blurred = get(chats, "blurredView");
            if (blurred instanceof View && ((View) blurred).getVisibility() == View.VISIBLE && ((View) blurred).getBackground() != null) {
                visibility *= 1f - clamp01(((View) blurred).getAlpha());
            }
        }
        if (anchor < 0) {
            anchor = AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
        }
        host.mini.setTopState(anchor, zone, shift, visibility);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ------------------------------------------------------------ insets of the tab fragments

    /** per fragment: {base additionNavigationBarHeight, base additionFloatingButtonOffset, last written nav, last written fab} */
    private static final Map<BaseFragment, int[]> bases = new WeakHashMap<>();

    private static void applyInsets(Host host) {
        SparseArray<?> states = (SparseArray<?>) get(host.tabs, "fragmentsArr");
        if (states == null) return;
        for (int i = 0; i < states.size(); i++) {
            BaseFragment fragment = (BaseFragment) get(states.valueAt(i), "fragment");
            if (fragment == null) continue;
            boolean chats = fragment instanceof DialogsActivity;
            int padding = host.mini.getTargetOffset(chats);
            int inset = Math.round(host.mini.getVisibleOffset(chats));
            applyInsets(fragment, padding, inset);
        }
    }

    private static void applyInsets(BaseFragment fragment, int padding, int inset) {
        Object nav = get(fragment, "additionNavigationBarHeight");
        if (!(nav instanceof Integer)) return;
        Object fab = get(fragment, "additionFloatingButtonOffset");
        int[] base = bases.get(fragment);
        int curNav = (Integer) nav;
        int curFab = fab instanceof Integer ? (Integer) fab : 0;
        if (base == null) {
            base = new int[]{curNav, curFab, curNav, curFab};
            bases.put(fragment, base);
        } else {
            // the fragment recomputed its fields (view recreated): take them as the new base
            if (curNav != base[2]) base[0] = curNav;
            if (curFab != base[3]) base[1] = curFab;
        }
        int newNav = base[0] + padding;
        int newFab = base[1] + inset;
        if (newNav == curNav && newFab == curFab) return;
        set(fragment, "additionNavigationBarHeight", newNav);
        if (fab instanceof Integer) set(fragment, "additionFloatingButtonOffset", newFab);
        base[2] = newNav;
        base[3] = newFab;
        View view = fragment.getFragmentView();
        if (view != null) {
            view.requestApplyInsets();
        }
    }

    private static void resetInsets(Host host) {
        SparseArray<?> states = (SparseArray<?>) get(host.tabs, "fragmentsArr");
        if (states == null) return;
        for (int i = 0; i < states.size(); i++) {
            BaseFragment fragment = (BaseFragment) get(states.valueAt(i), "fragment");
            if (fragment != null) {
                applyInsets(fragment, 0, 0);
                bases.remove(fragment);
            }
        }
    }
}
