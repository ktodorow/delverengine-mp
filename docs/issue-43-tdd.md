# Issue #43 — named Co-op Campaign Host setup

## Inspection and contract

Inspected live [#43](https://github.com/ktodorow/delverengine-mp/issues/43), parent [#40](https://github.com/ktodorow/delverengine-mp/issues/40), and project [#1](https://github.com/ktodorow/delverengine-mp/issues/1) before tests or implementation. Work uses existing `mp-v108-prototype` checkout. No parallel repository or agent gameplay launch. Owner subsequently authorized commit, push and issue closure after reviewing final automated results.

Product remains original v1.08 Delver experience shared by 2–4 Participants: one authoritative Host, existing native gameplay and presentation, each Participant's read-only Owned Game Copy, separate multiplayer profile, persistent private identities and Campaign Slots. Launcher changes cannot replace gameplay rules or mutate original game/save data.

#43 owns Host → Campaigns/New Campaign → one native setup form → Open Lobby. Form contains friendly Campaign Name, capacity 2/3/4, starting Lives 1–5 (default 3), typed Nickname, four existing Avatar choices, and port 1–65535 (default 37777). Campaign Name and Nickname are display values, independent of random private campaign ID and Launcher Identity. Open Lobby binds TCP/UDP without Start; bind errors keep fields editable. Nickname/Avatar defaults persist in existing profile and apply across roles. Static portraits must use gameplay drawable's resolved atlas, texture, fallback and tint, with selected portrait enlarged.

Friendly metadata must survive roster-only creation, authoritative saves, recovery, export/import, and migration. Older campaigns fall back to existing ID without replacing ownership or credentials. Native save cards/resume setup (#44), complete Connect form (#45), shared lobby/admission (#46), Ready (#47), and packaging remain separate.

## Approved test seams

#40 records owner confirmation: “Yes, use these seams.” Reuse existing public `DirectConnectSessionFlow`, real `DirectConnectHost`/`DirectConnectClient`, real temporary campaign/profile storage. Extend same flow with native New Campaign request; do not add substitute screen models. Native GL, mouse/keyboard/scaling and portrait appearance remain owner acceptance. Storage-boundary tests prove distinct migration/backup/corruption contracts. Owned-copy checks enabled on Windows.

## RED/GREEN evidence

1. **Named setup opens lobby, never gameplay.** RED: Windows `NamedCampaignSetupTest` failed compilation in 22s because public Host setup action and durable name were absent (`HostSetup`, `getCampaignName`). Test corrected to use existing `isStartingLivesLocked` observation rather than a nonexistent inspection method. Native form request validates display names/settings, generates private ID, opens actual listener, applies existing Lives rule and stores roster after successful bind. GREEN: 1 test passed, Windows build 50s (`green-01.log`).
2. **Shared presentation defaults and identity.** Test Host-selected presentation survives disconnect and can claim a real Client slot under same persisted Launcher Identity. RED: absent preferences API, then real behavioral failure (`LauncherPresentationStore.load()` returned initial default instead of Explorer/humanoid-3), Windows 49s. Corrected test cleanup to avoid initializing a null profile. Implementation saves chosen defaults atomically in existing profile after successful bind; ownership stays in existing identity/token stores. GREEN: 2 tests passed, Windows 49s (`green-02.log`).
3. **Unstarted Campaign settings survive closing lobby.** Test cold roster reload followed by reopening real Host retains chosen five Lives, capacity/name and original reconnect token. RED: reopened Host reported 3 rather than 5, Windows 32s. Host now initializes unstarted rules from durable roster metadata; actual Campaign Save still overrides them on resume. GREEN: 3 tests passed, Windows 42s (`green-03.log`).
4. **Authoritative metadata lifecycle.** Public name access initially exposes existing ID fallback. Test starts real synthetic Host/Client session, captures authoritative save, then checks disk reload, unclean recovery, export/import, Library and locked cold resume without replacing private ownership. RED: authoritative capture returned private UUID rather than typed friendly name, Windows 50s (`red-04.log`). Implementation adds validated Campaign Save format 8 name, retains it across history/scatter copies, captures from roster, and rebuilds imported roster name/Lives from authoritative save. GREEN: 4 tests passed, Windows 51s (`green-04.log`).
5. **Same-request saved retry.** Extend real lifecycle test to Retry after first Start and saved stop. Retry must preserve name/token and respect locked saved Lives. RED: `IllegalStateException` because setup reapplied locked Lives, Windows 29s (`red-05.log`). Retry now leaves restored, locked Lives authoritative. GREEN: 4 tests passed, Windows 43s (`green-05.log`).
6. **Backed-up predecessor migration.** Literal format 1 roster must migrate to format 2 with exact original backup, same ID/Identity/token/Avatar/capacity and default three Lives. Format 7 save from build 55 must migrate to named build 56/format 8 with ID fallback, exact backup and original native world/character/items. RED: missing roster backup and incompatible predecessor build, Windows 26s (`red-06.log`). Migration now backs up roster before replacing, retains save's existing backup path, and explicitly accepts known build 55/54/53 predecessor formats while still checking content hash. Build ID advances to 56; wire remains protocol 50. GREEN: 2 tests passed, Windows 45s (`green-06.log`).

Additional regression checks cover real UDP bind failure, form-release callback staying untouched, no orphan durable Campaign, unchanged defaults on failure, edited retry on same port, and bounded display/listener settings. These checks follow existing implementation; no artificial RED claimed. Historical save fixtures remove new trailing UTF name when reconstructing older layouts; existing corruption/backup assertions remain intact.

Native integration compiled and passed six setup/session regression checks in 46s (`native-integration.log`). First fresh full Windows run passed 657 core + 22 Desktop tests and source/release audits in 5m 7s. Native choice highlights then reused pressed assets/selected font color; fresh rerun passed same totals and audits in 5m 12s. Final native lifecycle review places backdrop cleanup at actual Campaign entry, since New setup deliberately retains backdrop through failed bind instead of releasing whole view beforehand. No additional synthetic RED or GL acceptance claimed for these view adaptations; final automated validation below includes them.

Next full run (`final-windows-validation-complete.log`, 4m 38s) had 1 failure among 657 core tests: existing #41 UDP-bind/retry fixture failed subsequent **TCP** bind. Fixture chose UDP-only ephemeral port and accepted any first bind failure, so TCP collision could masquerade as intended partial UDP-bind failure. Listener cleanup already awaits channel closure. Test fixture now reserves real available TCP/UDP pair, keeps UDP occupied, and explicitly requires UDP-stage error; no listener cleanup assertion removed. Same helper serves new setup tests, with additional real TCP-occupied error/edit/retry check. Focused confirmation passed 3 real bind checks in 27s (`bind-contract-green.log`). Final full rerun passed; fixture diagnosis does not claim native backdrop caused socket failure.

Baseline Windows build passed with seven cached tasks; no fresh baseline execution claimed.

## Final automated validation

Windows 11, existing shared checkout, real owned-copy checks enabled:

```text
gradlew.bat Dungeoneer:test DungeoneerDesktop:test DungeoneerDesktop:verifyOpenSourceDist --rerun-tasks --no-daemon --console=plain
BUILD SUCCESSFUL in 5m 5s
11 actionable tasks: 11 executed
Dungeoneer: 658 tests, 0 failures/errors/skips
DungeoneerDesktop: 22 tests, 0 failures/errors/skips
```

Evidence: `.scratch/issue-43/final-windows-validation-verified.log`, `.scratch/issue-43/final-results.json` and JUnit XML reports. Source asset audit, fork `game.jar` build, release artifact audit and open-source distribution audit passed. Git diff check passed; `Dungeoneer/assets` has no changes. Parallels UNC Git trust/filemode settings were process-scoped; repository configuration unchanged.

Owned `delver.jar` SHA-256 remains `a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae`. Final identifiers: build `mp-v108-prototype-named-campaigns-56`, protocol 50, Campaign Save format 8, roster format 2. Owner authorized publication on `mp-v108-prototype` and closure of #43; individual native visual/input/portrait and gameplay acceptance results remain unreported. Existing unrelated untracked files preserved.

## Owner Windows acceptance

Pending; automated checks do not certify native visuals or gameplay. Native integration uses existing `BaseScreen`, forest background, `UiSkin` pixel font/window/button assets and reusable application flow. Campaigns now has native New Campaign/Back actions and selectable scrolling rows; #44 still owns dedicated save cards/resume setup. New setup contains all Campaign/Host settings together. Native text style shares existing Party Chat button/font style; no replacement art/fonts. Enter opens Lobby; Escape/Back returns to Campaigns. Bind errors remain inline on same form.

`Campaign Name` accepts 1–64 Unicode code points, at most 256 UTF-8 bytes, trimmed, without control characters. Spaces, punctuation, slashes and brackets remain display metadata; only generated private UUID supplies new storage path. Nickname/Avatar retain existing `SlotPresentation` and Campaign Roster ownership rules. Last successful editable presentation persists atomically in `settings/multiplayer-presentation.properties` under selected multiplayer profile, independent of identity/reconnect files. Native Connect consumes same defaults; complete editable Connect form remains #45.

Owned v1.08 atlas definitions inspected read-only: no `tech_sprites` atlas. Each portrait creates actual `RemoteAvatar`, calls native drawable update, and uses resolved `DrawableSprite` atlas/index/tint. Native atlas fallback and out-of-range sprite fallback stay intact. Four existing choices retain gameplay mapping; UI labels Avatar 1–4 and does not promise four new humanoid skins. Selected portrait enlarges same resolved sprite. No owned assets added to repository.

Owner checks:

1. Native title → Host → Campaigns → New Campaign opens one form. Check original skin/forest, mouse, typed input/Tab, Enter, Escape, Back and small/wide/resized windows. Lives defaults 3; port defaults 37777. Capacity offers 2/3/4, Lives offers 1–5, Nickname stays editable.
2. Enter friendly name such as `Friday friends / dungeon [red]`, separate Host Nickname `Explorer`; select Avatar and rules. Check selected portrait enlarged and compare all four choices with actual remote gameplay sprites using owner's copy. Existing ownership rejects duplicate Nickname/Avatar; use distinct Client presentation.
3. Blank/oversized Campaign Name or Nickname, invalid port (blank/0/65536), or occupied TCP/UDP port shows useful error without losing typed settings. Edit port/name and retry; one successful Campaign created, no failed-attempt Campaign. Back cancels without opening listener.
4. Open Lobby waits; no gameplay before explicit existing Host Start. Connect Client, approve using existing Lobby controls, then Start. Native shared lobby/admission/Ready redesign remains #46–#47.
5. Close unstarted Lobby with Escape/confirmation; same Campaigns row retains name/capacity. Cold reopen retains chosen Lives and private ownership. After actual play, Save and Quit/restart retains name and locked saved Lives.
6. Reopen New form/restart same profile: last successful Nickname/Avatar appear and can be edited. Reuse same profile as Client of another Host: same defaults and existing Launcher Identity apply. For fresh Client native defaults, first choose distinct presentation in its New form, open temporary Lobby on another port, close and restart before Connect. #45 supplies direct Client editing later.
7. Existing recovery/export/import actions retain friendly name; imports require original Host Identity. Older profiles retain IDs/slots/tokens and create exact `roster.properties.before-format-2` / `campaign.save.before-format-8` backups when migrated. Never alter original archive or original single-player saves for testing.

Native Host launch, from Windows PowerShell:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:run -PprofileRoot=C:\DelverMpProfiles\Host "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

For first new-form play-test choose Host `Explorer` / Avatar 1 / port 37777, then use owner's Client command in separate window:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectClient -PsessionAddress=127.0.0.1 -PsessionPort=37777 -Pnickname=Friend -Pavatar=humanoid-2 -PprofileRoot=C:\DelverMpProfiles\Client "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

Original `runDirectHost` / `runDirectClient` tasks and explicit development flags remain supported. Full native menu needs ordinary `DungeoneerDesktop:run`; it keeps development tools disabled. No gameplay instances launched by agent.
