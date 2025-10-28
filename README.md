# JMeter Cognito Plugin

A JMeter plugin for load testing applications that use Amazon Cognito authentication. This plugin simplifies the process of performance testing Cognito-protected APIs by handling authentication flows directly within JMeter test plans.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## Features

- User Pool authentication flows (USER_SRP_AUTH)
- Automatic token refresh management
- ID token injection into subsequent requests
- Concurrent user simulation with unique credentials
- Support for custom authentication flows (coming soon)

## Prerequisites

- Java 11+
- Apache JMeter 5.6+
- AWS Account with Cognito User Pool
- Maven (for building from source)

## Installation

1. Download the latest release jar from the [releases page](../../releases)
2. Copy the jar to your JMeter's `lib/ext` directory:
   ```bash
   cp jmeter-cognito-{version}.jar $JMETER_HOME/lib/ext/
   ```
3. Restart JMeter

## Building from Source

```bash
# Clone the repository
git clone https://github.com/michaustriaqa/jmeter-cognito.git
cd jmeter-cognito

# Build the plugin
mvn clean package

# Copy to JMeter lib/ext directory
cp target/jmeter-cognito-{version}.jar $JMETER_HOME/lib/ext/
```

## Configuration

### AWS Credentials

Set up your AWS credentials using one of these methods:

1. Environment variables:
   ```bash
   export AWS_ACCESS_KEY_ID=your_access_key
   export AWS_SECRET_ACCESS_KEY=your_secret_key
   ```

2. AWS credentials file:
   ```
   # ~/.aws/credentials
   [default]
   aws_access_key_id=your_access_key
   aws_secret_access_key=your_secret_key
   ```

### Required Parameters

Configure these parameters in your test plan:

```properties
# UserPool Configuration
cognito.userPoolId=${__P(COGNITO_POOL_ID)}  # AWS Cognito User Pool ID
cognito.clientId=${__P(CLIENT_ID)}          # App client ID
cognito.region=${__P(AWS_REGION)}           # AWS region

# Authentication
auth.username=${__V(username)}              # Username variable
auth.password=${__V(password)}              # Password variable
auth.flow=USER_SRP_AUTH                     # Auth flow type
```

## Usage

1. Add the "Cognito Auth Config" element to your test plan
2. Configure your User Pool settings
3. Add the "Cognito Auth Sampler" to handle authentication
4. Use the generated tokens in subsequent requests

See the [examples](examples/) directory for sample test plans.

## Debugging

- Enable debug logging in `log4j2.xml`
- Use JMeter's View Results Tree listener
- Check AWS CloudWatch logs for Cognito errors
- Monitor token expiration timing

## Common Issues

### Authentication Failures
- Verify AWS credentials are properly configured
- Check Cognito User Pool client settings
- Ensure user exists and password meets requirements

### Performance Considerations
- Use appropriate thread group ramp-up periods
- Monitor Cognito service quotas
- Cache and reuse tokens when possible
- Consider connection pooling for large tests

## Contributing

1. Fork the repository
2. Create your feature branch
3. Add tests for any new features
4. Submit a pull request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.