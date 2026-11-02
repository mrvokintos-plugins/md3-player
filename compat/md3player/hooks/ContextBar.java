package md3player.hooks;

import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FragmentContextView;
import org.telegram.ui.Components.LayoutHelper;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

import md3player.compat.MediaCompat;
import md3player.compat.PluginConfig;
import md3player.player.Md3Player;
import md3player.player.PlayerBarView;

/**
 * The exteraless FragmentContextView changes: the MD3 bar replaces the stock audio bar in chats, and the stock
 * bar disappears on the main tabs (the mini player is there instead).
 */
public final class ContextBar {
    private static final String TAG = "md3player";
    private static final int STYLE_AUDIO_PLAYER = 0;

    private ContextBar() {
    }

    private static final class State {
        PlayerBarView bar;
        boolean md3;
    }

    private static final Map<FragmentContextView, State> states = new WeakHashMap<>();

    private static Field fragmentField, styleField, playButtonField, titleField, closeButtonField, speedButtonField,
            frameLayoutField, topPaddingField, resourcesField, playingField;

    private static Field f(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static synchronized boolean resolve() {
        if (styleField != null) {
            return true;
        }
        try {
            Class<?> c = FragmentContextView.class;
            fragmentField = f(c, "fragment");
            playButtonField = f(c, "playButton");
            titleField = f(c, "titleTextView");
            closeButtonField = f(c, "closeButton");
            speedButtonField = f(c, "playbackSpeedButton");
            frameLayoutField = f(c, "frameLayout");
            topPaddingField = f(c, "topPadding");
            resourcesField = f(c, "resourcesProvider");
            playingField = f(MediaController.class, "playingMessageObject");
            styleField = f(c, "currentStyle");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "FragmentContextView layout changed, MD3 bar disabled", t);
            return false;
        }
    }

    private static int style(FragmentContextView v) {
        try {
            return styleField.getInt(v);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------ main tabs: hide the stock bar

    private static MessageObject stashed;
    private static boolean stashing;

    /** before checkPlayer(boolean): pretends nothing plays while the mini player owns the tab. */
    public static void beforeCheckPlayer(Object view) {
        if (!resolve()) return;
        try {
            BaseFragment fragment = (BaseFragment) fragmentField.get(view);
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (Md3Player.hidesContextPlayer(fragment, playing)) {
                stashed = playing;
                stashing = true;
                playingField.set(MediaController.getInstance(), null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "beforeCheckPlayer", t);
        }
    }

    /** after checkPlayer(boolean) */
    public static void afterCheckPlayer(Object view) {
        if (stashing) {
            stashing = false;
            try {
                if (playingField.get(MediaController.getInstance()) == null) {
                    playingField.set(MediaController.getInstance(), stashed);
                }
            } catch (Throwable t) {
                Log.w(TAG, "afterCheckPlayer", t);
            }
            stashed = null;
        }
        check((FragmentContextView) view);
    }

    /** after updateStyle(...) */
    public static void afterUpdateStyle(Object view) {
        FragmentContextView v = (FragmentContextView) view;
        if (states.containsKey(v)) {
            check(v);
        }
    }

    // ------------------------------------------------------------ chats: the MD3 bar

    private static boolean computeMd3(FragmentContextView v) {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        return style(v) == STYLE_AUDIO_PLAYER && Md3Player.enabled() && PluginConfig.chatBar.Bool() && playing != null && playing.isMusic();
    }

    public static boolean isMd3(Object view) {
        State state = states.get(view);
        return state != null && state.md3;
    }

    /** getStyleHeight() replacement while the MD3 bar is shown. */
    public static int barHeight() {
        return PlayerBarView.HEIGHT_DP;
    }

    private static void check(FragmentContextView v) {
        if (!resolve()) return;
        try {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            boolean value = computeMd3(v);
            State state = states.get(v);
            if (state == null) {
                if (!value) return;
                state = new State();
                states.put(v, state);
            }
            if (!value && state.md3 && playing == null) {
                // playback stopped: the view is animating out, keep the MD3 bar until it is gone
                return;
            }
            state.md3 = value;
            if (value && state.bar == null) {
                Theme.ResourcesProvider rp = (Theme.ResourcesProvider) resourcesField.get(v);
                state.bar = new PlayerBarView(v.getContext(), rp, v::performClick, () -> {
                    MediaCompat.clearMusicPlaylistState();
                    MediaController.getInstance().cleanupPlayer(true, true);
                });
                v.addView(state.bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, PlayerBarView.HEIGHT_DP, Gravity.TOP | Gravity.LEFT));
            }
            if (state.bar != null) {
                state.bar.setVisibility(value ? View.VISIBLE : View.GONE);
            }
            if (style(v) == STYLE_AUDIO_PLAYER) {
                int stock = value ? View.INVISIBLE : View.VISIBLE;
                setVisibility(playButtonField.get(v), stock);
                setVisibility(titleField.get(v), stock);
                setVisibility(closeButtonField.get(v), stock);
                setVisibility(speedButtonField.get(v), stock);
            }
            // always match the frame to the current style: the stock code sizes it while our height is still applied
            int height = value ? PlayerBarView.HEIGHT_DP : v.getStyleHeight();
            int px = AndroidUtilities.dp(height);
            FrameLayout frame = (FrameLayout) frameLayoutField.get(v);
            if (frame != null && frame.getLayoutParams() != null && frame.getLayoutParams().height != px) {
                frame.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, height, Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));
                v.requestLayout();
            }
            float topPadding = topPaddingField.getFloat(v);
            if (topPadding > 0 && topPadding != AndroidUtilities.dp2(height)) {
                v.setTopPadding(AndroidUtilities.dp2(height));
            }
            if (value) {
                state.bar.update();
            }
        } catch (Throwable t) {
            Log.w(TAG, "ContextBar.check", t);
        }
    }

    private static void setVisibility(Object view, int visibility) {
        if (view instanceof View) {
            ((View) view).setVisibility(visibility);
        }
    }

    // ------------------------------------------------------------ drawing: no stock progress line over the bar

    private static FragmentContextView drawing;

    public static void beforeDraw(Object view) {
        if (isMd3(view) && style((FragmentContextView) view) == STYLE_AUDIO_PLAYER) {
            try {
                styleField.setInt(view, -1);
                drawing = (FragmentContextView) view;
            } catch (Throwable ignore) {
            }
        }
    }

    public static void afterDraw(Object view) {
        if (drawing == view) {
            drawing = null;
            try {
                styleField.setInt(view, STYLE_AUDIO_PLAYER);
            } catch (Throwable ignore) {
            }
        }
    }

    /** Removes every MD3 bar (plugin unload). */
    public static void shutdown() {
        for (Map.Entry<FragmentContextView, State> e : states.entrySet()) {
            State s = e.getValue();
            if (s.bar != null && s.bar.getParent() == e.getKey()) {
                e.getKey().removeView(s.bar);
            }
            s.md3 = false;
            s.bar = null;
            try {
                FragmentContextView v = e.getKey();
                if (style(v) == STYLE_AUDIO_PLAYER) {
                    setVisibility(playButtonField.get(v), View.VISIBLE);
                    setVisibility(titleField.get(v), View.VISIBLE);
                    setVisibility(closeButtonField.get(v), View.VISIBLE);
                }
                v.requestLayout();
            } catch (Throwable ignore) {
            }
        }
        states.clear();
    }

}
