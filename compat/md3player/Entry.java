package md3player;

import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

import md3player.compat.ConfigItem;
import md3player.compat.MediaCompat;
import md3player.gen.AppearanceConfig;
import md3player.gen.PortInfo;
import md3player.hooks.ContextBar;
import md3player.hooks.TabsHost;
import md3player.player.PlayerMiniView;
import md3player.player.PlayerSheet;

/** Called by the plugin script after loadDex. All methods are safe to call from any thread. */
public final class Entry {
    private static final String TAG = "md3player";

    private Entry() {
    }

    public static String version() {
        return PortInfo.UPSTREAM_SHA;
    }

    public static void init() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                MediaCompat.start();
                MediaCompat.checkAudioInfo();
                attachExisting();
            } catch (Throwable t) {
                Log.e(TAG, "init", t);
            }
        });
    }

    /** The plugin usually loads after MainTabsActivity was created: attach the mini player to it now. */
    private static void attachExisting() {
        LaunchActivity activity = LaunchActivity.instance;
        if (activity == null || activity.getActionBarLayout() == null) return;
        for (BaseFragment fragment : activity.getActionBarLayout().getFragmentStack()) {
            if (fragment instanceof MainTabsActivity && fragment.getFragmentView() != null) {
                TabsHost.attach(fragment);
            }
        }
    }

    public static void shutdown() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                MediaCompat.stop();
                if (PlayerSheet.instance != null) {
                    PlayerSheet.instance.dismissImmediately();
                }
                TabsHost.shutdown();
                ContextBar.shutdown();
            } catch (Throwable t) {
                Log.e(TAG, "shutdown", t);
            }
        });
    }

    // ------------------------------------------------------------ settings bridge for the plugin script

    public static boolean getBool(String key) {
        ConfigItem item = item(key);
        return item != null && item.Bool();
    }

    public static void setBool(String key, boolean value) {
        ConfigItem item = item(key);
        if (item == null) return;
        item.setConfigBool(value);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                PlayerMiniView.styleChanged();
                TabsHost.refresh();
                org.telegram.messenger.NotificationCenter.getInstance(org.telegram.messenger.UserConfig.selectedAccount)
                        .postNotificationName(org.telegram.messenger.NotificationCenter.messagePlayingPlayStateChanged, 0);
            } catch (Throwable t) {
                Log.w(TAG, "settings refresh", t);
            }
        });
    }

    private static ConfigItem item(String key) {
        for (ConfigItem item : AppearanceConfig.ALL) {
            if (item.key.equals(key)) return item;
        }
        for (ConfigItem item : md3player.compat.PluginConfig.ALL) {
            if (item.key.equals(key)) return item;
        }
        return null;
    }

    /** Back to defaults: every player setting and the plugin's own toggles. */
    public static void resetSettings() {
        ConfigItem.prefs().edit().clear().apply();
        for (ConfigItem item : AppearanceConfig.ALL) item.invalidate();
        for (ConfigItem item : md3player.compat.PluginConfig.ALL) item.invalidate();
        AndroidUtilities.runOnUIThread(() -> {
            try {
                PlayerMiniView.styleChanged();
                TabsHost.refresh();
                org.telegram.messenger.NotificationCenter.getInstance(org.telegram.messenger.UserConfig.selectedAccount)
                        .postNotificationName(org.telegram.messenger.NotificationCenter.messagePlayingPlayStateChanged, 0);
            } catch (Throwable t) {
                Log.w(TAG, "settings reset", t);
            }
        });
    }

    public static void openStyleEditor(Object fragment) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                PlayerSheet.openStyleEditor((BaseFragment) fragment);
            } catch (Throwable t) {
                Log.e(TAG, "openStyleEditor", t);
            }
        });
    }
}
