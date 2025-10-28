# Copilot Instructions for jmeter-cognito

This document provides essential context for AI coding assistants working in the jmeter-cognito project.

## Project Overview

jmeter-cognito is a JMeter plugin that enables load testing of applications using Amazon Cognito authentication. The project aims to simplify the process of performance testing Cognito-protected APIs by handling authentication flows within JMeter test plans.

## Project Structure

The project is organized as follows (to be expanded as files are added):

- `src/` - Core plugin implementation (to be added)
- `examples/` - Sample test plans and configurations (to be added)
- `docs/` - Documentation (to be added)
- `LICENSE` - Apache License 2.0

## Key Technologies

- Apache JMeter - Load testing framework
- Amazon Cognito - User authentication service
- Java - Primary implementation language

## Development Conventions

As files are added, document key conventions here, such as:
- Naming patterns for test plan elements
- Required configuration parameters
- Error handling and logging approaches
- Testing patterns and expectations

## Common Workflows

Document essential workflows here as they are established:

1. Building the Plugin
2. Running Tests
3. Debugging
4. Release Process

## Integration Points

Key integration points to be documented:

1. JMeter Plugin API Integration
2. AWS Cognito SDK Integration
3. Test Plan Component Integration

## Configuration

Document configuration parameters and settings here as they are defined:

```yaml
# Example configuration (to be expanded)
cognito:
  userPoolId: "region_poolid"
  clientId: "app_client_id"
  region: "aws-region"
```

## Common Issues and Solutions

Document common issues and their solutions here as they are discovered during development.

---

*Note: This is an initial template that will evolve as the project develops. Update sections as new patterns and conventions emerge.*