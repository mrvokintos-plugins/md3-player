package md3player.compat;

import android.util.Log;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import md3player.gen.Strings;

/**
 * Resource access by name. The plugin is compiled once and runs on many inugram builds, whose
 * resource ids differ, so the porter turns every R.x.y into a lookup here.
 *
 * Strings owned by the plugin get negative "synthetic" ids that only {@link #getString(int)} and
 * friends understand. Real ids are passed through to LocaleController.
 */
public final class Res {
    private static final String TAG = "md3player";

    private Res() {
    }

    private static final HashMap<String, Integer> ids = new HashMap<>();
    private static HashMap<String, Integer> keyIndex;
    private static final ArrayList<String> unknown = new ArrayList<>();
    private static String cachedLang;
    private static int cachedLangIndex;

    private static synchronized int keyIndex(String name) {
        if (keyIndex == null) {
            keyIndex = new HashMap<>();
            for (int i = 0; i < Strings.KEYS.length; i++) {
                keyIndex.put(Strings.KEYS[i], i);
            }
        }
        Integer i = keyIndex.get(name);
        return i == null ? -1 : i;
    }

    private static synchronized int lookup(String name, String type) {
        String cacheKey = type + "/" + name;
        Integer cached = ids.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        int id = 0;
        // the app's own R class carries the ids of the running build
        try {
            id = Class.forName("org.telegram.messenger.R$" + type).getField(name).getInt(null);
        } catch (Throwable ignore) {
        }
        if (id == 0) {
            try {
                android.content.Context ctx = ApplicationLoader.applicationContext;
                id = ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
                if (id == 0) {
                    id = ctx.getResources().getIdentifier(name, type, "org.telegram.messenger");
                }
            } catch (Exception ignore) {
            }
        }
        if (id == 0) {
            Log.w(TAG, "missing " + type + " resource " + name);
        }
        ids.put(cacheKey, id);
        return id;
    }

    /** R.string.name */
    public static int sid(String name) {
        int own = keyIndex(name);
        if (own >= 0) {
            return -(own + 1);
        }
        int id = lookup(name, "string");
        if (id != 0) {
            return id;
        }
        synchronized (unknown) {
            int at = unknown.indexOf(name);
            if (at < 0) {
                unknown.add(name);
                at = unknown.size() - 1;
            }
            return -(Strings.KEYS.length + at + 1);
        }
    }

    /** R.drawable.name, 0 when the running build does not have it. */
    public static int drawable(String name) {
        return lookup(name, "drawable");
    }

    /** R.raw.name */
    public static int raw(String name) {
        return lookup(name, "raw");
    }

    private static int langIndex() {
        String lang = "en";
        try {
            LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && info.pluralLangCode != null) {
                lang = info.pluralLangCode;
            } else {
                Locale locale = LocaleController.getInstance().getCurrentLocale();
                if (locale != null) {
                    lang = locale.getLanguage();
                }
            }
        } catch (Exception ignore) {
        }
        int dash = lang.indexOf('_');
        if (dash < 0) dash = lang.indexOf('-');
        if (dash > 0) lang = lang.substring(0, dash);
        lang = lang.toLowerCase(Locale.ROOT);
        if (lang.equals(cachedLang)) {
            return cachedLangIndex;
        }
        int index = 0;
        for (int i = 0; i < Strings.LANGS.length; i++) {
            if (Strings.LANGS[i].equals(lang)) {
                index = i;
                break;
            }
        }
        cachedLang = lang;
        cachedLangIndex = index;
        return index;
    }

    private static String own(int id) {
        int index = -id - 1;
        if (index >= Strings.KEYS.length) {
            synchronized (unknown) {
                int at = index - Strings.KEYS.length;
                return at < unknown.size() ? unknown.get(at) : "";
            }
        }
        String value = Strings.VALUES[langIndex()][index];
        if (value == null) {
            value = Strings.VALUES[0][index];
        }
        return value == null ? Strings.KEYS[index] : value;
    }

    public static String getString(int id) {
        return id < 0 ? own(id) : LocaleController.getString(id);
    }

    public static String getString(String key) {
        return getString(sid(key));
    }

    public static String getString(String key, int id) {
        return id < 0 ? own(id) : LocaleController.getString(key, id);
    }

    public static String formatString(int id, Object... args) {
        if (id >= 0) {
            return LocaleController.formatString(id, args);
        }
        try {
            return String.format(currentLocale(), own(id), args);
        } catch (Exception e) {
            return own(id);
        }
    }

    public static String formatString(String key, int id, Object... args) {
        return id < 0 ? formatString(id, args) : LocaleController.formatString(key, id, args);
    }

    public static String formatPluralString(String key, int count, Object... args) {
        String own = ownPlural(key, count);
        if (own == null) {
            return LocaleController.formatPluralString(key, count, args);
        }
        Object[] formatArgs = args == null || args.length == 0 ? new Object[]{count} : args;
        try {
            return String.format(currentLocale(), own, formatArgs);
        } catch (Exception e) {
            return own;
        }
    }

    public static String getPluralString(String key, int count) {
        String own = ownPlural(key, count);
        return own != null ? own : LocaleController.getPluralString(key, count);
    }

    /** Plugin-owned plurals are stored as key_one/key_few/... like Telegram's own. */
    private static String ownPlural(String key, int count) {
        if (keyIndex(key + "_other") < 0) {
            return null;
        }
        String form = "other";
        try {
            LocaleController lc = LocaleController.getInstance();
            java.lang.reflect.Field rulesField = LocaleController.class.getDeclaredField("currentPluralRules");
            rulesField.setAccessible(true);
            Object rules = rulesField.get(lc);
            if (rules != null) {
                java.lang.reflect.Method quantity = rules.getClass().getMethod("quantityForNumber", int.class);
                quantity.setAccessible(true);
                int q = (Integer) quantity.invoke(rules, count);
                java.lang.reflect.Method name = LocaleController.class.getDeclaredMethod("stringForQuantity", int.class);
                name.setAccessible(true);
                form = (String) name.invoke(lc, q);
            }
        } catch (Throwable ignore) {
        }
        int index = keyIndex(key + "_" + form);
        if (index < 0) {
            index = keyIndex(key + "_other");
        }
        return own(-(index + 1));
    }

    private static Locale currentLocale() {
        try {
            Locale locale = LocaleController.getInstance().getCurrentLocale();
            if (locale != null) return locale;
        } catch (Exception ignore) {
        }
        return Locale.getDefault();
    }
}
