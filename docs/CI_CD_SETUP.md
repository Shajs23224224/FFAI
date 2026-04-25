# CI/CD Setup Guide for FFAI

This guide explains how to configure the GitHub Actions CI/CD pipeline for building, testing, and releasing FFAI APKs.

## Table of Contents

1. [Quick Start](#quick-start)
2. [Required Secrets](#required-secrets)
3. [Workflows Overview](#workflows-overview)
4. [Local Testing](#local-testing)
5. [Troubleshooting](#troubleshooting)

## Quick Start

### 1. Fork/Clone the Repository

```bash
git clone https://github.com/yourusername/FFAI.git
cd FFAI
```

### 2. Generate Signing Keystore

Run the helper script to generate a keystore:

```bash
chmod +x scripts/generate-keystore-base64.sh
./scripts/generate-keystore-base64.sh ffai-release ffai-key
```

This will output:
- Base64-encoded keystore (for GitHub Secrets)
- Keystore password
- Key alias
- Key password

**⚠️ Save these values securely - they cannot be recovered!**

### 3. Configure GitHub Secrets

Navigate to **Settings > Secrets and variables > Actions** in your GitHub repository.

Add the following secrets:

| Secret Name | Description | Example Value |
|-------------|-------------|---------------|
| `KEYSTORE_BASE64` | Base64-encoded keystore file | `MIIStwIBAzCC...` (long string) |
| `KEYSTORE_PASSWORD` | Keystore password | `your-keystore-pass` |
| `KEY_ALIAS` | Key alias | `ffai-key` |
| `KEY_PASSWORD` | Key password | `your-key-pass` |

Optional secrets for notifications:

| Secret Name | Description |
|-------------|-------------|
| `SLACK_WEBHOOK_URL` | Slack webhook for build notifications |

### 4. Verify Setup

Trigger a test build by pushing a commit or manually running the workflow:

1. Go to **Actions > Build FFAI APK**
2. Click **Run workflow**
3. Select `debug` as the release type
4. Click **Run workflow**

## Required Secrets

### Generating the Keystore

The keystore is required to sign release APKs. Android requires all APKs to be signed before installation.

#### Option A: Using the Helper Script (Recommended)

```bash
./scripts/generate-keystore-base64.sh
```

This script will:
1. Generate a new RSA 2048-bit keystore
2. Encode it to base64
3. Display all values needed for GitHub Secrets

#### Option B: Manual Generation

```bash
# Generate keystore
keytool -genkey -v \
    -keystore ffai.keystore \
    -alias ffai-key \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000

# Encode to base64
base64 -w 0 ffai.keystore
```

### Secret Descriptions

#### KEYSTORE_BASE64
The entire keystore file encoded in base64. This allows GitHub Actions to reconstruct the keystore during the build process.

#### KEYSTORE_PASSWORD
The password used to access the keystore file itself.

#### KEY_ALIAS
The alias name of the key within the keystore.

#### KEY_PASSWORD
The password for the specific key (can be the same as keystore password).

## Workflows Overview

### 1. Build and Release (`build-and-release.yml`)

**Triggers:**
- Push to `main` or `develop`
- Tag push (`v*`)
- Manual dispatch

**Jobs:**
- **Build & Test**: Compiles APK, runs unit tests, generates coverage
- **Artifact Upload**: Stores debug/release APKs
- **Release Creation**: Publishes to GitHub Releases (on tags)

**Optimizations:**
- Gradle build cache
- NDK caching
- Parallel execution
- Configuration cache

### 2. PR Checks (`pr-checks.yml`)

**Triggers:**
- Pull requests to `main` or `develop`

**Jobs:**
- **ktlint**: Code style checking
- **detekt**: Static analysis
- **Unit Tests**: Fast unit test execution
- **Android Lint**: Android-specific linting
- **Dependency Check**: Security vulnerability scanning
- **Build Validation**: Ensures PR doesn't break the build

### 3. Nightly (`nightly.yml`)

**Triggers:**
- Daily at 2:00 AM UTC
- Manual dispatch

**Jobs:**
- **Instrumentation Tests**: Full test suite on multiple API levels (29, 31, 34)
- **Build Performance**: Monitors build times
- **Code Quality**: Deep analysis with coverage
- **Security Scan**: Trivy and OWASP dependency check

### 4. Dependabot (`dependabot.yml`)

**Schedule:**
- Weekly (Mondays at 9:00 AM ET)

**Monitors:**
- Gradle dependencies
- GitHub Actions
- Groups updates by ecosystem

## Local Testing

### Running the Same Checks Locally

```bash
# Code style
./gradlew ktlintCheck

# Static analysis
./gradlew detekt

# Unit tests with coverage
./gradlew testDebugUnitTest jacocoTestReport

# Android lint
./gradlew lintDebug

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing config)
./gradlew assembleRelease
```

### Testing GitHub Actions Locally

Use [act](https://github.com/nektos/act) to run workflows locally:

```bash
# Install act
brew install act

# Run build workflow
act -j build-and-test

# Run PR checks
act -j ktlint
```

## Troubleshooting

### Build Failures

#### "Keystore file not found"
- Verify `KEYSTORE_BASE64` secret is set
- Check that the base64 string is complete (not truncated)
- Regenerate the keystore if needed

#### "Invalid keystore format"
- Ensure the keystore was properly encoded to base64
- Check for extra whitespace in the secret value

#### Gradle Out of Memory
The workflow is configured with 8GB heap. If builds still fail:
- Check if it's a memory leak in the build
- Increase `-Xmx` in `gradle.properties`

### Test Failures

#### "No test results found"
- Ensure tests are in the correct directory (`src/test`)
- Check that test dependencies are configured in `build.gradle`

#### Emulator timeout
- The nightly workflow uses macOS runners which can be slow
- Increase `timeout-minutes` if needed
- Consider using API 29 (faster) for PR checks

### Slow Builds

#### Enable Build Cache
Already enabled in `gradle.properties`:
```properties
org.gradle.caching=true
org.gradle.configuration-cache=true
```

#### Check Cache Hits
Look for `Task :app:compileDebugKotlin FROM-CACHE` in logs.

### Security Scanning

#### Trivy false positives
Add `.trivyignore` file for known acceptable vulnerabilities:
```
# Acceptable for testing
CVE-2021-12345
```

## Best Practices

### Version Tagging

Use semantic versioning:
- `v1.0.0` - Major release
- `v1.2.0` - Minor release
- `v1.2.3` - Patch release
- `v1.2.3-alpha.1` - Pre-release

### Branch Protection

Recommended settings for `main` branch:
- Require PR reviews (1+)
- Require status checks to pass:
  - `PR Checks Summary`
  - `Build & Test`
- Require signed commits (optional)

### Release Process

1. Create a release branch: `git checkout -b release/v1.2.0`
2. Update version in `app/build.gradle` if needed
3. Merge to `main` via PR
4. Tag the release: `git tag -a v1.2.0 -m "Release v1.2.0"`
5. Push the tag: `git push origin v1.2.0`
6. GitHub Actions automatically builds and creates the release

## Additional Resources

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Android CI/CD Best Practices](https://developer.android.com/studio/build/building-cmdline)
- [Gradle Build Cache](https://docs.gradle.org/current/userguide/build_cache.html)
- [Keystore Management](https://developer.android.com/studio/publish/app-signing)

## Support

For issues with the CI/CD pipeline:
1. Check the [Actions tab](../../actions) for error logs
2. Review this troubleshooting guide
3. Open an issue with the workflow logs attached
