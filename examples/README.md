# Example: Cognito Auth Sample Test Plan

`cognito_auth_sample.jmx` is a minimal, runnable JMeter test plan showing how
`scripts/cognito_auth.groovy` and `scripts/globalassertion.groovy` fit
together. See `../docs/SETUP.md` first for AWS/SSM/jar prerequisites.

## Structure

- **Cognito Auth** (JSR223 Sampler, runs `scripts/cognito_auth.groovy`)
  Logs in once (shared across all threads/iterations) and stores
  `access_token`, `id_token`, `refresh_token` as JMeter variables.
  - **Token Was Issued** (JSR223 Assertion): fails the run immediately if
    no `access_token` came back, instead of letting a blank token flow
    silently into the next request.
- **Auth Header** (HTTP Header Manager)
  Adds `Authorization: Bearer ${access_token}` to requests that follow it.
- **Sample Protected Request** (HTTP Request)
  A placeholder `GET ${BASE_URL}${PROTECTED_PATH}` call. Replace with your
  real protected endpoint(s).
  - **Response Code Is 200** (Response Assertion): fails unless the HTTP
    status is exactly 200.
  - **Response Under 5s** (Duration Assertion): fails if the response takes
    longer than 5000 ms.
  - **Global Assertion** (JSR223 Assertion, runs `scripts/globalassertion.groovy`):
    fails if the token was missing, the status wasn't 2xx, the body was
    empty, or the body contains auth-failure text despite a 2xx status.
- **View Results Tree**: for inspecting requests/responses while you're
  getting things working. Remove or disable it for real load runs.

## Before running

Edit these (via `-J` properties, or the User Defined Variables element in
the test plan) to point at your real API:

- `BASE_URL` — host of the protected API (default `api.example.com`)
- `PROTECTED_PATH` — path to call (default `/protected/resource`)
- `cognito.username` / `cognito.password` — if not using the hardcoded test
  defaults in the script
- `cognito.paramPath` — if your SSM parameter isn't at
  `/cognito-test/jmeter/dev`

## Running

```bash
jmeter -n -t examples/cognito_auth_sample.jmx \
  -JBASE_URL=your-api.example.com \
  -JPROTECTED_PATH=/your/protected/path
```

Or open it in the JMeter GUI (`jmeter -t examples/cognito_auth_sample.jmx`)
for a first run with View Results Tree so you can see the actual
request/response while validating the setup.

A run only counts as passed if all four checks above hold: a token was
actually issued, the HTTP status was 200, the response arrived within 5s,
and the body doesn't contain auth-failure text — not just "the sampler
didn't throw an exception".
