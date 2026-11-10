package androidx.test.platform.app;

import android.app.Instrumentation;
import android.os.Bundle;

/** See ../../README.md: a stand-in with the signatures Robolectric calls. */
public final class InstrumentationRegistry {
    private static Instrumentation instrumentation;
    private static Bundle arguments;

    private InstrumentationRegistry() {
    }

    public static void registerInstance(Instrumentation instance, Bundle args) {
        instrumentation = instance;
        arguments = args;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static Bundle getArguments() {
        return arguments;
    }
}
