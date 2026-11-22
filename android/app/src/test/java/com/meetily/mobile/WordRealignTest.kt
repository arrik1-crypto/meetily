package com.meetily.mobile

import com.meetily.mobile.data.WordRealign
import com.meetily.mobile.data.WordStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word timings are what make a transcript word tappable. A fix to a line's
 * wording must keep the timings of every word it did not touch, and give the
 * words it did a sensible place on the line's timeline.
 */
class WordRealignTest {

    private val text = "Hello Jon, how are you"
    private val words = listOf(
        WordStamp(0, "Hello"),
        WordStamp(400, "Jon,"),
        WordStamp(900, "how"),
        WordStamp(1100, "are"),
        WordStamp(1300, "you")
    )

    private fun edit(text: String, find: String, replacement: String): WordRealign.Edit {
        val at = text.indexOf(find)
        return WordRealign.Edit(at, at + find.length, replacement)
    }

    /** Every word is still found where the transcript list looks for it. */
    private fun assertPlaceable(text: String, words: List<WordStamp>) {
        val starts = WordRealign.locate(text, words)
        assertTrue("unplaceable in '$text': $words", starts.all { it >= 0 })
    }

    @Test
    fun oneForOneKeepsEveryTimingAndRenamesTheWord() {
        val edits = listOf(edit(text, "Jon", "John"))
        val newText = WordRealign.applyText(text, edits)
        assertEquals("Hello John, how are you", newText)
        val out = WordRealign.applyEdits(text, words, edits)!!
        assertEquals(
            listOf(
                WordStamp(0, "Hello"),
                WordStamp(400, "John,"),
                WordStamp(900, "how"),
                WordStamp(1100, "are"),
                WordStamp(1300, "you")
            ),
            out
        )
        assertPlaceable(newText, out)
    }

    @Test
    fun moreWordsSpreadTheReplacedSpanAndLeaveTheRestAlone() {
        val edits = listOf(edit(text, "Jon", "John Smith"))
        val newText = WordRealign.applyText(text, edits)
        val out = WordRealign.applyEdits(text, words, edits)!!
        assertEquals(listOf("Hello", "John", "Smith,", "how", "are", "you"), out.map { it.text })
        // Untouched words keep their timings exactly.
        assertEquals(WordStamp(0, "Hello"), out[0])
        assertEquals(words.subList(2, 5), out.subList(3, 6))
        // The new words share the old word's span: from its start to the next word.
        assertEquals(400L, out[1].ms)
        assertTrue(out[2].ms in 401L until 900L)
        assertPlaceable(newText, out)
    }

    @Test
    fun fewerWordsKeepTheSurvivorsOwnTiming() {
        val line = "so Doctor Jon Smith said"
        val stamps = listOf(
            WordStamp(0, "so"), WordStamp(200, "Doctor"), WordStamp(500, "Jon"),
            WordStamp(800, "Smith"), WordStamp(1200, "said")
        )
        val edits = listOf(edit(line, "Doctor Jon Smith", "Jon Smith"))
        val out = WordRealign.applyEdits(line, stamps, edits)!!
        assertEquals(
            listOf(
                WordStamp(0, "so"), WordStamp(500, "Jon"),
                WordStamp(800, "Smith"), WordStamp(1200, "said")
            ),
            out
        )
    }

    @Test
    fun differentWordsTakeTheOldSpan() {
        val line = "we are gonna ship it"
        val stamps = listOf(
            WordStamp(0, "we"), WordStamp(200, "are"), WordStamp(400, "gonna"),
            WordStamp(1000, "ship"), WordStamp(1300, "it")
        )
        val out = WordRealign.applyEdits(line, stamps, listOf(edit(line, "gonna", "going to")))!!
        assertEquals(listOf("we", "are", "going", "to", "ship", "it"), out.map { it.text })
        assertEquals(400L, out[2].ms)
        assertTrue(out[3].ms > 400L && out[3].ms < 1000L)
        assertEquals(1000L, out[4].ms)
        // Still in order, so playback follow can binary-search them.
        assertEquals(out.map { it.ms }.sorted(), out.map { it.ms })
    }

    @Test
    fun lastWordSpreadsOverAnEstimateWhenNothingFollows() {
        val line = "see you Jon"
        val stamps = listOf(WordStamp(0, "see"), WordStamp(300, "you"), WordStamp(600, "Jon"))
        val out = WordRealign.applyEdits(line, stamps, listOf(edit(line, "Jon", "John Smith")))!!
        assertEquals(listOf("see", "you", "John", "Smith"), out.map { it.text })
        assertEquals(600L, out[2].ms)
        assertTrue(out[3].ms > 600L)
    }

    @Test
    fun deletingAWordDropsOnlyThatWord() {
        val line = "we um should go"
        val stamps = listOf(
            WordStamp(0, "we"), WordStamp(300, "um"), WordStamp(700, "should"), WordStamp(1000, "go")
        )
        val edits = listOf(WordRealign.Edit(3, 6, ""))
        assertEquals("we should go", WordRealign.applyText(line, edits))
        val out = WordRealign.applyEdits(line, stamps, edits)!!
        assertEquals(
            listOf(WordStamp(0, "we"), WordStamp(700, "should"), WordStamp(1000, "go")),
            out
        )
    }

    @Test
    fun severalEditsInOneLineEachKeepTheirNeighbours() {
        val line = "Jon told Jon about it"
        val stamps = listOf(
            WordStamp(0, "Jon"), WordStamp(300, "told"), WordStamp(600, "Jon"),
            WordStamp(900, "about"), WordStamp(1200, "it")
        )
        val edits = listOf(
            WordRealign.Edit(0, 3, "John"),
            WordRealign.Edit(9, 12, "John")
        )
        val out = WordRealign.applyEdits(line, stamps, edits)!!
        assertEquals(
            listOf(
                WordStamp(0, "John"), WordStamp(300, "told"), WordStamp(600, "John"),
                WordStamp(900, "about"), WordStamp(1200, "it")
            ),
            out
        )
    }

    @Test
    fun editInsideAWordOfAnUnspacedScriptTouchesOnlyThatWord() {
        val line = "東京都で会議"
        val stamps = listOf(
            WordStamp(0, "東京"), WordStamp(300, "都"), WordStamp(500, "で"), WordStamp(600, "会議")
        )
        val out = WordRealign.applyEdits(line, stamps, listOf(WordRealign.Edit(2, 3, "府")))!!
        assertEquals(
            listOf(WordStamp(0, "東京"), WordStamp(300, "府"), WordStamp(500, "で"), WordStamp(600, "会議")),
            out
        )
    }

    @Test
    fun freeEditWidensToWholeWords() {
        val line = "the cat sat down"
        val stamps = listOf(
            WordStamp(0, "the"), WordStamp(200, "cat"), WordStamp(500, "sat"), WordStamp(800, "down")
        )
        val out = WordRealign.realign(line, stamps, "the cats sat down")!!
        assertEquals(
            listOf(WordStamp(0, "the"), WordStamp(200, "cats"), WordStamp(500, "sat"), WordStamp(800, "down")),
            out
        )
    }

    @Test
    fun freeEditWithChangesAtBothEndsKeepsTheMiddle() {
        val line = "um the cat sat down ok"
        val stamps = listOf(
            WordStamp(0, "um"), WordStamp(100, "the"), WordStamp(200, "cat"),
            WordStamp(500, "sat"), WordStamp(800, "down"), WordStamp(1000, "ok")
        )
        val newText = "The cat sat down."
        val out = WordRealign.realign(line, stamps, newText)!!
        assertEquals(listOf("The", "cat", "sat", "down."), out.map { it.text })
        // The words in between were never changed and keep their timings.
        assertEquals(WordStamp(200, "cat"), out[1])
        assertEquals(WordStamp(500, "sat"), out[2])
        assertPlaceable(newText, out)
    }

    @Test
    fun freeEditThatKeepsTheWordCountStillKeepsEachWordsOwnTiming() {
        val line = "um we should go now"
        val stamps = listOf(
            WordStamp(0, "um"), WordStamp(300, "we"), WordStamp(600, "should"),
            WordStamp(900, "go"), WordStamp(1200, "now")
        )
        val newText = "we should go now please"
        val out = WordRealign.realign(line, stamps, newText)!!
        assertEquals(listOf("we", "should", "go", "now", "please"), out.map { it.text })
        // One word dropped and one added keeps the count: the words in
        // between keep their own times, not their neighbour's.
        assertEquals(
            listOf(WordStamp(300, "we"), WordStamp(600, "should"), WordStamp(900, "go"), WordStamp(1200, "now")),
            out.subList(0, 4)
        )
        assertTrue(out[4].ms >= 1200L)
        assertPlaceable(newText, out)

        // A word moved to the other end keeps its time too, and the rest theirs.
        val moved = WordRealign.realign(
            "Jon Smith said hello",
            listOf(WordStamp(0, "Jon"), WordStamp(300, "Smith"), WordStamp(600, "said"), WordStamp(900, "hello")),
            "Smith said hello Jon"
        )!!
        assertEquals(
            listOf(WordStamp(300, "Smith"), WordStamp(600, "said"), WordStamp(900, "hello")),
            moved.subList(0, 3)
        )
        assertEquals("Jon", moved[3].text)
    }

    @Test
    fun oneForOneSubstitutionsInAFreeEditStillMapByPosition() {
        val line = "Jon Smith said hello"
        val stamps = listOf(
            WordStamp(0, "Jon"), WordStamp(300, "Smith"), WordStamp(600, "said"), WordStamp(900, "hello")
        )
        assertEquals(
            listOf(WordStamp(0, "John"), WordStamp(300, "Smyth"), WordStamp(600, "said"), WordStamp(900, "hello")),
            WordRealign.realign(line, stamps, "John Smyth said hello")
        )
    }

    @Test
    fun freeEditOfAnUnspacedScriptStaysInsideTheWord() {
        val line = "東京都で会議"
        val stamps = listOf(
            WordStamp(0, "東京"), WordStamp(300, "都"), WordStamp(500, "で"), WordStamp(600, "会議")
        )
        val out = WordRealign.realign(line, stamps, "東京府で会議")!!
        assertEquals(WordStamp(0, "東京"), out[0])
        assertEquals(WordStamp(300, "府"), out[1])
        assertEquals(WordStamp(600, "会議"), out[3])
    }

    @Test
    fun noTimingsStayNoTimings() {
        assertNull(WordRealign.realign("a b", null, "a c"))
        assertNull(WordRealign.applyEdits("a b", emptyList(), listOf(WordRealign.Edit(2, 3, "c"))))
    }

    @Test
    fun unchangedTextKeepsTheWordsAsTheyAre() {
        assertEquals(words, WordRealign.realign(text, words, text))
    }

    @Test
    fun partitionDividesAtTheSplitAndCutsAStraddlingWord() {
        val line = "alpha beta gamma"
        val stamps = listOf(WordStamp(0, "alpha"), WordStamp(400, "beta"), WordStamp(900, "gamma"))
        val (first, second) = WordRealign.partition(line, stamps, line.indexOf("gamma"))
        assertEquals(listOf(WordStamp(0, "alpha"), WordStamp(400, "beta")), first)
        assertEquals(listOf(WordStamp(900, "gamma")), second)

        val (head, tail) = WordRealign.partition(line, stamps, line.indexOf("eta"))
        assertEquals(listOf(WordStamp(0, "alpha"), WordStamp(400, "b")), head)
        assertEquals(listOf(WordStamp(400, "eta"), WordStamp(900, "gamma")), tail)
    }
}
