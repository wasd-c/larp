# larp

larp is an Android app dedicated to language learning through on-device AI, daily voice exercises, and lessons.

## Optional telemetry and privacy

The official APK can send optional diagnostics to the project maintainer's self-hosted OpenObserve instance at `metrics.gemstud.io`. On first launch, larp asks for consent before initializing OpenObserve or making any request to the telemetry service. Declining does not disable or reduce any app feature. The choice can be changed later in **Settings → Optional telemetry**; revoking consent stops collection and asks the SDK to discard unsent data.

When enabled, larp reports technical events such as app and screen lifecycle, model download/readiness, permission outcomes, voice or text delivery type, reply latency, exercise/lesson creation, remix/import outcomes, exercise completion, performance, ANRs, handled errors, and crashes. Events may include coarse technical metadata such as Android/device type, app version, selected on-device model/backend, language tag, duration, and error type/stack trace.

larp deliberately does **not** send voice audio, transcripts, typed messages, AI replies, lesson or exercise content, learner answers, account identifiers, or advertising identifiers. Session replay and screen recording are not enabled. A strict attribute allowlist drops unknown fields before custom events are sent, and handled-error messages are removed before their technical stack is recorded.

## Building with telemetry

Telemetry configuration is not stored in this public repository and defaults to fully disabled. A source build with no configuration makes no telemetry request and does not show a meaningless consent prompt.

Provide the following values outside the checkout, for example in your user-level `~/.gradle/gradle.properties` or protected CI secrets:

```properties
LARP_TELEMETRY_CLIENT_TOKEN=<scoped OpenObserve RUM client token>
LARP_TELEMETRY_APPLICATION_ID=<OpenObserve RUM application id>
LARP_TELEMETRY_RUM_ENDPOINT=https://<host>/rum/v1/<organization>/rum
LARP_TELEMETRY_LOGS_ENDPOINT=https://<host>/rum/v1/<organization>/logs
```

Then build normally:

```bash
./gradlew :app:assembleRelease
```

Use a RUM client token restricted to frontend ingestion, never an administrator password, service-account token, or broadly privileged API key. Build-time values are omitted from Git, but any client credential compiled into an APK must still be treated as extractable and narrowly scoped.

The APK attached to official project releases is built with endpoints under `metrics.gemstud.io`. To send telemetry to another URL, build the application yourself with your own four values above; there is intentionally no runtime URL override in the distributed APK.
