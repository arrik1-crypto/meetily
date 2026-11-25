package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.StorageUsage
import com.meetily.mobile.data.StorageUsage.Area
import com.meetily.mobile.data.StorageUsage.Entry
import com.meetily.mobile.data.StorageUsage.Family
import com.meetily.mobile.data.StorageUsage.Kind
import com.meetily.mobile.data.StorageUsage.Root
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Storage screen's per-area totals, largest meetings and unused models. */
class StorageUsageTest {

    private fun files(path: String) = StorageUsage.areaFor(Root.FILES, path)

    @Test
    fun meetingFilesLandInTheirAreas() {
        assertEquals(Area.AUDIO, files("audio/1b2c.aac"))
        assertEquals(Area.AUDIO, files("audio/1b2c.m4a"))
        assertEquals(Area.PHOTOS_ATTACHMENTS, files("photos/1b2c_17000.jpg"))
        assertEquals(Area.PHOTOS_ATTACHMENTS, files("attachments/1b2c_17000_deck.pdf"))
        assertEquals(Area.TRANSCRIPTS, files("meetings/1b2c.json"))
        assertEquals(Area.TRANSCRIPTS, files("meetings/.active"))
        assertEquals(Area.TRANSCRIPTS, files("transcript-drafts/1b2c.json"))
        assertEquals(Area.TRANSCRIPTS, files("speaker-suggestions/1b2c.json"))
    }

    @Test
    fun clipsAndWaveformsAreCachesNotAudio() {
        // Both are rebuilt from the audio, so they must not inflate it.
        assertEquals(Area.CACHES, files("audio/clips/recap-clip-1b2c-17000.wav"))
        assertEquals(Area.CACHES, files("audio/1b2c.aac.peaks"))
        assertEquals(Area.CACHES, StorageUsage.areaFor(Root.CACHE, "shares/meeting.md"))
        assertEquals(Area.CACHES, StorageUsage.areaFor(Root.CODE_CACHE, "anything"))
    }

    @Test
    fun modelsAreCountedPerFamily() {
        assertEquals(Area.WHISPER_MODELS, files("whisper-models/ggml-base.en.bin"))
        // A resumable part file is still that family's download.
        assertEquals(Area.WHISPER_MODELS, files("whisper-models/ggml-small.bin.part"))
        assertEquals(Area.NEMO_MODELS, files("nemo-models/parakeet-tdt-v3/encoder.int8.onnx"))
        assertEquals(Area.SPEAKER_MODELS, files("diarization-models/nemo_en_titanet_small.onnx"))
        assertEquals(Area.LLM_MODELS, files("llm-models/qwen2.5-1.5b-instruct-q4_k_m.gguf"))
        assertEquals(Area.LLM_MODELS, files("litert-models/old.litertlm"))
        for (area in listOf(
            Area.WHISPER_MODELS, Area.NEMO_MODELS, Area.SPEAKER_MODELS, Area.LLM_MODELS
        )) {
            assertTrue(StorageUsage.isModels(area))
        }
        assertFalse(StorageUsage.isModels(Area.AUDIO))
        assertFalse(StorageUsage.isModels(Area.CACHES))
    }

    @Test
    fun anythingElseIsOther() {
        assertEquals(Area.OTHER, files("voice_profiles.json"))
        assertEquals(Area.OTHER, files("voiceprint-audio/Alex.pcm"))
        assertEquals(Area.OTHER, files("crashlogs/crash-1.txt"))
        assertEquals(Area.OTHER, StorageUsage.areaFor(Root.NO_BACKUP, "vad/ggml-silero-v6.2.0.bin"))
        // A file named like a directory is not inside it.
        assertEquals(Area.OTHER, files("audio"))
        assertEquals(Area.OTHER, files("audio/"))
        assertEquals(Area.OTHER, files(""))
        // Separators are normalised, and a leading slash is tolerated.
        assertEquals(Area.AUDIO, files("/audio/x.aac"))
        assertEquals(Area.AUDIO, files("audio\\x.aac"))
    }

    @Test
    fun totalsAddUpPerAreaAndListEveryArea() {
        val totals = StorageUsage.totals(
            listOf(
                Entry(Root.FILES, "audio/a.aac", 1_000),
                Entry(Root.FILES, "audio/b.aac", 2_500),
                Entry(Root.FILES, "audio/a.aac.peaks", 40),
                Entry(Root.FILES, "audio/clips/recap-clip-a-1.wav", 600),
                Entry(Root.CACHE, "shares/x.md", 60),
                Entry(Root.FILES, "meetings/a.json", 300),
                Entry(Root.FILES, "photos/a_1.jpg", 700),
                Entry(Root.FILES, "nemo-models/parakeet-tdt-v2/encoder.int8.onnx", 9_000),
                Entry(Root.FILES, "nemo-models/parakeet-tdt-v2/tokens.txt", 10),
                // Empty and unreadable (negative) sizes are ignored.
                Entry(Root.FILES, "audio/empty.aac", 0),
                Entry(Root.FILES, "audio/gone.aac", -1)
            )
        )
        assertEquals(Area.values().toList(), totals.keys.toList())
        assertEquals(3_500L, totals[Area.AUDIO])
        assertEquals(700L, totals[Area.CACHES])
        assertEquals(300L, totals[Area.TRANSCRIPTS])
        assertEquals(700L, totals[Area.PHOTOS_ATTACHMENTS])
        assertEquals(9_010L, totals[Area.NEMO_MODELS])
        assertEquals(0L, totals[Area.WHISPER_MODELS])
        assertEquals(0L, totals[Area.OTHER])
        assertEquals(14_210L, totals.values.sum())
    }

    @Test
    fun totalsOfNothingAreAllZero() {
        val totals = StorageUsage.totals(emptyList())
        assertEquals(Area.values().size, totals.size)
        assertTrue(totals.values.all { it == 0L })
    }

    @Test
    fun largestMeetingsAreByAudioSizeWithoutAudiolessOnes() {
        val list = listOf(
            StorageUsage.MeetingAudio("a", "Standup", 10, hasTranscript = true),
            StorageUsage.MeetingAudio("b", "Board", 300, hasTranscript = true),
            StorageUsage.MeetingAudio("c", "Notes only", 0, hasTranscript = false),
            StorageUsage.MeetingAudio("d", "Retro", 300, hasTranscript = false),
            StorageUsage.MeetingAudio("e", "1:1", 50, hasTranscript = true)
        )
        val top = StorageUsage.largestByAudio(list, limit = 3)
        // Equal sizes keep a stable order by id.
        assertEquals(listOf("b", "d", "e"), top.map { it.id })
        assertEquals(listOf("b", "d", "e", "a"), StorageUsage.largestByAudio(list, 10).map { it.id })
        assertTrue(StorageUsage.largestByAudio(list, 0).isEmpty())
        assertTrue(StorageUsage.largestByAudio(list, -1).isEmpty())
    }

    @Test
    fun unusedModelsLeaveEverySelectionAlone() {
        val onDisk = listOf(
            StorageUsage.ModelOnDisk(Family.WHISPER, "base.en", "Base", 150),
            StorageUsage.ModelOnDisk(Family.WHISPER, "small-q5_1", "Small Q", 190),
            StorageUsage.ModelOnDisk(Family.NEMO, "parakeet-tdt-v3", "Parakeet v3", 660),
            StorageUsage.ModelOnDisk(Family.NEMO, "parakeet-tdt-v2", "Parakeet v2", 650),
            StorageUsage.ModelOnDisk(Family.SPEAKER, "resnet34-en", "ResNet34", 27),
            StorageUsage.ModelOnDisk(Family.SPEAKER, "titanet-en", "TitaNet", 40),
            StorageUsage.ModelOnDisk(Family.LLM, "qwen2.5-1.5b", "Qwen", 1_100),
            StorageUsage.ModelOnDisk(Family.LLM, "gemma", "Gemma", 2_000),
            // Nothing on disk: not offered for deletion.
            StorageUsage.ModelOnDisk(Family.LLM, "empty", "Empty", 0)
        )
        val unused = StorageUsage.unusedModels(
            onDisk,
            mapOf(
                // Whisper and NeMo share one selection: the transcription model,
                // plus whatever a queued check or the auto check will use.
                Kind.TRANSCRIPTION to setOf("parakeet-tdt-v3", "small-q5_1"),
                Kind.SPEAKER to setOf("resnet34-en"),
                Kind.LLM to setOf("qwen2.5-1.5b")
            )
        )
        // Largest first.
        assertEquals(listOf("gemma", "parakeet-tdt-v2", "base.en", "titanet-en"), unused.map { it.key })
    }

    @Test
    fun aKindWithNoSelectionHasEveryModelUnused() {
        val onDisk = listOf(StorageUsage.ModelOnDisk(Family.LLM, "qwen", "Qwen", 5))
        assertEquals(1, StorageUsage.unusedModels(onDisk, emptyMap()).size)
        // The same key in another kind's set does not protect it.
        assertEquals(
            1,
            StorageUsage.unusedModels(onDisk, mapOf(Kind.TRANSCRIPTION to setOf("qwen"))).size
        )
    }

    @Test
    fun unfinishedTranscriptsKeepTheModelTheyContinueWith() {
        fun m(id: String, model: String?, resumeMs: Long?, audio: String? = "$id.aac") =
            Meeting(id = id, title = id, createdAtMs = 1L).apply {
                transcriptModel = model
                transcriptResumeMs = resumeMs
                audioFile = audio
            }
        val meetings = listOf(
            // Stopped part-way: Continue needs its model.
            m("a", "medium", 2_530_000L),
            m("b", "parakeet-tdt-v3", 0L),
            // Finished: nothing to continue.
            m("c", "large-v3", null),
            // Unfinished, but its audio is gone: it can never be continued.
            m("d", "small", 60_000L, audio = null),
            // No model recorded: Continue uses the current one.
            m("e", null, 60_000L),
            m("f", " ", 60_000L)
        )
        val keys = StorageUsage.modelsToContinue(meetings) { it.audioFile != null }
        assertEquals(setOf("medium", "parakeet-tdt-v3"), keys)
        // Protected from "Delete unused models" once in the in-use set.
        val onDisk = listOf(
            StorageUsage.ModelOnDisk(Family.WHISPER, "medium", "Medium", 1_500),
            StorageUsage.ModelOnDisk(Family.WHISPER, "small", "Small", 480)
        )
        val unused = StorageUsage.unusedModels(onDisk, mapOf(Kind.TRANSCRIPTION to setOf("base.en") + keys))
        assertEquals(listOf("small"), unused.map { it.key })
    }

    @Test
    fun familiesMapToTheirKindAndArea() {
        assertEquals(Kind.TRANSCRIPTION, StorageUsage.kindOf(Family.WHISPER))
        assertEquals(Kind.TRANSCRIPTION, StorageUsage.kindOf(Family.NEMO))
        assertEquals(Kind.SPEAKER, StorageUsage.kindOf(Family.SPEAKER))
        assertEquals(Kind.LLM, StorageUsage.kindOf(Family.LLM))
        assertEquals(Area.NEMO_MODELS, StorageUsage.areaOf(Family.NEMO))
        assertEquals(Area.LLM_MODELS, StorageUsage.areaOf(Family.LLM))
    }
}
