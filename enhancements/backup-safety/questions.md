# Questions: backup-safety

## Q1 spec -> user
Should this fix get a spec and plan now?

## A1 user
Selected "backup-safety" when asked which fixes from FINDINGS.md to plan.
Applied: this spec. Its acceptance criteria are the session's proposal from the findings and
have not been reviewed by the owner.

## Q2 plan -> spec
AC-8: Android's backup rules select whole files. The login cookies and the proxy token are in
the same preferences file as every other setting, and plugin logins are in further preferences
files. Planned with: Android's own backup and device transfer carry only the database; no
settings at all travel that way (the app's own export remains for settings). Is that
acceptable, or must settings without login data still be transferred (that needs a custom
backup agent, a larger change)?

## Q3 plan -> spec
AC-6 names "login cookies and the proxy token". Two more stored values are credential-like:
the SponsorBlock private user id (`sponsor_block_user_id`, lets its holder submit as the user)
and the sync server address (`debug_sync_server`). Planned with: both are ordinary settings and
stay in every export. The plan's section 4 lists all keys treated as login data, including the
on/off switches that only mean something together with a cookie or the token. Should the
SponsorBlock id be login data too?

## Q4 plan -> spec
AC-7 does not say what "a backup without login data" is, and backups made before this fix have
no flag. Planned with: a backup has login data when at least one cookie or token value in it is
not empty. Without login data, all current logins and their switches are kept. With login data,
all of them are replaced by the backup's, as today, which also logs out of a service the backup
has no cookie for. Known gap: a backup whose settings are older than PipePipe settings version
2 makes upstream's own migration clear the YouTube cookie at the next start.

## Q5 plan -> spec
AC-3 says a successful import behaves as today. Planned with three differences in the cases
around it: (a) pressing Back on "Do you want to also import settings?" cancels the whole import
(today the database is already replaced and the app does not restart); (b) a backup whose
settings entry cannot be read is refused as a whole (today the settings are skipped silently
and the database is imported); (c) the database is exchanged during the restart, so a backup
that passes the checks but cannot be opened is reported by a message after the restart, with
the previous data restored. Are these acceptable?
