# Builds and releases

`ci.yml` runs `./gradlew test` plus the headless self-check on every push and PR — no hardware, no
libusb. `build.yml` runs on `v*` tags and manual dispatch, packaging macOS arm64 `.dmg`, Windows
x64 `.exe`, and Linux x64/arm64 `.deb` and `.AppImage` files, then attaching them to a GitHub
Release. jpackage can't cross-compile, so each package is built on its own runner; Windows wraps the
jpackage app image with Inno Setup (`installer/windows/EpsonReset.iss`), while Linux wraps the same
app image in an AppDir and runs `appimagetool` (`scripts/package-appimage.sh`).
Assets get version-less names so `releases/latest/download/<name>` links stay valid.

The app icon is authored as [`docs/images/logo.svg`](images/logo.svg). Generated platform assets
live under `src/main/icons/`, with the shared Linux/window PNG in
`src/main/composeResources/drawable/icon.png`.

An installer for the machine you're on:

```bash
./gradlew packageDistributionForCurrentOS
```

On Linux, build the portable AppImage after creating the Compose app image:

```bash
./gradlew createDistributable
./scripts/package-appimage.sh "$(uname -m)" 1.0.0
```

The script accepts `x86_64` and `aarch64`, downloads the matching official `appimagetool`, verifies
its pinned SHA-256 checksum, and writes the result under `build/appimage/`.

Tag and push to cut a release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

`APP_VERSION` is the tag with the `v` stripped; untagged runs build `1.0.0`.

That produces a **draft** release, not a public one. The six packages are built and attached,
and nothing else happens until you press Publish — which is when the release notes get written.
GitHub's auto-generated notes are assembled from merged pull requests, and the work here lands as
direct commits to `main`, so what it generates is close to empty; treat it as a placeholder to
replace, not as the notes.

Until the draft is published it is not the latest release, which means
`releases/latest/download/<name>` links and the in-app update check both keep resolving to the
previous version. Nobody is offered a half-finished release.

Two fields, and they are not the same one:

- **Body** — what the releases page shows. Editable at any time afterwards, and editing it does not
  re-notify watchers.
- **Title** — what the app shows in its update prompt, because `UpdateCheck` reads the release's
  `name`. `build.yml` sets no `name:`, so it defaults to the tag (`v1.0.0`). Worth changing to
  something readable while the draft is open.

## Versioning

Tags are `vMAJOR.MINOR.PATCH` ([semver](https://semver.org)), and the tag is the only place a
version is written by hand — the Gradle `version`, the installer's `packageVersion` and the
`app-version.properties` the running app reads all derive from it.

- **Patch** — bug fixes, new or corrected entries in the printer database, doc changes.
- **Minor** — new features, new hardware support, anything additive to the UI.
- **Major** — a redesign, a dropped platform, or a preferences/backup format an older build can't
  read.

Pre-releases are `v1.2.0-rc1`; `UpdateCheck.compare` orders them below the matching final release,
so an rc never offers itself as an upgrade to someone already on it.

Two shapes the tag can't take, both enforced by Compose at configuration time rather than at
packaging time — a bad tag fails the build immediately, it doesn't produce a broken installer:

- **`MAJOR` must be greater than zero.** The `.dmg` carries the version as `CFBundleVersion`, which
  has no concept of a `0.x` development series. There is no `v0.9.0`; the first release is
  `v1.0.0`.
- **The installer gets the numeric core only.** `-rc1` and `+sha` are stripped before the version
  reaches `packageVersion` (see `installerVersion` in `build.gradle.kts`), so a `v1.2.0-rc1` build
  installs as `1.2.0`. The full tag still reaches the app through `app-version.properties`, which
  is what the update check actually compares — an rc knows it's an rc even though its installer
  doesn't.

Release notes are generated from commits and PRs by `generate_release_notes` in `build.yml`; there
is no hand-maintained changelog. macOS builds are
signed and notarized only if the secrets exist (`MACOS_CERTIFICATE_P12`,
`MACOS_CERTIFICATE_PASSWORD`, `MACOS_SIGN_IDENTITY`, `APPLE_ID`, `APPLE_APP_PASSWORD`,
`APPLE_TEAM_ID`); without them the job produces an unsigned `.dmg` needing right-click → Open on
first launch.

`sync-printer-data.yml` is the third workflow — monthly or on demand, it regenerates the bundled
printer data and opens a PR. See [Counter database](counter-database.md#resyncing).
