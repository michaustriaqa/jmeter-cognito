# Example: Auth Sample Test Plans

Seven minimal, runnable JMeter test plans, one per supported provider, all
following the same shape. See `../docs/SETUP.md` first for prerequisites
and each provider's config format — Cognito needs AWS SDK jars and an SSM
parameter; every other provider needs neither, just `-J` properties
(below).

| Plan                          | Auth script                       |
|--------------------------------|--------------------------------------|
| `cognito_auth_sample.jmx`      | `../scripts/cognito_auth.groovy`    |
| `auth0_auth_sample.jmx`        | `../scripts/auth0_auth.groovy`      |
| `okta_auth_sample.jmx`         | `../scripts/okta_auth.groovy`       |
| `entra_id_auth_sample.jmx`     | `../scripts/entra_id_auth.groovy`   |
| `keycloak_auth_sample.jmx`     | `../scripts/keycloak_auth.groovy`   |
| `ping_auth_sample.jmx`         | `../scripts/ping_auth.groovy`       |
| `firebase_auth_sample.jmx`     | `../scripts/firebase_auth.groovy`   |

## Structure (same in all seven)

- **`<Provider> Auth`** (JSR223 Sampler, runs the provider's auth script)
  Logs in once (shared across all threads/iterations) and stores
  `access_token` (Cognito and Firebase also store `refresh_token`; Cognito
  also stores `id_token`) as JMeter variables.
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
  each provider's token for real, or just point `BASE_URL`/`PROTECTED_PATH`
  at your actual protected API.
  - **Response Code Is 200** (Response Assertion): fails unless the HTTP
    status is exactly 200.
  - **Response Under 5s** (Duration Assertion): fails if the response takes
    longer than 5000 ms.
  - **Global Assertion** (JSR223 Assertion, runs `../scripts/globalassertion.groovy`):
    fails if the token was missing, the status wasn't 2xx, the body was
    empty, or the body contains auth-failure text despite a 2xx status.
    This assertion is provider-agnostic, so it's identical across all
    seven plans.
- **View Results Tree**: for inspecting requests/responses while you're
  getting things working. Remove or disable it for real load runs.

## Before running

Edit these (via `-J` properties, or the User Defined Variables element in
the test plan) to point at your real API:

- `BASE_URL` / `PROTECTED_PATH` — host/path of your protected API (default
  `httpbin.org` / `/bearer`)
- **Cognito**: optional overrides `cognito.paramPath` (SSM path),
  `cognito.username`, `cognito.password` — see `../docs/SETUP.md` §4
- **Auth0**: `auth0.domain`, `auth0.clientId`, `auth0.clientSecret`,
  `auth0.audience` are **required**
- **Okta**: `okta.domain`, `okta.clientId`, `okta.clientSecret` are
  **required**
- **Entra ID**: `entraid.tenantId`, `entraid.clientId`,
  `entraid.clientSecret`, `entraid.scope` are **required**
- **Keycloak**: `keycloak.baseUrl`, `keycloak.realm`, `keycloak.clientId`
  are **required** (`keycloak.clientSecret` too, unless the client is public)
- **Ping**: `ping.tokenUrl`, `ping.clientId`, `ping.clientSecret` are
  **required**
- **Firebase**: `firebase.apiKey`, `firebase.email`, `firebase.password`
  are **required**

All of the above throw a clear "Missing required JMeter property" error at
script start if omitted — see `../docs/SETUP.md` §4 for the full property
list including optional overrides (`grantType`, `scope`, etc.).

## Running

```bash
jmeter -n -t examples/cognito_auth_sample.jmx \
  -JBASE_URL=your-api.example.com \
  -JPROTECTED_PATH=/your/protected/path

jmeter -n -t examples/auth0_auth_sample.jmx \
  -Jauth0.domain=your-tenant.auth0.com \
  -Jauth0.clientId=xxxxxxxxxxxx \
  -Jauth0.clientSecret=xxxxxxxxxxxx \
  -Jauth0.audience=https://your-api-identifier

jmeter -n -t examples/okta_auth_sample.jmx \
  -Jokta.domain=your-org.okta.com \
  -Jokta.clientId=xxxxxxxxxxxx \
  -Jokta.clientSecret=xxxxxxxxxxxx

jmeter -n -t examples/entra_id_auth_sample.jmx \
  -Jentraid.tenantId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx \
  -Jentraid.clientId=xxxxxxxxxxxx \
  -Jentraid.clientSecret=xxxxxxxxxxxx \
  -Jentraid.scope=https://graph.microsoft.com/.default

jmeter -n -t examples/keycloak_auth_sample.jmx \
  -Jkeycloak.baseUrl=https://keycloak.example.com \
  -Jkeycloak.realm=your-realm \
  -Jkeycloak.clientId=xxxxxxxxxxxx \
  -Jkeycloak.clientSecret=xxxxxxxxxxxx

jmeter -n -t examples/ping_auth_sample.jmx \
  -Jping.tokenUrl=https://auth.pingone.com/your-env-id/as/token \
  -Jping.clientId=xxxxxxxxxxxx \
  -Jping.clientSecret=xxxxxxxxxxxx

jmeter -n -t examples/firebase_auth_sample.jmx \
  -Jfirebase.apiKey=xxxxxxxxxxxx \
  -Jfirebase.email=someuser@example.com \
  -Jfirebase.password='YourPassword123!'
```

Or open one in the JMeter GUI (`jmeter -t examples/<plan>.jmx`) for a first
run with View Results Tree so you can see the actual request/response
while validating the setup.

A run only counts as passed if all four checks above hold: a token was
actually issued, the HTTP status was 200, the response arrived within 5s,
and the body doesn't contain auth-failure text — not just "the sampler
didn't throw an exception".
