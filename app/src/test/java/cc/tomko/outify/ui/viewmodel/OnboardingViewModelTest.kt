package cc.tomko.outify.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {

    @Test
    fun bundledClientIdIsUsedWhenNothingIsSaved() {
        assertTrue(isClientIdAvailable(saved = null, bundled = "from-build"))
    }

    @Test
    fun noClientIdWhenNothingIsSavedAndBuildHasNone() {
        assertFalse(isClientIdAvailable(saved = null, bundled = ""))
    }

    @Test
    fun savedClientIdIsEnoughWithoutOneInTheBuild() {
        assertTrue(isClientIdAvailable(saved = "saved", bundled = ""))
    }

    @Test
    fun emptySavedClientIdWinsOverTheBundledOne() {
        assertFalse(isClientIdAvailable(saved = "", bundled = "from-build"))
    }
}
