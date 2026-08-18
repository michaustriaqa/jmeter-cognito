# Example: Auth Sample Test Plans

Three minimal, runnable JMeter test plans, one per supported provider, all
following the same shape. See `../docs/SETUP.md` first for AWS/SSM/jar
prerequisites and each provider's config format.

| Plan                          | Auth script                    |
|--------------------------------|----------------------------------|
| `cognito_auth_sample.jmx`      | `../scripts/cognito_auth.groovy` |
| `auth0_auth_sample.jmx`        | `../scripts/auth0_auth.groovy`   |
| `okta_auth_sample.jmx`         | `../scripts/okta_auth.groovy`    |

## Structure (same in all three)

- **`<Provider> Auth`** (JSR223 Sampler, runs the provider's auth script)
  Logs in once (shared across all threads/iterations) and stores
  `access_token` (Cognito also stores `id_token` / `refresh_token`) as
  JMeter variables.
  - **Token Was Issued** (JSR223 Assertion): fails the run immediately if
    no `access_token` came back, instead of letting a blank token flow
    silently into the next request.
- **Auth Header** (HTTP Header Manager)
  Adds `Authorization: Bearer ${access_token}` to requests that follow it.
- **Sample Protected Request** (HTTP Request)
  Defaults to `GET https://httpbin.org/bearer` — a public endpoint that
  echoes back whatever `Authorization` header it receives. This makes the
  plan runnable immediately with no backend of your own, but it only
  proves the JMeter wiring works (header injection, assertions firing) —
  it does **not** confirm the token is a genuinely valid token your
  provider would accept. See `../docs/SETUP.md` §7 for how to validate
  each provider's token for real (Cognito `GetUser`, Auth0/Okta
  `/userinfo`), or just point `BASE_URL`/`PROTECTED_PATH` at your actual
  protected API.
  - **Response Code Is 200** (Response Assertion): fails unless the HTTP
    status is exactly 200.
  - **Response Under 5s** (Duration Assertion): fails if the response takes
    longer than 5000 ms.
  - **Global Assertion** (JSR223 Assertion, runs `../scripts/globalassertion.groovy`):
    fails if the token was missing, the status wasn't 2xx, the body was
    empty, or the body contains auth-failure text despite a 2xx status.
    This assertion is provider-agnostic, so it's identical across all
    three plans.
- **View Results Tree**: for inspecting requests/responses while you're
  getting things working. Remove or disable it for real load runs.

## Before running

Edit these (via `-J` properties, or the User Defined Variables element in
the test plan) to point at your real API:

- `BASE_URL` / `PROTECTED_PATH` — host/path of your protected API (default
  `httpbin.org` / `/bearer`)
- Provider-specific SSM path/region overrides (`cognito.paramPath`,
  `auth0.paramPath`, `okta.paramPath`, etc.) — see `../docs/SETUP.md` §4
- `cognito.username` / `cognito.password` — only for the Cognito plan, if
  not using the hardcoded test defaults in the script

## Running

```bash
jmeter -n -t examples/cognito_auth_sample.jmx \
  -JBASE_URL=your-api.example.com \
  -JPROTECTED_PATH=/your/protected/path

jmeter -n -t examples/auth0_auth_sample.jmx
jmeter -n -t examples/okta_auth_sample.jmx
```

Or open one in the JMeter GUI (`jmeter -t examples/<plan>.jmx`) for a first
run with View Results Tree so you can see the actual request/response
while validating the setup.

A run only counts as passed if all four checks above hold: a token was
actually issued, the HTTP status was 200, the response arrived within 5s,
and the body doesn't contain auth-failure text — not just "the sampler
didn't throw an exception".
