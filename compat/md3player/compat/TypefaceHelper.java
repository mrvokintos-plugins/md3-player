package md3player.compat;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;

import java.io.File;

/** lyricsTypeface() from nekogram TypefaceHelper: Google Sans (Display) bold when the device has it. */
public final class TypefaceHelper {
    private TypefaceHelper() {
    }

    private static Typeface lyricsTypeface;

    public static Typeface lyricsTypeface() {
        if (lyricsTypeface != null) {
            return lyricsTypeface;
        }
        Typeface base = null;
        for (String family : new String[]{"google-sans-display", "google-sans", "google-sans-text"}) {
            Typeface candidate = Typeface.create(family, Typeface.NORMAL);
            if (rendersDifferently(candidate, Typeface.DEFAULT)) {
                base = candidate;
                break;
            }
        }
        if (base == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File file = findGoogleSansFile();
            if (file != null) {
                try {
                    base = Typeface.createFromFile(file);
                } catch (Exception ignore) {
                }
            }
        }
        if (base == null) {
            lyricsTypeface = AndroidUtilities.bold();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lyricsTypeface = Typeface.create(base, 700, false);
        } else {
            lyricsTypeface = Typeface.create(base, Typeface.BOLD);
        }
        return lyricsTypeface;
    }

    private static boolean rendersDifferently(Typeface a, Typeface b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        Paint pa = new Paint();
        Paint pb = new Paint();
        pa.setTypeface(a);
        pb.setTypeface(b);
        pa.setTextSize(64);
        pb.setTextSize(64);
        String sample = "Gag Rhythm 0123456789";
        return Math.abs(pa.measureText(sample) - pb.measureText(sample)) > 0.5f;
    }

    private static File findGoogleSansFile() {
        String[] dirs = {"/product/fonts", "/system/fonts", "/system_ext/fonts"};
        String[] names = {"GoogleSans-Regular.ttf", "GoogleSansText-Regular.ttf", "GoogleSans-VF.ttf", "GoogleSansFlex-Regular.ttf"};
        for (String dir : dirs) {
            for (String name : names) {
                File f = new File(dir, name);
                if (f.isFile() && f.canRead()) {
                    return f;
                }
            }
        }
        return null;
    }
}
