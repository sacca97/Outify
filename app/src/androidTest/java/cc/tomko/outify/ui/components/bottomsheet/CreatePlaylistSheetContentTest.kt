package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.tomko.outify.R
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests the create/edit playlist form without a view model or a Spotify login.
 */
@RunWith(AndroidJUnit4::class)
class CreatePlaylistSheetContentTest {

    @get:Rule
    val rule = createComposeRule()

    private class Submit(
        val name: String,
        val description: String,
        val isPublic: Boolean?,
        val isCollaborative: Boolean,
    )

    private val submits = mutableListOf<Submit>()
    private val deleted = mutableListOf<String>()
    private var cancelled = 0

    private fun str(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun setSheet(
        playlistId: String? = null,
        initialName: String = "",
        initialDescription: String = "",
        isSaving: Boolean = false,
        loadPublic: suspend (String) -> Boolean? = { null },
        modifier: Modifier = Modifier,
    ) {
        rule.setContent {
            MaterialTheme {
                CreatePlaylistSheetContent(
                    playlistId = playlistId,
                    initialName = initialName,
                    initialDescription = initialDescription,
                    initialCollaborative = false,
                    isSaving = isSaving,
                    loadPublic = loadPublic,
                    onCancel = { cancelled++ },
                    onSubmit = { name, description, isPublic, isCollaborative ->
                        submits += Submit(name, description, isPublic, isCollaborative)
                    },
                    onDelete = { deleted += it },
                    modifier = modifier,
                )
            }
        }
    }

    // The first switch is Public, the second Collaborative.
    private fun publicSwitch() = rule.onAllNodes(isToggleable())[0]

    @Test
    fun createIsDisabledWhileNameIsBlank() {
        setSheet()

        rule.onNodeWithText(str(R.string.common_create)).assertIsNotEnabled()

        rule.onNodeWithText(str(R.string.playlist_name)).performTextInput("   ")
        rule.onNodeWithText(str(R.string.common_create)).assertIsNotEnabled()

        rule.onNodeWithText(str(R.string.playlist_name)).performTextInput("Road trip")
        rule.onNodeWithText(str(R.string.common_create)).assertIsEnabled()
    }

    @Test
    fun createSendsTrimmedNameAndPublicByDefault() {
        setSheet()

        rule.onNodeWithText(str(R.string.playlist_name)).performTextInput("  Road trip ")
        rule.onNodeWithText(str(R.string.common_create)).performClick()

        assertEquals(1, submits.size)
        assertEquals("Road trip", submits[0].name)
        assertEquals("", submits[0].description)
        assertEquals(true, submits[0].isPublic)
    }

    @Test
    fun publicSwitchIsDisabledWhileVisibilityIsLoading() {
        setSheet(playlistId = "abc", initialName = "Mix", loadPublic = { awaitCancellation() })

        rule.onNodeWithText(str(R.string.playlist_public_loading)).assertIsDisplayed()
        publicSwitch().assertIsNotEnabled()
    }

    @Test
    fun publicSwitchStaysDisabledWhenVisibilityCannotBeLoaded() {
        setSheet(playlistId = "abc", initialName = "Mix", loadPublic = { null })

        rule.onNodeWithText(str(R.string.playlist_public_unknown)).assertIsDisplayed()
        publicSwitch().assertIsNotEnabled()
    }

    @Test
    fun publicSwitchIsEnabledOnceVisibilityIsKnown() {
        setSheet(playlistId = "abc", initialName = "Mix", loadPublic = { true })

        rule.onNodeWithText(str(R.string.playlist_public_anyone)).assertIsDisplayed()
        publicSwitch().assertIsEnabled().assertIsOn()
    }

    @Test
    fun editLeavesVisibilityAloneUnlessItWasChanged() {
        setSheet(playlistId = "abc", initialName = "Mix", loadPublic = { true })

        rule.onNodeWithText(str(R.string.common_save)).performClick()
        assertEquals(null, submits.last().isPublic)

        publicSwitch().performClick()
        publicSwitch().assertIsOff()
        rule.onNodeWithText(str(R.string.common_save)).performClick()
        assertEquals(false, submits.last().isPublic)
    }

    @Test
    fun deleteAsksForConfirmationBeforeCallingOnDelete() {
        setSheet(playlistId = "abc", initialName = "Road trip")

        rule.onNodeWithText(str(R.string.playlist_delete)).performClick()

        rule.onNodeWithText(str(R.string.playlist_delete_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.playlist_delete_message, "Road trip")).assertIsDisplayed()
        assertTrue(deleted.isEmpty())

        rule.onNodeWithText(str(R.string.common_delete)).performClick()

        assertEquals(listOf("abc"), deleted)
        rule.onNodeWithText(str(R.string.playlist_delete_title)).assertDoesNotExist()
    }

    @Test
    fun cancellingTheDeleteConfirmationDoesNotDelete() {
        setSheet(playlistId = "abc", initialName = "Road trip")

        rule.onNodeWithText(str(R.string.playlist_delete)).performClick()
        // The dialog has its own Cancel button, the sheet's is behind it.
        rule.onNode(hasText(str(R.string.common_cancel)) and hasAnyAncestor(isDialog()))
            .performClick()

        rule.onNodeWithText(str(R.string.playlist_delete_title)).assertDoesNotExist()
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun deleteIsOnlyOfferedWhenEditing() {
        setSheet()

        rule.onNodeWithText(str(R.string.playlist_delete)).assertDoesNotExist()
    }

    @Test
    fun buttonsStayVisibleWithLongDescriptionWhenHeightIsLimited() {
        val longDescription = "A very long description. ".repeat(40)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.height(280.dp)) {
                    CreatePlaylistSheetContent(
                        playlistId = "abc",
                        initialName = "Mix",
                        initialDescription = longDescription,
                        initialCollaborative = false,
                        isSaving = false,
                        loadPublic = { true },
                        onCancel = {},
                        onSubmit = { _, _, _, _ -> },
                        onDelete = {},
                    )
                }
            }
        }

        rule.onNodeWithText(str(R.string.common_cancel)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.common_save)).assertIsDisplayed()
    }

    @Test
    fun buttonsAreDisabledWhileSaving() {
        setSheet(initialName = "Mix", isSaving = true)

        rule.onNodeWithText(str(R.string.common_cancel)).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.createplaylist_creating)).assertIsNotEnabled()
    }

    @Test
    fun cancelCallsOnCancel() {
        setSheet(initialName = "Mix")

        rule.onNodeWithText(str(R.string.common_cancel)).performClick()

        assertEquals(1, cancelled)
    }
}
