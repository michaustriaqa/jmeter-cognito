# Setup Guide: Auth Scripts for JMeter

This describes how the scripts in `scripts/` are actually used in this repo:
as JSR223 script files referenced from a JMeter test plan, not as a compiled
JMeter plugin. Three providers are supported, each with its own script but
the same shape:

| Provider | Script                       | Sample plan                              |
|----------|-------------------------------|-------------------------------------------|
| Cognito  | `scripts/cognito_auth.groovy` | `examples/cognito_auth_sample.jmx`        |
| Auth0    | `scripts/auth0_auth.groovy`   | `examples/auth0_auth_sample.jmx`          |
| Okta     | `scripts/okta_auth.groovy`    | `examples/okta_auth_sample.jmx`           |

All three log in once per test run (shared across threads/iterations — see
[§5](#5-how-the-login-is-shared-across-the-test)) and export `access_token`
as a JMeter variable, so `scripts/globalassertion.groovy` and the
`Authorization: Bearer ${access_token}` header pattern work the same way
regardless of provider.

## 1. Prerequisites

- Apache JMeter 5.6+
- Java 11+
- AWS credentials available to the machine running JMeter (environment
  variables, `~/.aws/credentials`, or an instance/role profile) with
  `ssm:GetParameter` on the parameter path used below — **all three
  scripts** read their provider config from AWS SSM Parameter Store, even
  Auth0/Okta, for consistency with the rest of this repo.
- For Cognito specifically, also `cognito-idp:InitiateAuth` and
  `cognito-idp:RespondToAuthChallenge`.

## 2. AWS SDK v2 jars

Every script uses the AWS SDK for Java v2 `ssm` client to load its config.
`cognito_auth.groovy` additionally needs the Cognito Identity Provider
client for the SRP handshake; `auth0_auth.groovy` and `okta_auth.groovy`
only need `ssm` since the actual login is a plain HTTP call made with
Java's built-in `java.net.http.HttpClient` (no extra jar for that part).

Download the following jars (and their transitive dependencies) and place
them in `JMETER_HOME/lib`, then restart JMeter:

- `ssm` (required by all three scripts)
- `cognitoidentityprovider` (only needed for `cognito_auth.groovy`)
- `auth`
- `regions`
- `sdk-core`
- `http-client-spi`
- `apache-client` (or another SDK HTTP client)
- `jackson-databind`, `jackson-core`, `jackson-annotations`

The simplest way to get a consistent set is to build a small Maven/Gradle
project with these as dependencies and copy the resolved jars (including
transitive ones) into `JMETER_HOME/lib`.

## 3. SSM Parameter Store values

Each script reads a single String parameter (JSON) from its own default
path, overridable via a JMeter property — see [§4](#4-configuration-properties).

### Cognito — default path `/cognito-test/jmeter/dev`

```json
{
  "region": "ap-southeast-1",
  "userPoolId": "ap-southeast-1_xxxxxxxxx",
  "clientId": "xxxxxxxxxxxxxxxxxxxxxxxxxx",
  "clientSecret": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "newPermanentPassword": "OptionalNewPassword1!"
}
```

`newPermanentPassword` is only used if Cognito responds with a
`NEW_PASSWORD_REQUIRED` challenge; otherwise it's ignored.

### Auth0 — default path `/auth0-test/jmeter/dev`

```json
{
  "domain": "your-tenant.auth0.com",
  "clientId": "...",
  "clientSecret": "...",
  "audience": "https://your-api-identifier",
  "grantType": "client_credentials"
}
```

`grantType` defaults to `client_credentials` (machine-to-machine, no user
password — works even when a tenant has the password grant disabled). Set
it to `"password"` and add `"username"` / `"password"` fields to use the
Resource Owner Password grant instead.

### Okta — default path `/okta-test/jmeter/dev`

```json
{
  "domain": "your-org.okta.com",
  "authServerId": "default",
  "clientId": "...",
  "clientSecret": "...",
  "scope": "api://default",
  "grantType": "client_credentials"
}
```

Same `grantType` behavior as Auth0. Okta authenticates the client via HTTP
Basic auth (`client_secret_basic`), which the script handles for you.

## 4. Configuration properties

Instead of editing the scripts, override these via JMeter `-J` properties:

| Provider | Property           | Purpose                                    |
|----------|---------------------|---------------------------------------------|
| Cognito  | `cognito.paramPath` | SSM parameter path (default `/cognito-test/jmeter/dev`) |
| Cognito  | `cognito.username`  | Username to authenticate as                 |
| Cognito  | `cognito.password`  | Password for that username                  |
| Auth0    | `auth0.paramPath`   | SSM parameter path (default `/auth0-test/jmeter/dev`) |
| Auth0    | `auth0.ssmRegion`   | AWS region for the SSM call (default `ap-southeast-1`) |
| Okta     | `okta.paramPath`    | SSM parameter path (default `/okta-test/jmeter/dev`) |
| Okta     | `okta.ssmRegion`    | AWS region for the SSM call (default `ap-southeast-1`) |

Example:

```bash
jmeter -n -t examples/cognito_auth_sample.jmx \
  -Jcognito.username=someuser@example.com \
  -Jcognito.password='YourPassword123!' \
  -JBASE_URL=your-api.example.com \
  -JPROTECTED_PATH=/your/protected/path
```

## 5. How the login is shared across the test

Every script is meant to run once per test, not once per request:

- It first checks the current thread's JMeter variables (`vars`) — if a
  token is already there, it returns immediately.
- Otherwise it checks a shared JMeter property (`props`, JVM-wide) under a
  `synchronized` block. The first thread to reach this authenticates for
  real; every other thread (and every later loop iteration) reuses the
  cached token instead of re-authenticating.

Place the JSR223 Sampler running the script as the **first sampler in the
Thread Group, outside of any loop** so it only runs once per thread and the
shared cache short-circuits the rest.

## 6. Assertions

`scripts/globalassertion.groovy` is provider-agnostic — it only looks at
the generic `access_token` variable and the HTTP response, so the same
assertion works after `cognito_auth.groovy`, `auth0_auth.groovy`, or
`okta_auth.groovy`. For the sampler it's attached to, it checks:

1. `access_token` exists for the current thread (the login actually
   happened before this request ran).
2. The HTTP response code is 2xx.
3. The response body is non-empty.
4. The response body doesn't contain common auth-failure text
   (`unauthorized`, `forbidden`, `invalid_token`, ...) that some APIs
   return with a misleading 200 status.

## 7. Validating a token for real (not just plumbing)

The sample plans default to `httpbin.org/bearer`, a public endpoint that
just echoes back whatever `Authorization` header it receives — good for
proving the JMeter wiring works, but it doesn't check that the token is a
token *your* provider actually issued and would accept. To validate for
real:

- **Cognito**: call the `GetUser` API — `POST` to
  `https://cognito-idp.<region>.amazonaws.com/` with header
  `X-Amz-Target: AWSCognitoIdentityProviderService.GetUser` and body
  `{"AccessToken": "${access_token}"}`.
- **Auth0**: `GET https://<domain>/userinfo` with the Bearer header (note:
  only returns identity claims for tokens issued via a user-based grant
  like `password`, not pure `client_credentials` tokens with no subject).
- **Okta**: `GET https://<domain>/oauth2/<authServerId>/v1/userinfo` with
  the Bearer header (same caveat as Auth0 — needs a token with a subject).

See `examples/README.md` for how everything above is wired together in the
sample test plans.
