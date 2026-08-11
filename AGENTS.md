# Repository workflow

- When this repository is owned by the user and is public, finish every requested fix or update by verifying the result, committing the relevant source changes, and pushing the current branch.
- After the push, build the Android APK and create a GitHub release as a draft. Never publish the release automatically: it must remain visible only to repository maintainers until the user publishes it.
- Attach both the APK and a `.tar.gz` archive generated from the exact pushed commit. Verify the draft release, tag, target commit, and downloadable assets before reporting success.
- Keep generated build output, IDE caches, local configuration, credentials, signing material, model files, and other secrets out of the source commit and source archive.

# Development servers

- When a server is already running, use that server instead of opening another port. Patches apply automatically; if a change may not hot-reload, ask the user to restart the existing server.
