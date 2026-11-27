package io.github.shivytyahoo.glidetube.ui.screens.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek

class GuestTasteTest {
    @Test
    fun `tokens keep tags and keywords and drop stop words`() {
        val tokens = GuestTaste.tokens("Best #Tensor tricks #viral", listOf("Tensor", "AI"))

        assertThat(tokens).containsExactly("tensor", "ai")
    }

    @Test
    fun `words drop everyday words and years`() {
        assertThat(GuestTaste.words("The Best Tensor G5 Review 2026"))
            .containsExactly("tensor", "g5", "review")
            .inOrder()
    }

    @Test
    fun `engagement grades skipped below watched below finished`() {
        assertThat(GuestTaste.engagement(1.0, 600.0)).isWithin(0.001).of(-0.8)
        assertThat(GuestTaste.engagement(200.0, 600.0)).isWithin(0.001).of(0.2)
        assertThat(GuestTaste.engagement(400.0, 600.0)).isWithin(0.001).of(0.6)
        assertThat(GuestTaste.engagement(580.0, 600.0)).isWithin(0.001).of(1.0)
    }

    @Test
    fun `engagement adds liking and subtracts disliking`() {
        assertThat(GuestTaste.engagement(580.0, 600.0, liked = true)).isWithin(0.001).of(3.0)
        assertThat(GuestTaste.engagement(580.0, 600.0, disliked = true)).isWithin(0.001).of(-2.0)
    }

    @Test
    fun `bucket names the weekday and the part of day`() {
        assertThat(GuestTaste.bucket(9, DayOfWeek.MONDAY)).isEqualTo("wdm")
        assertThat(GuestTaste.bucket(20, DayOfWeek.SATURDAY)).isEqualTo("wee")
        assertThat(GuestTaste.bucket(2, DayOfWeek.WEDNESDAY)).isEqualTo("wdn")
    }

    @Test
    fun `greeting part follows the hour`() {
        assertThat(GuestTaste.greetingPart(8)).isEqualTo(0)
        assertThat(GuestTaste.greetingPart(13)).isEqualTo(1)
        assertThat(GuestTaste.greetingPart(19)).isEqualTo(2)
        assertThat(GuestTaste.greetingPart(23)).isEqualTo(3)
    }

    @Test
    fun `decay leaves fresh scores alone and fades old ones`() {
        assertThat(GuestTaste.decayed(mapOf("a" to 1.0), 0.1)).containsExactly("a", 1.0)
        assertThat(GuestTaste.decayed(mapOf("a" to 1.0), 10.0)["a"]).isWithin(0.001).of(0.817)
    }

    @Test
    fun `decay drops scores too small to matter`() {
        assertThat(GuestTaste.decayed(mapOf("a" to 0.04), 10.0)).isEmpty()
    }

    @Test
    fun `scores cap at ten either way`() {
        assertThat(GuestTaste.capped(42.0)).isWithin(0.001).of(10.0)
        assertThat(GuestTaste.capped(-42.0)).isWithin(0.001).of(-10.0)
        assertThat(GuestTaste.capped(3.0)).isWithin(0.001).of(3.0)
    }
}
