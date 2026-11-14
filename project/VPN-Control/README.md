# VPN Control 0.4.3

Android VPN control application project.

## Build with GitHub Actions

The workflow installs Gradle 8.13 explicitly (rather than using the runner's
preinstalled Gradle), verifies the version, builds the debug APK, and uploads
`VPN-Control-0.4.3-debug` as an artifact.

See `BUILD-FIX-RU.txt` for Russian build instructions and the explanation of
the Gradle compatibility fix.
