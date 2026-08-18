# Setup Guide: Auth Scripts for JMeter

This describes how the scripts in `scripts/` are actually used in this repo:
as JSR223 script files referenced from a JMeter test plan, not as a compiled
JMeter plugin. Seven providers are supported, each with its own script but
the same shape:

| Provider              | Script                          | Sample plan                              |
|------------------------|-----------------------------------|--------------------------------------------|
| Cognito                | `scripts/cognito_auth.groovy`   | `examples/cognito_auth_sample.jmx`        |
| Auth0                   | `scripts/auth0_auth.groovy`     | `examples/auth0_auth_sample.jmx`          |
| Okta                    | `scripts/okta_auth.groovy`      | `examples/okta_auth_sample.jmx`           |
| Microsoft Entra ID      | `scripts/entra_id_auth.groovy`  | `examples/entra_id_auth_sample.jmx`       |
| Keycloak                | `scripts/keycloak_auth.groovy`  | `examples/keycloak_auth_sample.jmx`       |
| PingFederate / PingOne  | `scripts/ping_auth.groovy`      | `examples/ping_auth_sample.jmx`           |
| Google Identity Platform / Firebase Auth | `scripts/firebase_auth.groovy` | `examples/firebase_auth_sample.jmx` |

All seven log in once per test run (shared across threads/iterations — see
[§5](#5-how-the-login-is-shared-across-the-test)) and export `access_token`
as a JMeter variable, so `scripts/globalassertion.groovy` and the
`Authorization: Bearer ${access_token}` header pattern work the same way
regardless of provider.

## 1. Prerequisites

- Apache JMeter 5.6+
- Java 11+
- **Cognito only**: AWS credentials available to the machine running JMeter
  (environment variables, `~/.aws/credentials`, or an instance/role
  profile) with `ssm:GetParameter` on the parameter path used below, plus
  `cognito-idp:InitiateAuth` and `cognito-idp:RespondToAuthChallenge`.
  Every other provider has no AWS dependency — their config comes entirely
  from JMeter properties (see [§4](#4-configuration-properties)).

## 2. AWS SDK v2 jars

Only `cognito_auth.groovy` needs the AWS SDK. Every other script
(`auth0_auth.groovy`, `okta_auth.groovy`, `entra_id_auth.groovy`,
`keycloak_auth.groovy`, `ping_auth.groovy`, `firebase_auth.groovy`) has no
external dependency beyond JMeter/Groovy itself — the login is a plain
HTTP call made with Java's built-in `java.net.http.HttpClient`.

If you're using Cognito, download the following jars (and their
transitive dependencies) and place them in `JMETER_HOME/lib`, then restart
JMeter:

- `cognitoidentityprovider`
- `ssm`
- `auth`
- `regions`
- `sdk-core`
- `http-client-spi`
- `apache-client` (or another SDK HTTP client)
- `jackson-databind`, `jackson-core`, `jackson-annotations`

The simplest way to get a consistent set is to build a small Maven/Gradle
project with these as dependencies and copy the resolved jars (including
transitive ones) into `JMETER_HOME/lib`.

## 3. Cognito SSM Parameter Store value

`cognito_auth.groovy` reads a single String parameter (JSON) from SSM at
`/cognito-test/jmeter/dev` by default, overridable via `cognito.paramPath`:

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

No other provider uses SSM — see the next section.

## 4. Configuration properties

Cognito config lives in SSM (above) with a couple of property overrides.
Every other provider takes its entire config from JMeter `-J`
properties — nothing to provision in AWS.

Missing a required property throws a clear error at script start (e.g.
`Missing required JMeter property: -Jauth0.domain=...`) instead of failing
deep inside the HTTP call.

### Cognito

| Property             | Required? | Purpose                                   |
|------------------------|-----------|----------------------------------------------|
| `cognito.paramPath`    | no        | SSM parameter path (default `/cognito-test/jmeter/dev`) |
| `cognito.username`     | no        | Username to authenticate as                 |
| `cognito.password`     | no        | Password for that username                  |

### Auth0

| Property             | Required? | Purpose                                   |
|------------------------|-----------|----------------------------------------------|
| `auth0.domain`         | **yes**   | e.g. `your-tenant.auth0.com`                |
| `auth0.clientId`       | **yes**   | Auth0 application client ID                 |
| `auth0.clientSecret`   | **yes**   | Auth0 application client secret             |
| `auth0.audience`       | **yes**\* | API identifier (\*required for `client_credentials`, the default grant) |
| `auth0.grantType`      | no        | `client_credentials` (default) or `password` |
| `auth0.scope`          | no        | Only used for `grantType=password`; default `openid profile` |
| `auth0.username`       | if `grantType=password` | End-user username |
| `auth0.password`       | if `grantType=password` | End-user password |

### Okta

| Property             | Required? | Purpose                                   |
|------------------------|-----------|----------------------------------------------|
| `okta.domain`          | **yes**   | e.g. `your-org.okta.com`                    |
| `okta.clientId`        | **yes**   | Okta application client ID                  |
| `okta.clientSecret`    | **yes**   | Okta application client secret              |
| `okta.authServerId`    | no        | Default `"default"`                         |
| `okta.scope`           | no        | Default `"api://default"`                   |
| `okta.grantType`       | no        | `client_credentials` (default) or `password` |
| `okta.username`        | if `grantType=password` | End-user username |
| `okta.password`        | if `grantType=password` | End-user password |

Okta authenticates the client via HTTP Basic auth (`client_secret_basic`),
which the script handles for you.

### Microsoft Entra ID (Azure AD)

| Property               | Required? | Purpose                                   |
|--------------------------|-----------|----------------------------------------------|
| `entraid.tenantId`       | **yes**   | Directory (tenant) ID or verified domain    |
| `entraid.clientId`       | **yes**   | Application (client) ID                     |
| `entraid.clientSecret`   | **yes**   | Client secret value                         |
| `entraid.scope`          | **yes**   | e.g. `https://graph.microsoft.com/.default` |
| `entraid.grantType`      | no        | `client_credentials` (default) or `password` |
| `entraid.username`       | if `grantType=password` | End-user username |
| `entraid.password`       | if `grantType=password` | End-user password |

ROPC (`grantType=password`) has real restrictions on Microsoft's side: it
doesn't work with personal Microsoft accounts, and fails for accounts with
MFA or other Conditional Access requirements. Prefer `client_credentials`
unless you specifically need a user token and your test tenant/app is
configured to allow it.

### Keycloak

| Property               | Required? | Purpose                                   |
|--------------------------|-----------|----------------------------------------------|
| `keycloak.baseUrl`       | **yes**   | e.g. `https://keycloak.example.com` (no trailing slash) |
| `keycloak.realm`         | **yes**   | Realm name                                  |
| `keycloak.clientId`      | **yes**   | Client ID                                   |
| `keycloak.clientSecret`  | no\*      | \*Required unless the client is public (no secret) |
| `keycloak.grantType`     | no        | `client_credentials` (default) or `password` |
| `keycloak.scope`         | no        | e.g. `openid`; only sent if set             |
| `keycloak.username`      | if `grantType=password` | End-user username |
| `keycloak.password`      | if `grantType=password` | End-user password |

`client_credentials` requires the client to be confidential with "Service
accounts enabled"; `password` requires "Direct Access Grants" enabled on
the client.

### PingFederate / PingOne

| Property           | Required? | Purpose                                   |
|----------------------|-----------|----------------------------------------------|
| `ping.tokenUrl`      | **yes**   | Full token endpoint URL (see below)         |
| `ping.clientId`      | **yes**   | Client ID                                   |
| `ping.clientSecret`  | **yes**   | Client secret                               |
| `ping.grantType`     | no        | `client_credentials` (default) or `password` |
| `ping.scope`         | no        | Space-separated scopes; only sent if set    |
| `ping.username`      | if `grantType=password` | End-user username |
| `ping.password`      | if `grantType=password` | End-user password |

PingFederate and PingOne are different products with different token URL
shapes, so `ping.tokenUrl` takes the full endpoint rather than being
assembled from parts:

- PingOne: `https://auth.pingone.com/{envId}/as/token`
- PingFederate: `https://{pf-host}:9031/as/token.oauth2` (port/path vary
  by deployment)

The client is authenticated via HTTP Basic auth (`client_secret_basic`),
the default for both products.

### Google Identity Platform / Firebase Auth

| Property             | Required? | Purpose                                   |
|------------------------|-----------|----------------------------------------------|
| `firebase.apiKey`      | **yes**   | Web API key (Project settings > General)    |
| `firebase.email`       | **yes**   | Test user's email                           |
| `firebase.password`    | **yes**   | Test user's password                        |
| `firebase.tenantId`    | no        | Identity Platform multi-tenancy tenant ID, if used |

Unlike the other providers, Firebase/Identity Platform has no
client-credentials (machine-to-machine) grant for regular apps — this
script calls the Identity Toolkit REST API's `signInWithPassword`
endpoint directly, the same call the Firebase Auth SDKs make. It exports
Firebase's `idToken` as `access_token` to match this repo's
`Authorization: Bearer ${access_token}` convention, plus `refresh_token`.

### Examples

```bash
jmeter -n -t examples/cognito_auth_sample.jmx \
  -Jcognito.username=someuser@example.com \
  -Jcognito.password='YourPassword123!' \
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
assertion works after any of the auth scripts above. For the sampler it's
attached to, it checks:

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
- **Entra ID**: call Microsoft Graph, e.g.
  `GET https://graph.microsoft.com/v1.0/me` with the Bearer header for a
  user token, or `GET https://graph.microsoft.com/v1.0/users` for an
  app-only (`client_credentials`) token with the right permissions.
- **Keycloak**: `GET <baseUrl>/realms/<realm>/protocol/openid-connect/userinfo`
  with the Bearer header (needs a token with a subject).
- **Ping**: PingOne exposes `GET https://auth.pingone.com/<envId>/as/userinfo`;
  PingFederate's path is deployment-specific — check your instance's
  `/.well-known/openid-configuration`.
- **Firebase**: `POST https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=<apiKey>`
  with body `{"idToken": "${access_token}"}` returns the user's account
  info if the token is valid.

See `examples/README.md` for how everything above is wired together in the
sample test plans.
