# Issue #42 — original startup and multiplayer menu

## Inspection and scope

Inspected GitHub #42, then parent #40 and project PRD #1 on 2026-10-08.
Work uses existing `mp-v108-prototype` checkout. #41 is closed and provides
reusable application/session lifecycle. No parallel checkout or retail classes.

Goal: ordinary fork entry validates Participant's compatible Owned Game Copy,
mounts approved data read-only, shows native Delver logo/animated forest/camera/
music/press-key transition, then offers Host / Connect / Options / Quit in same
window. Preserve native attribution; identify multiplayer/build. Reuse native
pixel font, textures, panels, buttons and game options. Default local profile
remains outside replaceable application directory; development tools disabled.

Host/Connect use existing library/session behavior in this slice. Named campaign
setup (#43), save cards (#44), remembered Connect form (#45), shared lobby/Ready
(#46–#48), management/profile and portable packaging remain later slices.
Project PRD requires original v1.08 gameplay with multiplayer adaptations,
Host authority, local private identity and independent multiplayer saves.

Current gaps found through CCE and known edit targets:

* `DesktopLaunchOptions`: supplying owned-copy path implicitly selects tutorial.
* `DesktopStarter`: ordinary no-argument entry does not validate/mount owned data.
* `OwnedGameCopyLauncher`: rejected selected archive aborts process; no retry.
* `SplashScreen`: native presentation exists but transitions to single-player
  save menu. That menu reads original-style save slots and is wrong entry here.
* `GameApplication`: #41 initializes renderer once and allows reusable requests.
* Existing Campaign Library / Direct Connect screens can serve temporary routes;
  later issues replace their development presentation.

## Agreed seams and expected outcomes

Parent #40 explicitly records owner approval: “Yes, use these seams.” Reuse
startup/owned-data boundary (`DesktopLaunchOptions`, owned validation/selection/
read-only mount) and existing application/session flow. Real temporary profiles
and actual owned archive exercise durable behavior. Only native UI/file chooser
and GL/audio/input remain system boundaries. No widget-tree assertions, private
method tests, fabricated per-screen flow models or commercial fixtures.

1. Owned-copy argument selects assets for ordinary menu; only explicit
   `--owned-tutorial` launches tutorial. Direct Host/Client and utilities retain
   existing command contracts.
2. Missing/moved/unsupported selections offer Browse/retry before native asset
   initialization. Cancel exits setup cleanly. Only validated copies are mounted
   and remembered. Original archive/install/save files stay unchanged.
3. Compatible owned title definitions, scene, logo, music and UI resources load
   through approved data boundary. No executable archive entries become assets.
4. Ordinary startup uses existing profile, disables development tools and opens
   multiplayer-only menu. Host/Connect routes use existing reusable requests;
   Back returns in same window. Native options return to multiplayer menu; Quit
   ends application.

## RED → GREEN evidence

Each slice: document expectation, write behavioral test, run Windows test and
capture failure, implement minimum behavior, rerun same test and record outcome.
Logs under `.scratch/issue-42/`; no gameplay instances launched by automation.

| Slice | Expected behavior | RED evidence | GREEN evidence |
| --- | --- | --- | --- |
| 1 | Owned-copy path selects menu assets, not implicit tutorial | `red-01.log`: 1 test, 1 assertion failure; implicit tutorial selected | `green-01.log`: Windows launch-options suite passes |
| 2 | Rejected explicit selection can Browse compatible copy before native startup; only valid copy remembered/mounted | `red-02.log`: missing operation; `red-02-behavior-fixed.log`: validation aborts instead of offering Browse (1 failing test) | `green-02.log`: retry mounts and remembers real compatible owned copy |
| 3 | Remembered copy bypasses Browse; original title/forest/logo/music/pixel UI data accessible read-only, retail classes excluded | `red-03.log`: 2 tests, 1 assertion failure; remembered copy asked Browse | `green-03-corrected.log`: 2 tests pass against real owned archive |
| 4 | Production preparation mounts assets before native startup, keeps profile identity, disables debug/dev tools, opens multiplayer entry without listener | `red-04.log`: missing entry operations; `red-04-behavior.log`: legacy preparation retains development tools (1 assertion failure) | `green-04.log`: startup/selection/options suites pass; native screens compile on Windows |
| 5 | Browse opens selection even with compatible supplied path; Cancel creates no mount/remembered selection; explicit tutorial retained | `red-05.log`: 16 tests, 2 failures; Browse still implied tutorial and skipped chooser | `green-05.log`: focused startup/options/selection suites pass |
| 6 | Separating copy selection from tutorial preserves rejection of conflicting entry/utility modes | `red-06.log`: 14 tests, 1 failure; Direct Host incorrectly accepted ordinary Browse | `green-06.log`: full Windows Desktop suite, 22 tests pass, zero failures/errors/skips |

Slice 2 fixture initially called `getRoot()` before profile initialization;
`red-02-behavior.log` captures that fixture failure and is not behavior evidence.
Fixed fixture, reran delegating scaffold, confirmed real rejected-copy failure
before implementing retry.

First slice 3 GREEN attempt (`green-03.log`) exposed incorrect test assumption:
owned JSON is deliberately outside mount whitelist. Native engine supplies
`ui/skin.json` styles; compatible owned font/texture/panel data supply appearance.
Corrected test to check `ui/pixel.fnt`, `ui/pixel.png`, `ui/skin.png` and
`ui/window.png`; whitelist/content fingerprint stay unchanged.

Post-slice code check added disposal of native menu Stage when entering existing
Connect/session screen. GL resource/input transitions remain owner acceptance;
startup tests do not assert private widget state or fake graphics disposal.

First full run (`final-windows-validation.log`) passed 647 core + 22 Desktop
tests, zero failures/errors/skips, then stopped at Windows Git's UNC ownership
guard in source asset audit. Rerun uses process-scoped `safe.directory` for this
exact shared checkout via `.scratch/issue-42/windows-validation.cmd`; no persistent
Git trust configuration changed.

Final code rerun (`final-windows-validation-fixed.log`) passes 647 core + 22
Desktop tests with zero failures/errors/skips. Source asset audit then reports
Windows UNC execute-bit differences (`100755 => 100644`), confirmed by
`windows-git-asset-diff.log`; macOS asset diff is empty. Separate audit rerun uses
process-scoped `core.filemode=false` plus exact-checkout trust. Tracked asset
bytes/list and release content checks remain enabled; no asset or repository
configuration changed. Full test XML totals saved in `final-results.json`.

## Final automated validation

* Windows 11 Parallels: 647 core + 22 Desktop tests, zero failures/errors/skips;
  actual compatible owned archive enabled through `OWNED_GAME_COPY_TEST`.
* `final-windows-asset-validation.log`: `verifyOpenSourceAssets`, `dist`,
  `verifyReleaseArtifacts` and `verifyOpenSourceDist` pass (22 seconds).
  Release audit checks fork-built `DungeoneerDesktop/build/libs/game.jar`.
* `git diff --check` passes; tracked asset diff is empty. Existing branch and
  checkout retained. No new repository/worktree or agent gameplay launches.
* Original `delver.jar` SHA-256 remains
  `a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae`.
  Archive used read-only; original installation/saves untouched. No retail
  classes executed or commercial assets copied into repository/release.

Native title/menu visual, audio, input, scaling and same-window navigation still
need owner gameplay acceptance below. Tests prove startup/data/profile contracts;
compilation and source review do not certify native presentation.

Owner instructed commit, push and issue closure on 2026-10-08 after final
automated validation. Publication uses existing `mp-v108-prototype` branch;
visual/audio/menu acceptance remains unreported and checklist stays pending.

## Owner Windows acceptance — pending

Automated checks cannot certify visual/audio/input experience. Owner checks:
ordinary launch; first-run/moved/unsupported copy Browse/retry/cancel; original
logo, animated forest/camera, title music and press-key transition; Host/Connect/
Options/Quit only; native attribution plus multiplayer build; mouse/keyboard
navigation and small/wide/resized windows; native options save and Back; Host
library and Connect existing routes, Back/retry in same window; original owned
installation/saves unchanged. Existing development commands remain available.

## Native-menu launch commands

Run in Windows PowerShell. These enter native title/menu with separate existing
Participant profiles. Ordinary startup ignores development flags. With no
`profileRoot`, normal per-user multiplayer profile is used; remembered compatible
copy then permits launching without `ownedCopy`.

Host profile:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:run -PprofileRoot=C:\DelverMpProfiles\Host "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

Client profile, separate PowerShell window:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:run -PprofileRoot=C:\DelverMpProfiles\Client "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

After press-key, Host opens existing Campaign Library; Escape returns to native
menu. Existing library controls create/resume campaign. Connect prompts for Host
address (`127.0.0.1` for same Windows VM); current route uses port `37777`, capacity
2 and default Host/Participant avatars. Dedicated presentation/campaign forms
remain #43–#45. Existing supplied `runDirectHost` / `runDirectClient` commands
continue to bypass title/menu for direct gameplay testing.

For missing-copy check, use temporary nonexistent `ownedCopy` path; Browse/Retry/
Quit must appear before title. Test unsupported archive with separate fixture,
not by altering original archive. Check native options, Back, Quit, mouse/Up/Down/
Enter and window resize. Do not infer visual/audio acceptance from test totals.
