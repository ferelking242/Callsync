---
name: Android CI environment
description: Android builds are validated by GitHub Actions because the local workspace does not provide a complete Android SDK.
---

The authoritative Android build check for this project is the GitHub Actions pipeline; the local Replit container has Java and platform tools but no complete Android SDK.

**Why:** The project’s Gradle build requires Android SDK platform/build-tool packages that are not available in the local runtime, while the repository CI provisions them reliably.

**How to apply:** Push Android changes and monitor the debug and release workflows rather than treating a local SDK-location failure as a source-code failure.