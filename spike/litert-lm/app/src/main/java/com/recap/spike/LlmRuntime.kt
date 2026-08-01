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

    /**
     * Known from the CI artifact dump, not guessed any more. LiteRT-LM 0.15.0:
     *
     *   Engine(EngineConfig) : AutoCloseable
     *     initialize()
     *     createSession(SessionConfig) : Session
     *     createConversation(ConversationConfig) : Conversation
     *
     *   EngineConfig(modelPath, backend, visionBackend, audioBackend,
     *                Integer, Integer, String)
     *
     *   Backend — abstract class with getName(), NOT an enum, so the concrete
     *   backends are nested objects resolved by name below.
     *
     * Still reflective because this is throwaway scaffolding and because the
     * Backend instances have to be discovered at runtime anyway.
     */
    private val ENGINE_CANDIDATES = listOf(
        "com.google.ai.edge.litertlm.Engine",
        "com.google.mediapipe.tasks.genai.llminference.LlmInference"
    )

    private val OPTIONS_CANDIDATES = listOf(
        "com.google.ai.edge.litertlm.EngineConfig",
        "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions"
    )

    private const val BACKEND_CLASS = "com.google.ai.edge.litertlm.Backend"

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
            val engine = construct(context, engineClass, optionsClass, modelPath, backend, notes)
            // The engine may generate directly (MediaPipe) or hand out a
            // Session / Conversation that does (LiteRT-LM). Try in that order.
            var target: Any = engine
            var generate = stringToString(engine.javaClass)
            if (generate == null) {
                val maker = engineClass.methods.firstOrNull {
                    it.name == "createSession" || it.name == "createConversation"
                }
                if (maker != null) {
                    // Its config argument is optional in Kotlin; null relies on
                    // the runtime's own defaults.
                    val child = maker.invoke(engine, *arrayOfNulls<Any?>(maker.parameterCount))
                    if (child != null) {
                        notes += "generating via ${maker.name} -> ${child.javaClass.simpleName}"
                        target = child
                        generate = stringToString(child.javaClass)
                    }
                }
            }
            if (generate == null) {
                return Result.failure(
                    IllegalStateException(
                        "no String->String generate found on ${engine.javaClass.name} " +
                            "or its session; methods: " +
                            target.javaClass.methods.joinToString { it.name }.take(400)
                    )
                )
            }
            notes += "generate method: ${generate.name}"
            Result.success(Binding(engineClass, optionsClass, target, generate, notes))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun stringToString(cls: Class<*>): Method? = cls.methods.firstOrNull {
        it.parameterTypes.size == 1 &&
            it.parameterTypes[0] == String::class.java &&
            it.returnType == String::class.java &&
            (
                it.name.contains("generate", ignoreCase = true) ||
                    it.name.contains("sendMessage", ignoreCase = true) ||
                    it.name.contains("runPrefillDecode", ignoreCase = true) ||
                    it.name.contains("predict", ignoreCase = true)
                )
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
        // LiteRT-LM shape: EngineConfig is a data class taking the model path
        // and three Backend slots (text, vision, audio), then Engine(config)
        // followed by initialize(). No builder anywhere.
        if (optionsClass != null && optionsClass.name.endsWith("EngineConfig")) {
            val backendObj = resolveBackend(backend, notes)
            val ctor = optionsClass.constructors
                .filter { it.parameterTypes.firstOrNull() == String::class.java }
                .maxByOrNull { it.parameterCount }
                ?: error("no EngineConfig constructor taking a model path")
            val args = arrayOfNulls<Any?>(ctor.parameterCount)
            args[0] = modelPath
            // Every Backend-typed slot gets the requested backend; the vision
            // and audio ones are irrelevant here but must not be null.
            ctor.parameterTypes.forEachIndexed { i, t ->
                if (i > 0 && t.name == BACKEND_CLASS) args[i] = backendObj
            }
            notes += "EngineConfig ctor arity ${ctor.parameterCount}"
            val config = ctor.newInstance(*args)
            val engine = engineClass.constructors
                .firstOrNull { it.parameterCount == 1 }
                ?.newInstance(config)
                ?: error("no Engine(EngineConfig) constructor")
            engineClass.methods.firstOrNull {
                it.name == "initialize" && it.parameterCount == 0
            }?.invoke(engine)?.also { notes += "initialize() called" }
                ?: notes.add("no initialize() — assuming eager construction")
            return engine
        }

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

    /**
     * Finds the concrete Backend object whose getName() matches [backend].
     *
     * Backend is a sealed-style abstract class, so the options are nested
     * objects (Backend.CPU, Backend.GPU, and whatever else exists) rather
     * than enum constants. Which ones exist IS the finding: the notes record
     * every name discovered, so a run reports the real menu even when the
     * requested backend is not on it.
     */
    private fun resolveBackend(backend: String, notes: MutableList<String>): Any? {
        val base = try {
            Class.forName(BACKEND_CLASS)
        } catch (_: Throwable) {
            notes += "Backend class absent"
            return null
        }
        val found = mutableMapOf<String, Any>()
        // Nested objects expose themselves as a static INSTANCE field.
        for (nested in base.classes) {
            val instance = try {
                nested.getField("INSTANCE").get(null)
            } catch (_: Throwable) {
                try {
                    nested.getDeclaredConstructor().newInstance()
                } catch (_: Throwable) {
                    null
                }
            } ?: continue
            val name = try {
                base.getMethod("getName").invoke(instance) as? String
            } catch (_: Throwable) {
                null
            } ?: nested.simpleName
            found[name] = instance
        }
        // Static fields on Backend itself, in case they are declared that way.
        for (f in base.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            val v = try {
                f.isAccessible = true
                f.get(null)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (base.isInstance(v)) {
                val name = try {
                    base.getMethod("getName").invoke(v) as? String
                } catch (_: Throwable) {
                    null
                } ?: f.name
                found[name] = v
            }
        }
        notes += "backends available: ${found.keys.sorted().joinToString(", ").ifEmpty { "none found" }}"
        val match = found.entries.firstOrNull { it.key.equals(backend, ignoreCase = true) }
        if (match == null) {
            notes += "WARNING: '$backend' is NOT among them"
            return found.entries.firstOrNull { it.key.equals("cpu", true) }?.value
        }
        notes += "backend selected: ${match.key}"
        return match.value
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
