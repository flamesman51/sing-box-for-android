# SFA Smart

Personal Android fork of SFA for Pixel 8a, using the `smart` branch of
[`flamesman51/sing-box-extended`](https://github.com/flamesman51/sing-box-extended).
The application ID is `io.github.flamesman51.sfa`; the Kotlin namespace remains
`io.nekohasekai.sfa`. It installs alongside the stock SFA/SFA-extended app with
separate profiles, settings, providers, notification channels, and Quick Settings tile.

Both apps handle `sing-box://import-remote-profile` links and profile files.
Android chooses the saved default handler or offers a chooser; select **SFA Smart**
when importing into this fork. Only one VPN app can be active in an Android user profile.

## App behavior

- Android backup is disabled to avoid backup-framework termination of the VPN.
  Export important profiles deliberately; exports may contain secrets.
- User and system Always-on starts persist the intent to run. Sticky process-death
  recovery and boot/app-update recovery resume only when that intent was already
  set. Explicit stop, VPN permission revocation, and fatal startup/configuration
  errors clear it. Android force-stop and system policy can still prevent recovery;
  configure Always-on VPN in Android Settings for system-managed continuity.
- Remote-profile mutations reconfigure WorkManager, including imports, interval-only
  edits, and deletion. WorkManager is inexact. “Last updated” records a content
  change, not an unchanged download; the last successful check controls update
  cadence. Background update errors are retained and shown in the profile editor.
- The GitHub self-updater uses
  [`flamesman51/sing-box-for-android` releases](https://github.com/flamesman51/sing-box-for-android/releases),
  the `other` arm64 APK (or universal fallback), and `SFA-version-metadata.json`.
  It does not offer these arm64-only builds to non-arm64 devices.

## Building and signing

Use JDK 17, Android SDK platform 37.1, build-tools 36.0.0, NDK
28.0.13004108 (r28), and matching `app/libs/libbox.aar`.
With the core in a sibling checkout, build the AAR using Go 1.26.8:

```sh
make lib_install
go run ./cmd/internal/build_libbox -target android -platform android/arm64
```

Copy the generated AARs to this repository's `app/libs/` if the core builder did
not copy them automatically. In this repository:

```sh
./gradlew :app:compileOtherReleaseKotlin :app:testOtherDebugUnitTest
./gradlew :app:assembleOtherRelease
```

The release signing properties are `KEYSTORE_FILE`, `KEYSTORE_PASS`, `ALIAS_NAME`,
and `ALIAS_PASS`, loaded from gitignored `local.properties` or base64-encoded
`LOCAL_PROPERTIES`. `KEYSTORE_FILE` accepts an absolute path or a path relative
to `app/`; it defaults to `release.keystore`. Use your own signing key, for example
`KEYSTORE_FILE=/home/senpo/.local/share/sfa-smart/release.keystore`.
No signing key is tracked by this fork.

`.github/workflows/build.yml` checks out this app revision and
`flamesman51/sing-box-extended@smart`, builds arm64 AARs and signed `other` APKs,
runs JVM tests, and uploads arm64/universal APKs with update metadata. Its secrets
are `KEYSTORE_BASE64` (keystore) and `LOCAL_PROPERTIES` (signing properties).
Non-tag builds append `-smart.N` using the workflow run number; tag `v*` builds use
the tag without `v` and publish a GitHub release. Version codes are the checked-in
base code plus the workflow run number. The universal APK contains only arm64 core
code because this workflow builds only `android/arm64`.

## Documentation

https://sing-box.sagernet.org/installation/clients/sfa/

## License

```
Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.

In addition, no derivative work may use the name or imply association
with this application without prior consent.
```

Under the license, that forks of the app are not allowed to be listed on F-Droid or other app stores
under the original name.
