package org.schabi.newpipe.enhancements.backupsafety

/**
 * Which preferences are login data, and what an export and an import do with them. The keys
 * are the literal strings the app stores under (`res/values/settings_keys.xml`).
 */
object LoginData {
    /** Values that let their holder act as the user. */
    val SECRET_KEYS: Set<String> = setOf(
        "youtube_cookies_key",
        "bilibili_cookies_key",
        "niconico_cookies_key",
        "override_cookies_youtube_value_key",
        "override_cookies_bilibili_value_key",
        "override_cookies_niconico_value_key",
        "proxy_token_key",
        // leftovers of older versions that can still be stored
        "recaptcha_cookies_key",
        "youtube_po_token_key"
    )

    /** Switches that only mean something together with a secret. */
    val SWITCH_KEYS: Set<String> = setOf(
        "override_cookies_youtube_key",
        "override_cookies_bilibili_key",
        "override_cookies_niconico_key",
        "enable_proxy_key"
    )

    val ALL_KEYS: Set<String> = SECRET_KEYS + SWITCH_KEYS

    /** Keys that look like login data, were reviewed and are ordinary settings or buttons. */
    val REVIEWED_NOT_LOGIN: Set<String> = setOf(
        "clear_cookie_key",
        "login_key",
        "logout_key",
        "check_token_validity_key",
        "cookie_functions_bilibili_key",
        "cookie_functions_niconico_key",
        "login_session_refresh_time",
        "logout_keep_webview_session",
        "youtube_visitor_data",
        "sponsor_block_user_id",
        "debug_sync_server"
    )

    /**
     * What an export writes: a copy of [all], without the login data unless [includeLoginData].
     * A `java.util.HashMap`, because the reader of the legacy settings entry accepts no other map.
     */
    fun forExport(all: Map<String, *>, includeLoginData: Boolean): java.util.HashMap<String, Any?> {
        val result = java.util.HashMap<String, Any?>()
        for ((key, value) in all) {
            if (includeLoginData || key !in ALL_KEYS) {
                result[key] = value
            }
        }
        return result
    }

    /** A backup has login data when one of its secrets has a value; a key's presence is no signal. */
    fun hasLoginData(imported: Map<*, *>): Boolean =
        SECRET_KEYS.any { key -> (imported[key] as? String)?.isNotBlank() == true }

    /** entries of [current] to write back after an import; empty when [imported] has login data */
    fun keptOnImport(imported: Map<*, *>, current: Map<String, *>): Map<String, Any?> =
        if (hasLoginData(imported)) emptyMap() else current.filterKeys { it in ALL_KEYS }
}
