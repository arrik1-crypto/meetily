package com.recap.spike

import android.content.Context
import java.lang.reflect.Method

/**
 * Binds to whichever on-device LLM runtime is actually on the classpath —
 * by reflection, deliberately.
 *
 * The published Kotlin guide for LiteRT-LM was not reachable when this was
 * written, so the exact class and method names are not known here. Compiling
 * against a guess would mean a build failure per wrong guess and a CI round
 * trip to learn one signature. Reflection turns that into a single run: the
 * app builds regardless, and if the binding does not resolve it prints what
 * it DID find — the candidate classes that loaded, and their public methods —
 * which is the information needed to write the real thing.
 *
 * This is throwaway measurement scaffolding. Nothing here should ever be
 * copied into the app: reflection is exactly the wrong choice once the API is
 * known.
 */
object LlmRuntime {

    /** Class names to try, most likely first. */
    private val ENGINE_CANDIDATES = listOf(
        "com.google.mediapipe.tasks.genai.llminference.LlmInference",
        "com.google.ai.edge.litertlm.Engine",
        "com.google.ai.edge.litert.lm.Engine",
        "com.google.ai.edge.litertlm.LlmInference"
    )

    private val OPTIONS_CANDIDATES = listOf(
        "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions",
        "com.google.ai.edge.litertlm.EngineConfig",
        "com.google.ai.edge.litertlm.EngineSettings"
    )

    class Binding(
        val engineClass: Class<*>,
        val optionsClass: Class<*>?,
        val instance: Any,
        val generate: Method,
        val notes: List<String>
    )

    /** What loaded, and what it exposes — printed when binding fails. */
    fun describeClasspath(): String = buildString {
        appendLine("Runtime discovery")
        appendLine("=================")
        var foundAny = false
        for (name in ENGINE_CANDIDATES + OPTIONS_CANDIDATES) {
            val cls = try {
                Class.forName(name)
            } catch (_: Throwable) {
                null
            }
            if (cls == null) {
                appendLine("  absent : $name")
                continue
            }
            foundAny = true
            appendLine("  PRESENT: $name")
            for (m in cls.methods.sortedBy { it.name }) {
                if (m.declaringClass == Any::class.java) continue
                val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                appendLine("      ${m.returnType.simpleName} ${m.name}($params)")
            }
            for (c in cls.constructors) {
                appendLine("      <init>(${c.parameterTypes.joinToString(", ") { it.simpleName }})")
            }
        }
        if (!foundAny) {
            appendLine()
            appendLine("None of the candidate classes are on the classpath.")
            appendLine("The dependency coordinate in gradle.properties resolved,")
            appendLine("but exposes different class names. Check the CI log's")
            appendLine("\"AAR class dump\" step for the real ones.")
        }
    }

    /**
     * Best-effort bind. [backend] is passed through to whatever selector the
     * runtime exposes; [preferredBackendSetter] names are tried in order.
     */
    fun bind(context: Context, modelPath: String, backend: String): Result<Binding> {
        val notes = mutableListOf<String>()
        val engineClass = ENGINE_CANDIDATES.firstNotNullOfOrNull { name ->
            try {
                Class.forName(name).also { notes += "engine class: $name" }
            } catch (_: Throwable) {
                null
            }
        } ?: return Result.failure(IllegalStateException("no engine class on classpath"))

        val optionsClass = OPTIONS_CANDIDATES.firstNotNullOfOrNull { name ->
            try {
                Class.forName(name).also { notes += "options class: $name" }
            } catch (_: Throwable) {
                null
            }
        }

        return try {
            val instance = construct(context, engineClass, optionsClass, modelPath, backend, notes)
            val generate = engineClass.methods.firstOrNull {
                it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.returnType == String::class.java &&
                    it.name.contains("generate", ignoreCase = true)
            } ?: return Result.failure(
                IllegalStateException("no String generate(String) on ${engineClass.name}")
            )
            notes += "generate method: ${generate.name}"
            Result.success(Binding(engineClass, optionsClass, instance, generate, notes))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Builder-style construction, which every variant of this API has used:
     * Options.builder() -> setters -> build() -> Engine.createFromOptions().
     * Setter names are matched loosely so a rename does not break the probe.
     */
    private fun construct(
        context: Context,
        engineClass: Class<*>,
        optionsClass: Class<*>?,
        modelPath: String,
        backend: String,
        notes: MutableList<String>
    ): Any {
        if (optionsClass == null) {
            // Try a plain (Context, String) or (String) constructor.
            engineClass.constructors.forEach { c ->
                val p = c.parameterTypes
                if (p.size == 2 && p[1] == String::class.java) {
                    return c.newInstance(context, modelPath)
                }
                if (p.size == 1 && p[0] == String::class.java) {
                    return c.newInstance(modelPath)
                }
            }
            error("no options class and no usable constructor on ${engineClass.name}")
        }

        val builder = optionsClass.methods
            .firstOrNull { it.name == "builder" && it.parameterCount == 0 }
            ?.invoke(null)
            ?: error("no ${optionsClass.simpleName}.builder()")

        var builderObj = builder
        fun call(namePart: String, vararg args: Any?): Boolean {
            val m = builderObj.javaClass.methods.firstOrNull {
                it.name.contains(namePart, ignoreCase = true) &&
                    it.parameterCount == args.size
            } ?: return false
            builderObj = m.invoke(builderObj, *args) ?: builderObj
            notes += "applied ${m.name}"
            return true
        }

        if (!call("ModelPath", modelPath)) {
            error("builder has no model-path setter")
        }
        // Optional knobs — absence is fine, it just means a default.
        call("MaxTokens", 1024)
        call("MaxTopK", 40)

        // Backend selection: the whole point of the measurement. Enum values
        // are matched by name so CPU/GPU/NPU all work without knowing the type.
        val applied = applyBackend(builderObj, backend, notes)
        if (!applied) notes += "WARNING: no backend setter found — runtime default in use"

        val options = builderObj.javaClass.methods
            .firstOrNull { it.name == "build" && it.parameterCount == 0 }
            ?.invoke(builderObj)
            ?: error("builder has no build()")

        val factory = engineClass.methods.firstOrNull {
            it.name.contains("createFrom", ignoreCase = true) && it.parameterCount == 2
        }
        if (factory != null) {
            notes += "factory: ${factory.name}"
            return factory.invoke(null, context, options)
                ?: error("${factory.name} returned null")
        }
        val single = engineClass.methods.firstOrNull {
            it.name.contains("create", ignoreCase = true) && it.parameterCount == 1
        } ?: error("no create factory on ${engineClass.name}")
        notes += "factory: ${single.name}"
        return single.invoke(null, options) ?: error("${single.name} returned null")
    }

    private fun applyBackend(builder: Any, backend: String, notes: MutableList<String>): Boolean {
        val setter = builder.javaClass.methods.firstOrNull {
            it.name.contains("Backend", ignoreCase = true) && it.parameterCount == 1
        } ?: return false
        val type = setter.parameterTypes[0]
        val value: Any? = if (type.isEnum) {
            type.enumConstants?.firstOrNull { c ->
                (c as Enum<*>).name.equals(backend, ignoreCase = true)
            } ?: run {
                notes += "backend '$backend' not in ${type.simpleName}: " +
                    type.enumConstants.orEmpty().joinToString { (it as Enum<*>).name }
                return false
            }
        } else {
            backend
        }
        setter.invoke(builder, value)
        notes += "backend requested: $backend via ${setter.name}"
        return true
    }
}
