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
