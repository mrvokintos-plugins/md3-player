package md3player.compat;

import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;

import java.lang.reflect.Method;

/** Subset of nekogram MonetHelper the player uses. */
public final class MonetHelper {
    private MonetHelper() {
    }

    /** Only the "a1_500"-style system palette names are supported (system_accent1_500 etc). */
    public static Integer getColorOrNull(String color) {
        if (color == null || Build.VERSION.SDK_INT < 31) {
            return null;
        }
        String c = color.trim();
        if (c.length() < 4 || c.charAt(2) != '_') {
            return null;
        }
        String palette;
        switch (c.substring(0, 2)) {
            case "a1": palette = "system_accent1_"; break;
            case "a2": palette = "system_accent2_"; break;
            case "a3": palette = "system_accent3_"; break;
            case "n1": palette = "system_neutral1_"; break;
            case "n2": palette = "system_neutral2_"; break;
            default: return null;
        }
        try {
            int id = ApplicationLoader.applicationContext.getResources().getIdentifier(palette + c.substring(3), "color", "android");
            return id == 0 ? null : ApplicationLoader.applicationContext.getColor(id);
        } catch (Exception e) {
            return null;
        }
    }

    private static Method inuIsMonet;
    private static boolean inuIsMonetResolved;

    /** Theme.ThemeInfo.isMonet() is fork-only; inugram calls it inu_isMonet(). */
    public static boolean isMonet(Theme.ThemeInfo theme) {
        if (theme == null) {
            return false;
        }
        if (!inuIsMonetResolved) {
            inuIsMonetResolved = true;
            try {
                inuIsMonet = Theme.ThemeInfo.class.getMethod("inu_isMonet");
            } catch (Exception ignore) {
            }
        }
        if (inuIsMonet != null) {
            try {
                return (Boolean) inuIsMonet.invoke(theme);
            } catch (Exception ignore) {
            }
        }
        String name = theme.name;
        return name != null && name.startsWith("Monet");
    }
}
