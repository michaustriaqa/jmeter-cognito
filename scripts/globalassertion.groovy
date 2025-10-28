if (prev.getResponseCode() != '200') {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage("Expected 200 but got ${prev.getResponseCode()}")
}

if (!prev.getResponseDataAsString().contains("success")) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage("Response did not contain 'success'")
}