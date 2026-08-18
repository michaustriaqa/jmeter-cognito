/*
 * scripts/globalassertion.groovy
 * -----------------------------------------------------------
 * Response assertion used after protected API calls to confirm the
 * request actually succeeded end-to-end, rather than just "didn't throw".
 *
 * Checks, in order:
 * 1. The Cognito login actually produced a token for this thread
 *    (catches the case where cognito_auth.groovy silently failed or was
 *    skipped, and the request went out unauthenticated).
 * 2. The HTTP response code is in the 2xx range.
 * 3. The response body isn't empty.
 * 4. The response body doesn't contain common auth-failure markers,
 *    which some APIs return with a 200 status instead of 401/403.
 *
 * Attach this as a JSR223 Assertion on samplers that should only be
 * considered passed when the Cognito-authenticated call truly succeeded.
 */

def accessToken = vars.get("access_token")
if (!accessToken || accessToken.trim().isEmpty()) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage("No access_token found for this thread - cognito_auth.groovy did not run or failed before this request.")
    return
}

def responseCode = prev.getResponseCode()
if (!responseCode || !responseCode.matches("2\\d\\d")) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage("Expected a 2xx response but got ${responseCode}")
    return
}

def body = prev.getResponseDataAsString()
if (!body || body.trim().isEmpty()) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage("Response body was empty")
    return
}

def failureMarkers = ["unauthorized", "forbidden", "invalid_token", "invalid token", "missing authentication token"]
def lowerBody = body.toLowerCase()
def matchedMarker = failureMarkers.find { lowerBody.contains(it) }
if (matchedMarker) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage("Response body contained auth-failure marker '${matchedMarker}' despite a ${responseCode} status: ${body}")
}
