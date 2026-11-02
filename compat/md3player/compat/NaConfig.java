package md3player.compat;

import org.telegram.messenger.MessagesController;

/** The one NaConfig setting the player reads: whether messages are sent silently by default. */
public final class NaConfig {
    public static final NaConfig INSTANCE = new NaConfig();

    private NaConfig() {
    }

    public Flag getSilentMessageByDefault() {
        return Flag.FALSE;
    }

    public static final class Flag {
        static final Flag FALSE = new Flag();

        public boolean Bool() {
            return false;
        }
    }
}
