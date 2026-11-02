package md3player.compat;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/** Drop-in for tw.nekomimi.nekogram.config.ConfigItem, backed by the plugin's own preferences. */
public class ConfigItem {
    public static final int configTypeBool = 0;
    public static final int configTypeInt = 1;
    public static final int configTypeString = 2;

    public static final String PREFS = "md3player";

    public final String key;
    public final int type;
    public final Object defaultValue;
    public Object value;
    private boolean loaded;

    public ConfigItem(String key, int type, Object defaultValue) {
        this.key = key;
        this.type = type;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getKey() {
        return key;
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        SharedPreferences p = prefs();
        if (!p.contains(key)) {
            return;
        }
        try {
            switch (type) {
                case configTypeBool: value = p.getBoolean(key, (Boolean) defaultValue); break;
                case configTypeInt: value = p.getInt(key, (Integer) defaultValue); break;
                case configTypeString: value = p.getString(key, (String) defaultValue); break;
            }
        } catch (ClassCastException e) {
            value = defaultValue;
        }
    }

    /** Drops the cached value so the next read goes to preferences again (used after external edits). */
    public void invalidate() {
        loaded = false;
        value = defaultValue;
    }

    public boolean Bool() {
        load();
        return (Boolean) value;
    }

    public int Int() {
        load();
        return (Integer) value;
    }

    public String String() {
        load();
        return (String) value;
    }

    public boolean toggleConfigBool() {
        setConfigBool(!Bool());
        return Bool();
    }

    public void setConfigBool(boolean v) {
        loaded = true;
        value = v;
        prefs().edit().putBoolean(key, v).apply();
    }

    public void setConfigInt(int v) {
        loaded = true;
        value = v;
        prefs().edit().putInt(key, v).apply();
    }

    public void setConfigString(String v) {
        loaded = true;
        value = v;
        prefs().edit().putString(key, v).apply();
    }
}
