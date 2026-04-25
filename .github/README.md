# GitHub Workflows Documentation

This directory contains GitHub Actions workflows for the FFAI project.

## Workflows

### 🔨 Build and Release (`workflows/build-and-release.yml`)

**Purpose**: Main build workflow for generating signed APKs.

**Triggers**:
- Push to `main` or `develop` branches
- Tag push (e.g., `v1.0.0`)
- Manual workflow dispatch

**Features**:
- Debug and release APK builds
- Automatic signing for releases
- Artifact upload with configurable retention
- GitHub Release creation for tags
- Build performance optimizations (caching, parallel execution)

**Required Secrets**:
- `KEYSTORE_BASE64`: Base64-encoded keystore
- `KEYSTORE_PASSWORD`: Keystore password
- `KEY_ALIAS`: Key alias
- `KEY_PASSWORD`: Key password

### 🔍 PR Checks (`workflows/pr-checks.yml`)

**Purpose**: Fast validation for pull requests.

**Triggers**:
- Pull requests to `main` or `develop`

**Checks**:
- Code style (ktlint)
- Static analysis (detekt)
- Unit tests with coverage
- Android lint
- Dependency security scan
- Build validation

**Features**:
- Runs in parallel for fast feedback
- Posts results as PR comments
- Uploads test and lint reports

### 🌙 Nightly (`workflows/nightly.yml`)

**Purpose**: Comprehensive testing and monitoring.

**Triggers**:
- Daily at 2:00 AM UTC
- Manual workflow dispatch

**Jobs**:
- Instrumented tests on multiple API levels (29, 31, 34)
- Build performance monitoring
- Deep code quality analysis
- Security scanning (Trivy, OWASP)

**Artifacts**:
- Test reports (14-30 day retention)
- Coverage reports
- Security scan results
- Performance metrics

### 🤖 Dependabot (`dependabot.yml`)

**Purpose**: Automated dependency updates.

**Configuration**:
- Weekly updates (Monday 9:00 AM ET)
- Groups related updates (AndroidX, Kotlin, Testing)
- Ignores major updates for sensitive dependencies (TensorFlow Lite, ONNX)
- Monitors Gradle and GitHub Actions

## Configuration

### Required Secrets

Set these in **Settings > Secrets and variables > Actions**:

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded signing keystore |
| `KEYSTORE_PASSWORD` | Keystore access password |
| `KEY_ALIAS` | Key alias within keystore |
| `KEY_PASSWORD` | Key-specific password |
| `SLACK_WEBHOOK_URL` | Optional: Slack notifications |

See [CI/CD Setup Guide](../docs/CI_CD_SETUP.md) for detailed instructions.

### Environment Variables

Defined in workflow files:

```yaml
JAVA_VERSION: '17'
ANDROID_API_LEVEL: '34'
BUILD_TOOLS_VERSION: '34.0.0'
NDK_VERSION: '25.2.9519653'
CMAKE_VERSION: '3.22.1'
```

## Usage

### Running Workflows Manually

1. Navigate to **Actions** tab
2. Select the workflow (e.g., "Build FFAI APK")
3. Click **Run workflow**
4. Select options (branch, release type)
5. Click **Run workflow**

### Creating a Release

```bash
# 1. Tag the release
git tag -a v1.2.0 -m "Release v1.2.0"

# 2. Push the tag
git push origin v1.2.0

# 3. GitHub Actions automatically:
#    - Builds signed release APK
#    - Creates GitHub Release
#    - Uploads APK and mapping file
```

### Viewing Results

- **Build logs**: Actions tab > Workflow run
- **Artifacts**: Workflow run summary > Artifacts section
- **Test reports**: Uploaded as artifacts in PR checks
- **Coverage**: Jacoco reports in build artifacts

## Performance Optimizations

### Caching Strategy

| Cache | Key Components | Duration |
|-------|---------------|----------|
| Gradle | `~/.gradle/caches`, `~/.gradle/wrapper` | 30 days |
| NDK | `/usr/local/lib/android/sdk/ndk/` | 30 days |
| AVD | `~/.android/avd/` | Ephemeral |

### Build Optimizations

Enabled in `gradle.properties`:
- Configuration cache
- Build cache
- Parallel execution
- Non-transitive R classes
- Incremental compilation

## Troubleshooting

### Common Issues

**Build fails with "Keystore not found"**
- Verify all signing secrets are configured
- Check that `KEYSTORE_BASE64` is not truncated

**Slow builds**
- Check cache hits in build logs
- Verify `gradle.properties` optimizations are applied

**Emulator tests timeout**
- Normal for instrumented tests (up to 45 min)
- macOS runners are slower but required for emulator

**Out of memory**
- Gradle daemon configured with 8GB heap
- Check for memory leaks in native code

### Debugging

Enable debug logging:

```yaml
env:
  ACTIONS_STEP_DEBUG: true
  ACTIONS_RUNNER_DEBUG: true
```

View step-by-step debug info in workflow logs.

## Maintenance

### Updating Dependencies

1. Dependabot creates PRs automatically
2. Review and merge dependabot PRs
3. Monitor for breaking changes in major updates

### Workflow Updates

When modifying workflows:
1. Test on a feature branch first
2. Use `workflow_dispatch` for manual testing
3. Check that secrets are accessible
4. Verify artifact uploads work

### Security

- Rotate signing keystore annually
- Use strong passwords (32+ characters)
- Store secrets in GitHub, not in code
- Review dependency security scans weekly

## Resources

- [GitHub Actions Docs](https://docs.github.com/en/actions)
- [Android CI/CD Guide](https://developer.android.com/studio/build/building-cmdline)
- [Workflow Syntax](https://docs.github.com/en/actions/using-workflows/workflow-syntax-for-github-actions)

---

For detailed setup instructions, see [CI/CD Setup Guide](../docs/CI_CD_SETUP.md).
