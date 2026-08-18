/*
 * scripts/keycloak_auth.groovy
 * -----------------------------------------------------------
 * Reusable Keycloak login for JMeter.
 *
 * Tokens are obtained with a plain OAuth2 POST to the realm's token
 * endpoint, so this script uses Java's built-in HTTP client and has no
 * AWS/SDK dependency at all.
 *
 * Features:
 * - Reads Keycloak config (baseUrl, realm, clientId, clientSecret, etc.)
 *   entirely from JMeter properties (-J...) - nothing hardcoded, no
 *   external secret store required.
 * - Supports both the Client Credentials grant (default; no end-user
 *   password needed, requires the client to be configured as
 *   confidential with "Service accounts enabled") and the Resource
 *   Owner Password grant (set keycloak.grantType=password - the
 *   "Direct Access Grants" flag must be enabled on the client).
 * - Authenticates once and shares the resulting token across every
 *   thread/sampler for the rest of the test via JMeter properties,
 *   instead of re-authenticating on every iteration/thread.
 * - Exports access_token (and expires_in) to JMeter variables.
 *
 * Required JMeter properties (pass via -J on the command line, or set in
 * user.properties):
 *   keycloak.baseUrl     e.g. https://keycloak.example.com (no trailing slash)
 *   keycloak.realm       Realm name
 *   keycloak.clientId
 *   keycloak.clientSecret  required unless the client is public (no secret)
 *
 * Optional:
 *   keycloak.grantType   "client_credentials" (default) or "password"
 *   keycloak.scope       e.g. "openid" - only sent if set
 *   keycloak.username    required when grantType=password
 *   keycloak.password    required when grantType=password
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

import groovy.json.JsonSlurper


// =====[ FAST PATHS: SKIP RE-AUTH WHEN A TOKEN ALREADY EXISTS ]================

// 1. This thread already logged in earlier in the same script/loop.
if (vars.get("access_token")) {
    log.info("[keycloak_auth] access_token already set for this thread, skipping re-auth.")
    return
}

// Property keys used to share the token across threads once logged in.
final PROP_ACCESS_TOKEN = "keycloak.access_token"

// 2. Another thread already logged in; reuse the shared token instead of
//    hitting Keycloak again. The check-then-set below is guarded by the
//    synchronized block so only one thread ever performs the real login.
synchronized (props) {
    def cachedAccessToken = props.get(PROP_ACCESS_TOKEN)
    if (cachedAccessToken) {
        vars.put("access_token", cachedAccessToken)
        log.info("[keycloak_auth] Reusing token cached by another thread.")
        return
    }

    // =====[ CONFIGURATION ]====================================================

    def requireProp = { name ->
        def value = props.get(name)
        if (!value || value.trim().isEmpty()) {
            throw new RuntimeException("[keycloak_auth] Missing required JMeter property: -J${name}=...")
        }
        value
    }

    def baseUrl = requireProp("keycloak.baseUrl")
    def realm = requireProp("keycloak.realm")
    def clientId = requireProp("keycloak.clientId")
    def clientSecret = props.get("keycloak.clientSecret")
    def scope = props.get("keycloak.scope")
    def grantType = props.get("keycloak.grantType") ?: "client_credentials"

    // =====[ HELPERS ]==========================================================

    def formEncode = { Map<String, String> params ->
        params.collect { k, v -> "${URLEncoder.encode(k, 'UTF-8')}=${URLEncoder.encode(v as String, 'UTF-8')}" }.join("&")
    }

    // =====[ MAIN AUTH FLOW ]===================================================

    def formParams = ["grant_type": grantType, "client_id": clientId]
    if (clientSecret) {
        formParams["client_secret"] = clientSecret
    }
    if (scope) {
        formParams["scope"] = scope
    }

    if (grantType == "password") {
        formParams["username"] = requireProp("keycloak.username")
        formParams["password"] = requireProp("keycloak.password")
    }

    def httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    def tokenUrl = "${baseUrl}/realms/${realm}/protocol/openid-connect/token"

    def request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(formParams)))
            .timeout(Duration.ofSeconds(15))
            .build()

    def response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new RuntimeException("[keycloak_auth] Authentication failed (HTTP ${response.statusCode()}): ${response.body()}")
    }

    def result = new JsonSlurper().parseText(response.body())
    if (!result.access_token) {
        throw new RuntimeException("[keycloak_auth] Authentication response did not contain an access_token: ${response.body()}")
    }

    // Share the token across every thread via properties, and expose it to
    // this thread's own samplers via JMeter variables.
    props.put(PROP_ACCESS_TOKEN, result.access_token)

    vars.put("access_token", result.access_token)
    if (result.expires_in) {
        vars.put("expires_in", result.expires_in as String)
    }

    log.info("[keycloak_auth] Authentication successful. access_token stored as var/prop.")
}
