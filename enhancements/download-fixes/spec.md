# Download fixes

Findings D5, D6, M7 and S4 in `enhancements/FINDINGS.md`.

## Acceptance criteria

- **AC-1** (D5) A resumed download never appends a full response after an already downloaded
  part. If the server does not honour the resume request, the download starts over from the
  beginning of the file. A finished file is byte-for-byte what the server sent.
- **AC-2** (D6) The saved state of a pending download survives a process kill at any moment:
  after a restart the download is in the list with its last saved progress. A state file
  that cannot be read is reported and kept aside, not deleted silently.
- **AC-3** (M7) An HLS download holds at most a fixed amount of downloaded data in memory
  while waiting to write it, whatever the thread count, segment size or a stalled segment.
- **AC-4** (S4) The saved state of pending downloads is stored where other apps cannot read
  or replace it, on every supported Android version. State saved by an older version of the
  app is moved there at the first start.
- **AC-5** Downloads that work today work as before, including pause, resume and retry.
- **AC-6** No new setting.
