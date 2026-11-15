# Restore certificate checking

Finding S1 in `enhancements/FINDINGS.md`.

## Today

- At startup the app switches off certificate and host-name checking for every connection
  made through the platform's standard HTTPS class. The comment says it fixes a "random
  certificate issue for BiliBili".
- That covers the download engine, HLS downloads, one player data source and the optional
  sync client. Download requests can carry the user's login cookie.
- Browsing and normal playback use a different client and are checked.

## Acceptance criteria

- **AC-1** Connections are checked against the system's trusted certificates and the host
  name, everywhere in the app, by default.
- **AC-2** A connection to a host with an invalid certificate fails with an error the user
  can see where today an error would be shown (a failed download, a failed sync). Nothing is
  sent to that host: no cookie, no request headers beyond the handshake.
- **AC-3** If BiliBili hosts still need the workaround, it applies to those hosts only, by
  an explicit list of host names in one place in the code, and to nothing else.
- **AC-4** The sync client checks the host name whatever AC-3 allows.
- **AC-5** Downloads from hosts with valid certificates work as today.
- **AC-6** No new setting.

## Not covered

- Certificate pinning.
