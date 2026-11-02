package md3player.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.LaunchActivity;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import md3player.player.Md3Player;
import md3player.player.PlayerSheet;

/**
 * Static entry points for the plugin's xposed routines (see src/index.ts). Every method must be cheap when it
 * has nothing to do and must never throw into app code.
 */
public final class Hooks {
    private static final String TAG = "md3player";

    private Hooks() {
    }

    private static final Map<Object, Boolean> classic = Collections.synchronizedMap(new WeakHashMap<>());

    /** Marks a stock AudioPlayerAlert that must not be replaced by the MD3 player ("Classic player", voice). */
    public static <T extends Dialog> T classic(T dialog) {
        if (dialog != null) {
            classic.put(dialog, Boolean.TRUE);
        }
        return dialog;
    }

    public static boolean isClassic(Object dialog) {
        return dialog != null && classic.containsKey(dialog);
    }

    private static boolean handles() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        return Md3Player.enabled() && playing != null && playing.isMusic();
    }

    private static boolean replaceable(Object dialog) {
        return dialog instanceof AudioPlayerAlert && !isClassic(dialog) && handles();
    }

    private static Field resourcesField;

    private static Theme.ResourcesProvider resourcesOf(Object sheet) {
        try {
            if (resourcesField == null) {
                resourcesField = BottomSheet.class.getDeclaredField("resourcesProvider");
                resourcesField.setAccessible(true);
            }
            return (Theme.ResourcesProvider) resourcesField.get(sheet);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final int[] ALERT_EVENTS = {
            NotificationCenter.messagePlayingDidReset, NotificationCenter.messagePlayingPlayStateChanged,
            NotificationCenter.messagePlayingDidStart, NotificationCenter.messagePlayingProgressDidChanged,
            NotificationCenter.fileLoaded, NotificationCenter.fileLoadProgressChanged, NotificationCenter.musicDidLoad,
            NotificationCenter.moreMusicDidLoad, NotificationCenter.musicIdsLoaded,
    };

    /** The replaced alert was constructed (and subscribed) but will never be shown or dismissed. */
    private static void release(Object alert) {
        if (!(alert instanceof NotificationCenter.NotificationCenterDelegate)) return;
        NotificationCenter.NotificationCenterDelegate d = (NotificationCenter.NotificationCenterDelegate) alert;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            for (int id : ALERT_EVENTS) {
                NotificationCenter.getInstance(a).removeObserver(d, id);
            }
        }
        NotificationCenter.getGlobalInstance().removeObserver(d, NotificationCenter.messagePlayingSpeedChanged);
    }

    private static PlayerSheet create(Object alert) {
        Context context = ((Dialog) alert).getContext();
        Activity activity = AndroidUtilities.findActivity(context);
        if (activity == null) {
            activity = LaunchActivity.instance;
        }
        if (PlayerSheet.instance != null) {
            PlayerSheet.instance.dismissImmediately();
        }
        return new PlayerSheet(activity != null ? activity : context, resourcesOf(alert));
    }

    /** before BaseFragment.showDialog(Dialog, ...): returns the dialog to show instead, or null. */
    public static Object replaceDialog(Object dialog) {
        if (!replaceable(dialog)) return null;
        try {
            PlayerSheet sheet = create(dialog);
            release(dialog);
            return sheet;
        } catch (Throwable t) {
            Log.e(TAG, "replaceDialog", t);
            return null;
        }
    }

    /** before BottomSheet.show() on an AudioPlayerAlert: true = the MD3 player was shown instead. */
    public static boolean interceptShow(Object dialog) {
        if (!replaceable(dialog)) return false;
        try {
            PlayerSheet sheet = create(dialog);
            release(dialog);
            sheet.show();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "interceptShow", t);
            return false;
        }
    }

    /** after MusicPlayerService.onStartCommand: repeat/shuffle could have been toggled from the notification. */
    public static void onServiceCommand() {
        try {
            AndroidUtilities.runOnUIThread(PlayerSheet::onModesChanged);
        } catch (Throwable ignore) {
        }
    }
}
