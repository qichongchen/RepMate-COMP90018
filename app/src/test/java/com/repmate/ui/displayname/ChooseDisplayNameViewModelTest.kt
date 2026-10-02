package com.repmate.ui.displayname

import com.repmate.data.repo.ClaimResult
import com.repmate.data.repo.ClaimStatus
import com.repmate.data.repo.NameAvailability
import com.repmate.data.repo.NameCheck
import com.repmate.data.repo.UsernameRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChooseDisplayNameViewModelTest {
    private val repository = FakeUsernameRepository()

    @Before
    fun setUp() {
        // Not a fresh dispatcher per test: runTest's scheduler must be the one that runs viewModelScope.
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(mode: ChooseNameMode = ChooseNameMode.GATE): ChooseDisplayNameViewModel {
        kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ChooseDisplayNameViewModel(repository).also { it.start(mode) }
    }

    /** True if a `finished` event is waiting. The channel is buffered, so it can be read after the fact. */
    private suspend fun ChooseDisplayNameViewModel.finishedEvent(): Boolean = withTimeoutOrNull(1) { finished.first() } != null

    private fun TestScope.settle(vm: ChooseDisplayNameViewModel) {
        advanceTimeBy(AVAILABILITY_DEBOUNCE_MS + 1)
        advanceUntilIdle()
    }

    @Test
    fun `empty field is idle with continue disabled`() =
        runTest {
            val vm = viewModel()
            settle(vm)

            assertEquals(NameHint.IDLE, vm.uiState.value.hint)
            assertFalse(vm.uiState.value.canContinue)
        }

    @Test
    fun `typing shows checking then available after the debounce`() =
        runTest {
            repository.availability = NameAvailability.Available
            val vm = viewModel()

            vm.onNameChanged("Alex")
            assertEquals(NameHint.CHECKING, vm.uiState.value.hint)
            advanceTimeBy(AVAILABILITY_DEBOUNCE_MS - 50)
            assertEquals("no request before the debounce elapses", 0, repository.checkCalls.size)

            settle(vm)

            assertEquals(NameHint.AVAILABLE, vm.uiState.value.hint)
            assertEquals(listOf("Alex"), repository.checkCalls)
            assertTrue(vm.uiState.value.canContinue)
        }

    @Test
    fun `only the last name in a burst of typing is checked`() =
        runTest {
            val vm = viewModel()

            vm.onNameChanged("Ale")
            advanceTimeBy(100)
            vm.onNameChanged("Alex")
            advanceTimeBy(100)
            vm.onNameChanged("Alexa")
            settle(vm)

            assertEquals(listOf("Alexa"), repository.checkCalls)
        }

    @Test
    fun `taken name shows taken and disables continue`() =
        runTest {
            repository.availability = NameAvailability.Taken
            val vm = viewModel()

            vm.onNameChanged("coolguy")
            settle(vm)

            assertEquals(NameHint.TAKEN, vm.uiState.value.hint)
            assertFalse(vm.uiState.value.canContinue)
        }

    @Test
    fun `mine enables continue`() =
        runTest {
            repository.availability = NameAvailability.Mine
            val vm = viewModel(ChooseNameMode.RENAME)

            vm.onNameChanged("Alice")
            settle(vm)

            assertEquals(NameHint.MINE, vm.uiState.value.hint)
            assertTrue(vm.uiState.value.canContinue)
        }

    @Test
    fun `bad format is shown immediately and never sent to the server`() =
        runTest {
            val vm = viewModel()

            vm.onNameChanged("al-ice")
            assertEquals(NameHint.INVALID_FORMAT, vm.uiState.value.hint)
            settle(vm)

            assertEquals(NameHint.INVALID_FORMAT, vm.uiState.value.hint)
            assertEquals(emptyList<String>(), repository.checkCalls)
            assertFalse(vm.uiState.value.canContinue)
        }

    @Test
    fun `reserved and guest names get their own hint`() =
        runTest {
            val vm = viewModel()

            vm.onNameChanged("admin")
            assertEquals(NameHint.UNAVAILABLE, vm.uiState.value.hint)
            vm.onNameChanged("Guest99")
            assertEquals(NameHint.UNAVAILABLE, vm.uiState.value.hint)
            settle(vm)

            assertEquals(emptyList<String>(), repository.checkCalls)
        }

    @Test
    fun `offline check shows cant-check and disables continue`() =
        runTest {
            repository.availability = NameAvailability.Error
            val vm = viewModel()

            vm.onNameChanged("Alex")
            settle(vm)

            assertEquals(NameHint.CANT_CHECK, vm.uiState.value.hint)
            assertFalse(vm.uiState.value.canContinue)
        }

    @Test
    fun `a slow answer for old text does not overwrite the current hint`() =
        runTest {
            val slow = CompletableDeferred<NameAvailability>()
            repository.availabilityFor = { name -> if (name == "Alex") slow.await() else NameAvailability.Taken }
            val vm = viewModel()

            vm.onNameChanged("Alex")
            advanceTimeBy(AVAILABILITY_DEBOUNCE_MS + 1)
            vm.onNameChanged("Alexa")
            settle(vm)
            slow.complete(NameAvailability.Available)
            advanceUntilIdle()

            assertEquals("Alexa", vm.uiState.value.name)
            assertEquals(NameHint.TAKEN, vm.uiState.value.hint)
        }

    @Test
    fun `continue claims the name and finishes`() =
        runTest {
            repository.availability = NameAvailability.Available
            repository.claimResult = ClaimResult.Success
            val vm = viewModel()
            vm.onNameChanged("Alex")
            settle(vm)

            vm.onContinueClicked()
            advanceUntilIdle()

            assertEquals(listOf("Alex"), repository.claimCalls)
            assertTrue("finished should fire once the claim succeeds", vm.finishedEvent())
            assertFalse(vm.uiState.value.isSubmitting)
        }

    @Test
    fun `continue shows a loading state while the claim runs and blocks a second tap`() =
        runTest {
            repository.availability = NameAvailability.Available
            val gate = CompletableDeferred<ClaimResult>()
            repository.claimGate = gate
            val vm = viewModel()
            vm.onNameChanged("Alex")
            settle(vm)

            vm.onContinueClicked()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isSubmitting)
            assertFalse(vm.uiState.value.canContinue)
            vm.onContinueClicked()
            advanceUntilIdle()
            assertEquals("a second tap must not start a second claim", 1, repository.claimCalls.size)

            gate.complete(ClaimResult.Success)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isSubmitting)
        }

    @Test
    fun `a lost race reads as taken`() =
        runTest {
            repository.availability = NameAvailability.Available
            repository.claimResult = ClaimResult.Taken
            val vm = viewModel()
            vm.onNameChanged("coolguy")
            settle(vm)
            assertEquals(NameHint.AVAILABLE, vm.uiState.value.hint)

            vm.onContinueClicked()
            advanceUntilIdle()

            assertEquals(NameHint.TAKEN, vm.uiState.value.hint)
            assertFalse(vm.uiState.value.isSubmitting)
            assertFalse(vm.uiState.value.canContinue)
            assertFalse("a lost race must not finish", vm.finishedEvent())
        }

    @Test
    fun `an offline claim shows cant-check and does not finish`() =
        runTest {
            repository.availability = NameAvailability.Available
            repository.claimResult = ClaimResult.Error
            val vm = viewModel()
            vm.onNameChanged("Alex")
            settle(vm)

            vm.onContinueClicked()
            advanceUntilIdle()

            assertEquals(NameHint.CANT_CHECK, vm.uiState.value.hint)
            assertFalse(vm.uiState.value.isSubmitting)
            assertFalse("an offline claim must not finish", vm.finishedEvent())
        }

    @Test
    fun `a claim rejected as invalid shows the matching hint`() =
        runTest {
            repository.availability = NameAvailability.Available
            repository.claimResult = ClaimResult.Invalid(NameCheck.RESERVED)
            val vm = viewModel()
            vm.onNameChanged("Alex")
            settle(vm)

            vm.onContinueClicked()
            advanceUntilIdle()

            assertEquals(NameHint.UNAVAILABLE, vm.uiState.value.hint)
        }

    @Test
    fun `gate prefills the sanitised provider name and checks it`() =
        runTest {
            repository.suggestionSource = "José García"
            repository.availability = NameAvailability.Available

            val vm = viewModel(ChooseNameMode.GATE)
            settle(vm)

            assertEquals("Jose Garcia", vm.uiState.value.name)
            assertEquals(NameHint.AVAILABLE, vm.uiState.value.hint)
        }

    @Test
    fun `gate with no provider name starts empty`() =
        runTest {
            repository.suggestionSource = null

            val vm = viewModel(ChooseNameMode.GATE)
            settle(vm)

            assertEquals("", vm.uiState.value.name)
            assertEquals(NameHint.IDLE, vm.uiState.value.hint)
        }

    @Test
    fun `rename prefills the current name and re-confirming it finishes without a write`() =
        runTest {
            repository.currentName = "Alice"
            repository.availability = NameAvailability.Mine
            val vm = viewModel(ChooseNameMode.RENAME)
            settle(vm)

            assertEquals("Alice", vm.uiState.value.name)
            assertEquals(NameHint.MINE, vm.uiState.value.hint)
            vm.onContinueClicked()
            advanceUntilIdle()

            assertTrue("finished should fire without a write", vm.finishedEvent())
            assertEquals("no write for an unchanged name", emptyList<String>(), repository.claimCalls)
        }

    @Test
    fun `case-only change counts as mine and is claimed`() =
        runTest {
            repository.currentName = "alice"
            repository.availability = NameAvailability.Mine
            repository.claimResult = ClaimResult.Success
            val vm = viewModel(ChooseNameMode.RENAME)
            settle(vm)

            vm.onNameChanged("ALICE")
            settle(vm)
            assertTrue(vm.uiState.value.canContinue)
            vm.onContinueClicked()
            advanceUntilIdle()

            assertEquals(listOf("ALICE"), repository.claimCalls)
        }

    @Test
    fun `start is applied once so a recomposition cannot overwrite typing`() =
        runTest {
            repository.suggestionSource = "Alex"
            val vm = viewModel(ChooseNameMode.GATE)
            vm.onNameChanged("Typed_By_User")

            vm.start(ChooseNameMode.GATE)

            assertEquals("Typed_By_User", vm.uiState.value.name)
        }

    @Test
    fun `only the gate hides the back button`() {
        assertFalse(ChooseNameMode.GATE.showsBackButton)
        assertTrue(ChooseNameMode.UPGRADE.showsBackButton)
        assertTrue(ChooseNameMode.RENAME.showsBackButton)
    }

    @Test
    fun `mode argument parsing degrades to the gate`() {
        assertEquals(ChooseNameMode.RENAME, ChooseNameMode.fromArgument("RENAME"))
        assertEquals(ChooseNameMode.UPGRADE, ChooseNameMode.fromArgument("upgrade"))
        assertEquals(ChooseNameMode.GATE, ChooseNameMode.fromArgument(null))
        assertEquals(ChooseNameMode.GATE, ChooseNameMode.fromArgument("nonsense"))
    }
}

private class FakeUsernameRepository : UsernameRepository {
    var availability: NameAvailability = NameAvailability.Available
    var availabilityFor: (suspend (String) -> NameAvailability)? = null
    var claimResult: ClaimResult = ClaimResult.Success
    var claimGate: CompletableDeferred<ClaimResult>? = null
    var suggestionSource: String? = null
    var currentName: String? = null

    val checkCalls = mutableListOf<String>()
    val claimCalls = mutableListOf<String>()

    override suspend fun checkAvailability(name: String): NameAvailability {
        checkCalls += name
        return availabilityFor?.invoke(name) ?: availability
    }

    override suspend fun claim(name: String): ClaimResult {
        claimCalls += name
        return claimGate?.await() ?: claimResult
    }

    override suspend fun claimStatus(): ClaimStatus = ClaimStatus.UNKNOWN

    override suspend fun syncAuthNameIfClaimed() = Unit

    override fun authNameForSuggestion(): String? = suggestionSource

    override fun currentDisplayName(): String? = currentName
}
