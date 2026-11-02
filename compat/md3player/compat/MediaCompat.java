package md3player.compat;

import android.util.Log;

import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.ui.Components.VideoPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import md3player.player.Md3Player;
import md3player.player.SeekableFragmentsExtractorsFactory;

/**
 * Behaviour exteraless patched into MediaController / VideoPlayer, reimplemented on top of stock
 * through reflection. The xposed hooks in the plugin script call the static on*() entry points.
 */
public final class MediaCompat {
    private static final String TAG = "md3player";

    private MediaCompat() {
    }

    /** SharedConfig.repeatMode value for "stop after the current track". */
    public static final int PLAY_ONCE = 3;

    /** exteraless NotificationCenter.audioInfoLoaded; posted once the playing track's tags are known. */
    public static final int AUDIO_INFO_LOADED = allocateNotificationId();

    /** Set after the queue was reordered by hand; stock sortPlaylist() is skipped while it is set. */
    public static volatile boolean playlistReordered;

    private static int allocateNotificationId() {
        try {
            Field f = NotificationCenter.class.getDeclaredField("totalEvents");
            f.setAccessible(true);
            synchronized (NotificationCenter.class) {
                int id = f.getInt(null);
                f.setInt(null, id + 1);
                return id;
            }
        } catch (Throwable t) {
            Log.w(TAG, "totalEvents unavailable, using a fixed notification id", t);
            return 0x4d330001;
        }
    }

    // ------------------------------------------------------------- reflection helpers

    private static Field field(String name) throws NoSuchFieldException {
        Field f = MediaController.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(String name) {
        try {
            return (T) field(name).get(MediaController.getInstance());
        } catch (Throwable t) {
            Log.w(TAG, "MediaController." + name, t);
            return null;
        }
    }

    private static VideoPlayer audioPlayer() {
        return get("audioPlayer");
    }

    // ------------------------------------------------------------- methods the player calls

    public static boolean movePlaylistItem(int from, int to) {
        if (SharedConfig.shuffleMusic || from == to || from < 0 || to < 0) {
            return false;
        }
        ArrayList<MessageObject> playlist = get("playlist");
        if (playlist == null || from >= playlist.size() || to >= playlist.size()) {
            return false;
        }
        try {
            Field numField = field("currentPlaylistNum");
            MediaController mc = MediaController.getInstance();
            int num = numField.getInt(mc);
            MessageObject current = num >= 0 && num < playlist.size() ? playlist.get(num) : null;
            playlist.add(to, playlist.remove(from));
            playlistReordered = true;
            if (current != null) {
                int index = playlist.indexOf(current);
                if (index >= 0) {
                    numField.setInt(mc, index);
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "movePlaylistItem", t);
            return false;
        }
    }

    public static void seekShift(int ms) {
        VideoPlayer player = audioPlayer();
        if (player != null) {
            player.seekTo(Math.max(0, player.getCurrentPosition() + ms));
        }
    }

    public static void clearMusicPlaylistState() {
        try {
            Method m = MediaController.class.getDeclaredMethod("clearMusicPlaylistState");
            m.setAccessible(true);
            m.invoke(MediaController.getInstance());
        } catch (Throwable t) {
            Log.w(TAG, "clearMusicPlaylistState", t);
        }
    }

    // ------------------------------------------------------------- hook entry points

    /** before MediaController.sortPlaylist(): true = skip it. */
    public static boolean shouldSkipSort() {
        return playlistReordered && Md3Player.enabled();
    }

    /** after MediaController.clearPlaylist() */
    public static void onPlaylistCleared() {
        playlistReordered = false;
    }

    /**
     * before MediaController.playNextMessageWithoutOrder(byStop): handles "play once".
     * Returns true when the original must be skipped.
     */
    public static boolean onTrackEnd(boolean byStop) {
        if (!byStop || SharedConfig.repeatMode != PLAY_ONCE) {
            return false;
        }
        try {
            Boolean force = get("forceLoopCurrentPlaylist");
            if (force != null && force) {
                return false;
            }
            MediaController mc = MediaController.getInstance();
            MessageObject playing = mc.getPlayingMessageObject();
            if (playing == null) {
                return false;
            }
            mc.pauseMessage(playing);
            mc.seekToProgress(playing, 0f);
            playing.audioProgress = 0f;
            playing.audioProgressSec = 0;
            NotificationCenter.getInstance(playing.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playing.getId(), 0f);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "play once", t);
            return false;
        }
    }

    /**
     * after MediaController.playMessage / setPlaybackSpeed: with the MD3 player the music speed applies to every
     * track (stock only for 10+ minute ones) and keeps the pitch at 1.
     */
    public static void applyMusicSpeed() {
        if (!Md3Player.enabled()) {
            return;
        }
        MediaController mc = MediaController.getInstance();
        MessageObject playing = mc.getPlayingMessageObject();
        VideoPlayer player = audioPlayer();
        if (playing == null || player == null || player.player == null || !playing.isMusic()) {
            return;
        }
        float speed = Math.round(mc.getPlaybackSpeed(true) * 10f) / 10f;
        try {
            player.player.setPlaybackParameters(new PlaybackParameters(speed, 1f));
        } catch (Throwable t) {
            Log.w(TAG, "applyMusicSpeed", t);
        }
    }

    /** after new ProgressiveMediaSource.Factory(DataSource.Factory): makes fragmented MP4 seekable. */
    public static void patchProgressiveFactory(Object factory, Object dataSourceFactory) {
        if (!Md3Player.enabled() || factory == null || dataSourceFactory == null) {
            return;
        }
        try {
            ProgressiveMediaSource.Factory donor = new ProgressiveMediaSource.Factory(
                    (androidx.media3.datasource.DataSource.Factory) dataSourceFactory, new SeekableFragmentsExtractorsFactory());
            for (Field f : ProgressiveMediaSource.Factory.class.getDeclaredFields()) {
                if (f.getType().getName().equals("androidx.media3.exoplayer.source.ProgressiveMediaExtractor$Factory")) {
                    f.setAccessible(true);
                    f.set(factory, f.get(donor));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "patchProgressiveFactory", t);
        }
    }

    // ------------------------------------------------------------- audioInfoLoaded emulation

    private static MessageObject lastInfoMessage;
    private static AudioInfo lastInfo;

    /**
     * Stock loads tags synchronously in playMessage or after the download finishes; exteraless loads them
     * asynchronously and announces it. Called on playback notifications: posts AUDIO_INFO_LOADED whenever the
     * playing track's AudioInfo appears or changes.
     */
    public static void checkAudioInfo() {
        MediaController mc = MediaController.getInstance();
        MessageObject playing = mc.getPlayingMessageObject();
        AudioInfo info = playing == null ? null : mc.getAudioInfo();
        if (playing == null || info == null) {
            if (playing != lastInfoMessage) {
                lastInfo = null;
                lastInfoMessage = playing;
            }
            return;
        }
        if (playing == lastInfoMessage && info == lastInfo) {
            return;
        }
        lastInfoMessage = playing;
        lastInfo = info;
        final MessageObject message = playing;
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(message.currentAccount)
                .postNotificationName(AUDIO_INFO_LOADED, message));
    }

    private static final NotificationCenter.NotificationCenterDelegate observer = (id, account, args) -> checkAudioInfo();
    private static final int[] OBSERVED = {
            NotificationCenter.messagePlayingDidStart,
            NotificationCenter.messagePlayingPlayStateChanged,
            NotificationCenter.fileLoaded,
    };

    public static void start() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            for (int id : OBSERVED) {
                NotificationCenter.getInstance(a).addObserver(observer, id);
            }
        }
    }

    public static void stop() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            for (int id : OBSERVED) {
                NotificationCenter.getInstance(a).removeObserver(observer, id);
            }
        }
    }
}
