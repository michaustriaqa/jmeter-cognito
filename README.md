# JMeter Auth Scripts (Cognito / Auth0 / Okta / Entra ID / Keycloak / Ping / Firebase)

Groovy scripts for load testing authenticated APIs in Apache JMeter, across seven providers: **Amazon Cognito**, **Auth0**, **Okta**, **Microsoft Entra ID (Azure AD)**, **Keycloak**, **PingFederate/PingOne**, and **Google Identity Platform/Firebase Auth**. Instead of a compiled JMeter plugin, this repo uses **JSR223 Sampler/Assertion elements** that run the Groovy scripts directly — no build step, no jar to install, just script files referenced from your test plan.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## Features

- **Cognito** (`scripts/cognito_auth.groovy`): full `USER_SRP_AUTH` handshake, including `PASSWORD_VERIFIER` and `NEW_PASSWORD_REQUIRED` challenges; config from AWS SSM Parameter Store
- **Auth0, Okta, Entra ID, Keycloak, Ping, Firebase**: standard OAuth2/OIDC (or, for Firebase, the Identity Toolkit REST API) — Client Credentials grant by default where supported (no end-user password needed), or Resource Owner Password grant via config; config from plain JMeter properties, no AWS dependency
- Login happens **once per test run**, not once per request: the token is cached in shared JMeter properties (`props`) behind a `synchronized` check, so every thread and every loop iteration reuses it instead of re-authenticating
- `access_token` (Cognito and Firebase also: `refresh_token`; Cognito also: `id_token`) exposed as JMeter variables for use in subsequent samplers (e.g. `Authorization: Bearer ${access_token}`)
- A single, provider-agnostic JSR223 Assertion (`scripts/globalassertion.groovy`) that verifies a request truly succeeded — a token was actually issued, the response was 2xx, the body wasn't empty, and it doesn't contain auth-failure text under a misleading 200

## Prerequisites

- Java 11+
- Apache JMeter 5.6+
- An account with the provider(s) you're testing against (Cognito User Pool, Auth0 tenant, Okta org, Entra ID tenant, Keycloak realm, PingFederate/PingOne environment, and/or a Firebase/Identity Platform project)
- **Cognito only**: AWS SDK for Java v2 jars (see below) to read config from SSM and talk to Cognito. No Maven build required, these are just downloaded and dropped into JMeter's `lib` directory. Every other provider needs no extra jars — they use Java's built-in HTTP client.

## Setup

Full walkthrough — AWS SDK jars, IAM permissions, Cognito's SSM parameter JSON shape, all property names — is in [`docs/SETUP.md`](docs/SETUP.md). Short version:

1. **Cognito**: place the AWS SDK v2 jars (`cognitoidentityprovider`, `ssm`, `auth`, `regions`, `sdk-core`, an HTTP client, `jackson-*`) in `JMETER_HOME/lib`, restart JMeter, make sure AWS credentials with `ssm:GetParameter` + `cognito-idp:InitiateAuth`/`RespondToAuthChallenge` are available, and store your Cognito config as a String-type JSON parameter in SSM.
2. **Everything else** (Auth0, Okta, Entra ID, Keycloak, Ping, Firebase): nothing to provision — pass config directly as JMeter `-J` properties (see below).

## Configuration

Cognito reads config from SSM plus a couple of property overrides. Every
other provider takes its entire config from JMeter `-J` properties — the
scripts throw a clear error at startup if a required one is missing.

| Provider    | Property               | Required? | Purpose                                                   |
|-------------|--------------------------|-----------|-------------------------------------------------------------|
| Cognito     | `cognito.paramPath`      | no        | SSM parameter path (default: `/cognito-test/jmeter/dev`)    |
| Cognito     | `cognito.username`       | no        | Cognito username to authenticate as                          |
| Cognito     | `cognito.password`       | no        | Password for that username                                   |
| Auth0       | `auth0.domain`           | **yes**   | e.g. `your-tenant.auth0.com`                                 |
| Auth0       | `auth0.clientId`         | **yes**   | Auth0 application client ID                                  |
| Auth0       | `auth0.clientSecret`     | **yes**   | Auth0 application client secret                               |
| Auth0       | `auth0.audience`         | **yes**\* | API identifier (\*for the default `client_credentials` grant) |
| Okta        | `okta.domain`            | **yes**   | e.g. `your-org.okta.com`                                     |
| Okta        | `okta.clientId`          | **yes**   | Okta application client ID                                   |
| Okta        | `okta.clientSecret`      | **yes**   | Okta application client secret                                |
| Entra ID    | `entraid.tenantId`       | **yes**   | Directory (tenant) ID or verified domain                     |
| Entra ID    | `entraid.clientId`       | **yes**   | Application (client) ID                                      |
| Entra ID    | `entraid.clientSecret`   | **yes**   | Client secret value                                           |
| Entra ID    | `entraid.scope`          | **yes**   | e.g. `https://graph.microsoft.com/.default`                  |
| Keycloak    | `keycloak.baseUrl`       | **yes**   | e.g. `https://keycloak.example.com`                          |
| Keycloak    | `keycloak.realm`         | **yes**   | Realm name                                                    |
| Keycloak    | `keycloak.clientId`      | **yes**   | Client ID                                                      |
| Ping        | `ping.tokenUrl`          | **yes**   | Full token endpoint URL (PingOne/PingFederate shapes differ)  |
| Ping        | `ping.clientId`          | **yes**   | Client ID                                                      |
| Ping        | `ping.clientSecret`      | **yes**   | Client secret                                                  |
| Firebase    | `firebase.apiKey`        | **yes**   | Web API key                                                    |
| Firebase    | `firebase.email`         | **yes**   | Test user's email                                              |
| Firebase    | `firebase.password`      | **yes**   | Test user's password                                           |

See `docs/SETUP.md` §4 for the full list including optional overrides
(`grantType`, `scope`, `authServerId`, `clientSecret` for Keycloak, etc.).

Pass these on the command line with `-J`, e.g. `-Jauth0.domain=your-tenant.auth0.com -Jauth0.clientId=... -Jauth0.clientSecret=... -Jauth0.audience=...`.

## Usage

1. Add a **JSR223 Sampler** as the first sampler in your Thread Group (outside any loop), pointing its "Script File" at the auth script for your provider (`scripts/cognito_auth.groovy`, `scripts/auth0_auth.groovy`, `scripts/okta_auth.groovy`, `scripts/entra_id_auth.groovy`, `scripts/keycloak_auth.groovy`, `scripts/ping_auth.groovy`, or `scripts/firebase_auth.groovy`). It stores `access_token` (and, for Cognito, `id_token`/`refresh_token`; for Firebase, `refresh_token`) as JMeter variables.
2. Add an **HTTP Header Manager** using `Authorization: Bearer ${access_token}` for the requests that follow.
3. Attach a **JSR223 Assertion** pointing at `scripts/globalassertion.groovy` to your protected-request samplers to confirm they truly succeeded, not just that they didn't throw. This assertion is the same regardless of provider.

See [`examples/`](examples/) for a complete, runnable sample test plan per provider ([`examples/README.md`](examples/README.md) explains the structure).

## Debugging

- The auth scripts and `globalassertion.groovy` log via JMeter's `log` object (`log.info(...)`) — check `jmeter.log` or enable debug logging in `log4j2.xml`.
- Use JMeter's View Results Tree listener to inspect the auth sampler and subsequent requests.
- Check the provider's own logs for auth errors (AWS CloudWatch for Cognito, the tenant/org/realm/project's log stream for everyone else).
- Monitor token expiration timing — the scripts only log in once per run and don't currently refresh; long-running tests may need the `refresh_token` variable (Cognito, Firebase) wired up separately.

## Common Issues

### Authentication Failures
- Cognito: verify AWS credentials are properly configured and can reach SSM/Cognito, the SSM parameter JSON matches the expected shape (see `docs/SETUP.md`), User Pool client settings are correct (SRP auth flow enabled, client secret correct), and the user exists with a password meeting requirements
- Auth0/Okta/Entra ID/Keycloak/Ping/Firebase: check for a "Missing required JMeter property" error first — the script fails fast on that instead of a confusing HTTP error; then check the app/client is authorized for the grant type in use (Client Credentials apps need the API/authorization server explicitly granted; Resource Owner Password is often disabled by default and, for Entra ID, doesn't work with personal accounts or MFA)

### Performance Considerations
- Each auth script is designed to run once per test (shared across threads via `props`); make sure it's placed outside any loop so it isn't accidentally re-run per iteration
- Monitor your provider's rate limits/quotas if you do need multiple distinct logins (e.g. one per simulated user)
- Consider connection pooling for large tests

## Contributing

1. Fork the repository
2. Create your feature branch
3. Add tests for any new features
4. Submit a pull request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
