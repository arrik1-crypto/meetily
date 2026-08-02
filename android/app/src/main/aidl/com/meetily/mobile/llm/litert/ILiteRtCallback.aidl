package com.meetily.mobile.llm.litert;

/**
 * Progress from the sandbox back to the app. Oneway throughout: the sandbox
 * must never block on the app's main thread to report a section.
 */
oneway interface ILiteRtCallback {
    /** 1-based section of a map-reduce pass; (0, 0) means "writing the final summary". */
    void onSection(int index, int total);
}
