package androidx.test.platform.app;
public final class InstrumentationRegistry {
    private static android.app.Instrumentation instance;
    public static void registerInstance(android.app.Instrumentation i, android.os.Bundle args) { instance = i; }
    public static android.app.Instrumentation getInstrumentation() { return instance; }
}
