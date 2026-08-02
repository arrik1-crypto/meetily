package com.meetily.mobile.llm.litert;

import com.meetily.mobile.llm.litert.ILiteRtCallback;

/**
 * The sandbox boundary.
 *
 * LiteRT-LM ships one 21 MB .so that statically links its own tensor
 * runtime, while whisper.cpp and llama.cpp in this app deliberately SHARE a
 * single static ggml. Two overlapping tensor runtimes with global state in
 * one process is the kind of thing that surfaces as a random SIGSEGV inside
 * Whisper, mid-transcription, hours from anything LLM-shaped. Address spaces
 * do not share symbols, so this interface is the fix.
 *
 * It also contains the failures the app cannot catch. Every catch site in
 * the app catches Exception, so an OutOfMemoryError from a JVM-heap
 * allocation, or a NoClassDefFoundError from an absent AAR, would kill the
 * foreground service outright. Over a binder those become a dead process and
 * a DeadObjectException, which IS catchable, and the summary falls back.
 */
interface ILiteRtEngine {

    /**
     * Runs one generation. Blocking, and minutes long — callers must be on a
     * worker thread. Roles and contents are parallel arrays in message order.
     *
     * Returns the reply text, or null if generation produced nothing.
     * Throws IllegalStateException (across the binder) with a
     * user-presentable message when the model or backend will not load.
     */
    String generate(
        in String[] roles,
        in String[] contents,
        int maxReplyTokens,
        int threadBudget,
        boolean allowMapReduce,
        ILiteRtCallback callback);

    /**
     * Loads the model and reports the backend actually selected — which is
     * not always the one requested. Returns a backend id ("cpu", "gpu",
     * "tensor"), or throws with a presentable reason.
     */
    String probe(String modelPath, String requestedBackend, int threadBudget);

    /** Frees the engine. Returns once teardown is done. */
    void release();
}
