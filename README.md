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
- Live notification speed updates default to off. App-update notifications use a
  separate channel and request notification permission when server auto-updates
  are enabled, independently of the live speed setting.
- User and system Always-on starts persist the intent to run. Sticky process-death
  recovery and boot/app-update recovery resume only when that intent was already
  set. Explicit stop, VPN permission revocation, and fatal startup/configuration
  errors clear it. Android force-stop and system policy can still prevent recovery;
  configure Always-on VPN in Android Settings for system-managed continuity.
  App replacement bypasses the boot-only unread-crash gate and explicitly requests
  recovery, including while the screen is locked. Service starts enter the foreground
  immediately, wait for core setup, and journal every start decision and failure.
- Profile TUN `include_package` / `exclude_package` lists work without enabling
  **Profile Override → Per-App Proxy**. When enabled, that switch replaces both
  profile lists with the app's selected mode and list, including Managed Mode.
  Without an app override, conflicting profile lists use include mode and log a
  warning. Only one Android allow/deny list is ever applied. Include mode always
  includes this VPN app, even with an empty selection; exclude mode never excludes it.
- Managed Mode can bypass Chinese apps, Russian apps, or both. Existing installs
  retain China-only behavior. Region changes are published only with their completed
  scan result; leaving the screen cancels the change without replacing stored
  regions or lists. UI scans and package-change rescans share one process-wide lock.
  The app selector's **Scan** menu offers both groups separately.
  Russian detection combines `app/src/main/assets/ru-apps.json` (413 exact package
  names verified against store listings on 2026-11-04) with specific AppMetrica,
  VK ID/VK SDK, MyTracker, RuStore, and Sber ID component/DEX namespaces.
  It does not infer Russian apps from generic `ru.*`, Yandex, or VK prefixes, DEX
  size, or the absence of Firebase. SDK detection can still match international
  apps using those SDKs; manual per-app selection remains available.
  APK scanning is limited to two open archives at a time, with each DEX parse capped
  at 15 MB. Russian detection in larger files relies on exact-package and
  manifest-component checks.
- Remote-profile mutations reconfigure WorkManager, including imports, interval-only
  edits, and deletion. WorkManager is inexact. “Last updated” records the last
  successful check, including an unchanged download, and controls update cadence.
  Background update errors are retained and shown in the profile editor.
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
  for its next foreground visit. Restored unsealed sessions rewrite and sync the
  complete verified APK before committing. Pending updates clear only after the
  installed version catches up. A stale or broken manifest cannot suppress them.
  Background checks can be disabled.
- The GitHub self-updater remains selectable and uses
  [`flamesman51/sing-box-for-android` releases](https://github.com/flamesman51/sing-box-for-android/releases),
  the `other` arm64 APK, and `SFA-version-metadata.json`.

## Diagnostics and privacy

**Settings → App → Send diagnostics to the profile server** is on by default.
Turn it off to cancel scheduled uploads; the bounded local journal remains available.
Diagnostics are sent only for the currently selected remote profile, and only after
an accepted profile fetch returns the exact header `X-SFA-Smart-Report: 1` from a
final HTTPS response. The destination is that final response URL, stored separately
for each profile. A successful HTTP fetch without that exact header or with a
non-HTTPS final response clears its capability, even if configuration validation
then fails. A change to the profile URL or profile deletion also clears it.
Changing the URL back does not restore it; another accepted fetch must opt in.
Imported configurations cannot
enable reporting without a qualifying fetch. Report uploads use HTTPS and never
follow redirects. The profile URL is a credential: it is used for transport, never
included in a report.

Reports contain app/core versions, Android SDK and device model, VPN and update
events with fixed categories and numbers, and selector health/selection/throughput/
budgets. Failures and crashes include an exception class or core error type and a
fixed category, never an exception or panic message. Stack traces contain only
strictly validated JVM frames or the core's scrubbed Go function/file pairs.
Reports never contain configuration files, private keys, raw log tails, browsing
or connection destinations, DNS names, Wi-Fi names, BSSIDs, or network identifiers.
The server receiving a report can still see the connection's source address.

WorkManager requests a network-constrained upload every six hours and an expedited
one-off after a new crash/OOM report, VPN startup failure, or installed update.
Android may delay either request. Startup schedules a best-effort background scan
after core setup archives native crashes; diagnostics never gate VPN startup.
Failed scans retry on the next upload, which refreshes discovery before preparing
its batch. Report timestamps use independent formatters so background discovery
cannot corrupt the boot crash-safety scan. Installed-version preferences use a
background synchronous commit, never a lifecycle-drained `apply()` write.
The installed-update event waits for that version result independently of VPN
recovery, retaining the profile/source captured when the receiver ran.
Local events are persisted even for local profiles or profiles without reporting
capability, with a hashed profile/source association rather than the profile URL.
Those records become uploadable only if that same profile with the same source later
gains reporting capability; a different profile or source cannot claim them.
The local journal is capped at 1,000 entries and 512 KiB.
Each schema-1 JSON upload is at most 768 KiB of UTF-8, including at most
five crashes and 256 KiB of events. Selector snapshots are capped at four groups,
32 members and four recent panics per group, with 2 KiB of frames per panic; each
snapshot remains whole. Pending queues belong to each profile destination and keep
their report IDs across retries and profile switches. Successful uploads acknowledge
only their own records. A server's 413 response divides events and crashes, placing
the entire selector snapshot in exactly one fragment; transient failures back off.

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
- Smart group cards and Selector insights show a group-level notice when no member
  is healthy. An explicit offline state instead says that the network seems down.
  The notices use existing refreshes and clear when a healthy member returns.
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
