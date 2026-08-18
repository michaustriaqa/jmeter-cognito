/*
 * scripts/cognito_auth.groovy
 * -----------------------------------------------------------
 * Reusable Cognito SRP login for JMeter.
 *
 * Features:
 * - Reads Cognito config (region, poolId, clientId, clientSecret, etc.)
 *   from AWS SSM Parameter Store (String type JSON).
 * - Hardcoded username/password by default; overridable via JMeter
 *   properties (cognito.username, cognito.password, cognito.paramPath).
 * - Performs USER_SRP_AUTH with client secret.
 * - Handles PASSWORD_VERIFIER and NEW_PASSWORD_REQUIRED challenges.
 * - Authenticates once and shares the resulting tokens across every
 *   thread/sampler for the rest of the test via JMeter properties,
 *   instead of re-authenticating on every iteration/thread.
 * - Exports access_token, id_token, refresh_token to JMeter variables.
 *
 * Requirements:
 * - AWS SDK for Java v2 jars placed in JMETER_HOME/lib.
 *   (cognitoidentityprovider, ssm, auth, regions, core, jackson, etc.)
 * - Local AWS profile configured with "ssm:GetParameter" and
 *   "cognito-idp:InitiateAuth" + "cognito-idp:RespondToAuthChallenge".
 *
 * Placement:
 * - Add as a JSR223 Sampler/PreProcessor that runs once per thread
 *   (e.g. the first sampler in the Thread Group, outside any loop).
 *   The first thread to run it performs the real login; every other
 *   thread (and every later iteration) reuses the cached token.
 */

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

import groovy.json.JsonSlurper

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.*


// =====[ CONFIGURATION ]========================================================

// Parameter path in AWS Systems Manager Parameter Store (overridable via -Jcognito.paramPath=...)
def PARAM_PATH = props.get("cognito.paramPath") ?: "/cognito-test/jmeter/dev"

// Hardcoded default user credentials (for local testing).
// Override via -Jcognito.username=... / -Jcognito.password=... for real runs.
def username = props.get("cognito.username") ?: "mich.test@mailto.plus"
def password = props.get("cognito.password") ?: 'P@$$w0rd1234'

// Property keys used to share the token across threads once logged in.
final PROP_ACCESS_TOKEN = "cognito.access_token"
final PROP_ID_TOKEN = "cognito.id_token"
final PROP_REFRESH_TOKEN = "cognito.refresh_token"


// =====[ FAST PATHS: SKIP RE-AUTH WHEN A TOKEN ALREADY EXISTS ]================

// 1. This thread already logged in earlier in the same script/loop.
if (vars.get("access_token")) {
    log.info("[cognito_auth] access_token already set for this thread, skipping re-auth.")
    return
}

// 2. Another thread already logged in; reuse the shared token instead of
//    hitting SSM/Cognito again. The check-then-set below is guarded by the
//    synchronized block so only one thread ever performs the real login.
synchronized (props) {
    def cachedAccessToken = props.get(PROP_ACCESS_TOKEN)
    if (cachedAccessToken) {
        vars.put("access_token", cachedAccessToken)
        vars.put("id_token", props.get(PROP_ID_TOKEN))
        vars.put("refresh_token", props.get(PROP_REFRESH_TOKEN))
        log.info("[cognito_auth] Reusing token cached by another thread.")
        return
    }

    // =====[ HELPERS ]==========================================================

    // Load config JSON from Parameter Store (String type)
    def loadConfigFromSSM = { paramPath ->
        def ssm = SsmClient.builder()
                .region(Region.of("ap-southeast-1"))
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

    // Calculate Cognito SECRET_HASH = Base64(HMAC_SHA256(clientSecret, username + clientId))
    def calcSecretHash = { uname, cid, csecret ->
        def mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(csecret.getBytes("UTF-8"), "HmacSHA256"))
        def raw = mac.doFinal((uname + cid).getBytes("UTF-8"))
        Base64.encoder.encodeToString(raw)
    }

    // HKDF key derivation (used in SRP flow)
    def computeHKDF = { ikmBytes, saltBytes ->
        def mac1 = Mac.getInstance("HmacSHA256")
        mac1.init(new SecretKeySpec(saltBytes, "HmacSHA256"))
        def prk = mac1.doFinal(ikmBytes)

        def mac2 = Mac.getInstance("HmacSHA256")
        mac2.init(new SecretKeySpec(prk, "HmacSHA256"))
        def infoBits = "Caldera Derived Key".getBytes("UTF-8")
        mac2.update(infoBits)
        mac2.update((byte) 0x01)
        mac2.doFinal().copyOfRange(0, 16)
    }

    // =====[ MAIN AUTH FLOW ]===================================================

    // 1. Load Cognito configuration
    def cfg = loadConfigFromSSM(PARAM_PATH)
    def regionName = cfg.region
    def userPoolId = cfg.userPoolId
    def clientId = cfg.clientId
    def clientSecret = cfg.clientSecret
    def newPermanentPassword = cfg.newPermanentPassword ?: password

    def N = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD" +
            "3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C4" +
            "E3AEE4FBEF9C5B3A278A66D2C3FDFF3DB2F6F4C52C9DE2BCBF6955817183" +
            "995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFF",
            16
    )
    def g = new BigInteger("2", 16)

    def mdK = MessageDigest.getInstance("SHA-256")
    mdK.update(N.toString(16).getBytes("UTF-8"))
    mdK.update(g.toString(16).getBytes("UTF-8"))
    def k = new BigInteger(1, mdK.digest())

    def rnd = new SecureRandom()
    def a = new BigInteger(128, rnd).abs()
    def A = g.modPow(a, N)

    def awsRegion = Region.of(regionName)
    def cognito = CognitoIdentityProviderClient.builder()
            .region(awsRegion)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build()

    try {
        // 2. Initiate USER_SRP_AUTH
        def initReq = InitiateAuthRequest.builder()
                .authFlow(AuthFlowType.USER_SRP_AUTH)
                .clientId(clientId)
                .authParameters([
                        "USERNAME"   : username,
                        "SRP_A"      : A.toString(16),
                        "SECRET_HASH": calcSecretHash(username, clientId, clientSecret)
                ])
                .build()

        def initResp = cognito.initiateAuth(initReq)

        // 3. PASSWORD_VERIFIER step
        def respondPasswordVerifier = { resp ->
            def chal = resp.challengeParameters()
            def B_hex = chal.get("SRP_B")
            def salt_hex = chal.get("SALT")
            def secretBlock_b64 = chal.get("SECRET_BLOCK")
            def userIdForSrp = chal.get("USER_ID_FOR_SRP")

            def B = new BigInteger(B_hex, 16)
            def saltBytes = new BigInteger(salt_hex, 16).toByteArray()
            def secretBlock = Base64.decoder.decode(secretBlock_b64)

            // Compute u = SHA256(A|B)
            def mdU = MessageDigest.getInstance("SHA-256")
            mdU.update(A.toString(16).getBytes("UTF-8"))
            mdU.update(B.toString(16).getBytes("UTF-8"))
            def u = new BigInteger(1, mdU.digest())

            // Compute x = SHA256(salt | SHA256(poolSuffix + username + ":" + password))
            def poolSuffix = userPoolId.split("_", 2)[1]
            def mdUser = MessageDigest.getInstance("SHA-256")
            mdUser.update((poolSuffix + username + ":" + password).getBytes("UTF-8"))
            def userHash = mdUser.digest()

            def mdX = MessageDigest.getInstance("SHA-256")
            mdX.update(saltBytes)
            mdX.update(userHash)
            def x = new BigInteger(1, mdX.digest())

            // S = (B - k*g^x)^(a + u*x) mod N
            def gPowX = g.modPow(x, N)
            def intPart = B.subtract(k.multiply(gPowX)).mod(N)
            def exp = a.add(u.multiply(x))
            def S = intPart.modPow(exp, N)
            def hkdfKey = computeHKDF(S.toByteArray(), u.toByteArray())

            // Timestamp
            def timestamp = DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(ZonedDateTime.now(ZoneOffset.UTC))

            // Signature
            def macSig = Mac.getInstance("HmacSHA256")
            macSig.init(new SecretKeySpec(hkdfKey, "HmacSHA256"))
            macSig.update(poolSuffix.getBytes("UTF-8"))
            macSig.update(username.getBytes("UTF-8"))
            macSig.update(secretBlock)
            macSig.update(timestamp.getBytes("UTF-8"))
            def signature = Base64.encoder.encodeToString(macSig.doFinal())

            def verifyReq = RespondToAuthChallengeRequest.builder()
                    .challengeName(ChallengeNameType.PASSWORD_VERIFIER)
                    .clientId(clientId)
                    .challengeResponses([
                            "USERNAME"                   : userIdForSrp,
                            "PASSWORD_CLAIM_SECRET_BLOCK": secretBlock_b64,
                            "TIMESTAMP"                  : timestamp,
                            "PASSWORD_CLAIM_SIGNATURE"   : signature,
                            "SECRET_HASH"                : calcSecretHash(username, clientId, clientSecret)
                    ])
                    .session(resp.session())
                    .build()

            cognito.respondToAuthChallenge(verifyReq)
        }

        // 4. NEW_PASSWORD_REQUIRED step
        def respondNewPassword = { resp ->
            def req = RespondToAuthChallengeRequest.builder()
                    .challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED)
                    .clientId(clientId)
                    .challengeResponses([
                            "USERNAME"    : username,
                            "NEW_PASSWORD": newPermanentPassword,
                            "SECRET_HASH" : calcSecretHash(username, clientId, clientSecret)
                    ])
                    .session(resp.session())
                    .build()

            cognito.respondToAuthChallenge(req)
        }

        // =====[ EXECUTE AUTH FLOW ]============================================

        def current = initResp

        if ("PASSWORD_VERIFIER".equalsIgnoreCase(current.challengeNameAsString())) {
            current = respondPasswordVerifier(current)
        }

        if ("NEW_PASSWORD_REQUIRED".equalsIgnoreCase(current.challengeNameAsString())) {
            current = respondNewPassword(current)
        }

        def result = current.authenticationResult()
        if (result == null) {
            throw new RuntimeException("[cognito_auth] Authentication failed. Check credentials or CIDR policy.")
        }

        // Share tokens across every thread via properties, and expose them to
        // this thread's own samplers via JMeter variables.
        props.put(PROP_ACCESS_TOKEN, result.accessToken())
        props.put(PROP_ID_TOKEN, result.idToken())
        props.put(PROP_REFRESH_TOKEN, result.refreshToken())

        vars.put("access_token", result.accessToken())
        vars.put("id_token", result.idToken())
        vars.put("refresh_token", result.refreshToken())

        log.info("[cognito_auth] Authentication successful. Tokens stored as vars/props: access_token, id_token, refresh_token.")
    } finally {
        cognito.close()
    }
}
