package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.enhancements.downloadfixes.ResumeCheck.Action.CONTINUE
import org.schabi.newpipe.enhancements.downloadfixes.ResumeCheck.Action.DISCARD_AND_REQUEST_AGAIN
import org.schabi.newpipe.enhancements.downloadfixes.ResumeCheck.Action.RESTART_WITH_THIS_BODY

/**
 * AC-1 (D5), plan section 1: `ResumeCheck`, the decision the single-connection download takes
 * between the response headers and the first byte it writes.
 *
 * The decision list of the plan, in its order, with `bodyStart` = the first byte of
 * `Content-Range` for a 206 and 0 for every other 2xx:
 *
 * 1. `bodyStart == expectedStart` -> `CONTINUE`
 * 2. `expectedStart == 0` and `bodyStart == -1` -> `CONTINUE`
 * 3. `bodyStart == 0` -> `RESTART_WITH_THIS_BODY`
 * 4. otherwise -> `DISCARD_AND_REQUEST_AGAIN`
 *
 * What protects the user's file is one property, tested over a grid at the end: the body is
 * never written at an offset other than the one it starts at.
 */
class ResumeCheckTest {

    // ---- rangeStart ----

    /** AC-1: the first byte position of a well-formed `Content-Range`. */
    @Test
    fun ac1_rangeStart_readsTheFirstBytePosition() {
        assertEquals(0L, ResumeCheck.rangeStart("bytes 0-99/100"))
        assertEquals(100L, ResumeCheck.rangeStart("bytes 100-199/200"))
        assertEquals(1L, ResumeCheck.rangeStart("bytes 1-1/2"))
        assertEquals(734003200L, ResumeCheck.rangeStart("bytes 734003200-1073741823/1073741824"))
    }

    /** AC-1: downloads are larger than 2 GiB; the position is a `Long`, not an `Int`. */
    @Test
    fun ac1_rangeStart_beyondTwoGibibytes() {
        assertEquals(5_000_000_000L, ResumeCheck.rangeStart("bytes 5000000000-5999999999/6000000000"))
        assertEquals(2_147_483_648L, ResumeCheck.rangeStart("bytes 2147483648-2147483649/2147483650"))
    }

    /** AC-1, plan: "unit case-insensitive". */
    @Test
    fun ac1_rangeStart_unitIsCaseInsensitive() {
        assertEquals(42L, ResumeCheck.rangeStart("Bytes 42-99/100"))
        assertEquals(42L, ResumeCheck.rangeStart("BYTES 42-99/100"))
    }

    /** AC-1, plan: "surrounding blanks allowed". */
    @Test
    fun ac1_rangeStart_surroundingBlanksAreAllowed() {
        assertEquals(42L, ResumeCheck.rangeStart(" bytes 42-99/100"))
        assertEquals(42L, ResumeCheck.rangeStart("bytes 42-99/100  "))
        assertEquals(42L, ResumeCheck.rangeStart("\tbytes 42-99/100 "))
    }

    /** AC-1, plan: "-1 for null". */
    @Test
    fun ac1_rangeStart_nullIsUnknown() {
        assertEquals(-1L, ResumeCheck.rangeStart(null))
    }

    /** AC-1, plan: -1 for the form with a star in place of the range, which a server sends with 416: it names no position. */
    @Test
    fun ac1_rangeStart_unsatisfiedRangeIsUnknown() {
        assertEquals(-1L, ResumeCheck.rangeStart("bytes */100"))
        assertEquals(-1L, ResumeCheck.rangeStart("bytes */*"))
    }

    /**
     * AC-1, plan: "-1 for ... anything malformed". None of these may throw: the value comes
     * from the network, and an exception here would be counted as a failed attempt.
     */
    @Test
    fun ac1_rangeStart_malformedIsUnknown_andNeverThrows() {
        val malformed = listOf(
            "",
            " ",
            "bytes",
            "bytes ",
            "bytes 42",
            "bytes 42-",
            "bytes -42-99/100",
            "bytes x-99/100",
            "bytes 4x2-99/100",
            "bytes 0x10-99/100",
            "bytes 1.5-99/100",
            "bytes +42-99/100",
            "42-99/100",
            "bytes=42-99/100",
            "items 42-99/100",
            "kilobytes 42-99/100",
            "megabytes 42-99/100",
            "bytes 42-99/100, bytes 0-9/100",
            "bytes 99999999999999999999999-99999999999999999999999/999999999999999999999999",
            "bytes 9223372036854775808-9223372036854775809/9223372036854775810",
            "\u0000\u0001\u0002",
            "bytes ٢-9/10", // an Arabic-Indic digit is not a byte position
        )
        for (value in malformed) {
            assertEquals("rangeStart(\"$value\")", -1L, ResumeCheck.rangeStart(value))
        }
    }

    // ---- decide: every row of the decision list ----

    /** AC-1/AC-5 rule 1: a fresh download answered with 200: the body starts at 0, where it is written. */
    @Test
    fun ac1_decide_rule1_freshDownload_200_continues() {
        assertEquals(CONTINUE, ResumeCheck.decide(0, 200, null))
    }

    /** AC-5 rule 1: a fresh download answered with 206 from byte 0 (upstream always asks for `bytes=0-`). */
    @Test
    fun ac5_decide_rule1_freshDownload_206FromZero_continues() {
        assertEquals(CONTINUE, ResumeCheck.decide(0, 206, "bytes 0-99/100"))
    }

    /** AC-5 rule 1: the resume is honoured: 206 whose `Content-Range` starts at the resume offset. */
    @Test
    fun ac5_decide_rule1_resumeHonoured_continues() {
        assertEquals(CONTINUE, ResumeCheck.decide(100, 206, "bytes 100-199/200"))
        assertEquals(CONTINUE, ResumeCheck.decide(5_000_000_000L, 206, "bytes 5000000000-5999999999/6000000000"))
        assertEquals(CONTINUE, ResumeCheck.decide(1, 206, "BYTES 1-9/10 "))
    }

    /**
     * AC-5 rule 2: 206 without a usable `Content-Range` to a request for `bytes=0-`: nothing can
     * be misplaced, the download goes on as it does today.
     */
    @Test
    fun ac5_decide_rule2_fromZero_206WithoutUsableContentRange_continues() {
        assertEquals(CONTINUE, ResumeCheck.decide(0, 206, null))
        assertEquals(CONTINUE, ResumeCheck.decide(0, 206, ""))
        assertEquals(CONTINUE, ResumeCheck.decide(0, 206, "bytes */100"))
        assertEquals(CONTINUE, ResumeCheck.decide(0, 206, "garbage"))
    }

    /** AC-1 rule 3, the finding: the server ignores `Range` and answers 200 with the whole resource. */
    @Test
    fun ac1_decide_rule3_resumeAnsweredWith200_restartsWithThisBody() {
        assertEquals(RESTART_WITH_THIS_BODY, ResumeCheck.decide(100, 200, null))
        assertEquals(RESTART_WITH_THIS_BODY, ResumeCheck.decide(1, 200, null))
        assertEquals(RESTART_WITH_THIS_BODY, ResumeCheck.decide(5_000_000_000L, 200, null))
    }

    /**
     * AC-1 rule 3: a 200 carries the whole resource whatever a `Content-Range` next to it says;
     * the header counts for 206 only. A 200 that repeats the requested range in a header must
     * not be taken for an honoured resume.
     */
    @Test
    fun ac1_decide_rule3_contentRangeOfA200IsIgnored() {
        assertEquals(RESTART_WITH_THIS_BODY, ResumeCheck.decide(100, 200, "bytes 100-199/200"))
        assertEquals(RESTART_WITH_THIS_BODY, ResumeCheck.decide(100, 200, "bytes 50-199/200"))
        assertEquals(RESTART_WITH_THIS_BODY, ResumeCheck.decide(100, 200, "garbage"))
        assertEquals(CONTINUE, ResumeCheck.decide(0, 200, "bytes 100-199/200"))
    }

    /** AC-1 rule 3, plan: "a 200, or any other 2xx, carries the whole resource". */
    @Test
    fun ac1_decide_rule3_any2xxOtherThan206IsTheWholeResource() {
        for (status in listOf(200, 201, 202, 203, 226, 299)) {
            assertEquals("status $status, resume at 100", RESTART_WITH_THIS_BODY, ResumeCheck.decide(100, status, null))
            assertEquals("status $status, resume at 100", RESTART_WITH_THIS_BODY, ResumeCheck.decide(100, status, "bytes 100-199/200"))
            assertEquals("status $status, fresh download", CONTINUE, ResumeCheck.decide(0, status, null))
        }
    }

    /** AC-1 rule 3: 206 that starts at 0 although a later offset was asked for: the body is the file from its start. */
    @Test
    fun ac1_decide_rule3_206FromZeroToAResume_restartsWithThisBody() {
        assertEquals(RESTART_WITH_THIS_BODY, ResumeCheck.decide(100, 206, "bytes 0-199/200"))
        assertEquals(RESTART_WITH_THIS_BODY, ResumeCheck.decide(5_000_000_000L, 206, "bytes 0-5999999999/6000000000"))
    }

    /** AC-1 rule 4: 206 that starts somewhere else: the body can be written nowhere, it is discarded. */
    @Test
    fun ac1_decide_rule4_206FromAnotherOffset_discards() {
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(100, 206, "bytes 50-199/200"))
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(100, 206, "bytes 101-199/200"))
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(100, 206, "bytes 99-199/200"))
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(0, 206, "bytes 50-199/200"))
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(0, 206, "bytes 1-199/200"))
    }

    /** AC-1 rule 4: 206 to a resume that does not say where it starts ("or nowhere known"). */
    @Test
    fun ac1_decide_rule4_206WithoutUsableContentRangeToAResume_discards() {
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(100, 206, null))
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(100, 206, ""))
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(100, 206, "bytes */200"))
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(100, 206, "garbage"))
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(1, 206, null))
    }

    /**
     * AC-1 rule 4: positions that differ only beyond 32 bits are different positions. An
     * implementation that compares `Int`s would continue and write 4 GiB away from the truth.
     */
    @Test
    fun ac1_decide_positionsAreComparedAsLongs() {
        val fourGib = 4L * 1024 * 1024 * 1024
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(fourGib + 100, 206, "bytes 100-199/200"))
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(100, 206, "bytes ${fourGib + 100}-${fourGib + 199}/${fourGib + 200}"))
        assertEquals(RESTART_WITH_THIS_BODY, ResumeCheck.decide(fourGib, 206, "bytes 0-9/10"))
        assertEquals(DISCARD_AND_REQUEST_AGAIN, ResumeCheck.decide(0, 206, "bytes $fourGib-${fourGib + 9}/${fourGib + 10}"))
    }

    /**
     * AC-1, the property behind the list, over every combination of a grid: after acting on the
     * decision, the body is written at the position it starts at, or it is not written.
     *
     * - `CONTINUE` writes at `expectedStart`: the body is known to start there, or the download
     *   starts at 0 and nothing says otherwise.
     * - `RESTART_WITH_THIS_BODY` writes at 0: the body is known to start at 0, and a resume was
     *   asked for (a fresh download is never "restarted").
     * - `DISCARD_AND_REQUEST_AGAIN` only for a 206: a 200 always is the file and is never thrown away.
     */
    @Test
    fun ac1_decide_theBodyIsNeverWrittenWhereItDoesNotStart() {
        val starts = listOf(0L, 1L, 99L, 100L, 101L, 65536L, Int.MAX_VALUE.toLong() + 1, 5_000_000_000L)
        val headers: List<String?> = listOf(null, "", "garbage", "bytes */7000000000") +
            starts.map { "bytes $it-6999999999/7000000000" }
        val statuses = listOf(200, 201, 203, 206, 226)
        var combinations = 0

        for (expected in starts) for (status in statuses) for (header in headers) {
            combinations++
            val bodyStart = if (status == 206) ResumeCheck.rangeStart(header) else 0L
            val action = ResumeCheck.decide(expected, status, header)
            val case = "decide($expected, $status, $header) = $action"
            when (action) {
                CONTINUE -> assertTrue(case, bodyStart == expected || (expected == 0L && bodyStart == -1L))
                RESTART_WITH_THIS_BODY -> {
                    assertEquals(case, 0L, bodyStart)
                    assertNotEquals(case, 0L, expected)
                }
                DISCARD_AND_REQUEST_AGAIN -> {
                    assertEquals(case, 206, status)
                    assertNotEquals(case, expected, bodyStart)
                    assertNotEquals(case, 0L, bodyStart)
                }
            }
            // AC-5: a fresh download answered with the whole resource or with the range from 0 is never disturbed.
            if (expected == 0L && bodyStart <= 0L) assertEquals(case, CONTINUE, action)
        }
        assertEquals(8 * 5 * 12, combinations)
    }

    /** Plan section 1: the three actions, by name (the upstream lines compare against them). */
    @Test
    fun ac1_theThreeActions() {
        assertEquals(
            listOf("CONTINUE", "RESTART_WITH_THIS_BODY", "DISCARD_AND_REQUEST_AGAIN"),
            ResumeCheck.Action.values().map { it.name }
        )
    }

    // ---- through a real HTTP connection (plan section 5) ----

    /**
     * AC-1, plan section 5: a local server that ignores `Range` (200), read through
     * `HttpURLConnection` into `decide` with the two calls of the planned upstream line
     * (`getResponseCode()`, `getHeaderField("Content-Range")`).
     */
    @Test
    fun ac1_realConnection_serverIgnoresRange_restartsWithThisBody() {
        val content = bytesOf(1000, seed = 1)
        LocalServer { LocalServer.ignoring(content) }.use { server ->
            val connection = server.open(range = "bytes=400-")
            try {
                assertEquals(200, connection.responseCode)
                assertNull(connection.getHeaderField("Content-Range"))
                assertEquals(
                    RESTART_WITH_THIS_BODY,
                    ResumeCheck.decide(400, connection.responseCode, connection.getHeaderField("Content-Range"))
                )
                assertEquals("the body of that answer is the whole file", content.toList(), connection.inputStream.readBytes().toList())
            } finally {
                connection.disconnect()
            }
            assertEquals("the request did carry the resume offset", "bytes=400-", server.requests.single().range)
        }
    }

    /** AC-5, plan section 5: the same server honouring `Range` (206 + `Content-Range`): the download continues. */
    @Test
    fun ac5_realConnection_serverHonoursRange_continues() {
        val content = bytesOf(1000, seed = 2)
        LocalServer { LocalServer.honouring(content, it) }.use { server ->
            val connection = server.open(range = "bytes=400-")
            try {
                assertEquals(206, connection.responseCode)
                assertEquals("bytes 400-999/1000", connection.getHeaderField("Content-Range"))
                assertEquals(CONTINUE, ResumeCheck.decide(400, connection.responseCode, connection.getHeaderField("Content-Range")))
                assertEquals(content.copyOfRange(400, 1000).toList(), connection.inputStream.readBytes().toList())
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * AC-1/AC-5, plan section 5: 206 without the header. To the fresh download (`bytes=0-`, what
     * upstream sends on a first attempt) it continues; to a resume it is discarded.
     */
    @Test
    fun ac1_realConnection_206WithoutContentRange() {
        val content = bytesOf(1000, seed = 3)
        LocalServer { Response(206, content) }.use { server ->
            val fresh = server.open(range = "bytes=0-")
            try {
                assertEquals(206, fresh.responseCode)
                assertNull(fresh.getHeaderField("Content-Range"))
                assertEquals(CONTINUE, ResumeCheck.decide(0, fresh.responseCode, fresh.getHeaderField("Content-Range")))
            } finally {
                fresh.disconnect()
            }
            val resumed = server.open(range = "bytes=400-")
            try {
                assertEquals(
                    DISCARD_AND_REQUEST_AGAIN,
                    ResumeCheck.decide(400, resumed.responseCode, resumed.getHeaderField("Content-Range"))
                )
            } finally {
                resumed.disconnect()
            }
        }
    }

    /** AC-1: a 206 that answers a resume from another offset, as a real response: discarded. */
    @Test
    fun ac1_realConnection_206FromAnotherOffset_discards() {
        val content = bytesOf(1000, seed = 4)
        LocalServer { Response(206, content.copyOfRange(200, 1000), contentRange = "bytes 200-999/1000") }.use { server ->
            val connection = server.open(range = "bytes=400-")
            try {
                assertEquals(
                    DISCARD_AND_REQUEST_AGAIN,
                    ResumeCheck.decide(400, connection.responseCode, connection.getHeaderField("Content-Range"))
                )
            } finally {
                connection.disconnect()
            }
        }
    }
}
