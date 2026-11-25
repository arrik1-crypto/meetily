package com.meetily.mobile.llm

/**
 * Keeps a stop on the run that asked for it.
 *
 * llama_jni.c has one abort flag for the whole process, checked before
 * every decode by whichever generation holds the model. SummaryService
 * raises it when the phone is unplugged from a charging-only summary or
 * the summary runs out of foreground time. Raised as-is, it stopped
 * whichever chat held the model at that moment, an Ask or a title
 * included, while the summary it was meant for sat waiting for the lock.
 * And it stayed up until the stopped summary had wound down, so a chat
 * that took the model in between failed at its first decode.
 *
 * So a stop names the thread it is for, and [native] (the llama flag) is
 * up only while that thread holds the model: set by [request] if it holds
 * it now, by [enter] when it takes it, and lowered by [exit] when the
 * holder lets go. Pure JVM: [native] is the only effect, so the rules are
 * unit-tested.
 */
class AbortScope(private val native: (Boolean) -> Unit) {

    /** The thread a stop is pending for, or null. */
    private var target: Thread? = null

    /** The thread holding the model, between [enter] and [exit]. */
    private var holder: Thread? = null

    /** Stops [owner]'s generation: now if it holds the model, else once it takes it. */
    @Synchronized
    fun request(owner: Thread) {
        target = owner
        if (holder === owner) native(true)
    }

    /** Withdraws any pending stop. */
    @Synchronized
    fun clear() {
        target = null
        native(false)
    }

    /** [thread] has taken the model: the flag is up only if the stop is for it. */
    @Synchronized
    fun enter(thread: Thread) {
        holder = thread
        native(target === thread)
    }

    /** The holder is letting the model go: the next holder starts clean. */
    @Synchronized
    fun exit() {
        holder = null
        native(false)
    }

    /** True while a stop is pending for [thread]. */
    @Synchronized
    fun requestedFor(thread: Thread): Boolean = target === thread
}
