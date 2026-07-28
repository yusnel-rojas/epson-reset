# Builds and releases

`ci.yml` runs `./gradlew test` plus the headless self-check on every push and PR — no hardware, no
libusb. `build.yml` runs on `v*` tags and manual dispatch, packaging macOS arm64 `.dmg`, Windows
x64 `.exe` and Linux x64/arm64 `.deb` and attaching them to a GitHub Release. jpackage can't
cross-compile, so each installer is built on its own runner; Windows wraps the jpackage app image
with Inno Setup (`installer/windows/EpsonReset.iss`).
Assets get version-less names so `releases/latest/download/<name>` links stay valid.

An installer for the machine you're on:

```bash
./gradlew packageDistributionForCurrentOS
```

Tag and push to cut a release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

`APP_VERSION` is the tag with the `v` stripped; untagged runs build `1.0.0`. macOS builds are
signed and notarized only if the secrets exist (`MACOS_CERTIFICATE_P12`,
`MACOS_CERTIFICATE_PASSWORD`, `MACOS_SIGN_IDENTITY`, `APPLE_ID`, `APPLE_APP_PASSWORD`,
`APPLE_TEAM_ID`); without them the job produces an unsigned `.dmg` needing right-click → Open on
first launch.

`sync-printer-data.yml` is the third workflow — monthly or on demand, it regenerates the bundled
printer data and opens a PR. See [Counter database](counter-database.md#resyncing).
