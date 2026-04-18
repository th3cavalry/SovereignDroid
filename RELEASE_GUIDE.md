# Release Guide

This document explains how to create and publish a new beta release for SovereignDroid.

## Beta Release Process

The project uses a comprehensive GitHub Actions workflow to automatically build, test, audit, and release APKs when you push a git tag.

### Workflow Overview

The **Beta Release Pipeline** runs 5 steps:

1. **Check Versioning**: Validates semantic versioning and beta format
2. **Code Quality Checks**: Runs Android Lint, unit tests, and style checks
3. **Security Audit**: Scans for hardcoded secrets, checks manifest security, and audits dependencies
4. **Build APK**: Compiles the debug APK with version naming and checksums
5. **Create Release**: Automatically publishes a GitHub release with the APK (on tag push only)

### Creating a Beta Release

Follow these steps to create a new beta release:

#### 1. Bump the version number

For beta releases, use the format: `X.Y.Z-beta.N`

Update `app/build.gradle.kts`:
```kotlin
versionCode = 6  // Increment by 1
versionName = "0.1.0-beta.1"  // Use beta suffix
```

Or use the version bumping script:
```bash
./scripts/bump-version.sh <patch|minor|major>
# Then manually add -beta.N suffix
```

#### 2. Commit the changes

```bash
git add app/build.gradle.kts
git commit -m "Bump version to 0.1.0-beta.1"
git push origin main
```

#### 3. Create and push a tag

**Important:** The tag must match the versionName exactly!

```bash
git tag v0.1.0-beta.1
git push origin v0.1.0-beta.1
```

#### 4. GitHub Actions will automatically:
- ✅ Validate the version format
- 🔍 Run code quality checks (lint, tests)
- 🔐 Perform security audits
- 🔨 Build the APK with version naming
- 📦 Create a GitHub Release with the APK and SHA-256 checksum

### Version Numbering

The project uses semantic versioning with beta tags:

- **versionCode**: Internal integer, incremented for each build (e.g., 5, 6, 7...)
- **versionName**: Semantic version with beta suffix (e.g., "0.1.0-beta.1", "0.2.0-beta.2")

**Format**: `MAJOR.MINOR.PATCH-beta.BUILD`

Examples:
- `0.1.0-beta.1` - First beta of version 0.1.0
- `0.1.0-beta.2` - Second beta of version 0.1.0
- `0.2.0-beta.1` - First beta of version 0.2.0
- `1.0.0` - Stable release (no suffix)

### Transitioning to Stable

When ready to release a stable version:

1. Remove the `-beta.N` suffix:
   ```kotlin
   versionName = "0.1.0"  // Stable release
   ```

2. Tag without beta suffix:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

The workflow will automatically detect this is a stable release and mark it accordingly.

## Monitoring the Release

### Check Workflow Status

Monitor the release pipeline at:
```
https://github.com/th3cavalry/Android-llm/actions/workflows/beta-release.yml
```

### Each step shows:
- ✅ Step 1: Version validation results
- 🔍 Step 2: Lint and test reports (uploaded as artifacts)
- 🔐 Step 3: Security scan findings
- 🔨 Step 4: APK build logs and size
- 📦 Step 5: Release creation confirmation

### Download Build Artifacts

Even without creating a release, you can download:
- APK file (kept for 90 days)
- Lint reports (kept for 30 days)
- Test results (kept for 30 days)
- Dependency reports (kept for 30 days)

## Troubleshooting

### Build fails at Step 1 (Version Check)
- Ensure versionName follows semver format: `X.Y.Z-beta.N`
- Tag must match versionName exactly: `v0.1.0-beta.1`

### Build fails at Step 2 (Code Quality)
- Check lint report artifact for specific issues
- Review test results for failing tests
- These checks continue on error but may indicate problems

### Build fails at Step 3 (Security)
- Remove any hardcoded passwords or API keys
- Ensure `android:debuggable` is not set to true in manifest
- Review dependency report for vulnerable libraries

### Build fails at Step 4 (APK Build)
- Check Java version is 17+
- Verify Gradle wrapper is executable
- Review full build logs in Actions tab

### Release not created
- Ensure you pushed a tag starting with `v`
- Previous steps must complete successfully
- Check workflow permissions (requires `contents: write`)

## Manual Release Creation

If you need to create a release manually:

1. Go to GitHub → Releases → Create a new release
2. Create a new tag (e.g., `v0.1.0-beta.1`) matching your versionName
3. Check "This is a pre-release" for beta versions
4. Download the APK from workflow artifacts
5. Upload the APK and .sha256 file manually

## Build Artifacts

- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Versioned APK**: `app/build/outputs/apk/debug/SovereignDroid-vX.Y.Z-beta.N.apk`
- **Release APK**: For production (requires signing configuration)

## Next Steps

For stable 1.0.0 release:
- Complete all beta testing
- Remove `-beta.N` suffix from versionName
- Create tag `v1.0.0`
- Consider adding release signing configuration

