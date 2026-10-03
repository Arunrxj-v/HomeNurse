package com.homenurse.core.config

import com.homenurse.BuildConfig

/**
 * Single configuration abstraction for every server the app talks to.
 *
 * URLs come from BuildConfig (Gradle properties / environment variables per
 * environment — see app/build.gradle.kts), so no server URL is hardcoded in
 * source. The default endpoint for every environment is the Linux HomeServer
 * reached over Tailscale MagicDNS and HTTPS
 * (https://server.tailda589d.ts.net:8443/homenurse); override with
 * DEV_/STAGING_/PROD_API_URL or _MODEL_URL if needed. Production never uses
 * localhost, a LAN/public IP, plain HTTP, or a certificate Android cannot
 * validate with standard TLS checks.
 *
 * Two bases exist on purpose:
 *  * [API_BASE_URL]    — the HomeNurse backend: authentication/session only
 *    (the `/auth/` family) plus the model manifest endpoint (`/models/manifest`).
 *  * [MODEL_BASE_URL]  — the model distribution server (Gemma files). The
 *    authoritative download URL always comes from the manifest response;
 *    this base is only the fallback/relative-URL resolver.
 *
 * Neither server ever receives medical data — see the network isolation tests.
 */
object ServerConfig {

    /** Which environment this APK was built for: dev | staging | production. */
    val environment: String = BuildConfig.SERVER_ENV

    /** HomeNurse backend base URL (no trailing slash). */
    val API_BASE_URL: String = BuildConfig.API_BASE_URL.trimEnd('/')

    /** Model distribution server base URL (no trailing slash). */
    val MODEL_BASE_URL: String = BuildConfig.MODEL_BASE_URL.trimEnd('/')

    /**
     * Google OAuth *web* client id — public identifier, not a secret. Its
     * audience is what the backend verifies Google ID tokens against.
     */
    val GOOGLE_WEB_CLIENT_ID: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    val isGoogleSignInConfigured: Boolean get() = GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** URL for a backend path such as `/auth/login`. */
    fun apiUrl(path: String): String = API_BASE_URL + normalize(path)

    /** URL for a model-server path such as `/models/1.0.0/model.litertlm`. */
    fun modelUrl(path: String): String = MODEL_BASE_URL + normalize(path)

    /** Model manifest (requires an authenticated session). */
    val manifestUrl: String get() = apiUrl(MANIFEST_PATH)

    /** Resolve a manifest `downloadUrl` that may be root-relative. */
    fun resolveDownloadUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> modelUrl(url)
    }

    private fun normalize(path: String): String =
        if (path.startsWith("/")) path else "/$path"

    const val MANIFEST_PATH = "/models/manifest"
}
