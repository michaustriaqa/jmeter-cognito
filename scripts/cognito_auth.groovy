/*
 * scripts/cognito_auth.groovy
 * -----------------------------------------------------------
 * Reusable Cognito SRP login for JMeter.
 *
 * Features:
 * - Reads Cognito config (region, poolId, clientId, clientSecret, etc.)
 *   from AWS SSM Parameter Store (String type JSON).
 * - Hardcoded username/password for local testing.
 * - Performs USER_SRP_AUTH with client secret.
 * - Handles PASSWORD_VERIFIER and NEW_PASSWORD_REQUIRED challenges.
 * - Exports access_token, id_token, refresh_token to JMeter variables.
 *
 * Requirements:
 * - AWS SDK for Java v2 jars placed in JMETER_HOME/lib.
 *   (cognitoidentityprovider, ssm, auth, regions, core, jackson, etc.)
 * - Local AWS profile configured with "ssm:GetParameter" and
 *   "cognito-idp:InitiateAuth" + "cognito-idp:RespondToAuthChallenge".
 */

import java.math.BigInteger
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

// Parameter path in AWS Systems Manager Parameter Store
def PARAM_PATH = "/cognito-test/jmeter/dev"

// Hardcode user credentials (for testing)
def username = "mich.test@mailto.plus"
def password = "P@$$w0rd1234"

// =====[ HELPERS ]==============================================================

// Load config JSON from Parameter Store (String type)
def loadConfigFromSSM = { paramPath ->
    def ssm = SsmClient.builder()
            .region(Region.of("ap-southeast-1"))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build()

    def req = GetParameterRequest.builder()
            .name(paramPath)
            .build() // no decryption, since String type
    def jsonText = ssm.getParameter(req).parameter().value()
    new JsonSlurper().parseText(jsonText)
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
    mac2.update((byte)0x01)
    mac2.doFinal().copyOfRange(0,16)
}

// Simple class to hold SRP parameters
class SrpSession {
    BigInteger N
    BigInteger g
    BigInteger k
    BigInteger a
    BigInteger A

    SrpSession() {
        final N_HEX = (
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD" +
            "3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C4" +
            "E3AEE4FBEF9C5B3A278A66D2C3FDFF3DB2F6F4C52C9DE2BCBF6955817183" +
            "995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFF"
        )
        final g_hex = "2"

        this.N = new BigInteger(N_HEX, 16)
        this.g = new BigInteger(g_hex, 16)

        def md = MessageDigest.getInstance("SHA-256")
        md.update(this.N.toString(16).getBytes("UTF-8"))
        md.update(this.g.toString(16).getBytes("UTF-8"))
        this.k = new BigInteger(1, md.digest())

        def rnd = new SecureRandom()
        this.a = new BigInteger(128, rnd).abs()
        this.A = this.g.modPow(this.a, this.N)
    }
}


// =====[ MAIN AUTH FLOW ]=======================================================

// 1. Load Cognito configuration
def cfg = loadConfigFromSSM(PARAM_PATH)
def regionName = cfg.region
def userPoolId = cfg.userPoolId
def clientId = cfg.clientId
def clientSecret = cfg.clientSecret
def newPermanentPassword = cfg.newPermanentPassword ?: password

def awsRegion = Region.of(regionName)
def cognito = CognitoIdentityProviderClient.builder()
        .region(awsRegion)
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build()

def srp = new SrpSession()

// 2. Initiate USER_SRP_AUTH
def initReq = InitiateAuthRequest.builder()
        .authFlow(AuthFlowType.USER_SRP_AUTH)
        .clientId(clientId)
        .authParameters([
                "USERNAME"    : username,
                "SRP_A"       : srp.A.toString(16),
                "SECRET_HASH" : calcSecretHash(username, clientId, clientSecret)
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
    mdU.update(srp.A.toString(16).getBytes("UTF-8"))
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
    def gPowX = srp.g.modPow(x, srp.N)
    def intPart = B.subtract(srp.k.multiply(gPowX)).mod(srp.N)
    def exp = srp.a.add(u.multiply(x))
    def S = intPart.modPow(exp, srp.N)
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
                    "USERNAME"                    : userIdForSrp,
                    "PASSWORD_CLAIM_SECRET_BLOCK" : secretBlock_b64,
                    "TIMESTAMP"                   : timestamp,
                    "PASSWORD_CLAIM_SIGNATURE"    : signature,
                    "SECRET_HASH"                 : calcSecretHash(username, clientId, clientSecret)
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


// =====[ EXECUTE AUTH FLOW ]====================================================

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

// Store tokens in JMeter variables
vars.put("access_token", result.accessToken())
vars.put("id_token", result.idToken())
vars.put("refresh_token", result.refreshToken())

log.info("[cognito_auth] ✅ Authentication successful. Tokens stored as vars: access_token, id_token, refresh_token.")
