package md3player.compat;

/** Settings of the plugin itself (not taken from exteraless). */
public final class PluginConfig {
    private PluginConfig() {
    }

    /** MD3 bar instead of the stock audio bar in chats; exteraless ties it to the mini player toggle. */
    public static final ConfigItem chatBar = new ConfigItem("MD3PlayerChatBar", ConfigItem.configTypeBool, true);

    public static final ConfigItem[] ALL = {chatBar};
}
