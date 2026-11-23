package com.meetily.mobile

import java.io.DataInputStream
import java.io.File
import java.util.jar.JarFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Keeps src/main/baseline-prof.txt honest. AGP compiles it into the APK's
 * profile and drops, without a word, any rule that matches nothing, so a
 * renamed class or method would quietly stop being compiled ahead of time.
 *
 * Every line has to parse (as AGP's profile parser reads it, and stricter);
 * every app class a rule names has to be declared in src/main/java; and every
 * app rule has to match a compiled class, and a compiled method when it names
 * one, on this test's classpath. Kotlin standard library rules are matched
 * the same way. Material and RecyclerView rules are only held to their
 * packages: the local type-check's stand-ins for those libraries are not the
 * real classes.
 */
class BaselineProfileTest {

    /** Unit tests run from the module directory; the others are fallbacks. */
    private fun mainFile(path: String): File =
        listOf("src/main/$path", "app/src/main/$path", "android/app/src/main/$path")
            .map(::File).firstOrNull { it.exists() }
            ?: throw AssertionError("$path not found from ${File(".").absolutePath}")

    private val lines: List<String> by lazy { mainFile("baseline-prof.txt").readLines() }

    private val sourceRoot: File by lazy { mainFile("java") }

    /** Every rule with its line number; comments and blank lines left out. */
    private val rules: List<Pair<Int, ProfileRule>> by lazy {
        lines.mapIndexedNotNull { index, line ->
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            ProfileRule.parse(line).rule?.let { (index + 1) to it }
        }
    }

    private val appRules get() = rules.filter { it.second.target.startsWith(APP_TARGET) }

    @Test
    fun everyLineIsAValidRule() {
        val errors = lines.mapIndexedNotNull { index, line ->
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            ProfileRule.parse(line).error?.let { "line ${index + 1}: $it: $line" }
        }
        assertEquals("invalid baseline-prof.txt lines", emptyList<String>(), errors)
        // Big enough to be the curated file, small enough to stay well under
        // the size ART takes a profile at.
        assertTrue("${rules.size} rules", rules.size in 100..5000)
        assertTrue(mainFile("baseline-prof.txt").length() < 512 * 1024)
    }

    @Test
    fun noRuleIsRepeated() {
        val repeated = rules.groupBy { it.second.text }.filterValues { it.size > 1 }.keys
        assertEquals("repeated rules", emptySet<String>(), repeated)
    }

    @Test
    fun rulesNameOnlyTheAppAndLibrariesWithoutTheirOwnProfile() {
        // A misspelt package matches nothing and would never be noticed.
        val allowed = listOf(
            APP_TARGET,
            "Lkotlin/",
            "Lcom/google/android/material/",
            "Landroidx/recyclerview/widget/"
        )
        val stray = rules.filter { (_, rule) -> allowed.none { rule.target.startsWith(it) } }
            .map { (line, rule) -> "line $line: ${rule.text}" }
        assertEquals("rules outside the expected packages", emptyList<String>(), stray)
    }

    @Test
    fun appRulesAvoidNamesTheCompilerMayChange() {
        // A lambda's number moves whenever one is added above it, and an
        // internal member's name ends in the module name, which differs
        // between this test's build and the release build. Both are named by
        // a family (name$lambda$*, name$*) instead.
        val fragile = appRules.filter { (_, rule) ->
            val method = rule.method ?: return@filter false
            !rule.hasWildcard(method) &&
                (Regex("\\\$lambda\\\$\\d").containsMatchIn(method) || "\$main" in method ||
                    "\$app_" in method)
        }.map { (line, rule) -> "line $line: ${rule.text}" }
        assertEquals("rules naming generated method names", emptyList<String>(), fragile)
    }

    @Test
    fun everyAppClassIsDeclaredInTheSources() {
        val missing = appRules.mapNotNull { (line, rule) ->
            declarationProblem(rule.target)?.let { "line $line: $it" }
        }
        assertEquals("app classes named in baseline-prof.txt", emptyList<String>(), missing)
    }

    @Test
    fun everyAppRuleMatchesACompiledClassAndMethod() {
        val problems = appRules.mapNotNull { (line, rule) ->
            val classes = compiledAppClasses.filter { rule.matchesClass(it) }
            when {
                classes.isEmpty() -> "line $line: no compiled class matches ${rule.text}"
                rule.isClassRule -> null
                classes.none { cls -> methodsOf(cls).any { (n, d) -> rule.matchesMethod(n, d) } } ->
                    "line $line: no compiled method matches ${rule.text}"
                else -> null
            }
        }
        assertEquals("app rules matching nothing", emptyList<String>(), problems)
    }

    @Test
    fun everyKotlinRuleMatchesAStandardLibraryMethod() {
        val problems = rules.filter { it.second.target.startsWith("Lkotlin/") }.mapNotNull { (line, rule) ->
            // A class by name, or a file facade's family (CollectionsKt*);
            // never a whole package.
            val target = rule.target
            if (rule.hasWildcard(target.dropLast(2)) || (rule.hasWildcard(target) && !target.endsWith("Kt*;"))) {
                return@mapNotNull "line $line: name the class or its facade: ${rule.text}"
            }
            val classes = stdlibClasses.filter { rule.matchesClass(it) }
            when {
                classes.isEmpty() -> "line $line: no class matches ${rule.text}"
                rule.isClassRule -> null
                classes.none { cls -> methodsOf(cls).any { (n, d) -> rule.matchesMethod(n, d) } } ->
                    "line $line: no method matches ${rule.text}"
                else -> null
            }
        }
        assertEquals("Kotlin rules matching nothing", emptyList<String>(), problems)
    }

    @Test
    fun theThreeJourneysAreCompiledAheadOfTime() {
        // Hot (H) rules must reach these: start-up, the home list, opening a
        // meeting and its first render, and binding transcript lines.
        val hot = listOf(
            "com/meetily/mobile/RecapApp" to "onCreate",
            "com/meetily/mobile/MainActivity" to "onCreate",
            "com/meetily/mobile/MainActivity" to "onResume",
            "com/meetily/mobile/MainActivity" to "refresh",
            "com/meetily/mobile/MeetingAdapter" to "onBindViewHolder",
            "com/meetily/mobile/data/MeetingStore" to "listLight",
            "com/meetily/mobile/data/LightJson" to "parse",
            "com/meetily/mobile/data/Meeting\$Companion" to "fromJson",
            "com/meetily/mobile/MeetingDetailActivity" to "onCreate",
            "com/meetily/mobile/data/MeetingStore" to "loadStaged",
            "com/meetily/mobile/MeetingDetailActivity" to "onMeetingRead",
            "com/meetily/mobile/MeetingDetailActivity" to "renderTranscript",
            "com/meetily/mobile/TranscriptLinesAdapter" to "onBindViewHolder",
            "com/meetily/mobile/TranscriptLinesAdapter" to "bindLineText",
            "com/meetily/mobile/MeetingDetailActivity" to "lineAccessibility"
        )
        val missing = hot.filter { (cls, method) ->
            val descriptors = methodsOf(cls).filter { it.first == method }
            assertTrue("$cls.$method is not compiled", descriptors.isNotEmpty())
            rules.none { (_, rule) ->
                'H' in rule.flags && rule.matchesClass(cls) &&
                    descriptors.any { (n, d) -> rule.matchesMethod(n, d) }
            }
        }
        assertEquals("not compiled ahead of time", emptyList<Pair<String, String>>(), missing)
        for (cls in listOf("com/meetily/mobile/RecapApp", "com/meetily/mobile/MainActivity")) {
            assertTrue("$cls is not a start-up class", rules.any { (_, r) -> r.text == "L$cls;" })
        }
    }

    @Test
    fun parserAcceptsAndRejectsAsIntended() {
        for (good in listOf(
            "HSPLcom/a/B;->c()V",
            "Lcom/a/B;",
            "HPLcom/a/B\$*;->**(**)**",
            "HSPLcom/a/b/**->**(**)**",
            "PLcom/a/B;-><init>(I[JLjava/lang/String;)Lcom/a/B;",
            "HLcom/a/B;->c\$lambda\$*(**)**",
            "SLkotlin/Result;->constructor-impl(Ljava/lang/Object;)Ljava/lang/Object;"
        )) {
            assertNotNull("rejected: $good", ProfileRule.parse(good).rule)
        }
        for (bad in listOf(
            " HSPLcom/a/B;->c()V", // a leading space reads as an illegal flag
            "HSPLcom/a/B;->c()V ", // trailing space (AGP allows it; this file does not)
            "HSPLcom/a/B;->c()V x", // text after the rule
            "HXLcom/a/B;->c()V", // unknown flag
            "HHLcom/a/B;->c()V", // repeated flag
            "Lcom/a/B;->c()V", // a method rule needs a flag
            "HSPLcom/a/B;", // a class rule takes none
            "HSPcom/a/B;->c()V", // no L
            "HSPLcom/a/B->c()V", // class without its ;
            "HSPLcom/a/B;->c(Q)V", // no such type
            "HSPLcom/a/B;->c(Ljava/lang/String)V", // object type without its ;
            "HSPLcom/a/B;->c()", // no return type
            "HSPLcom/a/B;->c)(V", // brackets the wrong way round
            "HSPLcom/a/B;->(I)V", // no method name
            "HSPLcom/a//B;->c()V", // empty package segment
            "HSPLcom/a/B;->c()*", // a single * last: AGP's parser reads past the end
            "Lcom/a/B; # comment" // a comment after a rule
        )) {
            assertNull("accepted: $bad", ProfileRule.parse(bad).rule)
        }
        // Wildcards as AGP's parser (profgen) matches them.
        val family = ProfileRule.parse("HPLcom/a/B;->c\$lambda\$*(**)**").rule!!
        assertTrue(family.matchesMethod("c\$lambda\$12", "(Lcom/a/B;I)V"))
        assertFalse(family.matchesMethod("cd", "()V"))
        assertTrue(ProfileRule.parse("HPLcom/a/B;->get*(**)**").rule!!.matchesMethod("getX", "(I)Ljava/lang/String;"))
        assertFalse(ProfileRule.parse("HPLcom/a/B;->c(I)V").rule!!.matchesMethod("c", "(J)V"))
        val nested = ProfileRule.parse("HPLcom/a/B\$*;->**(**)**").rule!!
        assertTrue(nested.matchesClass("com/a/B\$Holder"))
        assertFalse(nested.matchesClass("com/a/B"))
        assertFalse(nested.matchesClass("com/a/b/C\$D"))
        val packageWide = ProfileRule.parse("HPLcom/a/**->**(**)**").rule!!
        assertTrue(packageWide.matchesClass("com/a/b/C\$D"))
        assertFalse(packageWide.matchesClass("com/ab/C"))
        // * stays inside one name; ** crosses packages.
        assertFalse(ProfileRule.parse("HPLcom/a/*;->**(**)**").rule!!.matchesClass("com/a/b/C"))
    }

    // --- Sources -----------------------------------------------------------

    /**
     * Null when the class [target] names is declared in src/main/java: its
     * top-level class, object, interface or file (for a FileKt class), and
     * its named nested classes. Anonymous and generated parts ($1,
     * $onCreate$2, $WhenMappings) are left to the compiled check.
     */
    private fun declarationProblem(target: String): String? {
        val body = target.removePrefix("L").substringBefore(';')
        val fixed = body.takeWhile { it != '*' && it != '?' }
        val slash = fixed.lastIndexOf('/')
        val dir = File(sourceRoot, fixed.substring(0, slash))
        val parts = fixed.substring(slash + 1).split('$')
        val outer = parts.first()
        // A wildcard inside the top-level name would leave nothing to check.
        if (parts.size == 1 && fixed.length < body.length) return "name the class in $target"
        val files = dir.listFiles { file -> file.isFile && file.extension == "kt" }.orEmpty()
        if (outer.endsWith("Kt") && File(dir, outer.removeSuffix("Kt") + ".kt").isFile) return null
        val topLevel = Regex(
            "^(?:(?:public|internal|private|data|sealed|abstract|open|enum|annotation|value|fun)\\s+)*" +
                "(?:class|object|interface)\\s+${Regex.escape(outer)}\\b",
            RegexOption.MULTILINE
        )
        val text = files.map { it.readText() }.firstOrNull { topLevel.containsMatchIn(it) }
            ?: return "no top-level $outer in ${dir.relativeTo(sourceRoot)}"
        for (nested in parts.drop(1).filter { it.isNotEmpty() }) {
            val declared = when {
                nested == "Companion" -> Regex("\\bcompanion\\s+object\\b")
                nested == "WhenMappings" || !nested.first().isUpperCase() -> continue
                else -> Regex("\\b(?:class|object|interface)\\s+${Regex.escape(nested)}\\b")
            }
            if (!declared.containsMatchIn(text)) return "no $nested in $outer ($target)"
        }
        return null
    }

    // --- Compiled classes --------------------------------------------------

    /** Every compiled class under com/meetily/mobile on the test classpath. */
    private val compiledAppClasses: List<String> by lazy {
        classesBeside("com/meetily/mobile/RecapApp.class", "com/meetily/mobile/")
    }

    /** Every class of the Kotlin standard library on the test classpath. */
    private val stdlibClasses: List<String> by lazy { classesBeside("kotlin/Unit.class", "kotlin/") }

    /**
     * The classes under [prefix] in the directory or jar that holds [probe],
     * as binary names (com/meetily/mobile/MainActivity).
     */
    private fun classesBeside(probe: String, prefix: String): List<String> {
        val url = javaClass.classLoader?.getResource(probe)
            ?: throw AssertionError("$probe is not on the classpath")
        val names = mutableListOf<String>()
        when (url.protocol) {
            "file" -> {
                var root = File(url.toURI())
                repeat(probe.count { it == '/' } + 1) { root = root.parentFile }
                File(root, prefix).walk()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { names += it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class") }
            }
            "jar" -> {
                val path = url.path.substringAfter("file:").substringBefore("!")
                JarFile(File(java.net.URI("file:$path"))).use { jar ->
                    for (entry in jar.entries()) {
                        if (entry.name.startsWith(prefix) && entry.name.endsWith(".class")) {
                            names += entry.name.removeSuffix(".class")
                        }
                    }
                }
            }
            else -> fail("cannot list classes from $url")
        }
        return names
    }

    private val methodCache = HashMap<String, List<Pair<String, String>>?>()

    /** The methods of a compiled class as (name, descriptor); null when absent. */
    private fun classMethods(binaryName: String): List<Pair<String, String>>? =
        methodCache.getOrPut(binaryName) {
            javaClass.classLoader?.getResourceAsStream("$binaryName.class")?.let { stream ->
                DataInputStream(stream.buffered()).use { ClassFileMethods.read(it) }
            }
        }

    private fun methodsOf(binaryName: String): List<Pair<String, String>> =
        classMethods(binaryName) ?: throw AssertionError("no compiled class $binaryName")

    companion object {
        private const val APP_TARGET = "Lcom/meetily/mobile/"
    }
}

/**
 * One line of a Baseline Profile, parsed by the grammar AGP's parser
 * (profgen) uses and stricter: no whitespace anywhere, and real field types
 * wherever a signature has no wildcard. A method rule is FLAGS, the class,
 * "->", then name(params)return; a class rule is the class alone. In a
 * pattern ? is one character, * any run within a name, ** any run at all.
 */
private class ProfileRule private constructor(
    val text: String,
    val flags: String,
    val target: String,
    val method: String?,
    private val params: String?,
    private val returns: String?
) {
    class Parsed(val rule: ProfileRule?, val error: String?)

    val isClassRule: Boolean get() = method == null

    fun hasWildcard(s: String) = s.any { it == '*' || it == '?' }

    /** [binaryName] is a class name with slashes and no L or ;. */
    fun matchesClass(binaryName: String): Boolean = glob(target).matches("L$binaryName;")

    fun matchesMethod(name: String, descriptor: String): Boolean {
        val m = method ?: return false
        val close = descriptor.indexOf(')')
        return glob(m).matches(name) &&
            glob(params!!).matches(descriptor.substring(1, close)) &&
            glob(returns!!).matches(descriptor.substring(close + 1))
    }

    companion object {
        private const val NAME = "[A-Za-z0-9_$]+"
        private val CLASS_NAME = Regex("$NAME(?:/$NAME)*")
        private val PATTERN = Regex("[A-Za-z0-9_$/;\\[*?]+")

        fun parse(line: String): Parsed {
            fun bad(why: String) = Parsed(null, why)
            // profgen looks one character past a *, to tell * from **: a
            // line ending in a single * would fail the build, not the rule.
            if (line.endsWith("*") && !line.endsWith("**")) return bad("a single * at the end")
            var i = 0
            while (i < line.length && line[i] in "HSP") i++
            val flags = line.substring(0, i)
            if (flags.toSet().size != flags.length) return bad("a flag given twice")
            if (i >= line.length || line[i] != 'L') return bad("expected flags then L at column ${i + 1}")
            val arrow = line.indexOf("->", i)
            val target = line.substring(i, if (arrow < 0) line.length else arrow)
            targetProblem(target)?.let { return bad(it) }
            if (arrow < 0) {
                if (flags.isNotEmpty()) return bad("a class rule takes no flags")
                if (!target.endsWith(";")) return bad("a class rule ends with ;")
                return Parsed(ProfileRule(line, flags, target, null, null, null), null)
            }
            if (flags.isEmpty()) return bad("a method rule needs a flag (H, S or P)")
            val rest = line.substring(arrow + 2)
            val open = rest.indexOf('(')
            val close = rest.indexOf(')')
            if (open <= 0 || close < open || rest.indexOf('(', open + 1) >= 0 ||
                rest.indexOf(')', close + 1) >= 0
            ) {
                return bad("expected name(params)return after ->")
            }
            val method = rest.substring(0, open)
            val params = rest.substring(open + 1, close)
            val returns = rest.substring(close + 1)
            methodProblem(method)?.let { return bad(it) }
            val wildParams = params.any { it == '*' || it == '?' }
            if (wildParams && !PATTERN.matches(params)) return bad("bad parameter pattern")
            if (!wildParams && !fieldTypes(params)) return bad("bad parameter types")
            val wildReturn = returns.any { it == '*' || it == '?' }
            if (wildReturn && !PATTERN.matches(returns)) return bad("bad return pattern")
            if (!wildReturn && returns != "V" && !(fieldTypes(returns) && oneType(returns))) {
                return bad("bad return type")
            }
            return Parsed(ProfileRule(line, flags, target, method, params, returns), null)
        }

        private fun targetProblem(target: String): String? {
            if (!Regex("L[A-Za-z0-9_$/*?;]+").matches(target)) return "bad class"
            val body = when {
                target.endsWith(";") -> target.substring(1, target.length - 1)
                target.endsWith("**") -> target.substring(1)
                else -> return "a class ends with ; (or ** before ->)"
            }
            if (';' in body) return "; inside a class name"
            if (body.split('/').any { it.isEmpty() }) return "empty name in a class"
            if (body.none { it == '*' || it == '?' } && !CLASS_NAME.matches(body)) return "bad class name"
            return null
        }

        private fun methodProblem(method: String): String? {
            if (method.isEmpty()) return "no method name"
            if (method == "<init>" || method == "<clinit>") return null
            return if (Regex("[A-Za-z0-9_$*?-]+").matches(method)) null else "bad method name"
        }

        /** [s] is a run of field types, possibly none. */
        private fun fieldTypes(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                while (i < s.length && s[i] == '[') i++
                if (i >= s.length) return false
                when (s[i]) {
                    'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> i++
                    'L' -> {
                        val end = s.indexOf(';', i)
                        if (end < 0 || !CLASS_NAME.matches(s.substring(i + 1, end))) return false
                        i = end + 1
                    }
                    else -> return false
                }
            }
            return true
        }

        private fun oneType(s: String): Boolean {
            val body = s.trimStart('[')
            return body.length == 1 || (body.startsWith("L") && body.indexOf(';') == body.length - 1)
        }

        /** The pattern as profgen matches it (its Part.WildChar, WildPart and WildParts). */
        private fun glob(pattern: String): Regex {
            val out = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '*' && pattern.getOrNull(i + 1) == '*' -> {
                        out.append("[-\\w$<>/;\\[\\]]*")
                        i += 2
                        continue
                    }
                    c == '*' -> out.append("[-\\w$<>\\[\\]]*")
                    c == '?' -> out.append("[\\w<>\\[\\]]")
                    else -> out.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(out.toString())
        }
    }
}

/** Reads just the method names and descriptors out of a class file. */
private object ClassFileMethods {

    fun read(input: DataInputStream): List<Pair<String, String>> {
        check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        var index = 1
        while (index < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[index] = input.readUTF()
                3, 4 -> skip(input, 4)
                5, 6 -> {
                    skip(input, 8)
                    index++ // eight-byte constants take two slots
                }
                7, 8, 16, 19, 20 -> skip(input, 2)
                9, 10, 11, 12, 17, 18 -> skip(input, 4)
                15 -> skip(input, 3)
                else -> error("constant pool tag $tag")
            }
            index++
        }
        skip(input, 6) // access flags, this class, superclass
        skip(input, 2 * input.readUnsignedShort()) // interfaces
        repeat(input.readUnsignedShort()) { // fields
            skip(input, 6)
            skipAttributes(input)
        }
        return List(input.readUnsignedShort()) {
            input.readUnsignedShort()
            val name = utf8[input.readUnsignedShort()] ?: error("method name")
            val descriptor = utf8[input.readUnsignedShort()] ?: error("method descriptor")
            skipAttributes(input)
            name to descriptor
        }
    }

    private fun skipAttributes(input: DataInputStream) {
        repeat(input.readUnsignedShort()) {
            input.readUnsignedShort()
            skip(input, input.readInt())
        }
    }

    private fun skip(input: DataInputStream, bytes: Int) {
        input.readFully(ByteArray(bytes))
    }
}
