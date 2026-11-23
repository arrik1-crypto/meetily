package com.meetily.mobile

import com.meetily.mobile.data.NativeEngines
import com.meetily.mobile.llm.LlamaBridge
import com.meetily.mobile.whisper.WhisperBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the JNI surface of Recap's native library.
 *
 * jni_exports.map hides every symbol of libmeetily_native (both CPU builds)
 * except the JNI entry points. A native that ends up hidden, misnamed or left
 * out of the library is not a build error: it is an UnsatisfiedLinkError at
 * its first call, on a phone. So the three descriptions of that surface must
 * agree here, before anything is built:
 *  - the natives of the bridge classes, read from the compiled classes, so the
 *    names are the ones the VM will look up;
 *  - the Java_ functions the library's C sources define (per CMakeLists.txt);
 *  - jni_exports.txt, which scripts/check_jni_exports.py checks the built
 *    libraries against in CI.
 * And the map must export every listed name and nothing that looks like
 * engine internals, and the names NativeEngines loads must be the libraries
 * the two Gradle modules build.
 */
class NativeExportsTest {

    /** Every class with natives in our library, and the library they are in. */
    private val bridges: Map<Class<*>, String> = mapOf(
        WhisperBridge::class.java to NativeEngines.BASELINE,
        LlamaBridge::class.java to NativeEngines.BASELINE
    )

    /** Unit tests run from the module directory; the others are fallbacks. */
    private fun source(path: String): File {
        val candidates = listOf("src/main/$path", "app/src/main/$path", "android/app/src/main/$path")
        return candidates.map(::File).firstOrNull { it.exists() }
            ?: run {
                fail("$path not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }
    }

    private fun natives(cls: Class<*>): List<Method> =
        cls.declaredMethods.filter { Modifier.isNative(it.modifiers) }

    /** Matched against [kotlinCode] with the literals blanked, so only declarations count. */
    private val nativeDeclaration = Regex("""\bexternal\s+fun\b""")

    /**
     * The code of a Kotlin source file: each comment becomes one space (block
     * comments nest in Kotlin), and so does each string or character literal
     * unless [keepLiterals]. Literals are skipped whole either way, so the
     * slash-star in a MIME type literal cannot start a comment.
     */
    private fun kotlinCode(text: String, keepLiterals: Boolean): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val start = i
            when {
                text.startsWith("//", i) -> {
                    i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    out.append(' ')
                }
                text.startsWith("/*", i) -> {
                    var depth = 0
                    do {
                        when {
                            text.startsWith("/*", i) -> { depth++; i += 2 }
                            text.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    } while (depth > 0 && i < text.length)
                    out.append(' ')
                }
                text.startsWith("\"\"\"", i) -> {
                    i = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append(if (keepLiterals) text.substring(start, i) else " ")
                }
                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i++]
                    while (i < text.length && text[i] != quote && text[i] != '\n') {
                        i += if (text[i] == '\\') 2 else 1
                    }
                    i = minOf(i + 1, text.length)
                    out.append(if (keepLiterals) text.substring(start, i) else " ")
                }
                else -> out.append(text[i++])
            }
        }
        return out.toString()
    }

    /** (library, symbol) for every native the Kotlin side declares. */
    private fun kotlinExports(): Set<Pair<String, String>> =
        bridges.flatMap { (cls, lib) ->
            natives(cls).map { lib to JniNames.shortName(cls.name, it.name) }
        }.toSet()

    /** (library, symbol) lines of jni_exports.txt. */
    private fun listed(): Set<Pair<String, String>> =
        source("cpp/jni_exports.txt").readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split(Regex("\\s+"))
                assertEquals("malformed jni_exports.txt line: $line", 2, parts.size)
                parts[0] to parts[1]
            }
            .toSet()

    @Test
    fun everyBridgeHasNatives() {
        for ((cls, _) in bridges) {
            assertTrue("${cls.name} declares no natives", natives(cls).isNotEmpty())
        }
    }

    @Test
    fun listMatchesTheKotlinNatives() {
        val expected = kotlinExports()
        val body = expected.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("\n") { "${it.first} ${it.second}" }
        assertEquals(
            "jni_exports.txt is out of step with the Kotlin natives; its entries should be:\n$body\n",
            expected,
            listed()
        )
    }

    @Test
    fun nativesAreNotOverloaded() {
        // The VM tries the short name first; an overload needs the long,
        // signature-mangled one, which jni_exports.txt and the CI check do
        // not model.
        for ((cls, _) in bridges) {
            val names = natives(cls).map { it.name }
            assertEquals("${cls.name} overloads a native", names.toSet().size, names.size)
        }
    }

    @Test
    fun eachLibraryDefinesExactlyItsListedSymbols() {
        val cmake = source("cpp/CMakeLists.txt").readText()
        val libOfVar = Regex("""set\((\w+)\s+(\w+)\$\{MEETILY_LIB_SUFFIX\}\)""")
            .findAll(cmake).associate { it.groupValues[1] to it.groupValues[2] }
        val sourcesOf = Regex("""add_library\(\$\{(\w+)\}\s+SHARED\s+([^)]*)\)""")
            .findAll(cmake).associate { m ->
                val lib = libOfVar[m.groupValues[1]]
                    ?: run {
                        fail("CMakeLists.txt: no library name for \${${m.groupValues[1]}}")
                        throw IllegalStateException()
                    }
                lib to m.groupValues[2].trim().split(Regex("\\s+"))
            }
        assertEquals(bridges.values.toSet(), sourcesOf.keys)

        val definition = Regex("""JNIEXPORT[^;{}]*?JNICALL\s+(Java_\w+)\s*\(""")
        val listed = listed()
        for ((lib, files) in sourcesOf) {
            val defined = files.flatMap { file ->
                definition.findAll(source("cpp/$file").readText()).map { it.groupValues[1] }.toList()
            }.toSet()
            val want = listed.filter { it.first == lib }.map { it.second }.toSet()
            assertEquals("Java_ functions compiled into lib$lib.so ($files)", want, defined)
        }
    }

    @Test
    fun mapExportsTheListedSymbolsAndHidesTheRest() {
        val text = source("cpp/jni_exports.map").readLines()
            .joinToString("\n") { it.substringBefore('#') }
        val node = text.substringAfter('{').substringBeforeLast('}')
        val section = { s: String -> s.split(';').map { it.trim() }.filter { it.isNotEmpty() } }
        val globals = section(node.substringAfter("global:").substringBefore("local:"))
        val locals = section(node.substringAfter("local:"))
        assertEquals("everything not global must be local", listOf("*"), locals)

        for ((lib, symbol) in listed()) {
            assertTrue(
                "$symbol (lib$lib.so) is not exported by jni_exports.map",
                globals.any { JniNames.glob(it).matches(symbol) }
            )
        }
        val internals = listOf(
            "meetily_render_chat",
            "meetily_vad_init",
            "meetily_engine_log",
            "whisper_vad_init_from_file_with_params",
            "ggml_init",
            "llama_backend_init",
            "whisper_full",
            "_Z26common_chat_templates_initPK11llama_model",
            "_ZNSt6__ndk112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEED2Ev",
            "__cxa_throw",
            "__emutls_get_address"
        )
        for (symbol in internals) {
            assertTrue(
                "jni_exports.map would export $symbol",
                globals.none { JniNames.glob(it).matches(symbol) }
            )
        }
    }

    @Test
    fun onlyTheBridgesDeclareNativesAndEachLoadsItsLibrary() {
        // A new class with natives in this library has to be added to
        // [bridges], or none of the checks above would see it. Natives in
        // com.k2fsa are sherpa-onnx's own library and are not checked here.
        val root = source("java/com/meetily/mobile")
        val declaring = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { nativeDeclaration.containsMatchIn(kotlinCode(it.readText(), keepLiterals = false)) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        val expected = bridges.keys.map {
            it.name.removePrefix("com.meetily.mobile.").replace('.', '/') + ".kt"
        }.toSet()
        assertEquals("files declaring natives (add a new bridge to [bridges])", expected, declaring)

        // One loader picks the CPU build and loads it once for every bridge.
        for ((cls, lib) in bridges) {
            val file = File(root, cls.name.removePrefix("com.meetily.mobile.").replace('.', '/') + ".kt")
            assertTrue(
                "${file.name} should load lib$lib.so through NativeEngines.load()",
                kotlinCode(file.readText(), keepLiterals = false).contains("NativeEngines.load()")
            )
        }
        // Loading one of our libraries anywhere else could load the other CPU
        // build beside it. (sherpa-onnx's library has loaders of its own.)
        val direct = Regex("""loadLibrary\(\s*"meetily""")
        val loaders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { direct.containsMatchIn(kotlinCode(it.readText(), keepLiterals = true)) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        assertEquals(
            "files loading a meetily library directly (use NativeEngines.load())",
            emptySet<String>(),
            loaders
        )
    }

    /** A file of the Gradle project; unit tests run from the app module. */
    private fun projectFile(path: String): File =
        listOf(File("../$path"), File(path), File("android/$path")).firstOrNull { it.exists() }
            ?: run {
                fail("$path not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }

    @Test
    fun libraryNamesAgreeWithTheBuild() {
        // A name NativeEngines loads that no module builds is an
        // UnsatisfiedLinkError on every phone, or for the fast build a
        // silent fall back to the slow one.
        val cmake = source("cpp/CMakeLists.txt").readText()
        val base = Regex("""set\(NATIVE_LIB\s+(\w+)\$\{MEETILY_LIB_SUFFIX\}\)""")
            .find(cmake)?.groupValues?.get(1)
        assertEquals("CMakeLists.txt library name", NativeEngines.BASELINE, base)

        val targets = Regex("""targets\s*\+=\s*listOf\(([^)]*)\)""")
        val quoted = Regex("\"([^\"]+)\"")
        fun targetsOf(text: String): List<String> = targets.findAll(text)
            .flatMap { m -> quoted.findAll(m.groupValues[1]).map { it.groupValues[1] } }
            .toList()

        val app = kotlinCode(projectFile("app/build.gradle.kts").readText(), keepLiterals = true)
        assertEquals("app module CMake targets", listOf(NativeEngines.BASELINE), targetsOf(app))

        val v82 = kotlinCode(projectFile("native-v82/build.gradle.kts").readText(), keepLiterals = true)
        assertEquals("native-v82 CMake targets", listOf(NativeEngines.DOTPROD), targetsOf(v82))
        val suffix = Regex("""-DMEETILY_LIB_SUFFIX=(\w+)""").find(v82)?.groupValues?.get(1)
        assertEquals("native-v82 library name", NativeEngines.DOTPROD, NativeEngines.BASELINE + suffix)
    }

    @Test
    fun nativeScanSeesDeclarationsNotProse() {
        val prose = "/** Calls the external\n * fun countTokens. */\n" +
            "/* outer /* nested */ external fun x */\n" +
            "val mime = \"audio/*\" // external fun\n" +
            "val raw = \"\"\"external fun y\"\"\"\n" +
            "val quote = '\"'\n" +
            "val msg = \"external fun missing\"\n"
        assertTrue(
            "comments and strings were read as a native declaration",
            !nativeDeclaration.containsMatchIn(kotlinCode(prose, keepLiterals = false))
        )
        val declared = prose + "object B {\n    @JvmStatic private external fun f(): Int\n}\n"
        assertTrue(
            "a native declaration after comments and strings was missed",
            nativeDeclaration.containsMatchIn(kotlinCode(declared, keepLiterals = false))
        )
        assertTrue(kotlinCode(declared, keepLiterals = true).contains("\"audio/*\""))
    }

    @Test
    fun jniNameManglingFollowsTheSpec() {
        assertEquals(
            "Java_com_meetily_mobile_whisper_WhisperBridge_initContext",
            JniNames.shortName("com.meetily.mobile.whisper.WhisperBridge", "initContext")
        )
        // "_" is escaped, "$" (nested classes) and non-ASCII become _0xxxx.
        assertEquals(
            "Java_a_b_1c_Outer_00024Inner_do_1it_000e9",
            JniNames.shortName("a.b_c.Outer\$Inner", "do_ité")
        )
        assertEquals("_2_3", JniNames.mangle(";["))
    }

    @Test
    fun globMatchesLikeTheLinker() {
        assertTrue(JniNames.glob("Java_*").matches("Java_x_y"))
        assertTrue(JniNames.glob("JNI_OnLoad*").matches("JNI_OnLoad"))
        assertTrue(JniNames.glob("a?c").matches("abc"))
        assertTrue(!JniNames.glob("Java_*").matches("xJava_"))
        assertTrue(!JniNames.glob("a.c").matches("abc"))
    }
}

/** JNI symbol names, as in the JNI specification's "Resolving Native Method Names". */
private object JniNames {

    fun shortName(className: String, methodName: String): String =
        "Java_" + mangle(className) + "_" + mangle(methodName)

    fun mangle(s: String): String = buildString {
        for (c in s) {
            when {
                c == '.' || c == '/' -> append('_')
                c == '_' -> append("_1")
                c == ';' -> append("_2")
                c == '[' -> append("_3")
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> append(c)
                else -> append("_0").append(String.format("%04x", c.code))
            }
        }
    }

    /** A version-script pattern as a regex: star and question mark only. */
    fun glob(pattern: String): Regex {
        require('[' !in pattern) { "character classes are not supported here: $pattern" }
        return Regex(pattern.split('*').joinToString(".*") { part ->
            part.split('?').joinToString(".") { Regex.escape(it) }
        })
    }
}
