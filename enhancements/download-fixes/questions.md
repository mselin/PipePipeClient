# Questions: download-fixes

## Q1 spec -> user
Should this fix get a spec and plan now?

## A1 user
Selected "download-fixes" when asked which fixes from FINDINGS.md to plan.
Applied: this spec. Its acceptance criteria are the session's proposal from the findings and
have not been reviewed by the owner.

## Q2 plan -> spec
AC-2 says an unreadable state file is "reported". To whom? Planned with: an error in the
system log naming the file, where it was kept and the cause, plus one short on-screen message
per start with the number of downloads affected (one new text, no setting). If the log alone
is meant, the message and its resource file are dropped.

## Q3 plan -> spec
AC-3 does not name the amount, and AC-1 does not say what the multi-connection path should do.
Planned with: 64 MiB per HLS download, counted as memory allocated for segment data (received,
assembled, decrypted, waiting); a segment that does not fit is not an error, it is written
straight to the file when its turn comes. For AC-1: a download with several connections
already stops with an error, writing nothing, when the server answers a part request with the
whole file; that is left as it is (it does not restart as a single connection).

## Q4 plan -> spec
AC-4 "where other apps cannot read or replace it": planned as the app's private files
directory. Two things stay as they are and are assumed out of scope: the app allows system
backups without exclusions, so the saved state (with cookies) is part of a backup, as it was
in the old location; and unfinished media files and HLS work files remain in the app's
external directory. Should either be covered here?

## A2 spec
"Reported" means a log entry and one message per app start that says how many pending
downloads could not be read and that their files were kept aside.

## A3 spec
64 MiB per transfer, as planned; a segment larger than that is not an error and is written
straight to the file. The multi-threaded path keeps stopping with an error when the server
does not honour a range request, since it never appends.

## A4 spec
Both out of scope here. Keeping download state out of Android's own backup belongs to
backup-safety (its backup rules carry the database only). Partial media and HLS work files
stay where they are.

## Q5 tests -> plan
`writeAtomically` uses one temporary name per target (`<target>.new`). Section 2 says saves
"never overlap", and `DownloadMission.writeThisToFile` is indeed under the mission's lock, but
`DownloadManager.java:253` saves the same file outside it. Two unsynchronized writers of one
target would share the temporary file, and one of them could rename the other's half-written
state into place. Working assumption, tested in
`PendingStateFilesTest.ac2_sameMissionSavedByTwoThreads_theFileIsAlwaysOneCompleteState`:
`PendingStateFiles` itself lets the writers of a target take turns (a `synchronized` is
enough), so that the file is always one complete state and no save fails. If the plan wants
this left to the callers, that test goes.

## Q6 tests -> plan
Section 4 allows "arrays of allowed types" and does not list `java.lang.Object`. A
`java.util.Arrays$ArrayList` (allowed) keeps its elements in the array it was made from, which
is an `Object[]` for a list of mixed or generic element type (`Arrays.asList` / Kotlin `listOf`
called with `Any` elements). Is `[Ljava.lang.Object;` allowed? Working assumption: the tests
assert nothing about `Object[]` in either direction. The real mission of
`RealMissionAllowListTest` (headers, HLS and SABR checkpoints, recovery info with
`YoutubeSabrInfo`, post-processing, an exception with cause) is read without it.

## Q7 tests -> plan
`ResumeCheck.rangeStart`: is `bytes 100-199/*` (complete length unknown, legal in RFC 9110)
"bytes a-b/len" (100) or "malformed" (-1)? Working assumption: not tested. Both readings keep
the file correct; with -1 an honoured resume from such a server is discarded and the download
starts over.

## Q8 tests -> plan
Three details of section 3 that the signatures leave open and the tests fix as follows.
(a) `SegmentBuffer(charge, initialCapacity)` charges `initialCapacity` in its constructor and
throws `SegmentOverBudgetException` there if it does not fit. (b) Growth is charged in steps of
exactly 256 KiB (`initialCapacity + n * 262144`); the number is in the test because the plan has
no constant for it. (c) After `toByteArray()` has built its one array, the charge is lowered to
the size of that array ("frees the chunks" includes their charge), so `charge.bytes` equals the
array's size and `peakBytes` saw chunks + array; when the first array is returned itself,
nothing changes. If any of these is meant differently, `SegmentBufferTest` changes.

## Q9 tests -> plan
`migrate`, case 3 "different bytes": the old file "is copied to `newDir/set_aside/`". Working
assumption, tested in `ac4_plantedFileWithTheNameOfAPrivateMission_neverReplacesIt`: when that
name is taken in `set_aside/` an existing file is never overwritten (some other free name is
used, as `setAside` does with `.1`, `.2`); the first one keeps the plain name. Also assumed:
`newDir` exists when `migrate` is called (section 2 calls it after `getPendingDir`); the tests
always create it, except one where it is a regular file and every copy must count as failed
with nothing deleted.

## A5 plan
As assumed: `PendingStateFiles` serializes the writers of one target.

## A6 plan
No. `Object[]` is not on the list; if a device shows a real mission needs it, the log names
the refused class and the list is extended then.

## A7 plan
Malformed. `bytes a-b/*` is treated like a missing range, which restarts from zero; that is
the safe reading.

## A8 plan
Accepted as the tests fix them.

## A9 plan
Accepted: a name collision in `set_aside` never overwrites, and `newDir` exists when
`migrate` is called.

## Q10 code -> plan
Section 4's list refuses classes that real error objects hold, so a legitimate download can
be set aside at a start instead of listed (the plan's first risk; now with concrete cases).
`DownloadMissionRecover.java:76` stores whatever exception `tryRecover` threw as `errObject`
without the mapping of `notifyError(Exception)`, and that includes the extractor's OkHttp
errors and TLS failures. Checked on the desktop JVM against the classes in the Gradle cache:

- `okhttp3.internal.http2.StreamResetException` (okhttp 5.4.0, an `IOException`) has the
  field `errorCode` of the enum `okhttp3.internal.http2.ErrorCode`: refused.
- `java.security.cert.CertPathValidatorException`, in the cause chain of a failed TLS trust
  check, always holds a `java.security.cert.CertPathValidatorException$BasicReason` (enum):
  refused. With a certificate path it also writes `java.security.cert.CertPath$CertPathRep`.
  (53 of 54 other exception types tried are read; this was the one refused.)

A state file written by the *previous* version with such an error object is set aside at the
first start after the update: the download leaves the list (the file is kept, the toast
says so). That part is as planned and not changed by the Code layer.

For files written by *this* version the Code layer added one thing the plan does not have:
`PendingStateFiles.writeAtomically` fails (and keeps the previous, readable state) when the
state holds a class the reader would refuse; it asks the same list through
`PendingStateAllowList.isAcceptedOnRead` from `ObjectOutputStream.annotateClass`, the bytes
written are unchanged. Effect: after such an error the saved state is the one from before
the error (the download is listed with its last progress, without the error), and
`Utility.writeToFile` logs the refused class at every save until the error is cleared.
Without it the download would vanish from the list at the next start.

What would work, for the plan to choose: (a) add the named classes
(`okhttp3.internal.http2.ErrorCode`, `java.security.cert.CertPathValidatorException$BasicReason`,
`java.security.cert.PKIXReason`, `java.security.cert.CertPath$CertPathRep`); the tests do not
forbid them but A6 reserves list changes for the plan. (b) Stop persisting arbitrary exception
graphs (an upstream edit: store a plain `Exception` with the message). (c) Keep things as they
are. Should the save-time check stay?

## Q11 code -> plan
Section 3 counts "the assembled array, the decrypted copy" for an encrypted segment, and the
engine acquires the cipher text's size before `cipher.doFinal` as planned. `doFinal(byte[])`
itself allocates an output array of the padded size and then returns a trimmed copy (PKCS5
always removes at least one byte), so for the duration of one decrypt a third array of the
segment's size exists that the budget does not see (JDK 25: measured, `doFinal` allocates
2.00 times the cipher text; Android's Conscrypt: assumed to do the same, not run). Worst case the heap held is 1.5 times the
budget (96 MiB), still fixed. Implemented as planned. If the bound is meant strictly: acquire
twice the cipher text's size before decrypting and call `checkDeclared` with 3 copies, at the
price of more segments falling back to streaming. Related, no action taken: a plain segment
between 32 and 64 MiB passes `checkDeclared(…, 1)`, is received in chunks, is refused at the
copy in `toByteArray()` (chunks + array > 64 MiB) and is then downloaded a second time by the
writer; correct, but twice the traffic for such segments.

## A10 plan
(a), and the save-time check stays. Four named classes join the list of section 4, because
real error objects hold them: `okhttp3.internal.http2.ErrorCode`,
`java.security.cert.CertPathValidatorException$BasicReason`, `java.security.cert.PKIXReason`
(three enums) and `java.security.cert.CertPath$CertPathRep` (the serialized form of a
certificate path). Not persisting exception graphs at all (b) would be the cleaner fix but is
a larger upstream edit; it is left for later. Class names on Android are still unverified;
device step 4 and the log of refused classes decide whether more are needed.

## A11 plan
Accepted as implemented. The bound during a decrypt is 96 MiB, not 64 MiB, and still fixed.
A plain segment between 32 and 64 MiB may be downloaded twice; accepted.
