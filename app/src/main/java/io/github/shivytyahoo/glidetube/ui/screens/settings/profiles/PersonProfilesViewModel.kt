package io.github.shivytyahoo.glidetube.ui.screens.settings.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.shivytyahoo.glidetube.data.local.PersonProfile
import io.github.shivytyahoo.glidetube.data.local.PersonProfiles
import io.github.shivytyahoo.glidetube.data.local.PersonProfilesState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** People on this device, and the gate that asks who's watching. */
@HiltViewModel
class PersonProfilesViewModel
    @Inject
    constructor(
        private val profiles: PersonProfiles,
    ) : ViewModel() {
        val state: StateFlow<PersonProfilesState> = profiles.state

        fun shouldShowGate(): Boolean = profiles.shouldShowGate()

        fun passGate() {
            profiles.gatePassedThisProcess = true
        }

        fun checkPin(
            person: PersonProfile,
            pin: String,
        ): Boolean = profiles.checkPin(person, pin)

        /** Picked from the gate or the settings list: the active person continues as-is, anyone else is switched to. */
        fun pickPerson(person: PersonProfile) {
            if (person.id == profiles.state.value.activeId) {
                passGate()
            } else {
                viewModelScope.launch { profiles.switchPerson(person.id) }
            }
        }

        fun addPerson(name: String) {
            if (name.isBlank()) return
            profiles.addPerson(name)
        }

        fun removePerson(personId: String) {
            viewModelScope.launch { profiles.removePerson(personId) }
        }

        fun setAskOnOpen(ask: Boolean) = profiles.setAskOnOpen(ask)

        fun setPin(
            personId: String,
            pin: String?,
        ) = profiles.setPin(personId, pin)
    }
