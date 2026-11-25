# Plan: tls-checks

Spec: `spec.md` (AC-1..AC-6). Everything below was read in the code at `origin/dev` `cd87fc7`
and in the fork's extractor checkout (`../PipePipeExtractor`, `56379dc`). Nothing was run.
Paths are under `app/src/main/java/` unless they start with `app/` or `extractor:`.

## 1. Inventory

### 1.1 Places that weaken or replace TLS validation

| # | Where | What it does | After the fix |
|---|---|---|---|
| W1 | `org/schabi/newpipe/MainActivity.java:143` calling `:946-962` | `trustEveryone()`: accept-all `X509TrustManager` and accept-all `HostnameVerifier` installed as the process-wide defaults of `HttpsURLConnection`, each time the main activity is created | call removed, method made inert |
| W2 | `extractor:extractor/src/main/java/org/schabi/newpipe/extractor/NewPipe.java:73` calling `:208-228` | **Not in finding S1.** The same `trustEveryone()`, run by `NewPipe.init(...)`, which `org/schabi/newpipe/App.java:132` calls in `Application.onCreate`. So the defaults are replaced at every process start, also when only a service or worker runs. Removing W1 alone changes nothing. | platform defaults captured before `NewPipe.init` and put back right after it (client side) |
| W3 | `org/schabi/newpipe/sync/RawHttp.kt:60` | asks `HttpsURLConnection.getDefaultHostnameVerifier()`, which W1/W2 made accept-all. The chain is checked (`:57` uses `SSLSocketFactory.getDefault()`, which W1/W2 do not touch), the host name is not: any publicly valid certificate for any name is accepted | own strict verifier, independent of the process default |
| - | `org/schabi/newpipe/DownloaderImpl.java:150-181` | `sslSocketFactory(TLSSocketFactoryCompat, platform trust manager)` + cipher list. Not a weakening: platform trust manager, OkHttp's default host-name check | unchanged |
| - | `org/schabi/newpipe/util/TLSSocketFactoryCompat.java:44-50` | `setAsDefault()` would install a validating factory; no caller | unchanged |
| - | `app/src/main/res/xml/network_security_config.xml` (+ debug copy), `app/src/main/AndroidManifest.xml:58` | trust anchors = system + ISRG Root X1 (commit `be02c84`). Adds one root, removes no check. Debug also permits cleartext | unchanged |
| - | WebViews: `SharedWebViewRuntime.java:318`, `error/ReCaptchaActivity.java:107`, `views/*LoginWebViewActivity`, `util/LoginSessions.kt` | no `onReceivedSslError` override anywhere (`git grep` is empty), so WebView cancels on a certificate error | unchanged |

No other match for `TrustManager`, `HostnameVerifier`, `SSLContext`, `SSLSocketFactory`,
`sslSocketFactory(`, `hostnameVerifier(`, `CertificatePinner` in `app/src`. The manifest has no
`android:process`, so there is one process and one set of defaults.

### 1.2 Network clients

| Client | Where | Stack | Today | After |
|---|---|---|---|---|
| Extractor requests, plugin and update fetches | `DownloaderImpl.java:104` (and `newBuilder()` copies `:339`, `:426`, `:545`) | OkHttp | checked | checked |
| Images | `util/PicassoHelper.java:98` | OkHttp, defaults | checked | checked |
| Download engine | `us/shandian/giga/get/DownloadMission.java:256` (used by `DownloadInitializer`, `DownloadRunnable`, `DownloadRunnableFallback`, `DownloadMissionRecover`) | `HttpsURLConnection` defaults | **not checked**; sends `Cookie` from the `#cookie=` URL part (`:248-260`) and stream headers | checked |
| HLS downloads | `us/shandian/giga/hls/transfer/HttpTransferConnectionFactory.kt:23` | same | **not checked**; `Cookie` at `:33` | checked |
| YouTube playback | `player/datasource/YoutubeHttpDataSource.java:806` | same | **not checked** | checked |
| All other playback | `player/helper/PurifiedHttpDataSource.java:16` (extends ExoPlayer `DefaultHttpDataSource`; built in `player/helper/PlayerDataSource.kt:81`, `:185`), `NiconicoLiveHttpDataSource.java:24` | same | **not checked** | checked |
| Sync | `sync/RawHttp.kt:53-62` (callers `sync/DebugSync.kt:83`, `:192`) | raw `SSLSocket` | chain checked, **host name not** | both checked |
| Live chat / NicoNico live sockets | `extractor:` `BilibiliWebSocketClient.java:38` (`wss://`), `NicoWebSocketClient`; Java-WebSocket 1.6.0 | own `SSLContext` | not touched by app code; whether its host-name check passes through the replaced default verifier depends on the platform's TLS provider and cannot be read from this repository | platform behaviour |
| Cast | `cast/CastController.kt:215`, `cast/CastReceiver.kt:36` | `ws://` on the LAN, no TLS | n/a | n/a |
| WebViews | see 1.1 | Chromium | checked | checked |

The spec's "Today" says normal playback is checked. The code says otherwise: every ExoPlayer
data source in the app opens `HttpURLConnection`. See Q2.

## 2. The BiliBili question (AC-3)

What the repository shows:

- The local history is a shallow clone: 420 commits, oldest `f3f2e83` (2026-07-01).
  `git log --all -S trustEveryone`, `-S"random certificate issue"` and
  `-S setDefaultSSLSocketFactory` each return only `f3f2e83`, the clone boundary, where the
  whole file appears. The workaround is older than the available history. No commit message
  in those 420 mentions it. The extractor checkout holds a single commit.
- The only statement of purpose is the comment at `MainActivity.java:143`. It names no host
  and no kind of failure (expired, wrong name, unknown issuer).
- The line above it (`:142`) is NewPipe's comment for `TLSSocketFactoryCompat.setAsDefault()`,
  which now has no caller: the accept-all call took the place of a validating one.
- The code knows BiliBili media hosts only by substring
  (`extractor:` `BilibiliService.java:330-334`, `:342`), and the comments there are about HEAD
  requests answering 404 and 403 for non-browser clients, not about certificates.
- Upstream's recent certificate fix (`be02c84`, 2026-11-13) adds a trust anchor in the network
  security config; it does not rely on this workaround.

Conclusion: **the reason and the hosts cannot be established from the repository.** The fix is
planned with no exception. AC-3 is then met by there being nothing that relaxes a check
(test T4 keeps it so). Whether BiliBili needs anything is decided by device steps D4/D5.

### If a device test shows an exception is needed

Record first, from logcat, the exact host name and the exception (`CertPathValidatorException`
text, or `SSLPeerUnverifiedException`). Then, in a new `spec`/`plan` round (Q4):

1. **Chain not trusted** (missing root or intermediate): add a `<domain-config>` for the
   recorded host with that certificate as trust anchor in
   `app/src/main/res/xml/network_security_config.xml` (and the debug copy). Host-name checking
   stays on. This is the existing one place for per-domain trust; no code.
2. **Host name does not match**: add
   `org/schabi/newpipe/enhancements/tlschecks/TlsExceptionHosts.kt`:
   ```kotlin
   object TlsExceptionHosts {
       /** Exact lower-case host names. The only place an exception is declared. */
       val HOSTS: Set<String> = setOf(/* names recorded on the device */)
       /** Exact match after lower-casing and dropping one trailing dot. No suffix, wildcard or substring. */
       @JvmStatic fun isListed(host: String?): Boolean
       /** No-op unless conn is HttpsURLConnection and isListed(conn.url.host). Then sets, on this
        *  connection only, a verifier: isListed(name) || StrictHostnames.matches(name, session). */
       @JvmStatic fun applyTo(conn: HttpURLConnection)
   }
   ```
   and one hook line `TlsExceptionHosts.applyTo(conn); // ENH:tls-checks` after
   `DownloadMission.java:256` and after `HttpTransferConnectionFactory.kt:23`. The chain is
   still validated. The verifier re-checks the name it is asked about, so a redirect to an
   unlisted host is checked normally.
3. Accepting any certificate for a host is not offered by this plan.

Why it can never reach other hosts or sync: nothing is installed process-wide (T4 allows
`setDefault*` only in `TlsDefaults`); the relaxed verifier lives on single download connections
and returns `true` only for a listed name; `BilibiliService.isBiliBiliDownloadUrl` is not
reused because it matches substrings of the whole URL; `RawHttp` never reads a connection or
process verifier (section 3.3) and T4 forbids any reference to `TlsExceptionHosts` under
`sync/`. Playback cannot take a per-connection exception (ExoPlayer creates its connections
internally); a playback-only failure would need its own plan.

## 3. The change

Three upstream files, six marked lines, two new files. No build file, resource or setting.

### 3.1 New: `org/schabi/newpipe/enhancements/tlschecks/TlsDefaults.kt` (no Android imports)

```kotlin
package org.schabi.newpipe.enhancements.tlschecks

/** Keeps the platform's HttpsURLConnection defaults across code that replaces them. */
object TlsDefaults {
    /** Remembers HttpsURLConnection.getDefaultSSLSocketFactory() and
     *  getDefaultHostnameVerifier(). Only the first call records; later calls do nothing,
     *  so already replaced defaults can never be recorded over the good ones. */
    @JvmStatic @Synchronized fun capture()

    /** Sets both defaults back to the recorded ones. Idempotent.
     *  @throws IllegalStateException if capture() was never called. */
    @JvmStatic @Synchronized fun restore()
}
```

### 3.2 New: `org/schabi/newpipe/enhancements/tlschecks/StrictHostnames.kt` (no Android imports)

```kotlin
package org.schabi.newpipe.enhancements.tlschecks

object StrictHostnames {
    /** RFC 2818 match of host (DNS name or IP literal) against the session's peer certificate.
     *  Delegates to okhttp3.internal.tls.OkHostnameVerifier, the verifier the browsing client
     *  already uses. Reads no process-wide or per-connection setting. False on any error. */
    @JvmStatic fun matches(host: String, session: javax.net.ssl.SSLSession): Boolean
}
```

`OkHostnameVerifier` is present in the build's OkHttp 5.4.0 (`app/build.gradle:231`). It sits
in an `internal` package: an OkHttp upgrade that moves it fails at compile time, not silently.

### 3.3 Edits to upstream files (every added or changed line ends with the marker)

| File | Edit |
|---|---|
| `App.java` | new line before `:132`: `TlsDefaults.capture(); // ENH:tls-checks` |
| `App.java` | new line after the `NewPipe.init(...)` statement (`:132-134`): `TlsDefaults.restore(); // ENH:tls-checks NewPipe.init replaces the HTTPS defaults` (fully qualified name, or one marked import line) |
| `MainActivity.java:143` | line replaced by `// ENH:tls-checks trustEveryone() is no longer called: certificates and host names are checked` |
| `MainActivity.java` after `:946` | new first line of the method: `if (true) return; // ENH:tls-checks inert, kept because upstream lines are not deleted` |
| `RawHttp.kt:11` → after `:13` | `import javax.net.ssl.HttpsURLConnection` removed; `import org.schabi.newpipe.enhancements.tlschecks.StrictHostnames // ENH:tls-checks` added in ktlint order |
| `RawHttp.kt:60` | `if (!StrictHostnames.matches(host, ssl.session)) { // ENH:tls-checks` |

`check-layers.sh`: `App.java` +2/-0, `MainActivity.java` +2/-1, `RawHttp.kt` +2/-2; all added
lines marked. `trustEveryone()` and its imports stay because the check forbids net deletion;
the method cannot do anything and T4 forbids callers.

Not edited: `DownloadMission`, `HttpTransferConnectionFactory`, the data sources. They use the
process defaults, which are the platform's again. The extractor is not edited (PROCESS.md:
only if a spec requires it); see Q3.

Error paths already exist and need no change: a failed handshake is an `SSLException` before
any request byte is written; downloads map it at `DownloadMission.java:342` to
`ERROR_SSL_EXCEPTION`, shown as "Could not establish a secure connection"
(`MissionAdapter.java:516`); HLS reports through `HlsDownloader.kt:80/84`; playback shows the
player's error; sync shows the toast at `DebugSettingsFragment.java:69-72` when the server is
set, and the periodic worker only logs (`DebugSync.kt:118-120`), as it does for any failure
today. `RawHttp` verifies at `:59-62`, before the request is written at `:86`.

## 4. Verification

`app/build.gradle:296-300`: JUnit 4.13.2 only for unit tests (no Robolectric, Mockito or
MockWebServer); androidTest has the AndroidX runner. Build files do not change.

### JVM unit tests, `app/src/test/java/org/schabi/newpipe/enhancements/tlschecks/`

Both new classes and `RawHttp` use only `java.*`/`javax.*`/OkHttp, so they load on the JVM. The
tests need a local TLS server (`SSLServerSocket` on port 0 on a thread, a dozen lines) and two
key stores embedded as Base64 constants in the test package (self-signed, long validity,
made once with `keytool`): `localhost` (SAN `localhost`, `127.0.0.1`) and `other` (SAN
`other.test`). Each test restores `SSLContext.setDefault` and the `HttpsURLConnection`
defaults in `@After`.

| Test | Shows | AC |
|---|---|---|
| T1 `TlsDefaultsTest` | `capture()`, then the extractor's real `NewPipe.trustEveryone()`, then `restore()`: both defaults are the same instances as before; a second `capture()` after replacing them does not record the replaced ones; `restore()` without `capture()` throws | AC-1 |
| T2 `TlsDefaultsConnectionTest` | `HttpsURLConnection` to the local server with an untrusted certificate: connects after `trustEveryone()`, throws `SSLException` after `restore()`, and the server read no request bytes | AC-1, AC-2 |
| T3 `RawHttpHostnameTest` | client trusts both test certificates. `https://localhost:<port>` with the `localhost` certificate: 200. With the `other` certificate: `IOException`, server read no request bytes. Same again with an accept-all default verifier installed: still `IOException` | AC-2, AC-4 |
| T4 `NoTlsWeakeningSourceTest` | scans `src/main/java` (relative to the module dir, the Gradle test working dir): `setDefaultHostnameVerifier(` / `setDefaultSSLSocketFactory(` only in `TlsDefaults.kt`, `TLSSocketFactoryCompat.java` and the inert `MainActivity.trustEveryone` body; no `trustEveryone();` call; `NewPipe.init(` exactly once and between `capture()` and `restore()`; first statement of `trustEveryone` is the inert return; no `onReceivedSslError`; `sslSocketFactory(`/`hostnameVerifier(` only in `DownloaderImpl.java`; nothing under `sync/` mentions `TlsExceptionHosts` or `getDefaultHostnameVerifier` | AC-1, AC-3, AC-4, AC-6 |

Before Code exists: T1/T2 do not compile, T3 fails (the JDK's default verifier rejects, and
the accept-all case connects), T4 fails on `MainActivity.java:143`.
Limit: T2 exercises the JDK's `HttpsURLConnection`, not Android's. The Android side is D1.

### Device (integration debug APK; not run by CI)

- **D1 instrumented**, `app/src/androidTest/.../enhancements/tlschecks/TlsDefaultsDeviceTest`,
  run in the app process after `App.onCreate`: `HttpsURLConnection` to
  `expired.badssl.com`, `wrong.host.badssl.com`, `self-signed.badssl.com`,
  `untrusted-root.badssl.com` each throws `SSLException`; `https://badssl.com/` answers 200;
  the same after launching `MainActivity`. (AC-1, AC-2)
- **D2 valid hosts**: download a YouTube video (video+audio and audio only) and one HLS
  stream; play a YouTube video and one from another service. All complete as before. (AC-5)
- **D3 invalid certificate, visible error**: route the device through an intercepting proxy
  whose CA is not installed on it. Start a download: the mission shows "Could not establish a
  secure connection"; start playback: player error. The proxy log shows failed handshakes and
  no request, so no cookie left the device. On today's build the same download succeeds
  through the proxy: that is the before/after proof. (AC-2)
- **D4 BiliBili download**: logged in, download two videos, 20 MB or more each, and pause and
  resume one. (AC-3, AC-5)
- **D5 BiliBili playback**: play three videos and one live stream for a minute each. On any
  failure in D4/D5 save `adb logcat | grep -iE "SSL|Cert"` with the host name; that is the
  input for section 2.
- **D6 sync**: in debug settings set the server to `https://wrong.host.badssl.com`: toast
  ending in "Certificate does not match wrong.host.badssl.com". `https://expired.badssl.com`:
  toast with a certificate error. The usual server (`http://` LAN or valid `https://`): "Sync
  server set" and a sync round completes. (AC-2, AC-4)
- **D7**: settings screens show no new entry. (AC-6)

## 5. AC coverage

| AC | Design piece | Checked by |
|---|---|---|
| AC-1 | W1 removed and inert; W2 undone by `TlsDefaults`; W3 by `StrictHostnames` | T1, T2, T4, D1 |
| AC-2 | handshake or name check fails before the request is written; existing error paths | T2, T3, D1, D3, D6 |
| AC-3 | no exception ships; section 2 fixes how one would be added | T4, D4, D5 |
| AC-4 | `RawHttp` uses `StrictHostnames`, never a default or per-connection verifier | T3, T4, D6 |
| AC-5 | valid hosts use the platform defaults, as they did before the workaround existed | D2, D4 |
| AC-6 | no resource, preference or string added | T4, D7 |

## 6. Order of work

1. `plan(tls-checks)`: this file and Q2-Q4. Wait for the answers; Q3 and Q4 can change scope.
2. `test(tls-checks)`: fixtures, T1-T4, D1.
3. `code(tls-checks)`: the two new files, then `App.java`, `RawHttp.kt`, `MainActivity.java`.
4. `check-layers.sh origin/dev`, unit tests, then the integration APK and D1-D7.
5. Only if D4/D5 fail: questions round, then section 2.

## 7. Risks

| Risk | How the user would notice | Handling |
|---|---|---|
| BiliBili media hosts really do present bad certificates | BiliBili downloads end with "Could not establish a secure connection", or BiliBili playback errors, while other services work | D4/D5 before merging; section 2. Until then the choice is a failing BiliBili path or an unchecked one; the plan picks failing |
| Playback is affected, which the spec did not expect | a stream that played before shows a player error on a network with a broken or intercepting certificate (captive portal, filtering proxy, wrong device clock) | intended by AC-1; Q2 |
| Android 7.0 devices lack newer roots | downloads or playback from some hosts fail on Android 7.0 only, as browsing already does there | the config already adds ISRG Root X1; further roots go in the same file |
| A sync server reached over `https://` with a certificate for another name (LAN box by IP) | toast "Certificate does not match ..." when setting the server; periodic sync silently stops | intended (AC-4); use `http://` on the LAN or a matching certificate |
| Requests made between `NewPipe.init` and `restore()` | none visible | two adjacent statements on the main thread during startup; sync does not depend on the defaults |
| Upstream or the extractor calls `trustEveryone()` or `NewPipe.init` from a new place | none visible: checks silently off again | T4 fails on the next rebase; the inert method covers the client copy; Q3 proposes removing the extractor copy |
| `OkHostnameVerifier` moves in a later OkHttp | build fails | replace the one delegate in `StrictHostnames` |
