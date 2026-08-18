/*
 * scripts/auth0_auth.groovy
 * -----------------------------------------------------------
 * Reusable Auth0 login for JMeter.
 *
 * Unlike Cognito's USER_SRP_AUTH, Auth0 tokens are obtained with a plain
 * OAuth2 POST to the tenant's /oauth/token endpoint, so this script uses
 * Java's built-in HTTP client instead of an SDK.
 *
 * Features:
 * - Reads Auth0 config (domain, clientId, clientSecret, audience, etc.)
 *   from AWS SSM Parameter Store (String type JSON) - same convention as
 *   cognito_auth.groovy, for consistency with the rest of this repo.
 * - Supports both the Client Credentials grant (default; no end-user
 *   password needed, works even when a tenant has ROPC disabled) and the
 *   Resource Owner Password grant (set "grantType": "password" in config).
 * - Authenticates once and shares the resulting token across every
 *   thread/sampler for the rest of the test via JMeter properties,
 *   instead of re-authenticating on every iteration/thread.
 * - Exports access_token (and expires_in) to JMeter variables.
 *
 * Requirements:
 * - AWS SDK for Java v2 "ssm" jar (and dependencies) in JMETER_HOME/lib,
 *   same as required for cognito_auth.groovy.
 * - Local AWS profile configured with "ssm:GetParameter".
 *
 * SSM parameter JSON shape:
 * {
 *   "domain": "your-tenant.auth0.com",
 *   "clientId": "...",
 *   "clientSecret": "...",
 *   "audience": "https://your-api-identifier",   // required for client_credentials
 *   "grantType": "client_credentials",            // or "password"
 *   "username": "user@example.com",               // only used when grantType=password
 *   "password": "..."                             // only used when grantType=password
 * }
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

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest


// =====[ CONFIGURATION ]========================================================

// Parameter path in AWS Systems Manager Parameter Store (overridable via -Jauth0.paramPath=...)
def PARAM_PATH = props.get("auth0.paramPath") ?: "/auth0-test/jmeter/dev"
def SSM_REGION = props.get("auth0.ssmRegion") ?: "ap-southeast-1"

// Property keys used to share the token across threads once logged in.
final PROP_ACCESS_TOKEN = "auth0.access_token"


// =====[ FAST PATHS: SKIP RE-AUTH WHEN A TOKEN ALREADY EXISTS ]================

// 1. This thread already logged in earlier in the same script/loop.
if (vars.get("access_token")) {
    log.info("[auth0_auth] access_token already set for this thread, skipping re-auth.")
    return
}

// 2. Another thread already logged in; reuse the shared token instead of
//    hitting Auth0 again. The check-then-set below is guarded by the
//    synchronized block so only one thread ever performs the real login.
synchronized (props) {
    def cachedAccessToken = props.get(PROP_ACCESS_TOKEN)
    if (cachedAccessToken) {
        vars.put("access_token", cachedAccessToken)
        log.info("[auth0_auth] Reusing token cached by another thread.")
        return
    }

    // =====[ HELPERS ]==========================================================

    def loadConfigFromSSM = { paramPath ->
        def ssm = SsmClient.builder()
                .region(Region.of(SSM_REGION))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()
        try {
            def req = GetParameterRequest.builder()
                    .name(paramPath)
                    .build() // no decryption, since String type
            def jsonText = ssm.getParameter(req).parameter().value()
            new JsonSlurper().parseText(jsonText)
        } finally {
            ssm.close()
        }
    }

    def formEncode = { Map<String, String> params ->
        params.collect { k, v -> "${URLEncoder.encode(k, 'UTF-8')}=${URLEncoder.encode(v as String, 'UTF-8')}" }.join("&")
    }

    // =====[ MAIN AUTH FLOW ]===================================================

    def cfg = loadConfigFromSSM(PARAM_PATH)
    def domain = cfg.domain
    def clientId = cfg.clientId
    def clientSecret = cfg.clientSecret
    def audience = cfg.audience
    def grantType = cfg.grantType ?: "client_credentials"

    def formParams = [
            "grant_type"   : grantType,
            "client_id"    : clientId,
            "client_secret": clientSecret
    ]

    if (grantType == "password") {
        formParams["username"] = cfg.username
        formParams["password"] = cfg.password
        formParams["scope"] = cfg.scope ?: "openid profile"
        if (audience) {
            formParams["audience"] = audience
        }
    } else {
        formParams["audience"] = audience
    }

    def httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    def request = HttpRequest.newBuilder()
            .uri(URI.create("https://${domain}/oauth/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(formParams)))
            .timeout(Duration.ofSeconds(15))
            .build()

    def response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new RuntimeException("[auth0_auth] Authentication failed (HTTP ${response.statusCode()}): ${response.body()}")
    }

    def result = new JsonSlurper().parseText(response.body())
    if (!result.access_token) {
        throw new RuntimeException("[auth0_auth] Authentication response did not contain an access_token: ${response.body()}")
    }

    // Share the token across every thread via properties, and expose it to
    // this thread's own samplers via JMeter variables.
    props.put(PROP_ACCESS_TOKEN, result.access_token)

    vars.put("access_token", result.access_token)
    if (result.expires_in) {
        vars.put("expires_in", result.expires_in as String)
    }

    log.info("[auth0_auth] Authentication successful. access_token stored as var/prop.")
}
