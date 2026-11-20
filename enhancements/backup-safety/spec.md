# Backup safety

Findings D1, D3, S2 and S3 in `enhancements/FINDINGS.md`.

## Today

- Importing a backup deletes the live database and then writes the new one over it. A
  truncated file or a full disk leaves a corrupt database.
- A backup whose database version the app cannot upgrade makes the app crash at every start,
  after the old database is gone.
- After an import, the app can offer to install a "missing plugin" from an address stored in
  the imported database. The address is not shown.
- Every backup and settings export contains the login cookies and the proxy token in clear
  text. Android's own backup and device transfer get them too.

## Acceptance criteria

Import

- **AC-1** An import either completes or leaves the database, its companion files and the
  settings exactly as they were. This holds for a truncated or damaged file, a full disk and
  a process kill at any moment.
- **AC-2** Before anything is replaced, the imported database is checked: it is a readable
  database, and its version is one the app can open. Otherwise the import is refused with a
  message that names the reason.
- **AC-3** A successful import behaves as today, including the restart.

Plugins after an import

- **AC-4** A plugin install offered because imported data refers to a missing plugin shows
  the full repository address and asks for confirmation that names it.
- **AC-5** That install is refused for an address that is not https, like an address typed
  in the plugin settings.

Secrets in backups

- **AC-6** Login cookies and the proxy token are not written to a backup or settings export
  unless the user ticks "Include login data" in the export, which is off by default and says
  that anyone with the file can use the accounts.
- **AC-7** Importing a backup without login data leaves the current logins as they are.
- **AC-8** Android's own backup and device transfer do not include login cookies, the proxy
  token or installed plugins.
- **AC-9** Scheduled backups never include login data.

## Not covered

- Encrypting backups.
