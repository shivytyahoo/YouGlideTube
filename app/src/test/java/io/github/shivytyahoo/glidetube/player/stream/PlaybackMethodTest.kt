package io.github.shivytyahoo.glidetube.player.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The playback-method preference is one DataStore string; the mapping between the stored value
 * and the method is the whole contract, so it is pinned here.
 */
class PlaybackMethodTest {
    @Test
    fun `every stored value round-trips`() {
        for (method in PlaybackMethod.entries) {
            assertEquals(method, PlaybackMethod.fromStored(method.storedValue))
        }
    }

    @Test
    fun `blank and unknown stored values read as Auto`() {
        assertEquals(PlaybackMethod.AUTO, PlaybackMethod.fromStored(""))
        assertEquals(PlaybackMethod.AUTO, PlaybackMethod.fromStored(null))
        assertEquals(PlaybackMethod.AUTO, PlaybackMethod.fromStored("FOO"))
        // The stored values are exact: a lowercase write is not a method.
        assertEquals(PlaybackMethod.AUTO, PlaybackMethod.fromStored("visionos"))
    }

    @Test
    fun `the menu lists Auto first, then the forced methods`() {
        assertEquals(
            listOf(
                PlaybackMethod.AUTO,
                PlaybackMethod.VISIONOS,
                PlaybackMethod.IOS_PRODUCER,
                PlaybackMethod.ANDROID_VR,
                PlaybackMethod.KIDS,
                PlaybackMethod.SABR,
                PlaybackMethod.TV,
            ),
            PlaybackMethod.menuOrder,
        )
    }
}
