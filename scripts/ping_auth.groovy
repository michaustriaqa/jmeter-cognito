/*
 * scripts/ping_auth.groovy
 * -----------------------------------------------------------
 * Reusable PingFederate / PingOne login for JMeter.
 *
 * Both products expose a standard OAuth2 token endpoint, but the URL
 * shape differs by deployment (self-hosted PingFederate vs. PingOne's
 * per-environment SaaS URL, and PingFederate installs commonly customize
 * their base path), so this script takes the full token endpoint URL as
 * config rather than assembling it from parts. It uses Java's built-in
 * HTTP client and has no AWS/SDK dependency at all.
 *
 * Examples of ping.tokenUrl:
 *   PingOne:        https://auth.pingone.com/{envId}/as/token
 *   PingFederate:    https://{pf-host}:9031/as/token.oauth2
 *
 * Features:
 * - Reads Ping config (tokenUrl, clientId, clientSecret, etc.) entirely
 *   from JMeter properties (-J...) - nothing hardcoded, no external
 *   secret store required.
 * - Supports both the Client Credentials grant (default; no end-user
 *   password needed) and the Resource Owner Password grant (set
 *   ping.grantType=password).
 * - Authenticates the client via HTTP Basic auth (client_secret_basic),
 *   the default for both products.
 * - Authenticates once and shares the resulting token across every
 *   thread/sampler for the rest of the test via JMeter properties,
 *   instead of re-authenticating on every iteration/thread.
 * - Exports access_token (and expires_in) to JMeter variables.
 *
 * Required JMeter properties (pass via -J on the command line, or set in
 * user.properties):
 *   ping.tokenUrl       full token endpoint URL (see examples above)
 *   ping.clientId
 *   ping.clientSecret
 *
 * Optional:
 *   ping.scope           space-separated scopes, only sent if set
 *   ping.grantType        "client_credentials" (default) or "password"
 *   ping.username         required when grantType=password
 *   ping.password         required when grantType=password
 *
 * Placement:
 * - Add as a JSR223 Sampler/PreProcessor that runs once per thread
 *   (e.g. the first sampler in the Thread Group, outside any loop).
 *   The first thread to run it performs the real login; every other
 *   thread (and every later iteration) reuses the cached token.
 */

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.time.Duration
import java.util.Base64

import groovy.json.JsonSlurper


// =====[ FAST PATHS: SKIP RE-AUTH WHEN A TOKEN ALREADY EXISTS ]================

// 1. This thread already logged in earlier in the same script/loop.
if (vars.get("access_token")) {
    log.info("[ping_auth] access_token already set for this thread, skipping re-auth.")
    return
}

// Property keys used to share the token across threads once logged in.
final PROP_ACCESS_TOKEN = "ping.access_token"

// 2. Another thread already logged in; reuse the shared token instead of
//    hitting Ping again. The check-then-set below is guarded by the
//    synchronized block so only one thread ever performs the real login.
synchronized (props) {
    def cachedAccessToken = props.get(PROP_ACCESS_TOKEN)
    if (cachedAccessToken) {
        vars.put("access_token", cachedAccessToken)
        log.info("[ping_auth] Reusing token cached by another thread.")
        return
    }

    // =====[ CONFIGURATION ]====================================================

    def requireProp = { name ->
        def value = props.get(name)
        if (!value || value.trim().isEmpty()) {
            throw new RuntimeException("[ping_auth] Missing required JMeter property: -J${name}=...")
        }
        value
    }

    def tokenUrl = requireProp("ping.tokenUrl")
    def clientId = requireProp("ping.clientId")
    def clientSecret = requireProp("ping.clientSecret")
    def scope = props.get("ping.scope")
    def grantType = props.get("ping.grantType") ?: "client_credentials"

    // =====[ HELPERS ]==========================================================

    def formEncode = { Map<String, String> params ->
        params.collect { k, v -> "${URLEncoder.encode(k, 'UTF-8')}=${URLEncoder.encode(v as String, 'UTF-8')}" }.join("&")
    }

    // =====[ MAIN AUTH FLOW ]===================================================

    def formParams = ["grant_type": grantType]
    if (scope) {
        formParams["scope"] = scope
    }
    if (grantType == "password") {
        formParams["username"] = requireProp("ping.username")
        formParams["password"] = requireProp("ping.password")
    }

    def basicAuth = Base64.encoder.encodeToString("${clientId}:${clientSecret}".getBytes("UTF-8"))

    def httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    def request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic ${basicAuth}")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(formParams)))
            .timeout(Duration.ofSeconds(15))
            .build()

    def response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new RuntimeException("[ping_auth] Authentication failed (HTTP ${response.statusCode()}): ${response.body()}")
    }

    def result = new JsonSlurper().parseText(response.body())
    if (!result.access_token) {
        throw new RuntimeException("[ping_auth] Authentication response did not contain an access_token: ${response.body()}")
    }

    // Share the token across every thread via properties, and expose it to
    // this thread's own samplers via JMeter variables.
    props.put(PROP_ACCESS_TOKEN, result.access_token)

    vars.put("access_token", result.access_token)
    if (result.expires_in) {
        vars.put("expires_in", result.expires_in as String)
    }

    log.info("[ping_auth] Authentication successful. access_token stored as var/prop.")
}
