# Issue #48 — Lobby presentation edits

## Contract inspected before tests

Sources: [#48](https://github.com/ktodorow/delverengine-mp/issues/48), complete launcher PRD [#40](https://github.com/ktodorow/delverengine-mp/issues/40), complete project PRD [#1](https://github.com/ktodorow/delverengine-mp/issues/1), current domain glossary and existing #47 Ready implementation.

Participants edit typed Nickname and existing static Avatar inside shared pregame lobby. Host validates trimmed 1–32 Unicode code points, at most 64 UTF-8 bytes, no control characters, case-insensitive campaign uniqueness and existing Avatar availability, including absent reserved slots. Accepted changes publish authoritative cards and clear only editor's Ready. Rejection explains conflict while retaining prior presentation and valid Ready. Identical presentation is no change. Identity, token, slot number, character and private campaign ID remain intact. Accepted choices become editable local defaults; reconnect/resume restores campaign presentation independently.

Original v1.08 engine/native presentation and each participant's read-only owned data remain baseline. This task adds no gameplay policy, skins or identity scheme.

## Already confirmed seams

Parent #40 explicitly records owner confirmation: “Yes, use these seams.” Tests use production `DirectConnectSessionFlow` action/session boundary, actual TCP/UDP Host/Client, temporary campaign/profile storage and narrow bounded reliable-wire boundary. Existing roster/persistence tests provide prior art. Native widget appearance, keyboard/mouse entry, resolved portraits/tints and gameplay acceptance belong to owner Windows play-test. No substitute UI model or private-method assertions.

## RED/GREEN log

Each slice records expected outcome before implementation, Windows RED evidence, minimal change and Windows GREEN evidence. Raw logs and retained test XML live in existing checkout under `.scratch/issue-48/`.

### 1 — Host edit through launcher action

Expected: editing Host while both Participants Ready updates shared cards and durable roster; only Host consent clears. Campaign identity, slot ownership and token stay identical; listener remains open. Test added before production edit API.

RED: Windows test compilation fails because production `editPresentation` action is absent (1 missing-symbol error, 24s; `.scratch/issue-48/red-01.log`).

GREEN: production Host action validates, saves roster and publishes shared projection. Windows 1 test passed, 51s (`green-01.log`, `green-01.xml`).

### 2 — Client edit through actual TCP/session

Expected: Client edits own presentation without leaving; every card reflects accepted choice, only Client consent clears, saved identity/token stay identical and gameplay Avatar/Nickname use edited choice after explicit Ready/Start.

RED: real-session test fails at Client edit with `Presentation editing is unavailable` (2 tests, 1 failure, 30s; `red-02.log`, `red-02.xml`).

GREEN: existing framed wire carries own-slot edits; Host validates, saves and updates canonical connection slot before projection. Windows 2 tests passed, 51s (`green-02.log`, `green-02.xml`), including edited gameplay descriptor.

### 3 — Explained rejection preserves valid state

Expected: Host/Client get correlated result for each edit. Invalid bounds/control characters/UTF-8, case-insensitive occupied or absent-reserved nickname and unavailable Avatar reject without changing authoritative presentation, saved roster, valid consent or connection. Accepted responses carry authoritative presentation.

RED: Windows compilation fails because correlated result type/getter are absent (5 errors, 22s; `red-03.log`).

GREEN: correlated Host decision/result implemented. Windows 3 tests passed, 52s (`green-03-fixed.log`, `green-03.xml`). Initial implementation compile failed from missing `SlotPresentation` import (36s, `green-03.log`); import fixed before passing run. Not behavior RED.

### 4 — Reliable presentation authority

Expected: edits/results round-trip through small bounded reliable TCP frames. Neither can encode or decode through UDP. Independent literal request/result assertions include authoritative prior presentation on rejection.

RED: Windows runtime fails because UDP encoder accepts edit authority (8 tests, 1 failure, 31s; `red-04.log`, `red-04.xml`).

GREEN: both UDP encode/decode gates reject edit/result authority. Windows all 8 lobby-wire checks passed, 43s (`green-04.log`, `green-04.xml`).

### 5 — Accepted defaults, immutable identity and authoritative return

Expected: only accepted correlated Host result updates editable local defaults. Conflict retains prior defaults. Fresh flow reloads preferences; profile identity/token unchanged. Reconnect using changed provisional defaults restores edited campaign presentation. Stale peer result cannot write preferences; identical accepted edit retains valid consent.

RED: Windows compilation fails because current-result preference action is absent (3 missing-symbol errors, 23s; `red-05.log`).

GREEN: current-peer result action persists accepted presentation only. Windows 4 launcher/session tests passed, 52s (`green-05.log`, `green-05.xml`); preferences/identity/token and reconnect/no-op checks included.

### 6 — Presentation replay and stale Ready

Expected: actual authenticated raw TCP participant cannot replay older edit over newer accepted choice or invalidate newer consent. Pre-edit Ready sequence cannot reconfirm changed choice.

RED: Windows runtime fails because request 1 replaces newer request 2 (4 authority tests, 1 failure, 31s; `red-06.log`, `red-06.xml`).

GREEN: own current connection tracks monotonic edit request ID. Windows 4 authority tests passed, 53s (`green-06.log`, `green-06.xml`).

### 7 — Atomic persistence failure

Expected: real filesystem refusing replacement returns explained rejection for Host and Client. Prior live presentation, both Ready states, connection, identity/token and stored roster survive. Test obstructs actual roster path; no mocked store or internal method assertion.

RED: Windows runtime fails at Host edit with `IllegalStateException` caused by `AccessDeniedException` (5 session tests, 1 failure, 31s; `red-07.log`, `red-07.xml`).

GREEN: store persists validated candidate before changing live roster. Failure returns bounded rejection with prior choice; Windows 5 session tests passed, 51s (`green-07.log`, `green-07.xml`).

### 8 — Save compatibility across presentation wire upgrade

Expected: new build/protocol reads explicit predecessor build 58/protocol 52, retains named campaign, ownership, character and native world. Reading leaves original file unchanged; next save retains exact predecessor backup and unchanged gameplay body bytes. Content mismatch still rejects.

RED: Windows runtime refuses predecessor save as incompatible (20 save tests, 1 failure, 31s; `red-08.log`, `red-08.xml`).

GREEN: explicit predecessor migration and build 59/protocol 53 added. Save format 8 and roster format 2 retained; Windows 20 save tests passed, 48s (`green-08.log`, `green-08.xml`).

## Native adapter and regression checks

Native same-Stage modal added over passing production edit/result action. Existing field/choice styles and original-game portrait/tint resolver reused. Pending decision disables form; conflict retains draft and valid authoritative card; accepted decision closes form. Editor consumes Space/Enter/Esc independently from lobby Ready/Start/Leave. Mouse plus Up/Down Avatar and typed-name keyboard actions included.

Additional real-session regressions cover unauthenticated/unsynchronized/cross-slot/wrong-token/old-session/previous-connection and active-game edits; cold resume retains Downed health/Lives, gold, held Orb and inventory, while next resume restores edited choice. Bounded malformed-wire regression checks IDs/slots/flags/UTF-8/truncation/trailing bytes. These check existing safeguards; no fabricated RED required for already passing behavior.

Initial native/regression build compiled core and Desktop successfully; test compilation failed from missing `assertNotNull` static import (56s; `regressions-01.log`). Import fixed; not behavior RED. First regression runtime: 40 tests, 2 failures, 33s (`regressions-02.log`, retained XML). Test harness omitted required post-edit Client Ready; corrected to assert blocked Start and explicitly reconfirm. Malformed nickname mutation placed newline at beginning, which trims legally; corrected mutation to embedded newline. Production behavior matched contract; neither failure is counted as implementation RED. Corrected Windows regression run passed all 40 checks, 33s (`regressions-03.log`, retained XML). Core/Desktop compilation passed.

## Review

Separate Standards and Spec reviews against task starting commit `a93c0d32917a6e57340744a884abea3f80467ec1`: zero material findings in either axis. Read-only review covered task working changes/new files; unrelated pre-existing untracked files excluded. Native acceptance remains owner step.

## Final Windows validation

Fresh full Windows validation passed: **708 core + 22 Desktop tests**, zero failures/errors/skips. All 11 compile/test/package/source/release/isolated-runtime tasks executed, **6m18s**. `git diff --check` passed. All **709 source/test/build input hashes** match pre-run manifest. Original owned `delver.jar` SHA256 unchanged.

Retained evidence: `.scratch/issue-48/final-windows-validation.log`, `.scratch/issue-48/final-TEST-*.xml`, `.scratch/issue-48/final-results.json`. Fork artifact: `DungeoneerDesktop/build/libs/game.jar`, SHA256 `2fa5c9006f6f42581009afc81e20b12fe6de2253a1be1833481764806769ebb7`. Build `mp-v108-prototype-lobby-presentation-59`, protocol 53, Campaign Save format 8, roster format 2. Predecessor builds resume through explicit migration; content mismatch still rejected.

Owner explicitly authorized commit, push and closure of issue #48 when all tests green. Publication uses verified passing source/test/build hashes on existing `mp-v108-prototype` branch, from baseline `a93c0d32917a6e57340744a884abea3f80467ec1`. Unrelated pre-existing files preserved; individual native acceptance results remain unreported. Command:

```cmd
gradlew.bat Dungeoneer:test DungeoneerDesktop:test DungeoneerDesktop:verifyOpenSourceDist --rerun-tasks --no-daemon --console=plain
```

`OWNED_GAME_COPY_TEST` points to original owned `delver.jar`; per-command Git configuration permits existing Parallels UNC checkout and its executable/line-ending metadata. No global Git configuration changed. Source/test/build hashes captured before run in `.scratch/issue-48/final-validation-sources.json`.

## Owner acceptance

Pending. Automated tests cannot certify native rendering/input/gameplay. Existing branch/check-out retained. Owner authorized publication and issue closure after passing tests; individual native acceptance remains unreported.

Start two native windows from Windows CMD, using separate persistent Launcher Identity profiles:

```cmd
"\\Mac\Home\Repos\delverengine-mp\.scratch\issue-48\playtest-host.cmd"
"\\Mac\Home\Repos\delverengine-mp\.scratch\issue-48\playtest-client.cmd"
```

Scripts launch native menu through existing checkout with original owned copy read-only. Host profile: `C:\DelverMpProfiles\Host`; Client profile: `C:\DelverMpProfiles\Client`. Choose Host campaign capacity 4 and connect Client to `127.0.0.1:37777`. Launcher defaults stay editable; campaign owns saved presentation.

- [ ] Native lobby `Edit player (E)` opens typed Nickname and four matching static portraits without changing window/session. Check actual textures, tint, selected preview and visibility at normal Windows resolution.
- [ ] Type name containing spaces; Space never toggles Ready while editor open. Up/Down chooses Avatar; Enter applies edit; Esc cancels editor and retains connection. Mouse controls perform same actions.
- [ ] With both players Ready, change Host name/Avatar. Both cards update; only Host becomes Not ready. Explicitly reconfirm Host. Repeat Client edit; only Client becomes Not ready. Host Start remains blocked until Client reconfirms.
- [ ] While both Ready, propose other player's name in different letter case or Avatar. Conflict explains reason, draft remains editable, authoritative card and both Ready states remain unchanged.
- [ ] Claim then leave third slot using separate profile; nickname and Avatar remain reserved. Editor rejects those reserved choices. Claimed/reserved/empty counts stay honest.
- [ ] Try empty, overly long, Unicode and control-character pasted nicknames. Host enforces bounds with useful rejection; session remains connected. Identical trimmed choice preserves valid Ready.
- [ ] Start only after explicit consent. Gameplay Party names/Avatars match accepted cards; same character keeps inventory/equipment/gold/health/Lives. Save and reopen campaign; slot/token/character and edited presentation survive.
- [ ] Reconnect after changing local defaults; authoritative campaign presentation returns. Fresh new-campaign/connect defaults reflect accepted preference. No auto-Ready or auto-Start after return.

No native game launched by agent. Owned-copy appearance/keyboard/mouse/gameplay acceptance remains unreported until owner play-test.
