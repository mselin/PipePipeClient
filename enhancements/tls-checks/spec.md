# Restore certificate checking

Finding S1 in `enhancements/FINDINGS.md`.

## Today

- At startup the app switches off certificate and host-name checking for every connection
  made through the platform's standard HTTPS class. The comment says it fixes a "random
  certificate issue for BiliBili". The extractor library does the same when it is
  initialised, at every start of the app.
- That covers the download engine, HLS downloads, all playback, and the host-name check of
  the optional sync client. Download requests can carry the user's login cookie.
- Browsing (search, channel pages, video details), images and the built-in web views use
  other clients and are checked.

## Acceptance criteria

- **AC-1** Connections are checked against the system's trusted certificates and the host
  name, everywhere in the app, by default.
- **AC-2** A connection to a host with an invalid certificate fails with an error the user
  can see where today an error would be shown (a failed download, a failed sync). Nothing is
  sent to that host: no cookie, no request headers beyond the handshake.
- **AC-3** If BiliBili hosts still need the workaround, it applies to those hosts only, by
  an explicit list of host names in one place in the code, and to nothing else.
- **AC-4** The sync client checks the host name whatever AC-3 allows.
- **AC-5** Downloads from and playback of hosts with valid certificates work as today.
- **AC-6** No new setting.

## Not covered

- Certificate pinning.
