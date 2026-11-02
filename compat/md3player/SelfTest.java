package md3player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.LaunchActivity;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import md3player.player.LyricsView;
import md3player.player.PlayerBarView;
import md3player.player.PlayerMiniView;
import md3player.player.PlayerSheet;
import md3player.player.WavySeekBar;

/**
 * Builds and draws the player's views off-screen to catch linkage problems against the running inugram build
 * (missing methods, fields, resources) without showing anything. Run with:
 * inu eval md3-player "inu.jvm.cls('md3player.SelfTest').callStatic('run')"
 */
public final class SelfTest {
    private SelfTest() {
    }

    public static String run() throws InterruptedException {
        StringBuilder out = new StringBuilder();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runOnUi(out);
            return out.toString();
        }
        CountDownLatch latch = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                runOnUi(out);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            out.append("timeout\n");
        }
        return out.toString();
    }

    private interface Step {
        Object run() throws Throwable;
    }

    private static void step(StringBuilder out, String name, Step step) {
        try {
            Object result = step.run();
            out.append("ok   ").append(name);
            if (result instanceof View) {
                out.append(" -> drew ").append(draw((View) result));
            }
            out.append('\n');
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String trace = sw.toString();
            out.append("FAIL ").append(name).append(": ").append(trace, 0, Math.min(trace.length(), 1500)).append('\n');
        }
    }

    private static String draw(View view) {
        int w = AndroidUtilities.displaySize.x > 0 ? AndroidUtilities.displaySize.x : 1080;
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(800), View.MeasureSpec.AT_MOST));
        int h = Math.max(1, view.getMeasuredHeight());
        view.layout(0, 0, w, h);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        bitmap.recycle();
        return w + "x" + h;
    }

    private static void runOnUi(StringBuilder out) {
        Activity activity = LaunchActivity.instance;
        if (activity == null) {
            out.append("no LaunchActivity\n");
            return;
        }
        out.append("exteraless@").append(Entry.version()).append('\n');
        step(out, "WavySeekBar", () -> new WavySeekBar(activity));
        step(out, "LyricsView", () -> new LyricsView(activity));
        step(out, "PlayerMiniView.preview", () -> PlayerMiniView.preview(activity));
        step(out, "PlayerBarView", () -> new PlayerBarView(activity, null, () -> { }, () -> { }));
        step(out, "PlayerSheet", () -> {
            PlayerSheet sheet = new PlayerSheet(activity, null);
            java.lang.reflect.Field f = org.telegram.ui.ActionBar.BottomSheet.class.getDeclaredField("containerView");
            f.setAccessible(true);
            return f.get(sheet);
        });
    }
}
