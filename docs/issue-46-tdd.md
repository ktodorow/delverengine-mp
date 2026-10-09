# Issue #46 — automatic admission and shared native lobby

## Inspection and expected behavior

Inspected live [#46](https://github.com/ktodorow/delverengine-mp/issues/46), launcher PRD [#40](https://github.com/ktodorow/delverengine-mp/issues/40), project PRD [#1](https://github.com/ktodorow/delverengine-mp/issues/1), CCE history, domain glossary and current session/launcher paths. Dependencies #43/#45 are implemented on current `mp-v108-prototype` branch. Work uses existing checkout only.

Product baseline: original v1.08 single-player experience shared by 2–4 Participants, one authoritative Host, persistent Campaign Slots and private identity/reconnect ownership, read-only compatible owned assets. Launcher work cannot reset characters, replace gameplay rules or import retail executable classes.

#46 automatically admits compatible new pregame Participants into unused capacity, preserves automatic active admission, and provides one reliable Host-owned lobby projection consumed by Host and Clients. Cards show authoritative slot number, Nickname, gameplay-matching static Avatar portrait, Host marker and connection/authentication/synchronization state. Connected, offline claimed/reserved and empty capacity remain separate facts. Campaign name, capacity and starting Lives agree across peers and appear locked in lobby. Private identities and reconnect credentials never label cards.

Compatibility, capacity, occupied/reserved slots, nickname/Avatar restrictions, reconnect credentials, explicit trusted relink and kick safeguards remain authoritative. Failed provisional Connect selections stay editable. Projection follows join/leave/reconnect; Clients cannot author slots. Player Ready/Start consent arrives in #47; this slice preserves existing Start behavior and exposes network state accurately.

Inspection gaps: ordinary pregame `claimSlot` uses approval-only roster submit; Client handshake supplies campaign basics/own claim only; session view is plain development text and exposes a Private Session identifier rather than native roster cards. Existing native Host/Connect forms and matching portrait resolver are reusable.

## Approved seams and method

Parent #40 explicitly records owner confirmation: “Yes, use these seams.” Use public `DirectConnectSessionFlow` actions with real TCP/UDP Host/Client sessions and temporary profile/campaign stores. Observe public peer projections and retained authoritative roster/character outcomes. Wire boundary checks cover serialization bounds, malformed payloads and sender authority. Native screens consume same projection; no duplicate screen-specific policy/model or widget-tree tests.

Vertical TDD slices: document independent expected behavior → write one failing test → capture Windows RED → minimum implementation → capture Windows GREEN. Existing already-working safeguards receive regression coverage without invented RED. Owner performs Windows visual/input/gameplay acceptance; agent compiles/tests/builds.

## RED/GREEN evidence

1. **Automatic pregame admission.** Expected: public Connect action reaches authenticated LOBBY without Host approval, owns free slot, persists reconnect credentials, leaves gameplay unstarted. Test written before implementation. Windows runtime RED: 1 test, 1 failure in 37s (`.scratch/issue-46/red-01.log`, preserved XML `red-01.xml`), expected LOBBY but received AWAITING_APPROVAL. Pregame now uses existing validated roster admission path. Windows GREEN: 1 test passed in 40s (`green-01.log`).

2. **Shared settings/cards.** Expected: actual Client and Host expose identical bounded lobby facts: Friday Delver, capacity 4, starting Lives 5, Host slot 1, connected/claimed 2, empty 2, reserved 0, authoritative matching presentation, authenticated/synchronized Friend, empty slots without presentation. Test written before projection API/implementation. Windows RED: projection type/public peer API absent; test compilation failed in 24s (`red-02.log`). Added bounded immutable public facts, reliable TCP report and receipt acknowledgment; Windows GREEN: 2 tests passed in 53s (`green-02-fixed.log`). First implementation compile found two typos (missing nickname-key accessor and duplicate switch variable); corrected before passing run, not behavior RED.

3. **Reserved pregame return.** Expected: valid credentials restore saved Friend/Avatar 2 even when provisional form defaults conflict with Host; private token and slot remain unchanged. Test written before fix; Windows runtime RED: 3 tests, 1 failure in 38s (`red-03.log`, `red-03.xml`): valid reserved return rejected as NICKNAME_TAKEN due to provisional Host defaults. Pregame now validates original identity/token/slot with saved presentation. Windows GREEN: all 3 tests pass in 47s (`green-03.log`).

4. **Reliable lobby wire.** Expected: lobby reports and receipts cannot encode/decode as UDP datagrams; only ordered reliable TCP carries authority. Test written before channel restriction. Windows runtime RED: 4 tests, 1 failure in 29s (`red-04-runtime.log`, `red-04.xml`), UDP encoded lobby authority. Earlier test compile had missing nested ProtocolException qualifier, corrected before runtime RED. Datagram encoder/decoder now reject lobby reports/receipts; Windows GREEN: all 4 tests pass in 48s (`green-04-fixed.log`). One attempted edit matched wrong multiline signature and made no change; subsequent unchanged run (`green-04.log`) failed again, not GREEN evidence.

5. **Previous named-campaign build remains resumable.** Expected: build-56/protocol-50 format-8 save loads into current lobby build with same name, private owners/tokens/presentation, rules, gold, items and native floor; wrong content leaves bytes untouched; next checkpoint backs up exact predecessor bytes. New public save-store test written before migration. Windows runtime RED: 1 test, 1 failure in 30s (`red-05-save-upgrade.log`, `red-05-save-upgrade.xml`), build-56 save rejected as incompatible with build 57. Added exact known-predecessor compatibility migration; Windows GREEN: 1 test passes in 50s (`green-05-save-upgrade.log`). Save format remains 8; wire header changes only. Strengthened regression compares complete saved gameplay body bytes before/after checkpoint.

6. **Earlier supported name migration reaches current build.** Existing format-7 migration test now targets current build explicitly, with authentic protocol-50 predecessor header. Expected: same supported earlier save migrates its campaign name and retains exact backup, ownership and world. Windows runtime RED: 1 test, 1 failure in 29s (`red-06-legacy-upgrade.log`, `red-06-legacy-upgrade.xml`), earlier supported save rejected by current build. Extended only existing known-predecessor name-migration target to lobby build; Windows GREEN: 31 save-store/owned-floor tests pass in 2m37s (`green-06-legacy-and-owned.log`). Owned native travel now passes with automatic admission; no save schema/gameplay changes.

## Regression and native integration checks

Additional real-session coverage exercises four Participants, exact Host/Client projection parity after joins, departure, authenticated saved-slot return and kick; retained private credentials and presentation; wrong-token rejection; reserved-slot protection; explicit trusted relink and token rotation. Existing launcher/integration fixtures now expect automatic ordinary admission. Cancel after admission keeps reserved ownership and cannot enter replacement session. Direct roster validation remains unchanged.

Reliable wire checks use actual framed Netty codec: round-trip, small-frame bound, malformed capacity/count/Lives/Host/slot/flags, truncation and trailing data. Raw admitted TCP Client attempts to author lobby settings or forge an unauthenticated receipt; Host rejects authority and retains ownership. Maximum Unicode campaign name and four maximum-byte nicknames still fit existing 1024-byte reliable frame. TCP fixture helpers now use TCP codec rather than datagram codec.

First expanded run (`regressions-01.log`): 8 tests, 1 failed expectation. Requested occupied slot follows existing RELINK_REQUIRED pending recovery rather than ordinary rejection; corrected regression to prove explicit relink/decline while ownership remains unchanged. No implementation change required for that safeguard.

Native view shares existing engine background/skin, portrait resolver and public projection, with 2–4 ordered cards, bounded static portraits, separate counts, locked settings, mouse actions and Enter/Esc/T/L/R shortcuts. Native Host and admitted Client forms route to same lobby; failed Connect remains editable. Removed two tests tied solely to obsolete development-text coordinates; retained authoritative floor-entry behavior check. View consumes tested projection directly; visual/input acceptance remains owner task.

Expanded Windows run (`regressions-02.log`): 129 tests, 127 passed, two older raw-TCP fixtures expected next frame to be gameplay response and instead received new lobby report. Updated raw helper to consume shared reports while waiting for gameplay responses; dedicated lobby authority check reads those reports explicitly. No gameplay implementation change for those failures. First fresh full Windows run (`windows-validation.log`): 683 core tests, 2 failures in 5m22s. Owned native travel still expected AWAITING_APPROVAL; fixture updated to automatic LOBBY. Format-7 migration fixture carried current protocol-51 header, inconsistent with historical build; corrected to authentic protocol 50. Follow-up persistence TDD checks protect build-56 and earlier supported saved campaigns across lobby wire upgrade. Fresh final Windows tests (`final-windows-validation.log`): 684 core +22 desktop, zero failures/errors/skips. Same invocation stopped after tests at `verifyOpenSourceAssets`: Windows Git ownership check rejected shared UNC checkout. Audit retry uses build-command-only `GIT_CONFIG_COUNT=1` / `safe.directory=//Mac/Home/Repos/delverengine-mp`; no persistent Git trust changes. All 634 source/test/build hashes remain unchanged. First audit retry (`final-windows-audits.log`) passed ownership check but Windows reported Unix executable-bit differences on UNC assets. Diagnostic `windows-git-assets-diagnostic.log` shows mode changes only; macOS asset diff remains clean. Final audit/build retry uses command-local `GIT_CONFIG_COUNT=3`, adding `core.filemode=false` and `core.autocrlf=false` without changing Git configuration or source assets. `final-windows-audits-fixed.log`: all seven compile/package/source/release/isolated-runtime cleanup tasks rerun successfully in 53s.

## Final validation

| Windows check | Result |
| --- | --- |
| Core suite | 684 passed; 0 failures/errors/skips |
| Desktop suite | 22 passed; 0 failures/errors/skips |
| Shared lobby / wire | 6 real-session +4 wire checks passed |
| Save store / owned native floors | 18 +13 checks passed |
| Source asset / release artifact / isolated runtime cleanup audits | Passed |
| Final source/test/build hashes | 634 unchanged after tests and package rebuild |
| `git diff --check` | Passed |

Fresh tests completed in 5m51s; subsequent same-source audit/package retry succeeded in 53s after Windows Git environment correction. Evidence: `.scratch/issue-46/final-results.json`, `final-windows-validation.log`, `final-windows-audits-fixed.log` and preserved relevant XML. Earlier failed runs remain documented above.

Built fork: `DungeoneerDesktop/build/libs/game.jar`, 15,677,787 bytes, SHA-256 `6f6074203b54729afb98b63ad110235bff18bdea84a031c05552d2087ff84316`. Verified shared lobby/model/wire classes and build-57 identifier present. Protocol 51; Campaign Save 8 and Campaign Roster 2 unchanged. Known compatible saved campaign migration is explicit; content/build incompatibility safeguards retain strict rejection.

Read-only original `delver.jar` SHA-256 unchanged: `a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae`. Owner authorized commit, push and closure after green automated validation. Publication check confirmed all 634 source/test/build hashes and both archive hashes still match final validation; work uses existing `mp-v108-prototype` checkout. Individual native visual/input/gameplay acceptance results remain unreported.

## Owner acceptance

Pending after automated validation: native Host/Connect entry to shared lobby; 2–4 static player cards and gameplay-matching tint/portrait; parity on joins/leaves/returns; reserved versus empty slots; locked settings; keyboard/mouse/scaling; conflict edit/retry; retained character on reconnect. Ready controls belong #47. Automated checks cannot certify native visual or gameplay acceptance.


## Windows play-test entry

Use separate persistent profiles so Host and Client have different private identities. Original archive stays read-only. Run each command in its own Windows PowerShell window; ordinary launcher opens native menu.

Host:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:run -PprofileRoot=C:\DelverMpProfiles\Host "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

Client:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:run -PprofileRoot=C:\DelverMpProfiles\Client "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

1. Host → New Campaign: name, capacity 4, Lives 5, Host/Avatar 1 → open lobby. Expect Host card and three empty cards; settings locked.
2. Client → Connect: Host LAN address (127.0.0.1 when both processes use same VM), port 37777, Friend/Avatar 2. Expect automatic shared lobby, no ordinary approval; both views agree on authoritative cards/counts.
3. Add profiles `Client3`/`Client4` with unique Nickname/Avatar 3/4. Expect four cards, connected 4, claimed 4, empty 0.
4. Leave one Client. Expect connected 3, claimed 4, reserved 1, empty 0. Reconnect same profile with changed provisional defaults. Expect original slot, Nickname and Avatar restored.
5. New profile with occupied Nickname or Avatar: expect editable Connect rejection; choose free presentation and retry when unused capacity exists. Never replace reserved owner automatically.
6. Check portraits/tints against actual gameplay; mouse, Enter/Esc, smaller/larger window scaling and Host close confirmation. Start retains existing behavior for this slice; Ready consent belongs #47.
7. Resume saved campaign and return reserved Client. Check retained health/Lives/inventory and native gameplay. Automated tests cover saved-character regressions; owner confirms visual/input/gameplay experience.
