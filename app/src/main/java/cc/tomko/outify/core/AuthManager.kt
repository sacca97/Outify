package cc.tomko.outify.core

import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class AuthManager @Inject constructor() {
    /**
     * Checks for cached credentials.
     * Performs raw filesystem check
     */
    external fun hasCachedCredentials(): Boolean

    /**
     * Processes the OAuth Code and caches the credentials into storage if success.
     * Returns JSON: {"success":true} on success, {"error":{"type":"...","message":"..."}} on failure.
     */
    external fun handleOAuthCode(code: String, state: String? = ""): String

    /**
     * Returns URL of Spotify authorization page, or `null` while there is no session yet
     */
    external fun getAuthorizationURL(): String?

    /**
     * Deletes the credentials cache file for Spirc
     */
    external fun logout(): Boolean
}
