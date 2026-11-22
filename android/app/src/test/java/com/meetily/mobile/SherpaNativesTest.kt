package com.meetily.mobile

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the JNI surface of the vendored sherpa-onnx Kotlin API.
 *
 * scripts/check_sherpa_jni.py runs in CI right after fetch_sherpa_libs.sh and
 * checks every name in scripts/sherpa_jni_natives.txt against the exported
 * symbols of each ABI's libsherpa-onnx-jni.so. It reads the natives from the
 * Kotlin source; this keeps that list equal to the natives of the COMPILED
 * classes, the names the VM will actually look up, so a slip in either the
 * list or the script's source scan fails here.
 */
class SherpaNativesTest {

    private val pkg = "com.k2fsa.sherpa.onnx"

    /** Unit tests run from the module directory; the others are fallbacks. */
    private fun find(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: run {
                fail("${candidates.first()} not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }

    private fun vendoredDir() = find(
        "src/main/java/com/k2fsa/sherpa/onnx",
        "app/src/main/java/com/k2fsa/sherpa/onnx",
        "android/app/src/main/java/com/k2fsa/sherpa/onnx"
    )

    private fun listFile() = find(
        "../scripts/sherpa_jni_natives.txt",
        "scripts/sherpa_jni_natives.txt",
        "android/scripts/sherpa_jni_natives.txt"
    )

    private val topLevel = Regex(
        """^(?:(?:data|enum|open|abstract|sealed)\s+)*(?:class|object)\s+(\w+)""",
        RegexOption.MULTILINE
    )

    /**
     * Every class compiled from the vendored files, nested ones included,
     * loaded WITHOUT initialisation: their static initialisers load the
     * native library, which a JVM test does not have.
     */
    private fun vendoredClasses(): List<Class<*>> {
        val loader = javaClass.classLoader
        fun withNested(c: Class<*>): List<Class<*>> =
            listOf(c) + c.declaredClasses.flatMap { withNested(it) }
        return vendoredDir().listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
            .flatMap { file ->
                val names = topLevel.findAll(file.readText()).map { it.groupValues[1] }.toList()
                val facade = runCatching {
                    Class.forName("$pkg.${file.nameWithoutExtension}Kt", false, loader)
                }.getOrNull()
                names.map { Class.forName("$pkg.$it", false, loader) } + listOfNotNull(facade)
            }
            .flatMap { withNested(it) }
    }

    private fun natives(cls: Class<*>) =
        cls.declaredMethods.filter { Modifier.isNative(it.modifiers) }

    private fun mangle(s: String): String = buildString {
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

    private fun listed(): Set<String> =
        listFile().readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    @Test
    fun listMatchesTheCompiledNatives() {
        val compiled = vendoredClasses().flatMap { cls ->
            natives(cls).map { "Java_" + mangle(cls.name) + "_" + mangle(it.name) }
        }.toSet()
        assertTrue("the vendored classes declare no natives", compiled.isNotEmpty())
        assertEquals(
            "sherpa_jni_natives.txt is out of step with the vendored classes; its entries should be:\n" +
                compiled.sorted().joinToString("\n") + "\n",
            compiled,
            listed()
        )
    }

    @Test
    fun everyDeclarationWasFound() {
        // A native in a class the scan above missed would be absent from both
        // sides of the comparison; counting declarations catches that.
        val declared = vendoredDir().listFiles { f -> f.extension == "kt" }!!.sumOf { file ->
            Regex("""^\s*(?:@\w+\s+)*(?:(?:private|public|internal|protected)\s+)?external\s+fun\b""",
                RegexOption.MULTILINE).findAll(file.readText()).count()
        }
        assertEquals(declared, vendoredClasses().sumOf { natives(it).size })
    }

    @Test
    fun nativesAreNotOverloaded() {
        // The VM tries the short name first; an overload needs the long,
        // signature-mangled name, which neither the list nor the CI check
        // models.
        for (cls in vendoredClasses()) {
            val names = natives(cls).map { it.name }
            assertEquals("${cls.name} overloads a native", names.toSet().size, names.size)
        }
    }

    @Test
    fun vendoredFilesNameTheFetchedTag() {
        val script = find(
            "../scripts/fetch_sherpa_libs.sh",
            "scripts/fetch_sherpa_libs.sh",
            "android/scripts/fetch_sherpa_libs.sh"
        ).readText()
        val ver = Regex("""(?m)^VER="([^"]+)"""").find(script)?.groupValues?.get(1)
            ?: run {
                fail("VER= not found in fetch_sherpa_libs.sh")
                throw IllegalStateException()
            }
        for (file in vendoredDir().listFiles { f -> f.extension == "kt" }!!) {
            val first = file.readLines().firstOrNull().orEmpty()
            assertTrue("${file.name}: $first", first.contains("(tag $ver)"))
        }
    }
}
