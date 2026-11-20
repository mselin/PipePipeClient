package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.settings.PreferencesObjectInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AC-6, AC-7, AC-9: which settings are login data, and what an export and an import do with
 * them ([LoginData], plan section 4).
 *
 * - AC-6: "Login cookies and the proxy token are not written to a backup or settings export
 *   unless the user ticks 'Include login data'".
 * - AC-7: "Importing a backup without login data leaves the current logins as they are."
 * - AC-9: "Scheduled backups never include login data."
 *
 * The source tests at the end read `res/values/settings_keys.xml` and
 * `settings/ScheduledBackupWorker.kt` as text.
 */
class LoginDataTest {

    private val secrets = setOf(
        "youtube_cookies_key", "bilibili_cookies_key", "niconico_cookies_key",
        "override_cookies_youtube_value_key", "override_cookies_bilibili_value_key",
        "override_cookies_niconico_value_key", "proxy_token_key",
        "recaptcha_cookies_key", "youtube_po_token_key"
    )
    private val switches = setOf(
        "override_cookies_youtube_key", "override_cookies_bilibili_key",
        "override_cookies_niconico_key", "enable_proxy_key"
    )

    /** Settings of a logged-in user, with every type a preferences file can hold. */
    private val loggedIn: Map<String, Any?> = mapOf(
        "youtube_cookies_key" to "SID=abc; HSID=def",
        "bilibili_cookies_key" to "SESSDATA=xyz",
        "niconico_cookies_key" to "user_session=1",
        "override_cookies_youtube_value_key" to "SID=override",
        "override_cookies_bilibili_value_key" to "",
        "override_cookies_niconico_value_key" to "",
        "proxy_token_key" to "token-123",
        "recaptcha_cookies_key" to "recaptcha=1",
        "youtube_po_token_key" to "po-token",
        "override_cookies_youtube_key" to true,
        "override_cookies_bilibili_key" to false,
        "override_cookies_niconico_key" to false,
        "enable_proxy_key" to true,
        "theme" to "dark",
        "show_comments" to true,
        "seek_duration" to 10_000,
        "last_used" to 1_700_000_000_000L,
        "speed" to 1.25f,
        "tabs" to hashSetOf("a", "b"),
        "sponsor_block_user_id" to "private-id",
        "cookie_functions_bilibili_key" to hashSetOf("x")
    )
    private val ordinary = loggedIn - secrets - switches

    // ---------------------------------------------------------------- the key lists

    /** AC-6: the secrets are the nine keys of plan section 4. */
    @Test
    fun ac6_secretKeysAreThePlansNine() {
        assertEquals(secrets, LoginData.SECRET_KEYS)
    }

    /** AC-6: the switches that only mean something together with a secret are the plan's four. */
    @Test
    fun ac6_switchKeysAreThePlansFour() {
        assertEquals(switches, LoginData.SWITCH_KEYS)
    }

    /** AC-6: `ALL_KEYS` is both groups. */
    @Test
    fun ac6_allKeysAreBothGroups() {
        assertEquals(secrets + switches, LoginData.ALL_KEYS)
    }

    /** AC-6 (answer A3): reviewed and not login data; no key is in both lists. */
    @Test
    fun ac6_reviewedKeysAreNotLoginData() {
        val reviewed = setOf(
            "clear_cookie_key", "login_key", "logout_key", "check_token_validity_key",
            "cookie_functions_bilibili_key", "cookie_functions_niconico_key",
            "login_session_refresh_time", "logout_keep_webview_session",
            "youtube_visitor_data", "sponsor_block_user_id", "debug_sync_server"
        )
        assertTrue(
            "REVIEWED_NOT_LOGIN lacks ${reviewed - LoginData.REVIEWED_NOT_LOGIN}",
            LoginData.REVIEWED_NOT_LOGIN.containsAll(reviewed)
        )
        assertEquals(emptySet<String>(), LoginData.REVIEWED_NOT_LOGIN intersect LoginData.ALL_KEYS)
    }

    // ---------------------------------------------------------------- export

    /** AC-6, AC-9: without the tick (the default and every scheduled backup) both groups are left out, everything else is kept. */
    @Test
    fun ac6_ac9_exportWithoutLoginData_dropsSecretsAndSwitches() {
        val exported = LoginData.forExport(loggedIn, includeLoginData = false)
        assertEquals(ordinary, exported)
        for (key in secrets + switches) {
            assertFalse("AC-6: $key must not be exported", exported.containsKey(key))
        }
    }

    /** AC-6: no secret's value appears anywhere in what is written, whatever the format. */
    @Test
    fun ac6_exportWithoutLoginData_containsNoSecretValue() {
        val text = LoginData.forExport(loggedIn, includeLoginData = false).toString()
        for (value in listOf("SID=abc", "SESSDATA", "user_session", "SID=override", "token-123", "recaptcha=1", "po-token")) {
            assertFalse("AC-6: the export still contains \"$value\"", text.contains(value))
        }
    }

    /** AC-6: with the tick everything is exported, as before the fix. */
    @Test
    fun ac6_exportWithLoginData_keepsEverything() {
        assertEquals(loggedIn, LoginData.forExport(loggedIn, includeLoginData = true))
    }

    /** AC-6: every single login key is dropped on its own, also when it is the only entry. */
    @Test
    fun ac6_everyLoginKeyIsDroppedOnItsOwn() {
        for (key in secrets) {
            assertTrue(key, LoginData.forExport(mapOf(key to "value"), false).isEmpty())
        }
        for (key in switches) {
            assertTrue(key, LoginData.forExport(mapOf(key to true), false).isEmpty())
        }
    }

    /**
     * AC-6: the result is a `java.util.HashMap` in both cases, because the reader of the legacy
     * entry accepts only that class, and it is a copy: the preferences' own map is not changed.
     */
    @Test
    fun ac6_exportIsAHashMapCopy() {
        for (include in listOf(false, true)) {
            val source = HashMap(loggedIn)
            val exported = LoginData.forExport(source, include)
            assertSame(java.util.HashMap::class.java, exported.javaClass)
            assertEquals("the map given to forExport must not be changed", loggedIn, source)
            exported.clear()
            assertEquals("the result must be a copy", loggedIn, source)
        }
    }

    /** AC-6, AC-3: what is exported can be read back by upstream's reader of `newpipe.settings`. */
    @Test
    fun ac6_exportIsReadableByTheLegacyReader() {
        for (include in listOf(false, true)) {
            val exported = LoginData.forExport(loggedIn, include)
            val bytes = ByteArrayOutputStream()
            ObjectOutputStream(bytes).use { it.writeObject(exported) }
            val back = PreferencesObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
            assertEquals(exported, back)
        }
    }

    /** AC-6: an empty settings map exports as an empty map. */
    @Test
    fun ac6_exportOfNothing() {
        assertTrue(LoginData.forExport(emptyMap<String, Any?>(), false).isEmpty())
    }

    // ---------------------------------------------------------------- import: has login data?

    /** AC-7 (answer A4): a backup has login data when some secret has a value that is not blank. */
    @Test
    fun ac7_hasLoginData_forEverySecretWithAValue() {
        for (key in secrets) {
            assertTrue(key, LoginData.hasLoginData(mapOf("theme" to "dark", key to "value")))
        }
        assertTrue(LoginData.hasLoginData(loggedIn))
    }

    /**
     * AC-7 (answer A4): a key's presence is no signal. `setDefaultValues` writes
     * `proxy_token_key=""`, so every backup has it; blank values are no login data.
     */
    @Test
    fun ac7_hasLoginData_notForEmptyOrBlankValues() {
        assertFalse(LoginData.hasLoginData(emptyMap<String, Any?>()))
        assertFalse(LoginData.hasLoginData(ordinary))
        assertFalse(LoginData.hasLoginData(secrets.associateWith { "" }))
        assertFalse(LoginData.hasLoginData(secrets.associateWith { "  \t\n" }))
        assertFalse(LoginData.hasLoginData(secrets.associateWith { null }))
    }

    /** AC-7: the switches alone are no login data (a backup made without the tick never has them, an edited one may). */
    @Test
    fun ac7_hasLoginData_notForSwitchesAlone() {
        assertFalse(LoginData.hasLoginData(switches.associateWith { true } + ("proxy_token_key" to "")))
    }

    /** AC-7: a secret key holding something that is no text is no login data. */
    @Test
    fun ac7_hasLoginData_notForValuesThatAreNoText() {
        assertFalse(LoginData.hasLoginData(mapOf("youtube_cookies_key" to true, "proxy_token_key" to 5)))
    }

    /** AC-7: what `forExport` writes without the tick is "without login data", with the tick "with". */
    @Test
    fun ac6_ac7_exportWithoutTheTickIsABackupWithoutLoginData() {
        assertFalse(LoginData.hasLoginData(LoginData.forExport(loggedIn, false)))
        assertTrue(LoginData.hasLoginData(LoginData.forExport(loggedIn, true)))
    }

    // ---------------------------------------------------------------- import: what is kept

    /**
     * AC-7: a backup without login data. Every login key that has a value now is written back
     * with that value and its type; ordinary settings are not written back.
     */
    @Test
    fun ac7_backupWithoutLoginData_keepsEveryCurrentLoginEntry() {
        val imported = mapOf("theme" to "light", "proxy_token_key" to "", "enable_proxy_key" to false)
        assertEquals(loggedIn - ordinary.keys, LoginData.keptOnImport(imported, loggedIn))
    }

    /** AC-7: the kept values keep their type (text stays text, a switch stays a boolean). */
    @Test
    fun ac7_keptValuesKeepTheirType() {
        val kept = LoginData.keptOnImport(emptyMap<String, Any?>(), loggedIn)
        assertEquals("SID=abc; HSID=def", kept["youtube_cookies_key"])
        assertEquals(true, kept["enable_proxy_key"])
        assertEquals(false, kept["override_cookies_bilibili_key"])
        assertEquals("", kept["override_cookies_bilibili_value_key"])
    }

    /** AC-7: login keys that are absent now are not invented; with nothing to keep the result is empty. */
    @Test
    fun ac7_absentKeysAreNotWrittenBack() {
        val current = mapOf("theme" to "dark", "youtube_cookies_key" to "SID=abc")
        assertEquals(
            mapOf("youtube_cookies_key" to "SID=abc"),
            LoginData.keptOnImport(mapOf("theme" to "light"), current)
        )
        assertTrue(LoginData.keptOnImport(mapOf("theme" to "light"), ordinary).isEmpty())
    }

    /** AC-7 (answer A4): a backup with login data replaces all logins, as today: nothing is written back. */
    @Test
    fun ac7_backupWithLoginData_keepsNothing() {
        val imported = mapOf("theme" to "light", "bilibili_cookies_key" to "SESSDATA=other")
        assertTrue(LoginData.keptOnImport(imported, loggedIn).isEmpty())
        assertTrue(LoginData.keptOnImport(loggedIn, loggedIn).isEmpty())
    }

    /**
     * AC-7, the round trip of device steps 7 and 8, imitated: export without the tick, import
     * it over the same settings the way the hook does (the editor clears, puts the backup's
     * entries, then puts what `keptOnImport` returns). The logins are as before.
     */
    @Test
    fun ac7_exportWithoutLoginThenImport_leavesTheLoginsAsTheyAre() {
        val backup = LoginData.forExport(loggedIn, false)
        val changedSince = loggedIn + ("theme" to "light") + ("youtube_cookies_key" to "SID=newer login")
        val afterImport = HashMap<String, Any?>(backup).apply { putAll(LoginData.keptOnImport(backup, changedSince)) }
        assertEquals("SID=newer login", afterImport["youtube_cookies_key"])
        assertEquals("token-123", afterImport["proxy_token_key"])
        assertEquals(true, afterImport["enable_proxy_key"])
        assertEquals("ordinary settings come from the backup", "dark", afterImport["theme"])
        assertEquals(changedSince.filterKeys { it in secrets + switches }, afterImport.filterKeys { it in secrets + switches })
    }

    // ---------------------------------------------------------------- against upstream's sources

    /** `<string name="..">value</string>` entries of `res/values/settings_keys.xml`. */
    private fun settingsKeys(): Map<String, String> {
        val file = MainFiles.existing("AC-6", "res/values/settings_keys.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate {
            nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
        }
    }

    /**
     * AC-6: the keys are the literal strings the app stores under. Every login key is declared
     * in `settings_keys.xml` with a value equal to its name; a renamed value would make the
     * filter miss the stored secret.
     */
    @Test
    fun ac6_everyLoginKeyEqualsItsValueInSettingsKeys() {
        val declared = settingsKeys()
        for (key in LoginData.ALL_KEYS) {
            assertEquals("settings_keys.xml: the value of <string name=\"$key\">", key, declared[key])
        }
    }

    /**
     * AC-6: no secret is forgotten when upstream adds one. Every key of `settings_keys.xml`
     * whose name or value contains `cookie`, `token`, `password`, `secret` or `credential` is
     * login data or was reviewed and listed as not being login data.
     */
    @Test
    fun ac6_everySecretLookingKeyIsClassified() {
        val words = listOf("cookie", "token", "password", "secret", "credential")
        val known = LoginData.ALL_KEYS + LoginData.REVIEWED_NOT_LOGIN
        val unclassified = settingsKeys().filter { (name, value) ->
            words.any { name.contains(it, ignoreCase = true) || value.contains(it, ignoreCase = true) } &&
                name !in known && value !in known
        }
        assertEquals(
            "settings_keys.xml has keys that look like login data and are in neither LoginData.ALL_KEYS " +
                "nor LoginData.REVIEWED_NOT_LOGIN",
            emptyMap<String, String>(),
            unclassified
        )
    }

    /**
     * AC-9: "Scheduled backups never include login data." The worker builds its own
     * `ContentSettingsManager`, whose `includeLoginData` is `false` unless somebody sets it;
     * the worker's source must not mention the property (and carries no line of this fix).
     */
    @Test
    fun ac9_scheduledBackupWorkerNeverAsksForLoginData() {
        val source = MainFiles.text("AC-9", "java/org/schabi/newpipe/settings/ScheduledBackupWorker.kt")
        assertFalse(
            "AC-9: ScheduledBackupWorker.kt must not mention includeLoginData",
            source.contains("includeLoginData")
        )
        assertFalse("AC-9: ScheduledBackupWorker.kt is not edited by this fix", source.contains("ENH:backup-safety"))
        assertTrue(
            "AC-9: the worker builds its own manager (if this changed, review where the flag comes from)",
            source.contains("ContentSettingsManager(NewPipeFileLocator(")
        )
        assertTrue(source.contains("manager.exportDatabase("))
    }
}
