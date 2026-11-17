/*
 * Maintained by Shivyt | YouGlideTube
 *
 * This file is part of YouGlideTube (https://github.com/shivytyahoo/YouGlideTube).
 */

package io.github.shivytyahoo.glidetube.ui.screens.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeSubscriptionScopeTest {
    private val subs = setOf("UCa", "UCb")

    @Test
    fun `with subscriptions on Home they are boosted and nothing is hidden`() {
        val scope = HomeSubscriptionScope.of(subs, showOnHome = true)

        assertThat(scope.boosted).isEqualTo(subs)
        assertThat(scope.hidden).isEmpty()
    }

    @Test
    fun `with subscriptions off Home hides them and boosts nothing`() {
        val scope = HomeSubscriptionScope.of(subs, showOnHome = false)

        assertThat(scope.boosted).isEmpty()
        assertThat(scope.hidden).isEqualTo(subs)
    }
}
