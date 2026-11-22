package com.meetily.mobile

import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingLanguage
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.whisper.NemoModels
import com.meetily.mobile.whisper.TranscriptionModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingLanguageTest {

    // --- Persistence ---------------------------------------------------------

    @Test
    fun json_roundTripsTheLanguageAndTranslation() {
        val meeting = Meeting(id = "m1", title = "Planung", createdAtMs = 1L).apply {
            language = "de"
            translated = true
        }
        val restored = Meeting.fromJson(meeting.toJson())
        assertEquals("de", restored.language)
        assertTrue(restored.translated)
        assertEquals("en", restored.transcriptLanguage())
    }

    @Test
    fun json_meetingsSavedBeforeTheLanguageExistedReadAsUnknown() {
        val legacy = org.json.JSONObject()
            .put("id", "old")
            .put("title", "Older meeting")
            .put("createdAtMs", 1L)
            .put("segments", org.json.JSONArray())
        val restored = Meeting.fromJson(legacy)
        assertNull(restored.language)
        assertFalse(restored.translated)
        assertNull(restored.transcriptLanguage())
    }

    @Test
    fun json_omitsTheLanguageWhenUnknownSoOldFilesStayTheSame() {
        val plain = Meeting(id = "m2", title = "Plain", createdAtMs = 0L).toJson()
        assertFalse(plain.has("language"))
        assertFalse(plain.has("translated"))
    }

    @Test
    fun json_aStoredValueThatIsNotALanguageReadsAsUnknown() {
        val odd = org.json.JSONObject()
            .put("id", "odd")
            .put("language", "German!!")
        assertNull(Meeting.fromJson(odd).language)
        val locale = org.json.JSONObject().put("id", "loc").put("language", "pt-BR")
        assertEquals("pt", Meeting.fromJson(locale).language)
    }

    @Test
    fun merge_aScreenThatNeverTouchedTheLanguageTakesTheStoredOne() {
        val base = Meeting(id = "m", title = "T", createdAtMs = 0L)
        val ours = base.copy(title = "Renamed")
        // A re-transcription saved meanwhile.
        val disk = base.copy(language = "fr", translated = false)
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals("Renamed", merged.title)
        assertEquals("fr", merged.language)
    }

    @Test
    fun queue_aJobKeepsItsLanguageWhenItReplacesAnEarlierOne() {
        val first = JobQueue.Job(JobQueue.KIND_CHECK, "m", "base", 1L)
        val second = JobQueue.Job(JobQueue.KIND_CHECK, "m", "base", 2L, language = "de")
        val queued = JobQueue.add(JobQueue.add(emptyList(), first), second)
        assertEquals(listOf("de"), queued.map { it.language })
    }

    // --- Codes ---------------------------------------------------------------

    @Test
    fun normalize_reducesToTheBaseCodeAndRejectsNonCodes() {
        assertEquals("de", MeetingLanguage.normalize("de-DE"))
        assertEquals("de", MeetingLanguage.normalize(" DE_at "))
        assertEquals("he", MeetingLanguage.normalize("iw"))
        assertEquals("id", MeetingLanguage.normalize("in"))
        assertEquals("yue", MeetingLanguage.normalize("yue"))
        assertNull(MeetingLanguage.normalize("auto"))
        assertNull(MeetingLanguage.normalize(""))
        assertNull(MeetingLanguage.normalize(null))
        assertNull(MeetingLanguage.normalize("same"))
        assertNull(MeetingLanguage.normalize("d3"))
    }

    @Test
    fun everyChoiceHasAnEnglishName() {
        for (code in MeetingLanguage.CHOICES) {
            val name = MeetingLanguage.englishName(code)
            assertTrue("no name for $code", !name.isNullOrBlank())
        }
        assertEquals("German", MeetingLanguage.englishName("de"))
        assertNull(MeetingLanguage.englishName(null))
    }

    // --- What each engine is told -------------------------------------------

    @Test
    fun pin_anOverrideWinsAndAutoOverridesAPinnedSetting() {
        assertNull(MeetingLanguage.pinFor(null, "auto"))
        assertEquals("de", MeetingLanguage.pinFor(null, "de"))
        assertEquals("fr", MeetingLanguage.pinFor("fr", "de"))
        assertNull(MeetingLanguage.pinFor("auto", "de"))
        // Blank is "no override", as a job queued before the field existed.
        assertEquals("de", MeetingLanguage.pinFor("", "de"))
    }

    @Test
    fun whisper_englishOnlyModelsAreAlwaysToldEnglish() {
        assertEquals("auto", MeetingLanguage.whisperArgument(null, englishOnly = false))
        assertEquals("de", MeetingLanguage.whisperArgument("de", englishOnly = false))
        assertEquals("en", MeetingLanguage.whisperArgument("de", englishOnly = true))
    }

    @Test
    fun nemotron_getsItsOwnCodesAndAutoForTheRest() {
        val nemotron = NemoModels.byKeyOrNull("nemotron-3.5")!!
        assertEquals("auto", nemotron.languageOptionFor(null))
        assertEquals("de", nemotron.languageOptionFor("de"))
        // Whisper's Norwegian is Nemotron's Bokmål.
        assertEquals("nb", nemotron.languageOptionFor("no"))
        // Not among its 40 locales: asked for auto rather than for nothing.
        assertEquals("auto", nemotron.languageOptionFor("id"))
        assertTrue(MeetingLanguage.nemotronSupports("ja"))
        assertFalse(MeetingLanguage.nemotronSupports("ms"))
    }

    @Test
    fun parakeet_takesNoLanguageAtAll() {
        for (key in listOf("parakeet-tdt-v2", "parakeet-tdt-v3", "parakeet-ultra")) {
            assertNull(NemoModels.byKeyOrNull(key)!!.languageOptionFor("de"))
        }
    }

    @Test
    fun acceptsLanguage_onlyMultilingualWhisperAndNemotron35() {
        assertTrue(TranscriptionModels.acceptsLanguage("large-v3-turbo-q5_0"))
        assertTrue(TranscriptionModels.acceptsLanguage("base"))
        assertTrue(TranscriptionModels.acceptsLanguage("nemotron-3.5"))
        assertFalse(TranscriptionModels.acceptsLanguage("base.en"))
        assertFalse(TranscriptionModels.acceptsLanguage("parakeet-tdt-v3"))
        assertFalse(TranscriptionModels.acceptsLanguage("parakeet-ultra"))
        assertFalse(TranscriptionModels.acceptsLanguage("nemotron-en"))
    }

    // --- What a run records --------------------------------------------------

    @Test
    fun recorded_whisperKeepsWhatItDetectedOrWasTold() {
        assertNull(MeetingLanguage.whisperRecorded("auto"))
        assertEquals("ja", MeetingLanguage.whisperRecorded("ja"))
        assertEquals("en", MeetingLanguage.whisperRecorded("en"))
    }

    @Test
    fun recorded_nemoKeepsThePinOrNothing() {
        assertNull(MeetingLanguage.nemoRecorded(null, englishOnly = false, languagePrompt = true))
        assertEquals(
            "de", MeetingLanguage.nemoRecorded("de", englishOnly = false, languagePrompt = true)
        )
        assertEquals(
            "en", MeetingLanguage.nemoRecorded(null, englishOnly = true, languagePrompt = true)
        )
        // Parakeet takes no hint: the pin is still the user's word for it.
        assertEquals(
            "de", MeetingLanguage.nemoRecorded("de", englishOnly = false, languagePrompt = false)
        )
    }

    @Test
    fun recorded_nemotronOnAutoForAnUnsupportedPinRecordsNothing() {
        // Indonesian and Malay are not among Nemotron's locales, so it ran
        // on auto and what it decoded is unknown.
        assertNull(MeetingLanguage.nemoRecorded("id", englishOnly = false, languagePrompt = true))
        assertNull(MeetingLanguage.nemoRecorded("ms", englishOnly = false, languagePrompt = true))
        // Norwegian is supported (as Bokmål); the meeting keeps Whisper's code.
        assertEquals(
            "no", MeetingLanguage.nemoRecorded("no", englishOnly = false, languagePrompt = true)
        )
    }

    @Test
    fun acceptsLanguage_nemotronOnlyForItsOwnLocales() {
        assertTrue(TranscriptionModels.acceptsLanguage("nemotron-3.5", "de"))
        assertFalse(TranscriptionModels.acceptsLanguage("nemotron-3.5", "id"))
        assertFalse(TranscriptionModels.acceptsLanguage("nemotron-3.5", "ms"))
        // Whisper takes every language in the list.
        assertTrue(TranscriptionModels.acceptsLanguage("large-v3-turbo-q5_0", "id"))
        assertFalse(TranscriptionModels.acceptsLanguage("parakeet-tdt-v3", "de"))
        assertFalse(TranscriptionModels.acceptsLanguage("base.en", "de"))
        for (code in MeetingLanguage.CHOICES) {
            assertEquals(
                code,
                MeetingLanguage.nemotronSupports(code),
                TranscriptionModels.acceptsLanguage("nemotron-3.5", code)
            )
        }
    }

    @Test
    fun detectsLanguage_onlyMultilingualWhisperReportsWhatItHeard() {
        assertTrue(TranscriptionModels.detectsLanguage("large-v3-turbo-q5_0"))
        assertTrue(TranscriptionModels.detectsLanguage("base"))
        assertFalse(TranscriptionModels.detectsLanguage("base.en"))
        assertFalse(TranscriptionModels.detectsLanguage("nemotron-3.5"))
        assertFalse(TranscriptionModels.detectsLanguage("parakeet-tdt-v3"))
    }

    // --- English-only heuristics -------------------------------------------

    @Test
    fun heuristics_runForEnglishAndUnknownOnly() {
        assertTrue(MeetingLanguage.englishHeuristics(null))
        assertTrue(MeetingLanguage.englishHeuristics("en"))
        assertTrue(MeetingLanguage.englishHeuristics("en-GB"))
        assertFalse(MeetingLanguage.englishHeuristics("de"))
        // A German meeting translated to English is English text.
        assertTrue(
            MeetingLanguage.englishHeuristics(MeetingLanguage.transcriptLanguage("de", true))
        )
    }
}
