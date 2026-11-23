package com.meetily.mobile

import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordAlign
import com.meetily.mobile.data.WordAlign.Interval
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.data.WordTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-time words: what the aligner is given for a line, which lines and
 * which audio, and how its word tier maps back onto the line's words.
 */
class WordAlignTest {

    // --- Tokens -----------------------------------------------------------------

    @Test
    fun punctuationAtTheEdgesGoesAndCaseIsLowered() {
        assertEquals(listOf("hello"), WordAlign.tokens("Hello,"))
        assertEquals(listOf("world"), WordAlign.tokens("\"World!\""))
        assertEquals(listOf("why"), WordAlign.tokens("(why?)"))
    }

    @Test
    fun wordsOfPunctuationAloneHaveNoTokens() {
        assertEquals(emptyList<String>(), WordAlign.tokens("—"))
        assertEquals(emptyList<String>(), WordAlign.tokens("..."))
        assertEquals(emptyList<String>(), WordAlign.tokens("-"))
    }

    @Test
    fun contractionsKeepTheirApostropheWrittenAsTheDictionaryDoes() {
        assertEquals(listOf("don't"), WordAlign.tokens("don't"))
        assertEquals(listOf("don't"), WordAlign.tokens("Don’t"))
        assertEquals(listOf("don't"), WordAlign.tokens("donʼt"))
        assertEquals(listOf("we're"), WordAlign.tokens("We're,"))
        // A leading or trailing apostrophe is a quote mark.
        assertEquals(listOf("cause"), WordAlign.tokens("'cause"))
        assertEquals(listOf("dogs"), WordAlign.tokens("dogs'"))
    }

    @Test
    fun innerPunctuationSplitsAWordIntoDictionaryWords() {
        assertEquals(listOf("state", "of", "the", "art"), WordAlign.tokens("state-of-the-art"))
        assertEquals(listOf("and", "or"), WordAlign.tokens("and/or"))
        assertEquals(listOf("e", "g"), WordAlign.tokens("e.g."))
        assertEquals(listOf("co", "founder"), WordAlign.tokens("Co‑founder"))
    }

    @Test
    fun numbersAreKeptAsTheAlignerKeepsThem() {
        assertEquals(listOf("42"), WordAlign.tokens("42"))
        assertEquals(listOf("3", "30"), WordAlign.tokens("3:30"))
        assertEquals(listOf("100"), WordAlign.tokens("$100"))
        assertEquals(listOf("50"), WordAlign.tokens("50%"))
    }

    @Test
    fun accentsAreDecomposedAsTheDictionaryKeysAre() {
        // NFKD: e and a combining acute accent, which is part of the word.
        assertEquals(listOf("josé"), WordAlign.tokens("José"))
        assertEquals(listOf("abc"), WordAlign.tokens("ＡＢＣ"))
        // A no-break space decomposes to a space: two words.
        assertEquals(listOf("ten", "am"), WordAlign.tokens("ten am"))
    }

    @Test
    fun alignerTextIsEveryTokenSpaceSeparated() {
        val words = WordAlign.words("Well,  it's state-of-the-art — really!")
        assertEquals(listOf("Well,", "it's", "state-of-the-art", "—", "really!"), words)
        assertEquals("well it's state of the art really", WordAlign.alignerText(words))
    }

    // --- Which lines --------------------------------------------------------------

    private fun line(
        text: String,
        audioMs: Long? = 1_000L,
        words: List<WordStamp>? = null,
        times: WordTimes? = null,
        stamp: Long = 1L
    ) = TranscriptSegment(stamp, text, audioMs = audioMs, words = words, wordTimes = times)

    @Test
    fun estimatedOrMissingTimingsNeedRetiming() {
        val words = listOf(WordStamp(0, "Hello"), WordStamp(300, "there"))
        assertTrue(WordAlign.needsRetime(line("Hello there", words = words, times = WordTimes.ESTIMATED)))
        assertTrue(WordAlign.needsRetime(line("Hello there")))
        assertTrue(WordAlign.needsRetime(line("Hello there", words = emptyList())))
        // As the recogniser timed them, or nobody knows: not offered.
        assertFalse(WordAlign.needsRetime(line("Hello there", words = words)))
        assertFalse(WordAlign.needsRetime(line("Hello there", words = words, times = WordTimes.ALIGNED)))
        // No place in the recording, or nothing to align.
        assertFalse(WordAlign.needsRetime(line("Hello there", audioMs = null)))
        assertFalse(WordAlign.needsRetime(line("— …")))
        assertFalse(WordAlign.needsRetime(line("   ")))
    }

    @Test
    fun linesToRetimeAreTheIndicesInOrder() {
        val timed = listOf(WordStamp(0, "fine"))
        val segments = listOf(
            line("fine", words = timed, stamp = 1),
            line("edited line", words = timed, times = WordTimes.ESTIMATED, stamp = 2),
            line("untimed line", stamp = 3),
            line("no audio", audioMs = null, stamp = 4)
        )
        assertEquals(listOf(1, 2), WordAlign.linesToRetime(segments))
    }

    @Test
    fun onlyKnownEnglishSpeechIsAlignedWithoutAsking() {
        assertEquals(WordAlign.Speech.ENGLISH, WordAlign.speech("en", false))
        assertEquals(WordAlign.Speech.ENGLISH, WordAlign.speech("EN", false))
        // Whisper's translate task on English speech: still English.
        assertEquals(WordAlign.Speech.ENGLISH, WordAlign.speech("en", true))
        assertEquals(WordAlign.Speech.UNKNOWN, WordAlign.speech(null, false))
        assertEquals(WordAlign.Speech.UNKNOWN, WordAlign.speech("auto", false))
        assertEquals(WordAlign.Speech.OTHER, WordAlign.speech("de", false))
        assertEquals(WordAlign.Speech.TRANSLATED, WordAlign.speech("de", true))
        assertEquals(WordAlign.Speech.TRANSLATED, WordAlign.speech(null, true))
    }

    // --- Spans and runs -------------------------------------------------------------

    @Test
    fun aLineRunsToTheNextLinesStartWithAMarginEachSide() {
        val segments = listOf(line("Hello there everyone", audioMs = 1_000L), line("Next", audioMs = 5_000L))
        val span = WordAlign.span(segments, 0, null)!!
        assertEquals(1_000L, span.startMs)
        assertEquals(5_000L, span.endMs)
        assertEquals(700L, span.clipStartMs)
        assertEquals(5_300L, span.clipEndMs)
        assertEquals(-300L, span.clipOffsetMs)
        assertEquals(4_000L, span.lengthMs)
    }

    @Test
    fun aFarNextLineDoesNotStretchTheClip() {
        val segments = listOf(line("Hi there", audioMs = 1_000L), line("Much later", audioMs = 600_000L))
        val span = WordAlign.span(segments, 0, null)!!
        // Its own estimate (the tail, 1.5 s) plus the slack.
        assertEquals(1_000L + WordAlign.TAIL_MS + WordAlign.SLACK_MS, span.endMs)
    }

    @Test
    fun theLastLineEndsByItsWordsOrItsLength() {
        val words = listOf(WordStamp(0, "one"), WordStamp(3_000, "two"))
        val last = WordAlign.span(listOf(line("one two", audioMs = 2_000L, words = words)), 0, null)!!
        assertEquals(2_000L + 3_000L + WordAlign.TAIL_MS, last.endMs)
        val long = "x".repeat(100)
        val byText = WordAlign.span(listOf(line(long, audioMs = 0L)), 0, null)!!
        assertEquals(100L * 60L, byText.endMs)
        assertEquals(0L, byText.clipStartMs)
    }

    @Test
    fun spansAreClampedToTheRecording() {
        val segments = listOf(line("Hello there", audioMs = 9_000L))
        val span = WordAlign.span(segments, 0, 10_000L)!!
        assertEquals(10_000L, span.endMs)
        assertEquals(10_000L, span.clipEndMs)
        assertNull(WordAlign.span(segments, 0, 9_000L))
        assertNull(WordAlign.span(segments, 0, 9_100L))
        assertNull(WordAlign.span(listOf(line("x", audioMs = null)), 0, null))
        assertNull(WordAlign.span(segments, 3, null))
    }

    @Test
    fun linesSharingAStartLookPastEachOther() {
        // A split can leave two lines at one start; the next later one ends it.
        val segments = listOf(
            line("first half", audioMs = 1_000L),
            line("second half", audioMs = 1_000L),
            line("next", audioMs = 4_000L)
        )
        assertEquals(4_000L, WordAlign.span(segments, 0, null)!!.endMs)
    }

    @Test
    fun nearbyClipsShareOnePass() {
        val clips = listOf(
            100_000L to 104_000L,
            0L to 5_000L,
            6_000L to 9_000L,
            40_000L to 41_000L,
            41_500L to 45_000L
        )
        val runs = WordAlign.runs(clips, maxGapMs = 30_000L)
        assertEquals(3, runs.size)
        assertEquals(listOf(1, 2), runs[0].items)
        assertEquals(0L, runs[0].startMs)
        assertEquals(9_000L, runs[0].endMs)
        assertEquals(listOf(3, 4), runs[1].items)
        assertEquals(listOf(0), runs[2].items)
        assertTrue(WordAlign.runs(emptyList()).isEmpty())
    }

    // --- Mapping ------------------------------------------------------------------------

    /**
     * An aligner result for [words]: each (label, start s, end s, unknown)
     * with silence gaps between, and one phone per word ("spn" for unknown).
     */
    private fun tiers(vararg words: Pair<String, Pair<Float, Float>>, unknown: Set<String> = emptySet()):
        Pair<List<Interval>, List<Interval>> {
        val wordTier = mutableListOf<Interval>()
        val phoneTier = mutableListOf<Interval>()
        var t = 0f
        for ((label, span) in words) {
            val (start, end) = span
            if (start > t) {
                wordTier.add(Interval(t, start, ""))
                phoneTier.add(Interval(t, start, "sil"))
            }
            wordTier.add(Interval(start, end, label))
            if (label in unknown) {
                phoneTier.add(Interval(start, end, WordAlign.OOV_PHONE))
            } else {
                val mid = (start + end) / 2
                phoneTier.add(Interval(start, mid, "a"))
                phoneTier.add(Interval(mid, end, "b"))
            }
            t = end
        }
        return wordTier to phoneTier
    }

    private fun map(
        words: List<String>,
        tiers: Pair<List<Interval>, List<Interval>>,
        offset: Long = 0L,
        length: Long = 10_000L
    ) = WordAlign.mapWords(words, tiers.first, tiers.second, offset, length)

    private fun starts(mapped: WordAlign.Mapped?) = mapped!!.words.map { it.ms }

    @Test
    fun eachWordTakesItsAlignedStartRelativeToTheLine() {
        val words = listOf("Hello,", "world.")
        val result = map(words, tiers("hello" to (0.30f to 0.62f), "world" to (0.70f to 1.10f)), offset = -300L)
        assertEquals(listOf(0L, 400L), starts(result))
        assertEquals(words, result!!.words.map { it.text })
        assertEquals(2, result.aligned)
    }

    @Test
    fun aWordOfPunctuationSitsBetweenItsNeighbours() {
        val result = map(
            listOf("So", "—", "yes"),
            tiers("so" to (0.0f to 0.4f), "yes" to (1.0f to 1.3f))
        )
        val s = starts(result)
        assertEquals(0L, s[0])
        assertEquals(400L, s[1])
        assertEquals(1_000L, s[2])
    }

    @Test
    fun oneUnknownWordKeepsTheStretchTheModelGaveIt() {
        val result = map(
            listOf("we", "use", "Zorblax", "daily"),
            tiers(
                "we" to (0.1f to 0.3f), "use" to (0.3f to 0.6f),
                "zorblax" to (0.8f to 1.4f), "daily" to (1.5f to 2.0f),
                unknown = setOf("zorblax")
            )
        )
        assertEquals(listOf(100L, 300L, 800L, 1_500L), starts(result))
        assertEquals(3, result!!.aligned)
    }

    @Test
    fun unknownRunsAreSpreadByLengthAtTheStartMiddleAndEnd() {
        val unknown = setOf("xyzzy", "plugh", "frobozz", "quux")
        val result = map(
            listOf("Xyzzy", "Plugh", "said", "Frobozz", "Quux", "then", "zz", "qq"),
            tiers(
                "xyzzy" to (0.2f to 0.5f), "plugh" to (0.5f to 1.2f),
                "said" to (1.3f to 1.6f),
                "frobozz" to (1.7f to 2.0f), "quux" to (2.0f to 2.7f),
                "then" to (2.8f to 3.0f),
                "zz" to (3.1f to 3.3f), "qq" to (3.3f to 3.9f),
                unknown = unknown + setOf("zz", "qq")
            )
        )
        val s = starts(result)
        // Start: over 0.2-1.2 s by length (6 and 6 characters with a space).
        assertEquals(200L, s[0])
        assertEquals(700L, s[1])
        assertEquals(1_300L, s[2])
        // Middle: 1.7-2.7 s, 8 and 5 characters.
        assertEquals(1_700L, s[3])
        assertEquals(1_700L + 1_000L * 8 / 13, s[4])
        assertEquals(2_800L, s[5])
        // End: 3.1-3.9 s.
        assertEquals(3_100L, s[6])
        assertEquals(3_500L, s[7])
        // Only "said" and "then" were found in the dictionary.
        assertEquals(2, result!!.aligned)
    }

    @Test
    fun aLineOfUnknownWordsOnlyKeepsItsTimings() {
        val result = map(
            listOf("Xyzzy", "Plugh"),
            tiers("xyzzy" to (0.2f to 0.5f), "plugh" to (0.5f to 1.2f), unknown = setOf("xyzzy", "plugh"))
        )
        assertNull(result)
    }

    @Test
    fun anEmptyAlignmentKeepsTheTimings() {
        assertNull(WordAlign.mapWords(listOf("hello"), emptyList(), emptyList(), 0L, 1_000L))
        assertNull(WordAlign.mapWords(emptyList(), listOf(Interval(0f, 1f, "hello")), emptyList(), 0L, 1_000L))
        assertNull(
            WordAlign.mapWords(listOf("—"), listOf(Interval(0f, 1f, "hello")), emptyList(), 0L, 1_000L)
        )
    }

    @Test
    fun repeatedWordsKeepTheirOwnTimes() {
        val result = map(
            listOf("the", "the", "the", "end"),
            tiers("the" to (0.1f to 0.2f), "the" to (0.4f to 0.5f), "the" to (0.9f to 1.0f), "end" to (1.2f to 1.5f))
        )
        assertEquals(listOf(100L, 400L, 900L, 1_200L), starts(result))
    }

    @Test
    fun aHyphenatedWordStartsWithItsFirstPart() {
        val result = map(
            listOf("state-of-the-art", "design"),
            tiers(
                "state" to (0.1f to 0.4f), "of" to (0.4f to 0.5f), "the" to (0.5f to 0.6f),
                "art" to (0.6f to 0.9f), "design" to (1.0f to 1.5f)
            )
        )
        assertEquals(listOf(100L, 1_000L), starts(result))
        assertEquals(listOf("state-of-the-art", "design"), result!!.words.map { it.text })
    }

    @Test
    fun contractionsAndNumbersMapBackToTheirWords() {
        val result = map(
            listOf("Don't", "call", "at", "3:30."),
            tiers(
                "don't" to (0.0f to 0.3f), "call" to (0.3f to 0.6f), "at" to (0.6f to 0.7f),
                "3" to (0.8f to 1.0f), "30" to (1.0f to 1.4f),
                unknown = setOf("3", "30")
            )
        )
        assertEquals(listOf(0L, 300L, 600L, 800L), starts(result))
        assertEquals(3, result!!.aligned)
    }

    @Test
    fun startsStayWithinTheLineAndNeverRunBackwards() {
        // Aligned early (in the margin before the line) and late (after it).
        val result = map(
            listOf("early", "middle", "late"),
            tiers("early" to (0.0f to 0.2f), "middle" to (0.5f to 0.7f), "late" to (2.6f to 2.9f)),
            offset = -300L,
            length = 2_000L
        )
        assertEquals(listOf(0L, 200L, 1_999L), starts(result))
        for (k in 1 until result!!.words.size) {
            assertTrue(result.words[k].ms >= result.words[k - 1].ms)
        }
    }

    @Test
    fun aMismatchedWordListIsMatchedInOrder() {
        // One label the line does not have (and one token the aligner lost).
        val wordTier = listOf(
            Interval(0.0f, 0.2f, "hello"),
            Interval(0.2f, 0.4f, "uh"),
            Interval(0.5f, 0.9f, "world")
        )
        val phones = listOf(
            Interval(0.0f, 0.2f, "h"), Interval(0.2f, 0.4f, "a"), Interval(0.5f, 0.9f, "w")
        )
        val result = WordAlign.mapWords(listOf("Hello", "big", "world"), wordTier, phones, 0L, 2_000L)
        val s = result!!.words.map { it.ms }
        assertEquals(0L, s[0])
        assertEquals(500L, s[2])
        // "big" was not aligned: between its neighbours.
        assertTrue(s[1] in 200L..500L)
        assertEquals(2, result.aligned)
    }

    @Test
    fun tokensMatchOneToOneOrByLongestCommonRun() {
        assertEquals(
            listOf(0 to 0, 1 to 1),
            WordAlign.matchTokens(listOf("a", "b"), listOf("A", "b"))
        )
        assertEquals(
            listOf(0 to 0, 2 to 1),
            WordAlign.matchTokens(listOf("a", "x", "b"), listOf("a", "b"))
        )
        assertEquals(emptyList<Pair<Int, Int>>(), WordAlign.matchTokens(listOf("a"), listOf("b")))
    }

    // --- Saving -------------------------------------------------------------------------

    @Test
    fun resultsGoToTheLinesThatStillReadAsTheyDid() {
        val segments = mutableListOf(
            line("first line", audioMs = 0L, stamp = 10, times = WordTimes.ESTIMATED,
                words = listOf(WordStamp(0, "first"), WordStamp(500, "line"))),
            line("second line", audioMs = 3_000L, stamp = 20),
            line("third line", audioMs = 6_000L, stamp = 30)
        )
        val targets = segments.map { WordAlign.target(it)!! }
        // The second line is edited while the run is going.
        segments[1] = segments[1].copy(text = "second line, edited")
        val results = targets.map {
            WordAlign.Retimed(it, listOf(WordStamp(100, it.text.substringBefore(' ')), WordStamp(700, "line")))
        }
        assertEquals(2, WordAlign.apply(segments, results))
        assertEquals(WordTimes.ALIGNED, segments[0].wordTimes)
        assertEquals(listOf(100L, 700L), segments[0].words!!.map { it.ms })
        assertEquals("second line, edited", segments[1].text)
        assertNull(segments[1].words)
        assertNull(segments[1].wordTimes)
        assertEquals(WordTimes.ALIGNED, segments[2].wordTimes)
        // Applying again changes nothing.
        assertEquals(0, WordAlign.apply(segments, results))
    }

    @Test
    fun aDeletedOrMovedLineTakesNothing() {
        val segments = mutableListOf(line("only line", audioMs = 0L, stamp = 10))
        val target = WordAlign.target(segments[0])!!
        val moved = mutableListOf(segments[0].copy(audioMs = 50L))
        assertEquals(0, WordAlign.apply(moved, listOf(WordAlign.Retimed(target, listOf(WordStamp(0, "only"))))))
        assertEquals(0, WordAlign.apply(mutableListOf(), listOf(WordAlign.Retimed(target, emptyList()))))
        assertNotNull(WordAlign.target(segments[0]))
        assertNull(WordAlign.target(line("x", audioMs = null)))
    }

    @Test
    fun twoLinesThatReadAlikeTakeOneResultEach() {
        val segments = mutableListOf(
            line("yes", audioMs = 0L, stamp = 10),
            line("yes", audioMs = 0L, stamp = 10)
        )
        val target = WordAlign.target(segments[0])!!
        val applied = WordAlign.apply(
            segments,
            listOf(
                WordAlign.Retimed(target, listOf(WordStamp(10, "yes"))),
                WordAlign.Retimed(target, listOf(WordStamp(20, "yes")))
            )
        )
        assertEquals(2, applied)
        assertEquals(10L, segments[0].words!!.single().ms)
        assertEquals(20L, segments[1].words!!.single().ms)
    }
}
