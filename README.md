# JMeter Cognito Auth Scripts

Groovy scripts for load testing applications that use Amazon Cognito authentication in Apache JMeter. Instead of a compiled JMeter plugin, this repo uses **JSR223 Sampler/Assertion elements** that run the Groovy scripts directly — no build step, no jar to install, just script files referenced from your test plan.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## Features

- User Pool authentication via `USER_SRP_AUTH` (full SRP handshake, including `PASSWORD_VERIFIER` and `NEW_PASSWORD_REQUIRED` challenges)
- Cognito app config loaded from AWS SSM Parameter Store, not hardcoded in the script
- Login happens **once per test run**, not once per request: the token is cached in shared JMeter properties (`props`) behind a `synchronized` check, so every thread and every loop iteration reuses it instead of re-authenticating against Cognito
- `access_token` / `id_token` / `refresh_token` exposed as JMeter variables for use in subsequent samplers (e.g. `Authorization: Bearer ${access_token}`)
- A JSR223 Assertion that verifies a request truly succeeded — a token was actually issued, the response was 2xx, the body wasn't empty, and it doesn't contain auth-failure text under a misleading 200

## Prerequisites

- Java 11+
- Apache JMeter 5.6+
- AWS account with a Cognito User Pool
- AWS SDK for Java v2 jars (see below) — no Maven build required, these are just downloaded and dropped into JMeter's `lib` directory

## Setup

Full walkthrough — AWS SDK jars, IAM permissions, the SSM parameter JSON shape, property overrides — is in [`docs/SETUP.md`](docs/SETUP.md). Short version:

1. Place the AWS SDK v2 jars (`cognitoidentityprovider`, `ssm`, `auth`, `regions`, `sdk-core`, an HTTP client, `jackson-*`) in `JMETER_HOME/lib`, then restart JMeter.
2. Make sure AWS credentials with `ssm:GetParameter`, `cognito-idp:InitiateAuth`, and `cognito-idp:RespondToAuthChallenge` are available to the machine running JMeter (env vars, `~/.aws/credentials`, or an instance/role profile).
3. Store your Cognito config (`region`, `userPoolId`, `clientId`, `clientSecret`, optional `newPermanentPassword`) as a String-type JSON parameter in SSM.

## Configuration

`scripts/cognito_auth.groovy` reads these JMeter properties, falling back to hardcoded test defaults if unset:

| Property             | Purpose                                                   |
|-----------------------|------------------------------------------------------------|
| `cognito.paramPath`   | SSM parameter path (default: `/cognito-test/jmeter/dev`)   |
| `cognito.username`    | Cognito username to authenticate as                        |
| `cognito.password`    | Password for that username                                 |

Pass overrides on the command line with `-J`, e.g. `-Jcognito.username=someuser@example.com`.

## Usage

1. Add a **JSR223 Sampler** as the first sampler in your Thread Group (outside any loop), pointing its "Script File" at `scripts/cognito_auth.groovy`. It stores `access_token`, `id_token`, and `refresh_token` as JMeter variables.
2. Add an **HTTP Header Manager** using `Authorization: Bearer ${access_token}` for the requests that follow.
3. Attach a **JSR223 Assertion** pointing at `scripts/globalassertion.groovy` to your protected-request samplers to confirm they truly succeeded, not just that they didn't throw.

See [`examples/cognito_auth_sample.jmx`](examples/cognito_auth_sample.jmx) and [`examples/README.md`](examples/README.md) for a complete, runnable test plan wiring all of this together.

## Debugging

- `cognito_auth.groovy` and `globalassertion.groovy` log via JMeter's `log` object (`log.info(...)`) — check `jmeter.log` or enable debug logging in `log4j2.xml`.
- Use JMeter's View Results Tree listener to inspect the auth sampler and subsequent requests.
- Check AWS CloudWatch logs for Cognito-side auth errors.
- Monitor token expiration timing — the script only logs in once per run and doesn't currently refresh; long-running tests may need the `refresh_token` variable wired up separately.

## Common Issues

### Authentication Failures
- Verify AWS credentials are properly configured and can reach SSM/Cognito
- Check the SSM parameter JSON matches the expected shape (see `docs/SETUP.md`)
- Check Cognito User Pool client settings (SRP auth flow enabled, client secret correct)
- Ensure the user exists and the password meets requirements

### Performance Considerations
- The auth script is designed to run once per test (shared across threads via `props`); make sure it's placed outside any loop so it isn't accidentally re-run per iteration
- Monitor Cognito service quotas if you do need multiple distinct logins (e.g. one per simulated user)
- Consider connection pooling for large tests

## Contributing

1. Fork the repository
2. Create your feature branch
3. Add tests for any new features
4. Submit a pull request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.