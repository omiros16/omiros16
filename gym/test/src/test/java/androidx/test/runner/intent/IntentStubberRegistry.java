package androidx.test.runner.intent;

public final class IntentStubberRegistry {
    private IntentStubberRegistry() {
    }

    /** Nothing stubs intents here, so started activities get no result unless a test sends one. */
    public static boolean isLoaded() {
        return false;
    }

    public static IntentStubber getInstance() {
        return null;
    }
}
