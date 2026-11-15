# Questions: tls-checks

## Q1 spec -> user
Should this fix get a spec and plan now?

## A1 user
Selected "tls-checks" when asked which fixes from FINDINGS.md to plan.
Applied: this spec. Its acceptance criteria are the session's proposal from the findings and
have not been reviewed by the owner.

## Q2 plan -> spec
"Today" says browsing and normal playback use a different client and are checked. Browsing
does. Playback does not: every player data source (`YoutubeHttpDataSource`,
`PurifiedHttpDataSource`, `NiconicoLiveHttpDataSource`) opens the platform HTTPS class, so
playback of every service is unchecked today and becomes checked with AC-1. AC-2 and AC-5
name only downloads and sync. Should playback be named in "Today", AC-2 and AC-5?
Assumption planned with: yes. Playback is covered by AC-1, fails with the player's own error
on an invalid certificate, and is in the device script (plan D2, D3, D5).

## Q3 plan -> spec
Finding S1 names only `MainActivity`. The extractor does the same thing:
`NewPipe.init(...)` calls its own `trustEveryone()` (`NewPipe.java:73`, `:208-228` in
PipePipeExtractor), and `App.java:132` calls `NewPipe.init` at every process start. Removing
the `MainActivity` call alone fixes nothing. PROCESS.md allows touching the extractor only if
a spec proves it necessary. Should the fork's extractor also drop that call?
Assumption planned with: no extractor change. The client records the platform defaults
before `NewPipe.init` and puts them back right after it (plan 3.1, 3.3), which is sufficient
for AC-1 inside this app. Removing the call in the extractor would be the root-cause fix and
would also cover the instrumented tests that call `NewPipe.init` themselves.

## Q4 plan -> spec
AC-3 cannot be decided from the repository: the local history is shallow (420 commits; the
workaround is already in the oldest one), and the only explanation is the comment "Fix random
certificate issue for BiliBili", which names no host and no kind of failure. If the device
test (plan D4, D5) shows BiliBili hosts failing, how far may "the workaround" go for the
listed hosts: (a) trust one more certificate for those hosts, host name still checked;
(b) skip the host-name check for those hosts, chain still checked; (c) accept any certificate
from those hosts?
Assumption planned with: no exception ships. If one is needed, (a) or (b) according to the
failure recorded on the device, for exact host names only (plan section 2). (c) is not
planned and would need this spec to allow it explicitly.

## A2 spec
Playback is in scope of AC-1, AC-2 and AC-5. "Today" is corrected: playback is unchecked
today, and the extractor repeats the workaround.

## A3 spec
As planned for this change: the client restores the platform defaults after the extractor
has been initialised, with no change to the extractor. Removing the call in the fork's
extractor is the root-cause fix and is the owner's decision; it is noted in `TODO.md`.

## A4 spec
No exception ships. If a device test shows BiliBili needs one, it may only be an added trust
anchor or a host-name relaxation for exactly named hosts, as the plan describes. Accepting
any certificate is not allowed.

## Q5 tests -> plan
Section 4 says that before Code exists "T1/T2 do not compile" while T3 and T4 fail. All four
are in one source set: if T1/T2 do not compile, no unit test of the branch runs, T3 and T4
included. Also, T1 needs `restore()` on a `TlsDefaults` that never captured and a "first"
`capture()` per test, which a singleton shared by all tests of one JVM cannot give. May the
tests reach `TlsDefaults` and `StrictHostnames` by reflection?
Assumption tested with: yes. The tests look both classes up by name and check the plan's
signatures at run time (public static `capture()`, `restore()`, both synchronized;
`matches(String, SSLSession): Boolean`). T1/T2 load `TlsDefaults` a second time in a class
loader of their own (parent: JDK classes only; class path: the test run's), so every test
starts with nothing recorded; this assumes `TlsDefaults` needs only the JDK, the Kotlin stdlib
and app classes. Before Code exists T1/T2 fail with "... does not exist: not implemented yet".

## Q6 tests -> plan
The tests check more than T1 to T4 list. Is each of these wanted? All pass on today's sources
or against an implementation as planned.
- T2, second test: a certificate the default factory trusts but for another name connects after
  `trustEveryone()` and fails with `IOException` after `restore()` (the host-name half of AC-1).
- T3: `https://127.0.0.1:<port>` with the `localhost` certificate answers 200 (IP literal, plan
  3.2); an untrusted certificate is rejected with accept-all `HttpsURLConnection` defaults.
- `StrictHostnamesTest`: `StrictHostnames.matches` directly on real sessions (true for the
  certificate's names, false for others, false with an accept-all default verifier, false for a
  session without peer certificate). It is the only test that runs the planned verifier before
  `RawHttp` is edited.
- T4: `SSLContext.setDefault(` nowhere; `setSSLSocketFactory(`, `setHostnameVerifier(`,
  `.sslSocketFactory =`, `.hostnameVerifier =` nowhere (the plan's `hostnameVerifier(` does not
  match the per-connection setter, so a relaxed verifier on one connection would pass T4 as
  listed); `checkServerTrusted` only inside the inert `MainActivity.trustEveryone()`;
  `::trustEveryone` counted as a call; `capture()` and `restore()` must be the statements
  directly before and after `NewPipe.init(...)`, not merely somewhere around it.
Assumption tested with: yes to all. If section 2 (an exception list) is ever carried out, T4's
per-connection check has to be revised in that round.

## Q7 tests -> plan
Section 4 maps T4 to AC-6 but none of the listed checks concerns settings. What should T4
assert for AC-6?
Assumption tested with: `TlsDefaults.kt` and `StrictHostnames.kt` exist under
`src/main/java/org/schabi/newpipe/enhancements/tlschecks/`; no file of that package imports
`android.*`/`androidx.*` or mentions `Preference`; no file under `src/main/res` is named
`*tls*` and no resource XML mentions `tls-checks`/`tls_checks`. Device step D7 stays the real
check.

## Q8 tests -> plan
D1 (instrumented, badssl.com hosts) is not written in this round: it needs the public network
and a device or emulator at run time, neither of which the test layer had, so it could not be
run even once. Should it be written unrun, or stay a manual device step?
Assumption: left out; AC-1/AC-2 on Android are covered by device steps D3 and D6 until D1
exists.
