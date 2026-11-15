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
