package io.github.shivytyahoo.glidetube.ui.components.layout.navigation

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class GlideNavigationBarTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val selections = mutableListOf<GlideTab>()

    private fun label(tab: GlideTab) = context.getString(tab.labelRes)

    private fun setBar(
        tabs: List<GlideTab>,
        selected: GlideTab,
    ) {
        rule.setContent {
            MaterialTheme {
                GlideNavigationBar(tabs = tabs, selectedTab = selected, onTabSelected = { selections += it })
            }
        }
    }

    @Test
    fun `the selected tab is announced as selected and the others are not`() {
        setBar(GlideTab.entries.take(5), selected = GlideTab.Music)

        rule.onNodeWithText(label(GlideTab.Music)).assertIsSelected()
        rule.onNodeWithText(label(GlideTab.Home)).assertIsNotSelected()
    }

    @Test
    fun `each tab is announced once`() {
        setBar(GlideTab.entries.take(5), selected = GlideTab.Home)

        rule
            .onNodeWithText(label(GlideTab.Home))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `five tabs fit the bar without an overflow`() {
        setBar(GlideTab.entries.take(5), selected = GlideTab.Home)

        rule.onAllNodesWithText(context.getString(R.string.nav_more)).assertCountEquals(0)
    }

    @Test
    fun `tabs past the fourth move behind More and still navigate`() {
        setBar(GlideTab.entries, selected = GlideTab.Home)

        rule.onAllNodesWithText(label(GlideTab.Library)).assertCountEquals(0)
        rule.onNodeWithText(context.getString(R.string.nav_more)).performClick()
        rule.onNodeWithText(label(GlideTab.Explore)).performClick()

        assertThat(selections).containsExactly(GlideTab.Explore)
    }

    @Test
    fun `More reads as selected while an overflow tab is current`() {
        setBar(GlideTab.entries, selected = GlideTab.Search)

        rule.onNodeWithText(context.getString(R.string.nav_more)).assertIsSelected()
    }
}
