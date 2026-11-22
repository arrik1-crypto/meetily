package com.meetily.mobile.data

import java.util.Locale

/**
 * Continuing a transcription that stopped part-way instead of starting it
 * over.
 *
 * A pass stops early for many reasons: the charger comes out under an
 * "only while charging" pass, Android's daily foreground-service allowance
 * runs out, the process dies, or the user taps Cancel. The words it reached
 * were always kept, but the next pass began again at 0:00, so at 80% done
 * about 80% of the work, and of the battery it cost, was thrown away.
 *
 * So a pass that stops records where its words stop being trustworthy: the
 * resume point. It is always a cut the importer itself made at silence (at
 * least 0.8 s below the RMS floor), and only one where every chunk before
 * it is fully transcribed and the speech gate holds nothing that reaches
 * past it. The importer's chunker starts every chunk from a clean state, so
 * a pass that starts there cuts the rest of the recording exactly as the
 * first one would have.
 *
 * The point is stored with the words it describes (Meeting.transcriptResumeMs
 * for a meeting's own transcript, the draft's resumeMs for a staged accuracy
 * check) and in the queued job that will continue it (JobQueue.Job.resumeFromMs).
 * Lines at or after it are made again by the next pass; lines before it are
 * kept, edits included.
 *
 * Pure, so every rule here is unit-tested.
 */
object TranscriptResume {

    /**
     * The resume point of one pass, in samples. The importer reports every
     * cut it makes at silence; [pointFor] then picks the latest one that
     * everything before it has been finished up to.
     */
    class Cuts(private val startSample: Long) {
        /** The latest cut known to be safe: everything before it is done. */
        private var confirmed = startSample

        /** Cuts at silence not yet known to be safe, oldest first. */
        private val waiting = ArrayDeque<Long>()

        /**
         * The importer cut a chunk at silence, ending at [endSample], and
         * nothing it holds reaches past that sample.
         */
        fun quietCut(endSample: Long) {
            if (endSample <= confirmed) return
            if (waiting.isNotEmpty() && endSample <= waiting.last()) return
            waiting.addLast(endSample)
        }

        /**
         * The latest cut at silence at or before [frontier], the first sample
         * not yet fully transcribed; the pass's own start when there is none.
         * The frontier only ever moves forward, so a cut it has passed is
         * settled for good.
         */
        fun pointFor(frontier: Long): Long {
            while (waiting.isNotEmpty() && waiting.first() <= frontier) {
                confirmed = waiting.removeFirst()
            }
            // Defensive: never past what is finished, never before the start.
            return minOf(confirmed, maxOf(frontier, startSample))
        }
    }

    /**
     * The first sample not yet fully transcribed: the earliest start among
     * the chunks still [pending] (batched, held, in flight or abandoned to
     * a stop), or [next], where the chunk being gathered begins.
     */
    fun frontier(pending: List<Long?>, next: Long): Long {
        var out = next
        for (start in pending) if (start != null && start < out) out = start
        return out
    }

    fun msAt(sample: Long, rate: Int): Long = sample * 1000L / rate

    fun sampleAt(ms: Long, rate: Int): Long = ms * rate / 1000L

    /**
     * How many of [size] decoded samples that begin at absolute sample
     * [chunkStart] come before [from] and are skipped. [size] means all.
     */
    fun skipBefore(chunkStart: Long, size: Int, from: Long): Int =
        (from - chunkStart).coerceIn(0L, size.toLong()).toInt()

    /**
     * Where a line sits in the recording: its audio offset, or for a line
     * that has none, its wall-clock distance from [baseMs].
     */
    fun offsetOf(segment: TranscriptSegment, baseMs: Long): Long =
        segment.audioMs ?: (segment.timestampMs - baseMs)

    /**
     * Where [word] of [segment] is spoken in the recording. Word timings are
     * kept relative to their line, so a line placed at its absolute offset
     * carries its words there with it: nothing about them changes when a
     * pass starts part-way.
     */
    fun wordAt(segment: TranscriptSegment, word: WordStamp): Long? =
        segment.audioMs?.let { it + word.ms }

    /** The transcript a pass that continues at [resumeMs] keeps: the lines before it. */
    fun prefix(existing: List<TranscriptSegment>, resumeMs: Long, baseMs: Long): List<TranscriptSegment> =
        existing.filter { offsetOf(it, baseMs) < resumeMs }

    /** Whether a line the continuing pass made belongs after the seam at [resumeMs]. */
    fun after(segment: TranscriptSegment, resumeMs: Long, baseMs: Long): Boolean =
        offsetOf(segment, baseMs) >= resumeMs

    /**
     * [existing] continued by [resumed] at [resumeMs], with nothing twice
     * and nothing missing.
     *
     * Lines at or after the seam that [existing] already has came from
     * chunks the stopped pass finished after its last safe cut; the new pass
     * makes them again from the same audio, so they go. Anything the new
     * pass placed before the seam (it never should: it starts there) goes
     * too. Nothing needs merging: the seam is at least 0.8 s of silence, so
     * no line can straddle it.
     */
    fun seam(
        existing: List<TranscriptSegment>,
        resumed: List<TranscriptSegment>,
        resumeMs: Long,
        baseMs: Long
    ): List<TranscriptSegment> =
        prefix(existing, resumeMs, baseMs) + resumed.filter { after(it, resumeMs, baseMs) }

    /**
     * The highlight marks a transcript that stops at [resumeMs] can place.
     * A mark past the last line would otherwise star whatever line happens
     * to be last so far; it waits for the pass that reaches it. All of them
     * once the transcript is complete ([resumeMs] null).
     */
    fun marksReached(marks: List<Long>, resumeMs: Long?): List<Long> =
        if (resumeMs == null) marks else marks.filter { it < resumeMs }

    /**
     * Whether a pass asked to run in [requested] ("auto", a code, or blank
     * for the setting) may carry on words made in [stored]. Blank and "auto"
     * accept whatever the stopped pass settled on; a code must be that one,
     * unless the stopped pass settled on none (NeMo on auto, or no speech
     * reached Whisper yet), when the code decides.
     */
    fun languageCompatible(requested: String?, stored: String?): Boolean =
        requested.isNullOrBlank() || requested == MeetingLanguage.AUTO || stored == null ||
            MeetingLanguage.normalize(requested) == stored

    /**
     * Where a pass with [runModel] and [requestedLanguage] continues the
     * stored words from, or null to start from the beginning.
     *
     * Only the same model carries on: a different one would leave two
     * models' words either side of the seam, so a pass with another model
     * is a fresh start (ContinueTranscription asks before running one). A
     * transcript that records no model (blank key) carries on with any.
     */
    fun continueFrom(
        storedResumeMs: Long?,
        storedModel: String?,
        runModel: String,
        storedLanguage: String?,
        requestedLanguage: String?
    ): Long? = when {
        storedResumeMs == null || storedResumeMs <= 0L -> null
        !storedModel.isNullOrBlank() && storedModel != runModel -> null
        !languageCompatible(requestedLanguage, storedLanguage) -> null
        else -> storedResumeMs
    }

    /**
     * The language a continuing pass runs in: what the stopped pass settled
     * on (detected or pinned), so both sides of the seam are in one
     * language; [requested] when it settled on nothing.
     */
    fun languageFor(storedLanguage: String?, requested: String?): String? =
        storedLanguage ?: requested

    /**
     * Whether a queued first transcript still has work to do. One queued to
     * continue ([jobResumeMs] > 0) a transcript that has been finished since
     * ([storedResumeMs] null) has none, and running it would only start the
     * finished transcript over.
     */
    fun stillWanted(firstTranscript: Boolean, jobResumeMs: Long, storedResumeMs: Long?): Boolean =
        !(firstTranscript && jobResumeMs > 0L && storedResumeMs == null)

    enum class Action { CONTINUE, START_OVER, NO_MODEL }

    /** What "Continue transcription" does, and with which model. */
    data class Plan(val action: Action, val modelKey: String?)

    /**
     * The model that made the words carries on when it is still on the
     * phone. Otherwise the current one can only start over (asked first,
     * since its lines replace the ones there), and with neither there is
     * nothing to run.
     */
    fun plan(
        storedModel: String?,
        storedReady: Boolean,
        currentModel: String,
        currentReady: Boolean
    ): Plan = when {
        !storedModel.isNullOrBlank() && storedReady -> Plan(Action.CONTINUE, storedModel)
        storedModel.isNullOrBlank() && currentReady -> Plan(Action.CONTINUE, currentModel)
        !storedModel.isNullOrBlank() && currentReady && currentModel != storedModel ->
            Plan(Action.START_OVER, currentModel)
        else -> Plan(Action.NO_MODEL, null)
    }

    /**
     * Audio of one or more lines before the seam that start at the same
     * moment, for embedding their voice again: [startMs] to [endMs], and
     * the indices of those lines.
     */
    data class Window(val startMs: Long, val endMs: Long, val segments: List<Int>)

    /**
     * The voice windows of the lines before [resumeMs], in order and never
     * overlapping, so one sequential decode can fill them.
     *
     * A line's chunk ran from its start to the next chunk at most, and no
     * longer than [maxMs] (the importer's longest chunk). Its last word,
     * plus [tailMs], ends it sooner when known, so the silence a skipped
     * stretch leaves before the next line is not embedded as voice. Lines
     * with no audio offset have nothing to embed.
     */
    fun voiceWindows(
        segments: List<TranscriptSegment>,
        resumeMs: Long,
        maxMs: Long = 28_000L,
        tailMs: Long = 1_500L
    ): List<Window> {
        val byStart = segments.indices
            .filter { i -> segments[i].audioMs?.let { it >= 0L && it < resumeMs } == true }
            .groupBy { segments[it].audioMs!! }
            .toSortedMap()
        val starts = byStart.keys.toList()
        val out = ArrayList<Window>(starts.size)
        for ((n, start) in starts.withIndex()) {
            val lines = byStart.getValue(start)
            var end = minOf(start + maxMs, resumeMs)
            if (n + 1 < starts.size) end = minOf(end, starts[n + 1])
            val lastWord = lines.mapNotNull { i -> segments[i].words?.maxOfOrNull { it.ms } }.maxOrNull()
            if (lastWord != null) end = minOf(end, start + lastWord + tailMs)
            if (end > start) out.add(Window(start, end, lines))
        }
        return out
    }

    /**
     * Names the user gave speakers before the seam, carried to the lines
     * after it that clustering put with the same voice, so one person does
     * not read as a name on one side and "Speaker 3" on the other.
     *
     * Careful by design: a name goes over only when more than half of that
     * voice's lines before the seam carry it and no other name, and only
     * onto lines after the seam that have none.
     */
    fun carryNames(
        segments: List<TranscriptSegment>,
        resumeMs: Long,
        baseMs: Long
    ): List<TranscriptSegment> {
        val names = HashMap<Int, String>()
        for ((cluster, lines) in segments.filter { it.clusterId != null && !after(it, resumeMs, baseMs) }
            .groupBy { it.clusterId!! }
        ) {
            val named = lines.mapNotNull { it.speaker?.takeIf { s -> s.isNotBlank() } }
            val distinct = named.toSet()
            if (distinct.size == 1 && named.size * 2 > lines.size) names[cluster] = distinct.first()
        }
        if (names.isEmpty()) return segments
        return segments.map { s ->
            val name = s.clusterId?.let { names[it] }
            if (name != null && s.speaker.isNullOrBlank() && after(s, resumeMs, baseMs)) {
                s.copy(speaker = name)
            } else {
                s
            }
        }
    }

    /**
     * Whether a seek into audio of [mime] lands where its timestamps say.
     * MP3 seeks are estimated from the bitrate when the file has no table
     * of contents, so a continued pass would place its lines seconds off;
     * it is decoded from the start instead, discarding what comes before
     * the seam.
     */
    fun seekable(mime: String?): Boolean =
        !mime.isNullOrBlank() && !mime.equals("audio/mpeg", ignoreCase = true)

    /** "42:10", or "1:05:00" from an hour on: where a transcript stopped. */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
