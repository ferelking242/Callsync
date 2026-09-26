---
name: Android release workflow
description: Durable CI constraint for the CallSync Android release workflow.
---

The GitHub-hosted Android runner already provides the Android SDK needed by this project; adding the `android-actions/setup-android` step caused the release job to fail before Gradle started.

**Why:** The release workflow failed in roughly 17 seconds at SDK setup while the parallel Android workflow succeeded without that action.

**How to apply:** Keep the release workflow dependent on the runner-provided SDK unless the runner image changes and a specific missing SDK package is observed.