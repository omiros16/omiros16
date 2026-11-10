package com.omiros.gymnotes;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SmokeTest {
    @Test
    public void starts() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertTrue(a.top() instanceof HomeScreen);
    }
}
