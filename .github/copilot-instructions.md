# Copilot Instructions for jmeter-cognito

This document provides essential context for AI coding assistants working in the jmeter-cognito project.

## Project Overview

jmeter-cognito is a JMeter plugin that enables load testing of applications using Amazon Cognito authentication. The project aims to simplify the process of performance testing Cognito-protected APIs by handling authentication flows within JMeter test plans.

The plugin supports:
- User Pool authentication flows (USER_SRP_AUTH)
- Refresh token management
- ID token injection into subsequent requests
- Concurrent user simulation with unique credentials
- Custom authentication flows (to be implemented)

## Project Structure

The project follows Maven's standard directory layout:

```
jmeter-cognito/
├── src/
│   ├── main/java/         - Plugin implementation
│   │   └── com/github/michaustriaqa/jmeter/
│   │       ├── auth/      - Cognito authentication logic
│   │       ├── config/    - Plugin configuration elements
│   │       └── sampler/   - JMeter samplers
│   └── test/java/         - Unit and integration tests
├── examples/              - Sample test plans
│   ├── basic-auth/        - Basic authentication examples
│   └── custom-flow/       - Custom auth flow examples
└── docs/                  - Documentation and guides
```

## Key Technologies

- Apache JMeter 5.6+ - Load testing framework
- AWS SDK for Java v2 - For Cognito integration
- Amazon Cognito - User authentication service 
- Java 11+ - Implementation language
- Maven - Build and dependency management

## Development Conventions

### JMeter Component Naming
- Samplers: Prefix with "Cognito" (e.g., `CognitoAuthSampler`)
- Config Elements: Suffix with "Config" (e.g., `UserPoolConfig`)
- Test Elements: Use descriptive names (e.g., `RefreshTokenManager`)

### Error Handling
- Use JMeter's `AssertionResult` for test failures
- Log detailed errors using SLF4J
- Include AWS request IDs in error messages
- Propagate authentication failures to dependent samplers

### Testing Standards
- Unit tests for all authentication flows
- Integration tests against a test Cognito User Pool
- Load test scenarios in example directory
- Test with both success and failure cases

## Common Workflows

### Building the Plugin
```bash
mvn clean package
cp target/jmeter-cognito-{version}.jar $JMETER_HOME/lib/ext/
```

### Development Setup
1. Set up a Cognito User Pool for testing
2. Configure AWS credentials
3. Run integration tests: `mvn verify -Pintegration-test`

### Debugging
- Enable JMeter's debug logging
- Use JMeter's View Results Tree
- Check AWS CloudWatch logs for Cognito errors
- Monitor token expiration times

## Integration Points

### JMeter Plugin Integration
- Implement `AbstractSampler` for auth requests
- Use `TestBeanGUI` for configuration UIs
- Follow JMeter's thread safety guidelines
- Support variables and property functions

### AWS Cognito Integration
- Use AWS SDK v2's async client builders
- Handle common Cognito exceptions:
  - `NotAuthorizedException`
  - `UserNotFoundException`
  - `InvalidParameterException`
- Implement token refresh strategy
- Support multiple auth flows

### Test Plan Integration
- Provide clear auth flow examples
- Support JMeter's built-in assertions
- Enable thread group scaling
- Allow parameter externalization

## Configuration

### Required Parameters
```properties
# UserPool Configuration
cognito.userPoolId=${__P(COGNITO_POOL_ID)}  # AWS Cognito User Pool ID
cognito.clientId=${__P(CLIENT_ID)}          # App client ID
cognito.region=${__P(AWS_REGION)}           # AWS region

# Authentication
auth.username=${__V(username)}              # Username variable
auth.password=${__V(password)}              # Password variable
auth.flow=USER_SRP_AUTH                     # Auth flow type

# Optional Settings
auth.refreshBeforeExpiry=300                # Refresh tokens 5 min before expiry
auth.maxRetries=3                           # Max retry attempts
```

### AWS Credentials
```bash
# Option 1: Environment variables
export AWS_ACCESS_KEY_ID=your_access_key
export AWS_SECRET_ACCESS_KEY=your_secret_key

# Option 2: AWS credentials file
~/.aws/credentials
```

## Common Issues and Solutions

### Authentication Failures
- Verify AWS credentials are properly configured
- Check Cognito User Pool client settings
- Ensure user exists and password meets requirements
- Validate token refresh timing

### Performance Issues
- Use appropriate thread group ramp-up
- Monitor Cognito service quotas
- Cache and reuse tokens when possible
- Consider using connection pooling

### Thread Safety
- Don't share clients across threads
- Use ThreadLocal for per-thread storage
- Synchronize token refresh operations
- Handle concurrent token updates

---

*Note: This document should be updated as the project evolves and new patterns emerge. Refer to actual code examples once implemented.*