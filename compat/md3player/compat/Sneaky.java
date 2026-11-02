package md3player.compat;

/**
 * Rethrows checked exceptions without declaring them. The porter wraps method bodies with this
 * where R8 stripped `throws` clauses from the app classes the player overrides.
 */
public final class Sneaky {
    private Sneaky() {
    }

    public static RuntimeException raise(Throwable t) {
        Sneaky.<RuntimeException>doThrow(t);
        return new RuntimeException(t);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void doThrow(Throwable t) throws T {
        throw (T) t;
    }
}
