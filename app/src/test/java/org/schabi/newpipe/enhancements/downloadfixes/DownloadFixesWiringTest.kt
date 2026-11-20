package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The "wiring" tests: the marked lines the plan puts into four upstream files, read as text.
 * The four files cannot run on the JVM (`android.util.Log`, `SharpStream`, the transfer
 * engine's connections), the new classes do nothing unless upstream calls them, and a rebase
 * can drop a marked line without any other test noticing.
 *
 * Per file:
 *
 * 1. **The number of lines containing `ENH:download-fixes`.** Exact where the plan lists every
 *    line (section 7: "`DownloadRunnableFallback.java` 7, `Utility.java` 4,
 *    `DownloadManager.java` 6"); "at least one" for `HlsSegmentFileTransferEngine.kt`, where the
 *    plan says "about 45".
 *
 *    | File | Lines | Plan |
 *    |---|---|---|
 *    | `giga/get/DownloadRunnableFallback.java` | 7 | section 1: one import, six lines between `:91` and `:92` |
 *    | `giga/util/Utility.java` | 4 | section 2: import, first three lines of `writeToFile` |
 *    | `giga/service/DownloadManager.java` | 6 | sections 2 and 4: import, `prepare`, `:150`, `:153`, `reportSetAside`, `:80` |
 *    | `giga/hls/transfer/HlsSegmentFileTransferEngine.kt` | >= 1 | section 3 |
 *
 * 2. **What the marked lines say.** The marked lines of a file are joined, the marker and all
 *    whitespace are removed, and the plan's code is looked up in the result.
 * 3. **Where they are**, where the place is the point: the resume check between opening the
 *    file and the seek; the atomic save before upstream's in-place write; `prepare` before the
 *    pending missions are loaded.
 * 4. **What is gone**: the delete of an unreadable state file, the external directory, the
 *    unfiltered reader's only caller, the `ByteArrayOutputStream`.
 *
 * Until the Code layer adds the lines, every test here fails for the reason "must have N
 * line(s) marked ENH:download-fixes" (found 0), except `ac2_theMessageResource` (fails with
 * "does not exist" until the resource file is there) and `ac6_noSettingOfThisFix`, which holds
 * before and after.
 */
class DownloadFixesWiringTest {

    private companion object {
        const val MARKER = "ENH:download-fixes"
        const val P = "org.schabi.newpipe.enhancements.downloadfixes."
        const val HELPERS = "java/org/schabi/newpipe/enhancements/downloadfixes"

        const val FALLBACK = "java/us/shandian/giga/get/DownloadRunnableFallback.java"
        const val UTILITY = "java/us/shandian/giga/util/Utility.java"
        const val MANAGER = "java/us/shandian/giga/service/DownloadManager.java"
        const val ENGINE = "java/us/shandian/giga/hls/transfer/HlsSegmentFileTransferEngine.kt"

        /** File to its exact number of marked lines (plan section 7). The engine is "about 45": not in this map. */
        val EXACT = linkedMapOf(FALLBACK to 7, UTILITY to 4, MANAGER to 6)

        fun withoutMarker(line: String): String = line.substringBefore("// $MARKER").substringBefore("//$MARKER")
    }

    private fun squeeze(text: String): String = MainFiles.squeeze(text)

    /** A file of `app/src/main` and its marked lines. */
    private inner class Marked(val ac: String, val path: String) {
        val all: List<String> = MainFiles.lines(ac, path)
        val lines: List<String> = all.filter { it.contains(MARKER) }

        /** Marked lines joined, without the marker comment and without whitespace. */
        val code: String = squeeze(lines.joinToString("") { withoutMarker(it) })

        /** The whole file without marker comments and without whitespace: for code that spans a marked and an unmarked part. */
        val allCode: String = squeeze(all.joinToString("") { withoutMarker(it) })

        fun expectCount(what: String): Marked {
            val expected = EXACT[path]
            if (expected != null) {
                assertEquals("$ac: app/src/main/$path must have $expected line(s) marked $MARKER ($what)", expected, lines.size)
            } else {
                assertTrue("$ac: app/src/main/$path must have at least one line marked $MARKER ($what)", lines.isNotEmpty())
            }
            return this
        }

        fun occurrences(snippet: String): Int {
            val wanted = squeeze(snippet)
            return code.windowed(wanted.length).count { it == wanted }
        }

        /** [snippet] must be found [times] times in the marked lines (whitespace ignored). */
        fun expectCode(snippet: String, times: Int = 1): Marked {
            assertEquals(
                "$ac: the lines marked $MARKER in app/src/main/$path must contain `$snippet` $times time(s); " +
                    "marked lines: ${lines.map { it.trim() }}",
                times,
                occurrences(snippet)
            )
            return this
        }

        fun expectCodeAtLeast(snippet: String, times: Int = 1): Marked {
            assertTrue(
                "$ac: the lines marked $MARKER in app/src/main/$path must contain `$snippet` at least $times time(s), found ${occurrences(snippet)}",
                occurrences(snippet) >= times
            )
            return this
        }

        /** Lines whose code (before any `//` comment) contains [snippet]. */
        fun codeLinesWith(snippet: String): List<Int> = all.indices.filter { squeeze(all[it].substringBefore("//")).contains(squeeze(snippet)) }

        /**
         * Index of the only line whose code (whitespace and a trailing marker comment removed)
         * *is* [statement]. Equality, not containment.
         */
        fun lineThatIs(statement: String, marked: Boolean): Int {
            val wanted = squeeze(statement)
            val hits = all.indices.filter { squeeze(withoutMarker(all[it])) == wanted && all[it].contains(MARKER) == marked }
            assertEquals(
                "$ac: app/src/main/$path must have exactly one ${if (marked) "marked" else "unmarked"} line that is `$statement`",
                1,
                hits.size
            )
            return hits[0]
        }

        fun indentOf(index: Int): Int = all[index].takeWhile { it == ' ' }.length

        /** The name of the method whose declaration is the nearest one above line [index]. */
        fun methodAround(index: Int): String? {
            val declaration = Regex("""^\s*(?:(?:public|private|protected|static|final|synchronized)\s+)+[\w<>\[\]., ?]+\s+(\w+)\s*\(.*\{\s*$|^ {4}(?:private |internal |override )?fun (\w+)\(""")
            for (i in index downTo 0) {
                val match = declaration.find(all[i]) ?: continue
                return match.groupValues[1].ifEmpty { match.groupValues[2] }
            }
            return null
        }
    }

    // ---- section 1, D5: DownloadRunnableFallback.java ----

    private val sixLines = listOf(
        "final ResumeCheck.Action enhResume = ResumeCheck.decide(start, mConn.getResponseCode(), mConn.getHeaderField(\"Content-Range\"));",
        "if (enhResume != ResumeCheck.Action.CONTINUE) start = 0;",
        "mMission.done = mMission.offsets[mMission.current] - mMission.offsets[0] + start;",
        "if (mMission.unknownLength) mMission.length = start;",
        "if (enhResume == ResumeCheck.Action.DISCARD_AND_REQUEST_AGAIN) throw new IOException(",
        "if (enhResume == ResumeCheck.Action.RESTART_WITH_THIS_BODY && mMission.unknownLength && mF.canSetLength()) mF.setLength(mMission.offsets[mMission.current]);"
    )

    /** AC-1: the import and the six lines of plan section 1, as the plan writes them. */
    @Test
    fun ac1_wiring_fallbackHasTheImportAndTheSixLines() {
        val file = Marked("AC-1", FALLBACK).expectCount("plan section 1: one import, six lines")
            .expectCode("import ${P}ResumeCheck;")
            .expectCode("ResumeCheck.decide(", times = 1)
        for (line in sixLines) file.expectCode(line)
    }

    /**
     * AC-1: the six lines stand between opening the file (`:91`) and the seek (`:92`), in the
     * plan's order, with nothing in between. The order is the fix: `start` is corrected before
     * `done` and `length` are derived from it, the body is discarded before the file is cut, and
     * the seek that follows uses the corrected `start`. (`FallbackResumeModelTest` runs exactly
     * this sequence.)
     */
    @Test
    fun ac1_wiring_theSixLinesAreBetweenOpeningTheFileAndTheSeek_inOrder() {
        val file = Marked("AC-1", FALLBACK).expectCount("plan section 1")
        val open = file.lineThatIs("mF = mMission.storage.getStream();", marked = false)
        val seek = file.lineThatIs("mF.seek(mMission.offsets[mMission.current] + start);", marked = false)

        assertEquals("AC-1: exactly the six lines between getStream() and seek()", open + 7, seek)
        for ((n, expected) in sixLines.withIndex()) {
            val line = file.all[open + 1 + n]
            assertTrue("AC-1: line ${n + 1} after getStream() is marked, was `${line.trim()}`", line.contains(MARKER))
            assertTrue(
                "AC-1: line ${n + 1} after getStream() must be `$expected`, was `${line.trim()}`",
                squeeze(withoutMarker(line)).startsWith(squeeze(expected))
            )
        }
        assertEquals("AC-1: in run()", "run", file.methodAround(open + 1))
    }

    /**
     * AC-1/AC-5: what the plan leaves as it is. The download is reported finished in one place
     * only, after a body was read to its end; the thrown case is counted as a retry by the
     * existing `catch` (`mRetryCount` is decremented only for 416, as before).
     */
    @Test
    fun ac1_wiring_fallbackKeepsUpstreamsRetryAndFinish() {
        val file = Marked("AC-1", FALLBACK).expectCount("plan section 1")

        assertEquals("one place reports the download as finished", 1, file.codeLinesWith("mMission.notifyFinished();").size)
        val finished = file.lineThatIs("mMission.notifyFinished();", marked = false)
        assertEquals("under `if (done) {`", "if (done) {", file.all[finished - 1].trim())
        file.lineThatIs("done = len == -1;", marked = false)
        assertEquals("mRetryCount is decremented only in the 416 branch", 1, file.codeLinesWith("mRetryCount--").size)
        file.lineThatIs("if (mRetryCount++ >= mMission.maxRetry) {", marked = false)
        assertEquals("the offset is saved in the catch and at a pause, as before", 2, file.codeLinesWith("mMission.fallbackResumeOffset = start;").size)
    }

    // ---- section 2, D6: Utility.java ----

    /**
     * AC-2: `Utility.writeToFile` saves through `PendingStateFiles.tryWriteAtomically`, logs a
     * failure, and returns before upstream's in-place write, which truncates the only copy.
     */
    @Test
    fun ac2_wiring_writeToFileSavesAtomically_beforeTheInPlaceWrite() {
        val first = "final Exception enhError = PendingStateFiles.tryWriteAtomically(file, serializable);"
        val second = "if (enhError != null) Log.e(\"Utility\", \"State not saved, previous state kept: \" + file, enhError);"
        val third = "if (true) return;"
        val file = Marked("AC-2", UTILITY).expectCount("plan section 2: import, first three lines of writeToFile")
            .expectCode("import ${P}PendingStateFiles;")
            .expectCode(first)
            .expectCode("if (enhError != null) Log.e(")
            .expectCode(third)

        val save = file.lineThatIs(first, marked = true)
        val signature = file.lineThatIs("public static void writeToFile(@NonNull File file, @NonNull Serializable serializable) {", marked = false)
        val inPlace = file.codeLinesWith("new FileOutputStream(file)")
        assertEquals("upstream's in-place write stays in the file (no upstream line is deleted)", 1, inPlace.size)

        assertTrue("AC-2: the log line follows the save, was `${file.all[save + 1].trim()}`", squeeze(withoutMarker(file.all[save + 1])).startsWith(squeeze("if (enhError != null) Log.e(")))
        assertTrue(file.all[save + 1].contains(MARKER))
        assertTrue("AC-2: the log line carries the exception, so that the cause is in the log", squeeze(withoutMarker(file.all[save + 1])).endsWith(",enhError);"))
        assertEquals("AC-2: then the return", squeeze(third), squeeze(withoutMarker(file.all[save + 2])))
        assertTrue(file.all[save + 2].contains(MARKER))
        assertTrue("AC-2: the three lines are in writeToFile, before the in-place write", signature < save && save + 2 < inPlace[0])
        assertEquals("writeToFile", file.methodAround(save))
        assertTrue(
            "AC-2: only blank lines between the signature and the save",
            file.all.subList(signature + 1, save).all { it.isBlank() }
        )
        assertEquals("the log line of plan section 2 is whole", 1, file.occurrences(second))
    }

    // ---- sections 2 and 4, D6 and S4: DownloadManager.java ----

    /** AC-2/AC-4: `PendingStateAndroid.prepare` runs when the manager is created, after the directory is known and before the missions are loaded. */
    @Test
    fun ac4_wiring_managerPreparesTheDirectoryBeforeLoading() {
        val call = "PendingStateAndroid.prepare(context, mPendingMissionsDir);"
        val file = Marked("AC-4", MANAGER).expectCount("plan sections 2 and 4: six lines")
            .expectCode("import ${P}PendingStateAndroid;")
            .expectCode(call)

        val prepare = file.lineThatIs(call, marked = true)
        val dir = file.lineThatIs("mPendingMissionsDir = getPendingDir(context);", marked = false)
        val load = file.lineThatIs("loadPendingMissions(context);", marked = false)
        assertEquals("AC-4: directly after the directory is chosen", dir + 1, prepare)
        assertTrue("AC-4: before the pending missions are loaded (the move must have happened, temporaries must be gone)", prepare < load)
    }

    /**
     * AC-2/AC-4: pending state is read through `PendingStateAndroid.readMission` (the
     * allow-list, set aside on failure); the unfiltered `Utility.readFromFile` has no caller left.
     */
    @Test
    fun ac4_wiring_managerReadsThroughReadMission() {
        val read = "DownloadMission mis = PendingStateAndroid.readMission(sub);"
        val file = Marked("AC-4", MANAGER).expectCount("plan sections 2 and 4").expectCode(read)

        val line = file.lineThatIs(read, marked = true)
        assertEquals("loadPendingMissions", file.methodAround(line))
        assertEquals("AC-4: DownloadManager no longer calls Utility.readFromFile", 0, file.codeLinesWith("readFromFile(").size)
        assertEquals("AC-4: one place reads pending state", 1, file.codeLinesWith("DownloadMission mis =").size)
        assertEquals("the check for an unreadable file follows", "if (mis == null) {", file.all[line + 1].trim())
    }

    /**
     * AC-2: "A state file that cannot be read is ... kept aside, not deleted silently." The
     * `sub.delete();` in the `mis == null` branch is now a marked comment; the branch only
     * continues with the next file. The two other deletes of the loader (a finished mission, a
     * mission without storage) are upstream's decisions on readable state and stay.
     */
    @Test
    fun ac2_wiring_managerNoLongerDeletesAnUnreadableFile() {
        val file = Marked("AC-2", MANAGER).expectCount("plan sections 2 and 4")
        val branch = file.lineThatIs("if (mis == null) {", marked = false)
        val end = (branch + 1 until file.all.size).first { file.all[it].trim() == "}" }
        val body = file.all.subList(branch + 1, end)

        assertEquals(
            "AC-2: the branch for an unreadable file contains no code but `continue;`, was ${body.map { it.trim() }}",
            listOf("continue;"),
            body.map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
        )
        val marked = body.filter { it.contains(MARKER) }
        assertEquals("AC-2: the delete became one marked comment", 1, marked.size)
        assertTrue("AC-2: the marked line of the branch is a comment, was `${marked[0].trim()}`", marked[0].trim().startsWith("//"))
        assertEquals(
            "the two other deletes of the loader stay (:162 finished mission, :171 mission without storage)",
            2,
            file.all.count { it.substringBefore("//").trim() == "sub.delete();" }
        )
    }

    /** AC-2, A2: files set aside are reported once per start, after all pending files were read. */
    @Test
    fun ac2_wiring_managerReportsSetAsideFilesAfterTheLoop() {
        val call = "PendingStateAndroid.reportSetAside(ctx);"
        val file = Marked("AC-2", MANAGER).expectCount("plan sections 2 and 4").expectCode(call)

        val report = file.lineThatIs(call, marked = true)
        val loop = file.lineThatIs("for (File sub : subs) {", marked = false)
        val lastOfLoop = file.lineThatIs("mMissionsPending.add(mis);", marked = false)
        val sort = file.lineThatIs("if (mMissionsPending.size() > 1)", marked = false)
        assertEquals("loadPendingMissions", file.methodAround(report))
        assertTrue("AC-2: after the loop over the files, before the method ends", report in (lastOfLoop + 1) until sort)
        assertEquals("AC-2: outside the loop: once per start, not once per file", file.indentOf(loop), file.indentOf(report))
        assertEquals("AC-2: reported in one place", 1, file.codeLinesWith("reportSetAside(").size)
    }

    /**
     * AC-4: "stored where other apps cannot read or replace it, on every supported Android
     * version": `getPendingDir` no longer asks for the external directory (`:80` becomes
     * `File dir = null;`) and falls through to the private one, upstream's own fallback.
     */
    @Test
    fun ac4_wiring_managerNeverUsesTheExternalDirectory() {
        val file = Marked("AC-4", MANAGER).expectCount("plan sections 2 and 4").expectCode("File dir = null;")

        val nulled = file.lineThatIs("File dir = null;", marked = true)
        assertEquals("getPendingDir", file.methodAround(nulled))
        assertEquals("it is the first statement of getPendingDir", "private static File getPendingDir(@NonNull Context context) {", file.all[nulled - 1].trim())
        assertEquals("AC-4: no code of DownloadManager asks for an external directory for state", 0, file.codeLinesWith("getExternalFilesDir(DOWNLOADS_METADATA_FOLDER)").size)
        assertEquals(
            "AC-4: getPendingDir names one directory, the private one",
            1,
            file.all.count { squeeze(it.substringBefore("//")).contains("DOWNLOADS_METADATA_FOLDER)") }
        )
        val private = file.lineThatIs("dir = new File(context.getFilesDir(), DOWNLOADS_METADATA_FOLDER);", marked = false)
        assertTrue(nulled < private)
        assertEquals("the private directory is still probed", "if (testDir(dir)) return dir;", file.all[private + 1].trim())
    }

    /** AC-4: in upstream code nothing reads a file through `Utility.readFromFile` any more ("loses its only caller and stays"). */
    @Test
    fun ac4_wiring_theUnfilteredReaderHasNoCaller() {
        Marked("AC-4", MANAGER).expectCount("plan sections 2 and 4") // so that the test fails until the fix is in
        val mentions = sourceFiles()
            .associate { it.name to MainFiles.count(it.readText(), "readFromFile(") }
            .filterValues { it > 0 }

        assertEquals("AC-4: readFromFile( appears only as its own declaration", mapOf("Utility.java" to 1), mentions)
    }

    // ---- section 3, M7: HlsSegmentFileTransferEngine.kt ----

    /**
     * AC-3: every budget of the engine has one of the two limits of `HlsBufferPolicy`: one per
     * transfer for the segments, one for the encrypted init segment, one for the key (plan 1. and 5.).
     */
    @Test
    fun ac3_wiring_engineCreatesItsBudgetsWithThePolicysLimits() {
        val file = Marked("AC-3", ENGINE).expectCount("plan section 3")
        val segmentBudgets = Regex("""SegmentMemoryBudget\((?:HlsBufferPolicy\.)?LIMIT_BYTES\)""").findAll(file.code).count()
        val keyBudgets = Regex("""SegmentMemoryBudget\((?:HlsBufferPolicy\.)?KEY_LIMIT_BYTES\)""").findAll(file.code).count()

        assertTrue("AC-3: SegmentMemoryBudget(LIMIT_BYTES) for the segments and for the init segment, found $segmentBudgets", segmentBudgets >= 2)
        assertTrue("AC-3: SegmentMemoryBudget(KEY_LIMIT_BYTES) for the key, found $keyBudgets", keyBudgets >= 1)
        assertEquals("AC-3: no budget with any other capacity", segmentBudgets + keyBudgets, file.occurrences("SegmentMemoryBudget("))
        assertEquals("AC-3: no budget in an unmarked line", file.occurrences("SegmentMemoryBudget("), MainFiles.count(file.allCode, "SegmentMemoryBudget("))
    }

    /** AC-3: segments are received into a `SegmentBuffer` that is charged; no `ByteArrayOutputStream` is left (plan 1., `:516`). */
    @Test
    fun ac3_wiring_engineReceivesIntoTheChargedBuffer() {
        val file = Marked("AC-3", ENGINE).expectCount("plan section 3")
            .expectCode("val output = SegmentBuffer(charge, initialCapacity)")
            .expectCodeAtLeast("checkDeclared(")
            .expectCodeAtLeast(".settle(")
            .expectCodeAtLeast("charge.release()")

        assertEquals("AC-3: nothing is received into an uncounted buffer", 0, file.codeLinesWith("ByteArrayOutputStream(").size)
        assertEquals("AC-3: one place makes a SegmentBuffer", 1, MainFiles.count(file.allCode, "SegmentBuffer("))
        val buffer = file.lineThatIs("val output = SegmentBuffer(charge, initialCapacity)", marked = true)
        assertEquals("copyPartToMemory", file.methodAround(buffer))
    }

    /** AC-3: the submit condition has the window gate (plan 4., `:308`), and the first submissions stay as they are. */
    @Test
    fun ac3_wiring_engineGatesSubmissionsWithTheWindow() {
        val file = Marked("AC-3", ENGINE).expectCount("plan section 3")
            .expectCode("&& HlsBufferPolicy.maySubmit(nextToSubmit, nextToAppend, parallelism)")

        assertEquals(
            "AC-3: the gate is part of the while condition at :308",
            1,
            MainFiles.count(
                file.allCode,
                "while (nextToSubmit < playlist.segments.size && submitted - completed < parallelism && HlsBufferPolicy.maySubmit(nextToSubmit, nextToAppend, parallelism)"
            )
        )
        assertEquals("the gate is asked in one place", 1, MainFiles.count(file.allCode, "maySubmit("))
        file.lineThatIs("repeat(parallelism) { submitNextSegment() }", marked = false)
    }

    /**
     * AC-3: a segment that does not fit is handed over as "unbuffered" instead of failing or
     * being retried (plan 2.), and the writer releases the charge of a buffered one or streams
     * an unbuffered one into the file (plan 3.).
     */
    @Test
    fun ac3_wiring_engineHandsOverUnbufferedSegments_andTheWriterStreamsThem() {
        val file = Marked("AC-3", ENGINE).expectCount("plan section 3")
            .expectCode("val unbuffered: Boolean = false")
            .expectCode("val charge: SegmentCharge? = null")
            .expectCodeAtLeast("SegmentOverBudgetException")
            .expectCodeAtLeast("unbuffered = true")
            .expectCode("next.charge?.release()")
            .expectCode("private fun enhStreamSegment(")
            .expectCodeAtLeast("enhStreamSegment(", times = 2) // declared and called
            .expectCode("private fun enhSegmentCipher(")
            .expectCodeAtLeast("enhSegmentCipher(", times = 2)
            .expectCodeAtLeast("CipherInputStream(")
            .expectCode("transform: (InputStream) -> InputStream = { it }")
            .expectCodeAtLeast(".unbuffered")

        val release = file.lineThatIs("next.charge?.release()", marked = true)
        val append = file.codeLinesWith("nextToAppend++").single()
        val take = file.codeLinesWith("val next = downloadedSegments.remove(nextToAppend) ?: break").single()
        assertTrue("AC-3: the charge is released in the writer loop, when the segment is in the file", release in (take + 1) until append)
        assertEquals("downloadSegmentsInParallel", file.methodAround(release))
        // The streamed path goes through the function the init segment uses, with retries.
        val streamDeclaration = file.all.indexOfFirst { squeeze(it).contains(squeeze("private fun enhStreamSegment(")) }
        val nextDeclaration = (streamDeclaration + 1 until file.all.size).firstOrNull { Regex("""^\s{4}(?:private |internal )?fun \w+""").containsMatchIn(file.all[it]) } ?: file.all.size
        val body = squeeze(file.all.subList(streamDeclaration, nextDeclaration).joinToString("") { withoutMarker(it) })
        assertTrue("AC-3: enhStreamSegment retries like every other part (retryPolicy.withRetries)", body.contains("retryPolicy.withRetries"))
        assertTrue("AC-3: enhStreamSegment writes through downloadPart(", body.contains("downloadPart("))
        assertTrue("AC-3: enhStreamSegment returns a TransferResult", body.contains("):TransferResult"))
    }

    // ---- the whole change ----

    private fun sourceFiles(): List<File> {
        val root = MainFiles.file("java")
        if (!root.isDirectory) fail("app/src/main/java does not exist")
        return root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    }

    /**
     * Plan section 7: marked lines in four upstream files and nowhere else; 7 + 4 + 6 in the
     * three files whose lines the plan lists. A marked line in a fifth file is an edit the plan
     * does not have; the new package itself carries no marker.
     */
    @Test
    fun wiring_fourUpstreamFiles_andNoOther() {
        val helpers = MainFiles.file(HELPERS).canonicalFile
        val root = MainFiles.file("").canonicalFile
        val found = sourceFiles()
            .filter { !it.canonicalFile.startsWith(helpers) }
            .associate { it.canonicalFile.relativeTo(root).invariantSeparatorsPath to it.readLines().count { line -> line.contains(MARKER) } }
            .filterValues { it > 0 }

        assertEquals("plan section 7: the upstream files with marked lines", (EXACT.keys + ENGINE).toSortedSet(), found.keys.toSortedSet())
        assertEquals("plan section 7: the marked lines of the three files the plan lists line by line", EXACT.toSortedMap(), found.filterKeys { it != ENGINE }.toSortedMap())
        val inHelpers = sourceFiles().filter { it.canonicalFile.startsWith(helpers) }.sumOf { it.readLines().count { line -> line.contains(MARKER) } }
        assertEquals("no marker inside the new package, so that git grep lists exactly the upstream edits", 0, inHelpers)
    }

    /** PROCESS.md: every marked line ends with the marker comment (plan: "ends with `// ENH:download-fixes`"). */
    @Test
    fun wiring_everyMarkedLineEndsWithTheMarker() {
        for (path in EXACT.keys + ENGINE) {
            val file = Marked("plan", path).expectCount("plan section 7")
            for (line in file.lines) {
                assertTrue(
                    "app/src/main/$path: the marker is written as `// $MARKER`: `${line.trim()}`",
                    Regex("//\\s*" + Regex.escape(MARKER)).containsMatchIn(line)
                )
            }
        }
    }

    /**
     * AC-2, A2 and plan section 2: the one resource of the fix, the text of the message, with
     * both quantities and the number in each.
     */
    @Test
    fun ac2_theMessageResource() {
        val path = "res/values/enhancements_download_fixes.xml"
        val text = MainFiles.lines("AC-2", path).joinToString("\n")
        val plural = Regex("""<plurals\s+name="enh_download_state_set_aside"\s*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(text)
        if (plural == null) fail("AC-2: app/src/main/$path must define the plural enh_download_state_set_aside")
        val items = Regex("""<item\s+quantity="(\w+)"\s*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL).findAll(plural!!.groupValues[1])
            .associate { it.groupValues[1] to it.groupValues[2] }

        assertEquals("AC-2: quantities one and other (English only)", setOf("one", "other"), items.keys)
        for ((quantity, message) in items) {
            assertTrue("AC-2, A2: quantity $quantity says how many: `$message`", message.contains("%d") || message.contains("%1\$d"))
            assertTrue("AC-2, A2: quantity $quantity says that the state was kept aside: `$message`", message.contains("kept aside"))
        }
    }

    /**
     * AC-6 (no new setting): no preference key, no settings XML, no string of this fix other
     * than the one plural. Holds before Code exists; it fails when a setting of the fix appears.
     */
    @Test
    fun ac6_noSettingOfThisFix() {
        val res = MainFiles.file("res")
        assertTrue("app/src/main/res exists", res.isDirectory)
        val mentioning = res.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .filter { file -> file.readText().let { it.contains("download_fixes") || it.contains("downloadfixes") || it.contains("enh_download") || it.contains(MARKER) } }
            .map { it.canonicalFile.relativeTo(res.canonicalFile).invariantSeparatorsPath }
            .toList()
        assertTrue(
            "AC-6: nothing of this fix in res but values/enhancements_download_fixes.xml, found $mentioning",
            mentioning.all { it == "values/enhancements_download_fixes.xml" }
        )
        val named = res.walkTopDown().filter { it.isFile && (it.name.contains("download_fixes") || it.name.contains("downloadfixes")) }
            .map { it.canonicalFile.relativeTo(res.canonicalFile).invariantSeparatorsPath }.toList()
        assertTrue("AC-6: one resource file at most, found $named", named.all { it == "values/enhancements_download_fixes.xml" })

        val own = MainFiles.file("res/values/enhancements_download_fixes.xml")
        if (own.isFile) {
            val text = own.readText()
            assertEquals("AC-6: the file holds no <string> (a preference key or title would be one)", 0, Regex("<string[\\s>]").findAll(text).count())
            assertEquals("AC-6: one resource in the file", 1, Regex("<plurals[\\s>]").findAll(text).count())
            for (other in listOf("<bool", "<integer", "<string-array", "<array", "_key", "pref")) {
                assertEquals("AC-6: app/src/main/res/values/enhancements_download_fixes.xml must not contain `$other`", 0, MainFiles.count(text, other))
            }
        }
    }
}
