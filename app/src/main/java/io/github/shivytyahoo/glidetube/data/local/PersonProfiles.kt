package io.github.shivytyahoo.glidetube.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.shivytyahoo.glidetube.utils.StartupSentinel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One person on this device: a name and, optionally, a PIN that gates switching to them. */
@Serializable
data class PersonProfile(
    val id: String,
    val name: String,
    val pinHash: String? = null,
    val createdAtMs: Long = 0L,
)

/** Who exists, who is active, and whether opening the app asks who's watching. */
@Serializable
data class PersonProfilesState(
    val persons: List<PersonProfile> = emptyList(),
    val activeId: String? = null,
    val askOnOpen: Boolean = true,
)

/**
 * People on this device. Each person keeps their own settings files under `profiles/<id>/`;
 * the files the app actually reads always stay in their normal place, so switching is a
 * journaled file swap and then a restart: the stores open the new person's files fresh.
 * Downloads, history and the library are not swapped — the database stays open for the whole
 * process, so swapping it would need a close and reopen no device test here can verify.
 */
@Singleton
class PersonProfiles
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val PREFS_NAME = "person_profiles"
            private const val KEY_STATE = "state"
            private const val PIN_SALT = "youglidetube-person-pin-v1"
            private const val PIN_LENGTH = 4
            private val json = Json { ignoreUnknownKeys = true }

            /**
             * Finishes or abandons a swap the last process died in the middle of. Runs first in
             * `GlideApplication.onCreate`, before any preferences store opens. The journal is the
             * truth: a swap whose backup never ran leaves the live files untouched, so it is
             * abandoned; any swap past that point is completed towards its target, and the
             * active person is set to match the files that ended up live.
             */
            fun recoverInterruptedSwap(context: Context) {
                val dir = profilesDir(context)
                val journalFile = File(dir, "swap.json")
                val journal =
                    runCatching { json.decodeFromString(SwapJournal.serializer(), journalFile.readText()) }
                        .getOrNull()
                        ?: run {
                            File(dir, "swap.json.tmp").delete()
                            return
                        }
                val complete = journal.phase == SwapJournal.PHASE_BACKED_UP || journal.fromId.isEmpty()
                if (complete) {
                    applyPhaseB(context, journal.toId)
                    val state = readState(context)
                    writeState(context, state.copy(activeId = journal.toId), commit = true)
                }
                journalFile.delete()
            }

            private fun profilesDir(context: Context): File = File(context.filesDir, "profiles")

            private fun datastoreDir(context: Context): File = File(context.filesDir, "datastore")

            private fun readState(context: Context): PersonProfilesState {
                val text =
                    context
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_STATE, null)
                        ?: return PersonProfilesState()
                return runCatching { json.decodeFromString(PersonProfilesState.serializer(), text) }
                    .getOrDefault(PersonProfilesState())
            }

            private fun writeState(
                context: Context,
                state: PersonProfilesState,
                commit: Boolean = false,
            ) {
                val editor =
                    context
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_STATE, json.encodeToString(PersonProfilesState.serializer(), state))
                if (commit) editor.commit() else editor.apply()
            }

            // Live files out to the old person's folder, so switching back restores them.
            private fun copyLiveToPerson(
                context: Context,
                personId: String,
            ) {
                val target = File(profilesDir(context), personId).apply { mkdirs() }
                datastoreDir(context)
                    .listFiles { file -> file.name.endsWith(".preferences_pb") }
                    ?.forEach { file -> file.copyTo(File(target, file.name), overwrite = true) }
            }

            // Live files away, then the new person's files in; an empty folder means defaults.
            private fun applyPhaseB(
                context: Context,
                personId: String,
            ) {
                val live = datastoreDir(context)
                live.listFiles { file -> file.name.endsWith(".preferences_pb") }?.forEach { it.delete() }
                File(profilesDir(context), personId)
                    .listFiles { file -> file.name.endsWith(".preferences_pb") }
                    ?.forEach { file -> file.copyTo(File(live, file.name), overwrite = true) }
            }

            private fun writeJournal(
                context: Context,
                journal: SwapJournal,
            ) {
                val dir = profilesDir(context).apply { mkdirs() }
                val tmp = File(dir, "swap.json.tmp")
                tmp.writeText(json.encodeToString(SwapJournal.serializer(), journal))
                tmp.renameTo(File(dir, "swap.json"))
            }

            /** A PIN is four digits, and only its hash is ever stored. */
            fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it.isDigit() }

            fun hashPin(
                personId: String,
                pin: String,
            ): String {
                val bytes =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest("$personId:$pin:$PIN_SALT".toByteArray(Charsets.UTF_8))
                return bytes.joinToString("") { "%02x".format(it) }
            }
        }

        @Serializable
        private data class SwapJournal(
            val fromId: String,
            val toId: String,
            val phase: String,
        ) {
            companion object {
                const val PHASE_STARTED = "started"
                const val PHASE_BACKED_UP = "backed_up"
            }
        }

        private val _state = MutableStateFlow(readState(context))
        val state: StateFlow<PersonProfilesState> = _state.asStateFlow()

        /** True when opening the app should ask who's watching. Set once per process. */
        @Volatile
        var gatePassedThisProcess: Boolean = false

        /** More than one person, the gate on, and nobody has picked yet this launch. */
        fun shouldShowGate(): Boolean {
            val current = _state.value
            return current.persons.size > 1 && current.askOnOpen && !gatePassedThisProcess
        }

        fun checkPin(
            person: PersonProfile,
            pin: String,
        ): Boolean = person.pinHash != null && hashPin(person.id, pin) == person.pinHash

        /** A new person starts with the settings on screen now; their own files are backed up on the first switch away. */
        fun addPerson(name: String): PersonProfile {
            val person = PersonProfile(id = UUID.randomUUID().toString(), name = name.trim(), createdAtMs = System.currentTimeMillis())
            val current = _state.value
            val next = current.copy(persons = current.persons + person, activeId = current.activeId ?: person.id)
            writeState(context, next)
            _state.value = next
            return person
        }

        /**
         * Removes a person and their settings files. Removing the active person moves to the
         * next one, which is a file swap like any other switch; removing anyone else, or the
         * last person, needs no swap — the live files stay as they are.
         */
        suspend fun removePerson(personId: String) {
            val current = readState(context)
            val person = current.persons.firstOrNull { it.id == personId } ?: return
            val remaining = current.persons - person
            val wasActive = current.activeId == personId
            val nextActive = if (wasActive) remaining.firstOrNull()?.id else current.activeId
            val next = current.copy(persons = remaining, activeId = nextActive)
            writeState(context, next, commit = true)
            _state.value = next
            runCatching { File(profilesDir(context), personId).deleteRecursively() }
            if (wasActive && nextActive != null) {
                swapTo(nextActive, backupFromId = null)
            }
        }

        fun setAskOnOpen(ask: Boolean) {
            val next = _state.value.copy(askOnOpen = ask)
            writeState(context, next)
            _state.value = next
        }

        fun setPin(
            personId: String,
            pin: String?,
        ) {
            if (pin != null && !isValidPin(pin)) return
            updatePerson(personId) { person -> person.copy(pinHash = pin?.let { hashPin(person.id, it) }) }
        }

        private fun updatePerson(
            personId: String,
            change: (PersonProfile) -> PersonProfile,
        ) {
            val current = _state.value
            val next =
                current.copy(
                    persons =
                        current.persons.map { person ->
                            if (person.id == personId) change(person) else person
                        },
                )
            writeState(context, next)
            _state.value = next
        }

        /**
         * Swaps the settings files to [personId]'s and restarts, so the stores open their files
         * fresh. The restart is part of the switch: the stores already open hold the old
         * person's values in memory until the process ends.
         */
        suspend fun switchPerson(personId: String) =
            withContext(Dispatchers.IO) {
                val current = readState(context)
                val fromId = current.activeId
                if (fromId == personId) return@withContext
                current.persons.firstOrNull { it.id == personId } ?: return@withContext
                swapTo(personId, backupFromId = fromId)
            }

        // Journaled in two phases so an interruption can only leave a complete set live: the
        // old person's files are backed up first, then the live files are replaced. A swap
        // with no one to back up (the old person is being deleted) skips the backup.
        private suspend fun swapTo(
            personId: String,
            backupFromId: String?,
        ) = withContext(Dispatchers.IO) {
            writeJournal(context, SwapJournal(backupFromId.orEmpty(), personId, SwapJournal.PHASE_STARTED))
            if (backupFromId != null) copyLiveToPerson(context, backupFromId)
            writeJournal(context, SwapJournal(backupFromId.orEmpty(), personId, SwapJournal.PHASE_BACKED_UP))
            applyPhaseB(context, personId)
            val current = readState(context)
            writeState(context, current.copy(activeId = personId), commit = true)
            File(profilesDir(context), "swap.json").delete()
            StartupSentinel.markRestarted(context)
            StartupSentinel.restart(context)
        }
    }
