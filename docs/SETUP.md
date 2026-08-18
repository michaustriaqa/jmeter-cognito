# Setup Guide: Cognito Auth Scripts for JMeter

This describes how `scripts/cognito_auth.groovy` and `scripts/globalassertion.groovy`
are actually used in this repo: as JSR223 script files referenced from a JMeter
test plan, not as a compiled JMeter plugin.

## 1. Prerequisites

- Apache JMeter 5.6+
- Java 11+
- AWS credentials available to the machine running JMeter (environment
  variables, `~/.aws/credentials`, or an instance/role profile), with
  permission for:
  - `ssm:GetParameter` on the parameter path used below
  - `cognito-idp:InitiateAuth`
  - `cognito-idp:RespondToAuthChallenge`

## 2. AWS SDK v2 jars

`cognito_auth.groovy` uses the AWS SDK for Java v2. Download the following
jars (and their transitive dependencies) and place them in `JMETER_HOME/lib`,
then restart JMeter:

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

## 3. SSM Parameter Store value

`cognito_auth.groovy` reads a single String parameter (default path
`/cognito-test/jmeter/dev`, overridable — see below) containing JSON:

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

## 4. Configuring credentials without editing the script

The script reads these JMeter properties, falling back to hardcoded test
defaults if unset:

| Property             | Purpose                                   |
|-----------------------|-------------------------------------------|
| `cognito.paramPath`   | SSM parameter path (default: `/cognito-test/jmeter/dev`) |
| `cognito.username`    | Cognito username to authenticate as        |
| `cognito.password`    | Password for that username                 |

Pass them on the command line, e.g.:

```bash
jmeter -n -t examples/cognito_auth_sample.jmx \
  -Jcognito.username=someuser@example.com \
  -Jcognito.password='YourPassword123!' \
  -JBASE_URL=your-api.example.com \
  -JPROTECTED_PATH=/your/protected/path
```

## 5. How the login is shared across the test

`cognito_auth.groovy` is meant to run once per test, not once per request:

- It first checks the current thread's JMeter variables (`vars`) — if a
  token is already there, it returns immediately.
- Otherwise it checks a shared JMeter property (`props`, JVM-wide) under a
  `synchronized` block. The first thread to reach this authenticates for
  real; every other thread (and every later loop iteration) reuses the
  cached token instead of re-authenticating.

Place the JSR223 Sampler running this script as the **first sampler in the
Thread Group, outside of any loop** so it only runs once per thread and the
shared cache short-circuits the rest.

## 6. Assertions

`scripts/globalassertion.groovy` is a JSR223 Assertion that checks, for the
sampler it's attached to:

1. `access_token` exists for the current thread (the login actually
   happened before this request ran).
2. The HTTP response code is 2xx.
3. The response body is non-empty.
4. The response body doesn't contain common auth-failure text
   (`unauthorized`, `forbidden`, `invalid_token`, ...) that some APIs
   return with a misleading 200 status.

See `examples/README.md` for how these are wired together in the sample
test plan.
