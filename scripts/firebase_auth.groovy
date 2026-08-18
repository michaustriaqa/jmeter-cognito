/*
 * scripts/firebase_auth.groovy
 * -----------------------------------------------------------
 * Reusable Google Identity Platform / Firebase Authentication login for
 * JMeter.
 *
 * Unlike the other providers in this repo, Firebase/Identity Platform has
 * no client-credentials (machine-to-machine) grant for regular apps -
 * only user sign-in. This script calls the Identity Toolkit REST API's
 * signInWithPassword endpoint directly (the same call the Firebase Auth
 * SDKs make under the hood), using Java's built-in HTTP client - no
 * AWS/SDK dependency at all.
 *
 * Features:
 * - Reads config (apiKey, email, password) entirely from JMeter
 *   properties (-J...) - nothing hardcoded, no external secret store
 *   required.
 * - Authenticates once and shares the resulting token across every
 *   thread/sampler for the rest of the test via JMeter properties,
 *   instead of re-authenticating on every iteration/thread.
 * - Exports the ID token as access_token (matching this repo's
 *   Authorization: Bearer ${access_token} convention - Firebase-secured
 *   APIs expect the idToken, not a separate OAuth access_token), plus
 *   refresh_token and expires_in as JMeter variables.
 *
 * Required JMeter properties (pass via -J on the command line, or set in
 * user.properties):
 *   firebase.apiKey      Web API key for the Firebase project / Identity
 *                          Platform tenant (Project settings > General)
 *   firebase.email        Test user's email
 *   firebase.password     Test user's password
 *
 * Optional:
 *   firebase.tenantId     Identity Platform multi-tenancy tenant ID, if used
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
import java.time.Duration

import groovy.json.JsonSlurper
import groovy.json.JsonOutput


// =====[ FAST PATHS: SKIP RE-AUTH WHEN A TOKEN ALREADY EXISTS ]================

// 1. This thread already logged in earlier in the same script/loop.
if (vars.get("access_token")) {
    log.info("[firebase_auth] access_token already set for this thread, skipping re-auth.")
    return
}

// Property keys used to share the token across threads once logged in.
final PROP_ACCESS_TOKEN = "firebase.access_token"
final PROP_REFRESH_TOKEN = "firebase.refresh_token"

// 2. Another thread already logged in; reuse the shared token instead of
//    hitting Firebase again. The check-then-set below is guarded by the
//    synchronized block so only one thread ever performs the real login.
synchronized (props) {
    def cachedAccessToken = props.get(PROP_ACCESS_TOKEN)
    if (cachedAccessToken) {
        vars.put("access_token", cachedAccessToken)
        vars.put("refresh_token", props.get(PROP_REFRESH_TOKEN))
        log.info("[firebase_auth] Reusing token cached by another thread.")
        return
    }

    // =====[ CONFIGURATION ]====================================================

    def requireProp = { name ->
        def value = props.get(name)
        if (!value || value.trim().isEmpty()) {
            throw new RuntimeException("[firebase_auth] Missing required JMeter property: -J${name}=...")
        }
        value
    }

    def apiKey = requireProp("firebase.apiKey")
    def email = requireProp("firebase.email")
    def password = requireProp("firebase.password")
    def tenantId = props.get("firebase.tenantId")

    // =====[ MAIN AUTH FLOW ]===================================================

    def requestBody = [
            email            : email,
            password         : password,
            returnSecureToken: true
    ]
    if (tenantId) {
        requestBody["tenantId"] = tenantId
    }

    def httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    def request = HttpRequest.newBuilder()
            .uri(URI.create("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(requestBody)))
            .timeout(Duration.ofSeconds(15))
            .build()

    def response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new RuntimeException("[firebase_auth] Authentication failed (HTTP ${response.statusCode()}): ${response.body()}")
    }

    def result = new JsonSlurper().parseText(response.body())
    if (!result.idToken) {
        throw new RuntimeException("[firebase_auth] Authentication response did not contain an idToken: ${response.body()}")
    }

    // Share the token across every thread via properties, and expose it to
    // this thread's own samplers via JMeter variables. Firebase calls its
    // bearer token "idToken", but it's exported as access_token to match
    // this repo's Authorization: Bearer ${access_token} convention.
    props.put(PROP_ACCESS_TOKEN, result.idToken)
    if (result.refreshToken) {
        props.put(PROP_REFRESH_TOKEN, result.refreshToken)
    }

    vars.put("access_token", result.idToken)
    if (result.refreshToken) {
        vars.put("refresh_token", result.refreshToken)
    }
    if (result.expiresIn) {
        vars.put("expires_in", result.expiresIn as String)
    }

    log.info("[firebase_auth] Authentication successful. access_token stored as var/prop.")
}
