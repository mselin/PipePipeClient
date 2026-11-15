# Plan: download-fixes

Spec: `spec.md` (AC-1..AC-6). Read in the code at `origin/dev` `cd87fc7`; nothing was run.
Paths are under `app/src/main/java/` unless they start with `app/`. `giga/` stands for
`us/shandian/giga/`. New code lives in
`org/schabi/newpipe/enhancements/downloadfixes/` (short: `enh/`). Every line added to or
changed in an upstream file ends with `// ENH:download-fixes`; no upstream line is deleted.

## 1. D5: resume appends a full response (AC-1). Confirmed for the single-connection path

`giga/get/DownloadRunnableFallback.java`. `start` is the resource-relative offset the thread
will write at (`:55`, from `fallbackResumeOffset`; saved again at `:112` and `:140`).

| Path | What happens |
|---|---|
| Known length, server answers 200 to `Range: bytes=start-` | `:86-88` reset only `done`; `:92` seeks to `offsets[current] + start`; `:99-103` write the whole body there. File = prefix + whole body, `:138` reports it finished. |
| Unknown length, resume after pause | `:62` never sends `start` (`rangeStart = -1`; `:69` sends `bytes=0-`). The body always begins at 0 and is written at `start`. Every such resume corrupts the file. |
| Unknown length, retry inside the run | `:112` keeps `start`, `:133` calls `run()` again, no `Range` at all (`:67` is false), the same append. |
| Counters | `length` grows by every byte when `unknownLength` (`DownloadMission.java:322-324`) and is not reset at `:88`, so it ends as prefix + body and `DownloadMission.java:417` puts the next resource at a wrong offset. |

`416` is already handled (`:75-80`). **Multi-threaded path (`DownloadRunnable.java`): refuted.**
`:104-110` stop the mission with an error on anything but 206 before a byte is written, and a
retried block restarts from `block.done`, which only counts written bytes (`:120-124`). It
does not compare `Content-Range` with the request; that is a broken server, not an ignored
resume, and is left alone. `DownloadMissionRecover.java:272` already zeroes the offset.

### Detection: new `enh/ResumeCheck.kt` (no Android imports)

```kotlin
object ResumeCheck {
    enum class Action { CONTINUE, RESTART_WITH_THIS_BODY, DISCARD_AND_REQUEST_AGAIN }
    /** First byte position of "bytes a-b/len" (unit case-insensitive, surrounding blanks
     *  allowed); -1 for null, "bytes */len" or anything malformed. */
    @JvmStatic fun rangeStart(contentRange: String?): Long
    /** expectedStart: offset the caller is about to write at (>= 0). statusCode: a 2xx. */
    @JvmStatic fun decide(expectedStart: Long, statusCode: Int, contentRange: String?): Action
}
```

`bodyStart` = `rangeStart(contentRange)` when the status is 206, otherwise 0 (a 200, or any
other 2xx, carries the whole resource). Then, in this order:

1. `bodyStart == expectedStart` -> `CONTINUE`.
2. `expectedStart == 0` and `bodyStart == -1` -> `CONTINUE` (206 without a usable
   `Content-Range` to a request for `bytes=0-`: nothing can be misplaced; keeps AC-5).
3. `bodyStart == 0` -> `RESTART_WITH_THIS_BODY` (resume not honoured, the body is the file).
4. otherwise -> `DISCARD_AND_REQUEST_AGAIN` (206 that starts somewhere else or nowhere known).

### Edits: `DownloadRunnableFallback.java`, 7 marked lines

One import, then six lines between `:91` (`mF = ...getStream()`) and `:92` (`mF.seek`), which
stays as it is and now seeks with the corrected `start`:

```java
final ResumeCheck.Action enhResume = ResumeCheck.decide(start, mConn.getResponseCode(), mConn.getHeaderField("Content-Range"));
if (enhResume != ResumeCheck.Action.CONTINUE) start = 0;
mMission.done = mMission.offsets[mMission.current] - mMission.offsets[0] + start;
if (mMission.unknownLength) mMission.length = start;
if (enhResume == ResumeCheck.Action.DISCARD_AND_REQUEST_AGAIN) throw new IOException("resume not honoured, asking for the whole resource");
if (enhResume == ResumeCheck.Action.RESTART_WITH_THIS_BODY && mMission.unknownLength && mF.canSetLength()) mF.setLength(mMission.offsets[mMission.current]);
```

What a restart does: the write position goes back to the start of the current resource
(earlier resources of the mission are kept); `done` becomes the bytes of the earlier resources
(same formula as `:88`); with unknown length `length` restarts at 0 and the file is cut at the
resource start so that a shorter body leaves no tail (known length: the file keeps its
preallocated size from `DownloadInitializer.java:152` and is overwritten in full). The thrown
case lands in the existing `catch`: `:112` stores offset 0, `:124` counts one retry, `:133`
asks again without `Range`. `mRetryCount` is not decremented (unlike `:78`), so a server that
keeps answering nonsense ends in `notifyError` after `maxRetry`.

Regressions to watch: (a) lines 3-4 now run on every start. They also correct `done` after a
process kill (offset saved only at pause, `done` every 512 KiB) and `length` when a
known-length mission meets a response without `Content-Length` (`:83-84`); progress shown
after such a resume can be lower than before the kill, and is then true. (b) A paused
unknown-length download always starts over on resume; before, it produced a corrupt file.
The UI already labels that button "stop" (`giga/ui/adapter/MissionAdapter.java:224`).

## 2. D6: state rewritten in place (AC-2). Confirmed

`giga/util/Utility.java:89` opens `new FileOutputStream(file)`, which truncates the only copy,
then writes; `:91-93` swallow every error. A kill in between leaves a short file,
`readFromFile` (`:99-111`) returns `null`, and `giga/service/DownloadManager.java:151-154`
deletes it: the download leaves the list and its partial media file stays behind. Writers:
`DownloadMission.java:633` (every 512 KiB via `:330-334`, on pause, on error) and
`DownloadManager.java:253`.

### New `enh/PendingStateFiles.kt` (no Android imports)

```kotlin
object PendingStateFiles {
    const val TEMP_SUFFIX = ".new"
    const val ASIDE_DIR = "set_aside"

    /** Serializes to "<target>.new", flushes, FileDescriptor.sync(), closes, then
     *  File.renameTo(target) (rename(2): replaces atomically). On any failure the temporary
     *  file is deleted and the target is untouched. */
    @JvmStatic @Throws(IOException::class) fun writeAtomically(target: File, value: Serializable)
    /** Same, never throws: null on success, else the failure (any Exception, e.g. a
     *  ConcurrentModificationException from a field changed while it was serialized). */
    @JvmStatic fun tryWriteAtomically(target: File, value: Serializable): Exception?
    /** Deletes every "*.new" in dir: by construction an unfinished write. Returns the count. */
    @JvmStatic fun deleteTemporaries(dir: File): Int

    sealed class ReadResult {
        class Ok(val value: Any) : ReadResult()
        /** keptAt: where the file is now; null if it could not be moved and stays in place. */
        class Unreadable(val cause: Throwable, val keptAt: File?) : ReadResult()
    }
    /** Reads one object through AllowListObjectInputStream (section 4). Catches Exception,
     *  OutOfMemoryError and StackOverflowError; then calls setAside(file). */
    @JvmStatic fun read(file: File, pluginClasses: (String) -> Class<*>?): ReadResult
    /** Renames file to "<dir>/set_aside/<name>" (then "<name>.1", ".2", ... if taken). */
    @JvmStatic fun setAside(file: File): File?

    data class MigrationResult(val moved: Int, val duplicatesRemoved: Int, val setAside: Int, val failed: Int)
    @JvmStatic fun migrate(oldDir: File, newDir: File): MigrationResult   // section 4
}
```

The target always exists before the first write (`DownloadManager.java:239` creates it), so at
any instant the directory holds a complete previous state under the real name; a `.new` file
is never the only copy and may always be deleted. Mission files are named by timestamp digits
(`DownloadManager.java:236`), so neither `.new` nor `set_aside/` can collide; the loader skips
directories (`:147`). The directory itself is not synced: after power loss the rename may be
undone, which leaves the older complete state. A process kill (the AC) loses nothing.

### New `enh/PendingStateAndroid.kt` (thin Android shell) and one resource file

```kotlin
object PendingStateAndroid {
    /** deleteTemporaries(newDir), then the move of section 4. Logs the MigrationResult. */
    @JvmStatic fun prepare(context: Context, newDir: File)
    /** PendingStateFiles.read(file, PluginManager::findPluginClass). Unreadable: Log.e with
     *  path, new location and cause; counts it; returns null. Not a DownloadMission: same. */
    @JvmStatic fun readMission(file: File): DownloadMission?
    /** If the count is > 0: one Toast on the main thread, then resets the count. */
    @JvmStatic fun reportSetAside(context: Context)
}
```

`app/src/main/res/values/enhancements_download_fixes.xml`: plural
`enh_download_state_set_aside` ("%d unfinished download could not be read. Its saved state
was kept aside." / "... downloads ... Their ..."). English only. No preference (AC-6).

### Edits

| File | Edit | Lines |
|---|---|---|
| `Utility.java` | import; first three lines of `writeToFile` (before `:89`): `final Exception enhError = PendingStateFiles.tryWriteAtomically(file, serializable);` / `if (enhError != null) Log.e("Utility", "State not saved, previous state kept: " + file, enhError);` / `if (true) return;` (upstream's in-place write below is no longer reached; `readFromFile` loses its only caller and stays) | 4 |
| `DownloadManager.java` | import; after `:74`: `PendingStateAndroid.prepare(context, mPendingMissionsDir);` | 2 |
| | `:150` becomes `DownloadMission mis = PendingStateAndroid.readMission(sub);` | 1 |
| | `:153` `sub.delete();` becomes a marked comment (`readMission` already moved the file aside; a failed move must not end in a delete) | 1 |
| | after the loop (`:216`): `PendingStateAndroid.reportSetAside(ctx);` | 1 |
| | `:80`, see section 4 | 1 |

A file that cannot be read (short, empty, wrong class, refused class) ends in
`<pending dir>/set_aside/`, is named in logcat with the cause, and is counted in the toast.
Nothing deletes `set_aside/`; the files are a few kB each. The other deletes in the loader
(`:162`, `:171`: finished mission, mission without storage) are upstream decisions on readable
state and stay.

Regressions to watch: one `fsync` per save, under `DownloadMission.LOCK`, which block
acquire/release also take (`DownloadMission.java:194`, `:217`). Saves are at most one per
512 KiB and never overlap (`:330-334`); the file is tens of kB. A failed save was silent and
is now a log line; the previous state survives instead of a truncated one.

## 3. M7: unbounded HLS buffering (AC-3). Confirmed, plus two unbounded reads

`giga/hls/transfer/HlsSegmentFileTransferEngine.kt`. Each worker reads a whole segment into a
`ByteArrayOutputStream` (`:513-527`), decrypts into a second array (`:341-347`, `:599`) and
returns it; the writer keeps finished segments in `downloadedSegments` (`:279`) until
`nextToAppend` arrives (`:281-306`). `:308` submits while `submitted - completed <
parallelism`, which counts in-flight work only. When the segment at `nextToAppend` stalls
(30 s read timeout, 3 retries: minutes), the other workers (up to 5,
`giga/get/HlsDownloader.kt:358`) keep finishing later segments and all of them are held.
Nothing limits one segment either: the encrypted init segment (`:111`) and the key
(`:613`) are read whole with no cap.

### The bound

`HlsBufferPolicy.LIMIT_BYTES = 64 MiB` per transfer, counted as heap actually allocated for
segment data: bytes being received, the assembled array, the decrypted copy, and finished
segments waiting for their turn. Basis: a normal working set is 6 workers x 2-4 MiB segments
x at most 2 copies = 24-48 MiB, so the limit is not reached in ordinary use; the app runs with
`largeHeap` (`app/src/main/AndroidManifest.xml:57`, 256 MiB or more on supported devices), so
64 MiB leaves room for the player. Outside the count: one 256 KiB copy buffer per thread
(`HttpTransferConfig.bufferSize`) and playlist text.

### New files (no Android imports)

```kotlin
object HlsBufferPolicy {
    const val LIMIT_BYTES: Long = 64L * 1024 * 1024
    const val KEY_LIMIT_BYTES: Long = 64L * 1024
    /** Segments that may be submitted and not yet written: 2 per worker. */
    @JvmStatic fun window(parallelism: Int): Int
    @JvmStatic fun maySubmit(nextToSubmit: Int, nextToAppend: Int, parallelism: Int): Boolean
}
class SegmentOverBudgetException(needed: Long, capacity: Long) : IOException
class SegmentMemoryBudget(val capacityBytes: Long) {
    val heldBytes: Long          // synchronized
    val peakBytes: Long          // highest heldBytes so far
    fun open(): SegmentCharge
}
class SegmentCharge internal constructor(budget: SegmentMemoryBudget) {
    val bytes: Long
    /** Never blocks. If it does not fit: releases all this charge holds, then throws. */
    @Throws(SegmentOverBudgetException::class) fun acquire(bytes: Long)
    /** Throws at once if a declared size (> 0) can never fit the capacity. */
    @Throws(SegmentOverBudgetException::class) fun checkDeclared(declaredBytes: Long, copies: Int)
    fun settle(bytes: Long)      // lower the charge to bytes; never raises it
    fun release()                // idempotent
}
/** Replaces ByteArrayOutputStream. Allocates initialCapacity, then 256 KiB chunks, charging
 *  each allocation before making it. toByteArray(): returns the first array itself when it
 *  is exactly full, else charges and builds one array and frees the chunks. */
class SegmentBuffer(charge: SegmentCharge, initialCapacity: Int) : OutputStream() {
    override fun write(b: Int)
    override fun write(b: ByteArray, off: Int, len: Int)
    fun size(): Long
    fun toByteArray(): ByteArray
}
```

### Behaviour and edits (`HlsSegmentFileTransferEngine.kt`, about 45 marked lines)

1. `downloadSegmentsInParallel` creates one `SegmentMemoryBudget(LIMIT_BYTES)`.
   `downloadSegmentToMemory` gets it as a parameter, opens a charge, and passes the charge
   through `downloadPartToMemory` to `copyPartToMemory`, where `:516` becomes
   `val output = SegmentBuffer(charge, initialCapacity)` (the two lines using `output` stay).
   `downloadPartToMemory` first calls `charge.checkDeclared(expectedBytes(...), copies)` with
   2 copies for an encrypted segment, 1 otherwise. Before decrypting, the worker acquires the
   size of the ciphertext; after it, `settle(bytes.size)`.
2. Inside the retry lambda (`:334-340`) each attempt starts with `charge.release()` and
   catches `SegmentOverBudgetException`, returning `null`; `TransferRetryPolicy` would
   otherwise retry it (`TransferRetryPolicy.kt:30`). A `null` makes the worker return a
   `DownloadedSegment` with the new fields `unbuffered = true` and no bytes. The data class
   gains `val unbuffered: Boolean = false` and `val charge: SegmentCharge? = null`.
3. Writer loop (`:282-293`): a buffered segment is written as today, then
   `next.charge?.release()`. An unbuffered one is fetched now, by the writer thread, straight
   into the output file at `currentOutputPosition` by a new private
   `enhStreamSegment(...): TransferResult`: `retryPolicy.withRetries { downloadPart(...) }`,
   the function the init segment already uses (`:408-448`). `downloadPart` gains a parameter
   `transform: (InputStream) -> InputStream = { it }` applied at `:430`; for an AES-128
   segment it wraps the stream in `CipherInputStream` with a cipher from a new private
   `enhSegmentCipher(...)` (same key cache, key download and IV rules as `:582-598`).
   Checkpoint, progress and status handling after the write are unchanged.
4. Submit gate, `:308`: the condition gains
   `&& HlsBufferPolicy.maySubmit(nextToSubmit, nextToAppend, parallelism)`
   (`nextToSubmit - nextToAppend < window(parallelism)`).
5. The encrypted init segment (`:111`) gets its own `SegmentMemoryBudget(LIMIT_BYTES)`
   charge, the key (`:613`) one of `KEY_LIMIT_BYTES`. Over the limit: the transfer fails with
   the `IOException` after the usual retries. A valid key is 16 bytes (`:614`).

Back-pressure without waiting: no thread ever blocks on the budget. A worker that would
exceed it gives its memory back and reports "unbuffered"; the window (4.) stops new
submissions while the oldest unwritten segment is outstanding. Stalled segment: the others
finish at most `window - 1` later segments, buffered while they fit and unbuffered after
that, then idle. No deadlock: when the gate is closed, the segment at `nextToAppend` has been
submitted and is either in flight (its completion or failure wakes the loop, `:262`) or done
(the inner loop has written it). The price is bandwidth: an unbuffered segment is downloaded
again when its turn comes, at most once, and at most `window` segments per stall.

A single segment larger than the bound: never held. With a declared length it is refused
before a byte is read; without one, after at most 64 MiB. In both cases the writer streams it
into the file when its turn comes, encrypted or not, with no size limit. While it streams,
finished later segments wait inside the budget and nothing new is submitted.

Regressions to watch: `CipherInputStream` reports bad padding at end of stream as an
`IOException` rather than the engine's "decrypt failed" message, on the streamed path only.
Very large segments with 6 threads (above about 8 MiB each) will see some segments fall back
to serial streaming: slower, never failing. A retried streamed segment rewrites from the same
position (`:500`), as the init segment does today.

## 4. S4: state in external storage, read without a class filter (AC-4). Confirmed

`DownloadManager.java:80` prefers `getExternalFilesDir("pending_downloads")`. With
`minSdk 24`, on Android 7-10 any app holding the storage permission can read and write there.
The files are serialized `DownloadMission`s: `urls` including the `#cookie=` part
(`DownloadMission.java:249-251`) and `resourceRequestHeaders` (`:147`). They are read at
`:150` through `PluginObjectInputStream` (`org/schabi/newpipe/plugin/PluginObjectInputStream.kt:11-19`),
which resolves any class of the app, its libraries and every loaded plugin.

### New location

`new File(context.getFilesDir(), "pending_downloads")`: app-private on every supported
version, and already upstream's own fallback (`:83-84`), so no new path is invented.
Edit: `:80` becomes `File dir = null;` with the marker and a note that state is never stored
externally; `:81` then falls through to `:83`. One changed line.

### Move of existing state: `PendingStateFiles.migrate(oldDir, newDir)`

Called from `PendingStateAndroid.prepare` on every `DownloadManager` creation, with
`oldDir = File(context.getExternalFilesDir(null), "pending_downloads")` (not
`getExternalFilesDir("pending_downloads")`, which would create the directory again). It does
nothing when `getExternalFilesDir(null)` is null (storage not mounted: tried again at the
next start) or `oldDir` is not a directory. Otherwise, for each regular file:

1. `.tmp` (upstream's probe, `:98`) and `*.new`: deleted.
2. No file of that name in `newDir`: copy the bytes to `newDir/<name>.new`, sync, rename to
   `newDir/<name>`, then delete the old file. Counted as moved. A copy that fails (disk
   full) deletes its `.new`, leaves the old file and counts as failed; tried again next start.
3. A file of that name exists in `newDir`: same bytes -> the old one is a finished move, it
   is deleted. Different bytes -> the internal file wins (it is this version's newer state);
   the old one is copied to `newDir/set_aside/` and deleted, and logged.

Then `oldDir` is removed if empty. Interrupted at any point: before the rename the old file
is intact and the `.new` is removed by `deleteTemporaries` at the next start; after the
rename and before the delete, case 3 "same bytes" finishes the job; if the app has meanwhile
saved newer state, case 3 "different bytes" keeps the newer one. No state is lost and none
appears twice. The move never deserializes: it copies bytes.

### Reading: an allow-list is required

Moving a planted file into private storage does not make it safe, and the first load after
the move would read it. Unrestricted deserialization is therefore not acceptable, during the
move or after it, and all pending state is always read through one filtered stream. The
precedent is the settings import: `org/schabi/newpipe/settings/PreferencesObjectInputStream.kt:12-36`
(`resolveClass` against `CLASS_WHITELIST`), used at
`org/schabi/newpipe/settings/ContentSettingsManager.kt:150`. Its list (boxed primitives,
`HashMap`, `HashSet`) is too small for a mission, so a second one is added:

```kotlin
class AllowListObjectInputStream(
    input: InputStream,
    private val pluginClasses: (String) -> Class<*>? = { null },
) : ObjectInputStream(input) {
    override fun resolveClass(desc: ObjectStreamClass): Class<*>          // refused: InvalidClassException naming the class
    override fun resolveProxyClass(interfaces: Array<String>): Class<*>   // always refused
}
object PendingStateAllowList {
    /** cls was resolved without initialization; fromPlugin: only a plugin loader knew it. */
    @JvmStatic fun isAllowed(cls: Class<*>, fromPlugin: Boolean): Boolean
}
```

Allowed: primitives and arrays of allowed types; names starting with `us.shandian.giga.` or
`org.schabi.newpipe.` (missions, checkpoints, post-processing, `StoredFileHelper`, extractor
types such as `MediaFormat`, `YoutubeSabrInfo`, `ItagItem`); any subclass of `Throwable` and
`java.lang.StackTraceElement` (`errObject`, `DownloadMission.java:157`); `java.lang` `String`,
`Number`, the eight boxes, `Enum`; `java.util` `ArrayList`, `HashMap`, `LinkedHashMap`,
`HashSet`, `LinkedHashSet`, `Locale`, `Arrays$ArrayList`, `CollSer`, and `Collections$`
`EmptyList|EmptyMap|EmptySet|SingletonList|SingletonMap|SingletonSet|UnmodifiableCollection|UnmodifiableList|UnmodifiableRandomAccessList|UnmodifiableMap|UnmodifiableSet`;
names starting with `kotlin.collections.`, `kotlin.Pair`, `kotlin.Triple`; and a class that
only a loaded plugin defines (`MissionRecoveryInfo.deliveryMethodInfo` may be a plugin type,
`giga/get/MissionRecoveryInfo.kt:25`; a plugin is code the user installed and already runs).
Everything else is refused and the file is set aside and reported (section 2).

Regressions to watch: a legitimate mission that holds a class outside the list is set aside
at every start instead of loaded; the log names the class, the file is kept, and the list is
extended. A downgrade to an older build finds no pending downloads. Backups are unchanged:
`allowBackup="true"` with no rules covers the old and the new directory alike (Q4).

## 5. Verification

**JVM unit tests** (JUnit 4 only, `app/src/test/.../enhancements/downloadfixes/`). The four
upstream classes cannot run on the JVM (`android.util.Log` and others, and the build has no
`returnDefaultValues`), so the unit tests cover the new plain classes:

| Test class | Covers |
|---|---|
| `ResumeCheckTest` | AC-1: every row of the decision list; `rangeStart` parsing. One test with a local `ServerSocket` that ignores `Range` (200), honours it (206 + `Content-Range`) and sends 206 without the header, read through `HttpURLConnection` into `decide`. |
| `PendingStateFilesTest` | AC-2: write then read round trip; a write that fails mid-way (a `Serializable` whose `writeObject` throws) leaves the old content and no `.new`; `deleteTemporaries`; truncated, empty and garbage files end in `set_aside/` with unique names; a huge declared array length is survived. |
| `PendingStateMigrationTest` | AC-4: move; each interruption point reproduced by preparing the directory state (`.new` present; both copies equal; both copies different); missing old directory; old directory removed when empty. |
| `AllowListObjectInputStreamTest` | AC-4: allowed graph (collections, an exception with cause and stack, test classes under `org.schabi.newpipe.`); refused: `java.util.PriorityQueue`, `java.net.URL`, a dynamic proxy; plugin resolver honoured. Also a real `DownloadMission` (public constructor, null storage) with headers, HLS and SABR checkpoints and `MissionRecoveryInfo`, written and read back; this one loads Android-typed fields and moves unchanged to `androidTest` if the stub `android.jar` prevents it. |
| `SegmentMemoryBudgetTest`, `SegmentBufferTest`, `HlsBufferPolicyTest` | AC-3: `peakBytes` never above capacity with 6 threads writing random sizes; refusal releases the whole charge; `checkDeclared`; exact-size buffer returns its array without a copy; chunked content equals input; window arithmetic including the "head outstanding" case. |

**Instrumented** (`androidTest`, run by hand with `connectedDebugAndroidTest`; CI does not
run it): `FallbackResumeInstrumentedTest`: a mission built with the public constructor on a
file in the cache directory, `blocks` and `fallbackResumeOffset` set by reflection, a local
server that ignores `Range`; asserts the finished file equals the served bytes (AC-1). The
debug build permits cleartext (`app/src/debug/res/xml/network_security_config.xml`).

**Device script** (debug build, download threads set to 1 unless stated):

1. AC-1: route the phone through `mitmdump --modify-headers '/~q/Range/'` (drops the `Range`
   request header). Download a large progressive file (media.ccc.de), pause near 30 %, resume,
   let it finish; `sha256sum` equals a `curl` download of the same URL. Before the fix the
   file is larger and differs.
2. AC-5: without the proxy: pause/resume and airplane-mode retry with 1 and with 4 threads;
   a video+audio download that is muxed; checksums or playback as before.
3. AC-2: during a download run `adb shell am force-stop <package>` ten times at random
   moments; after each restart the download is listed with progress. Then
   `adb shell run-as <package> sh -c 'echo x > files/pending_downloads/<name>'`: after a
   restart the toast appears, the file is in `files/pending_downloads/set_aside/`.
4. AC-4: install the previous build, start two downloads and pause them, install the new
   build over it: both are listed; `Android/data/<package>/files/pending_downloads` is gone;
   `run-as <package> ls files/pending_downloads` shows them. Repeat with a `force-stop` right
   after first launch. On Android 7-10, a file planted in the old directory by `adb push`
   ends in `set_aside/`.
5. AC-3: an HLS download with 6 threads while one segment URL is held by the proxy
   (`mitmdump` script sleeping 60 s on one segment): `adb shell dumpsys meminfo <package>`
   Java heap stays flat; the file plays. An HLS source with one segment above 64 MiB downloads.
6. AC-6: the settings screens are unchanged.

## 6. AC coverage

| AC | Piece | Tested by |
|---|---|---|
| AC-1 | `ResumeCheck`, six lines in `DownloadRunnableFallback` | `ResumeCheckTest`, instrumented test, device 1 |
| AC-2 | `writeAtomically`, `read`/`setAside`, `readMission`, `reportSetAside` | `PendingStateFilesTest`, device 3 |
| AC-3 | budget, buffer, window, streamed fallback | three unit test classes, device 5 |
| AC-4 | `:80`, `migrate`, `AllowListObjectInputStream` | `PendingStateMigrationTest`, `AllowListObjectInputStreamTest`, device 4 |
| AC-5 | rules 1-2 of `decide`; non-blocking budget; unchanged formats and loader logic | device 2, and every unit test's "normal case" |
| AC-6 | no preference key, no settings XML | device 6, `git diff --stat` shows one new `values` file |

## 7. Order of work

Four code commits, each compiling alone and revertable alone, each with its unit tests green:

1. **D5**: `ResumeCheck.kt` + `DownloadRunnableFallback.java`.
2. **M7**: the four buffer classes + `HlsSegmentFileTransferEngine.kt`.
3. **D6**: `PendingStateFiles.kt` (write, read, set aside, with `AllowListObjectInputStream`
   and `PendingStateAllowList`; `migrate` not yet called), `PendingStateAndroid.kt` with
   `prepare` doing only `deleteTemporaries`, the resource file, `Utility.java`, and the
   `DownloadManager.java` lines except `:80`.
4. **S4**: `migrate` wired into `prepare`, and `DownloadManager.java:80`. Reverting it puts
   state back in external storage; the reader stays filtered.

Upstream lines in total: `DownloadRunnableFallback.java` 7, `Utility.java` 4,
`DownloadManager.java` 6, `HlsSegmentFileTransferEngine.kt` about 45. No build file changes.

## 8. Interaction with tls-checks

`origin/feature/tls-checks` edits `App.java`, `MainActivity.java` and `sync/RawHttp.kt`; no
file is shared, the two can merge in either order. Its optional exception hook would add one
line after `DownloadMission.java:256` and `HttpTransferConnectionFactory.kt:23`, neither
touched here. Effects: with certificates checked, a 200 to a range request can no longer be
injected on an HTTPS download, so D5 remains a server-behaviour fix; a failed handshake
arrives in the fallback's `catch` (`:109`) before `decide` runs and keeps the resume offset.
Device step 1 intercepts HTTPS: it works on a build without tls-checks; with tls-checks the
debug build trusts only system roots, so AC-1 is then shown by the instrumented test.

## 9. Risks

- **Allow-list too narrow** (section 4): the most likely regression. Limited by the real
  mission round-trip test, the class name in the log, and files kept in `set_aside/`.
- **M7 is the largest edit of upstream code** (about 45 lines in a file upstream changes
  often): rebase conflicts are likely there. All logic that can live outside does.
- **Streaming decrypt** is a second decrypt path; it runs only for segments that did not fit.
- **`fsync` on every save** may slow very fast downloads slightly on poor flash.
- **Prefix rules** admit every serializable class of the app and extractor; none was found
  whose deserialization has side effects, and the files are app-private after the move.
- **Not covered**: partial media and HLS work directories stay in external storage
  (`DownloadManager.java:571-573`, `HlsDownloader.kt:378`); the multi-threaded path stops with
  an error when a server answers 200 to a block request; backups still contain the state.
  Asked in Q3 and Q4.
