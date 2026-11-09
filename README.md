# SFA Smart

Personal Android fork of SFA, using the `smart` branch of
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
- Family-server updates are the default. A successful HTTPS remote-profile fetch
  discovers `X-SFA-Smart-Update` against the final HTTPS response URL and pins the
  first accepted origin. Other origins cannot replace that pin. Settings → Updates
  shows the pinned origin and can forget it without hiding an already pending update.
- The daily check downloads immutable, size/hash/package/version/signer-verified
  APKs on unmetered networks, or any connected network after 48 hours. **Download
  now** overrides the network wait. A persistent in-app banner, a daily reminder,
  an app-update notification, and the VPN notification expose a pending update.
  Notification denial or channel changes do not remove the in-app fallback.
- Installation is user-coordinated and never stops the VPN first. Unknown-app
  permission and Android confirmation are handled visibly. Canceling verification
  or staging abandons the uncommitted installation; once commit starts, Android
  controls completion. A paused coordinator retains confirmation and failure results
  for its next foreground visit. Pending updates clear only after the installed
  version catches up. A stale or broken manifest cannot suppress them. Background
  checks can be disabled.
- The GitHub self-updater remains selectable and uses
  [`flamesman51/sing-box-for-android` releases](https://github.com/flamesman51/sing-box-for-android/releases),
  the `other` arm64 APK, and `SFA-version-metadata.json`.

## Smart selector

Profiles can use the core's `smart` outbound group (see the core's
`docs/configuration/outbound/smart.md`); `urltest` profiles behave as before.

- **Settings → Smart**: country for the built-in direct-connectivity checks (automatic
  from network, SIM, timezone, then locale; never IP lookup), an optional per-network
  history helper (precise and background location, phone state; everything works
  without them, history just stays coarser), and the "all servers unreachable"
  notification toggle.
- **Selector insights**, from a Smart group's card or Tools: selected path and state,
  network and direct connectivity, member table, speed-test budget, per-network
  history, recent events and 24 h charts. It polls the running core every 2 s only
  while visible.
- **Export / import / reset**: exports are redacted by default; a raw export includes
  network names and cell identifiers and needs explicit confirmation. Imports (up to
  2 MiB) add bounded priors weighted by how similar the source phone is (same model and
  carrier 0.5, same carrier 0.25, else 0.1) and show what was applied. Reset clears
  learned history of the shown group only.

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
`KEYSTORE_FILE=/path/to/release.keystore`.
No signing key is tracked by this fork.

`.github/workflows/build.yml` checks out this app revision and
`flamesman51/sing-box-extended@smart`, builds arm64 AARs and signed `other` APKs,
runs JVM tests, and uploads the arm64 APK with update metadata. Its secrets are
`KEYSTORE_BASE64` (keystore) and `LOCAL_PROPERTIES` (signing properties).
Non-tag builds append `-smart.N` using the workflow run number; tag `v*` builds use
the tag without `v` and publish a GitHub release with the core commit in its notes.
Version codes use `(VERSION_CODE + run_number) * 10 + min(run_attempt, 9)`.
Release runs are serialized. Only arm64 APKs are published because the nominal
universal APK contains arm64-only libbox.

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
