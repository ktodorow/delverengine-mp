# Issue #47 — manual Ready before Host Start

## Inspection and expected behavior

Inspected live [#47](https://github.com/ktodorow/delverengine-mp/issues/47), launcher PRD [#40](https://github.com/ktodorow/delverengine-mp/issues/40), project PRD [#1](https://github.com/ktodorow/delverengine-mp/issues/1), CCE decisions, current lobby/session/wire contracts, domain glossary and Host-owned Campaign Save ADR. Dependency #46 is closed at current `mp-v108-prototype` HEAD `50f27a8`. Existing checkout and branch only.

Product baseline: original v1.08 experience shared through one authoritative native Host simulation, read-only owned assets, persistent private Campaign Slot ownership and retained characters. Pregame consent cannot replace network readiness, reset gameplay state or become floor-transition readiness.

Host and Clients start unready. Authenticated, synchronized pregame Participants explicitly mark their own current connection Ready through reliable TCP. Host has separate Ready and Start Campaign/Resume Campaign actions. Last Ready never starts gameplay. Start atomically requires every admitted connected Participant network-ready and player-Ready; no ready subset starts while another admitted connection waits. New campaigns require two including Host; saved campaigns permit Host alone. Offline reserved slots are exempt. Reconnect clears only returning Participant's consent; session/restart never persists Ready. Accepted presentation changes invalidate only editor's consent; rejected changes preserve prior presentation and consent. Active joins/reconnects retain existing automatic synchronization and character rules. Development entry points use identical authoritative gate.

Inspected baseline gap: `canStartSession` counted UDP-ready Participants and permitted saved resumes without consent; `startSession` rejected incomplete connections while starting remaining subset. Shared reliable lobby supplied authentication and synchronization receipts, but no player Ready state.

## Approved seams and method

Parent #40 records owner confirmation: “Yes, use these seams.” Use production `DirectConnectSessionFlow` public actions with actual TCP/UDP Host/Client sessions and temporary campaign/profile stores; observe public lobby/phase/spawn, retained ownership and Campaign Save outcomes. Extend real framed-wire boundary for bounded Ready payloads, channel restrictions, unauthorized cross-slot/stale requests and malformed bytes. No widget-tree or private-helper tests.

Vertical TDD: independent expected behavior → one failing test → captured Windows RED → minimum implementation → captured Windows GREEN. Existing invariants receive regression checks without invented failures. Owner performs native visual/input/gameplay acceptance after agent compilation/tests/build.

## RED/GREEN evidence

1. **Synchronized connections still need player consent.** Expected: fresh real Host/Client lobby remains unstarted and Start disabled/rejected despite completed TCP/UDP/lobby synchronization. Test written before gate implementation. Windows runtime RED: 1 test, 1 failure in 29s (`.scratch/issue-47/red-01.log`, `red-01.xml`), network-only gate incorrectly allowed Start. Windows GREEN: 1 test passed in 47s (`green-01.log`, `green-01.xml`).

2. **Own-slot Ready and separate Start.** Expected: Host and Client begin unready; reliable own-slot actions publish identical consent on both peers. Final Ready enables Start without starting; either player can withdraw consent. Explicit Host Start then enters both Participants. Test written before Ready API/wire implementation. Windows RED: Ready projection/action API absent; test compilation failed with 14 missing-symbol errors in 21s (`red-02.log`). Windows GREEN: 2 tests pass in 51s (`green-02-fixed.log`, `green-02.xml`). First implementation run (`green-02.log`) hit duplicate switch-variable name; corrected before passing run, not behavior RED.

3. **Ready requires reliable channel.** Expected: player consent cannot encode/decode through UDP; reliable framed TCP round-trips own-slot command and authoritative consent. Test written before channel restriction. Windows runtime RED: 1 test, 1 failure in 34s (`red-03.log`, `red-03.xml`), UDP encoder accepted Ready consent. Windows GREEN: all 5 lobby-wire tests pass in 45s (`green-03.log`, `green-03.xml`).

4. **Accepted presentation edit invalidates consent.** Expected: current authoritative Host presentation change clears only Host Ready, rejected conflict preserves both presentation and consent; no-op edit keeps consent. Changed-and-restored presentation still requires new consent. Test written before invalidation implementation. Windows runtime RED: 1 test, 1 failure in 30s (`red-04.log`, `red-04.xml`), accepted edit retained old consent. Windows GREEN: 3 real-session tests pass in 55s (`green-04.log`, `green-04.xml`). Consent follows immutable authoritative slot revision; unchanged presentation preserves revision. Shared projection refreshes edits and stale pre-edit Ready sequences cannot restore consent.

5. **Ready wire upgrade preserves saved campaigns.** Expected: build57/protocol51 save loads into Ready build58/protocol52 with byte-identical gameplay body, retained name/owners/tokens/characters/native world and exact predecessor backup at next checkpoint. Existing supported build56 and older name migrations still reach current build; incompatible content changes no bytes. New save-boundary test written before migration extension. Windows runtime RED: 19 tests, 3 failures in 28s (`red-05.log`, `red-05.xml`): build57, build56 and supported format7 predecessors rejected by current build. Windows GREEN: all 19 save-store tests pass in 38s (`green-05.log`, `green-05.xml`). Explicit known predecessor paths extended; save format8/roster2 unchanged and Ready never enters saved gameplay body.

6. **Failed pregame Host cannot use prior consent.** Review identified missing FAILED-phase guard. Expected: real saved campaign permits Host-only resume after own Ready; public native compatibility failure then disables consent and Start, rejects launcher Start, preserves FAILED phase and spawns no entity. New test written before guard change. Windows runtime RED: 1 test, 1 failure in 47s (`.scratch/issue-47/red-06.log`, `red-06.xml`), retained consent still allowed Start after public failure. Minimum fix: authoritative gate also rejects FAILED phase. Windows GREEN: all 35 focused Ready/session/authority/wire/save checks pass in 1m9s (`green-06.log`, four `green-06-*.xml` reports), including rejected launcher Start, retained FAILED phase and no spawn.

## Additional regression contracts

Real launcher/session checks cover new Host-only minimum, saved Host-only resume with offline reserved exemption, restart clearing consent, active authenticated return without manual Ready, three-player pregame reconnect clearing only returning consent and stale queued flow actions. Raw actual TCP/UDP checks cover unauthenticated/unsynchronized Ready, cross-slot/session/token/report spoofing, stale previous-connection replay and queued Start observing new unready admission. Framed wire checks cover Ready bounds, booleans, truncation/trailing bytes and authoritative consent round-trip. These extend already implemented safeguards; no invented RED claim. Windows GREEN: all 34 focused checks pass in 29s (`.scratch/issue-47/regressions-02.log` and four `regressions-02-*.xml` reports). First run failed compilation from wrong test-fixture `CampaignRosterStore.load` arity; corrected fixture before passing run, not behavior RED.

Legacy gameplay fixtures now explicitly send their own consent with `ReadyTestSupport.readyClient` and ready Host before production Start. Raw gameplay fixture performs actual reliable lobby receipt and own-slot Ready. No production bypass, hidden auto-Ready in phase polling, or active-join consent gate. Pre-review full Windows run (`full-03.log`) passed 696 core +22 desktop tests, zero failures/errors/skips, source/release/isolated-runtime audits and jar rebuild in 6m13s. All 707 source/test/build hashes unchanged; reports preserved as `pre-review-TEST-*.xml` and `pre-review-results.json`. Final full regression after review fix passed; see final validation below. Initial full run (`full-01.log`) failed compilation in 45s from raw fixture using `ServerAccepted.slot` instead of `slotNumber`; corrected actual field and used unfiltered lobby-frame reader before rerun. Second full attempt (`full-02.log`) failed compilation in 44s from test support calling Client-specific `getCampaignSlot` through `DirectConnectPeer`; corrected to public `getLocalCampaignSlot`. Fixture compilation errors are not behavior RED.

## Final Windows validation

Fresh final command: `gradlew.bat Dungeoneer:test DungeoneerDesktop:test DungeoneerDesktop:verifyOpenSourceDist --rerun-tasks --no-daemon --console=plain`, owned-copy test environment enabled. Command-local Windows Git settings handle shared UNC checkout ownership, executable-bit metadata and line endings; no persistent Git configuration changes.

| Check | Final result |
| --- | --- |
| Core suite | 697 passed; 0 failures/errors/skips |
| Desktop suite | 22 passed; 0 failures/errors/skips |
| Ready launcher/authority/wire/save | 6 +3 +7 +19 checks passed |
| Existing gameplay sessions / persistence | 88 +33 checks passed |
| Owned native floor build / travel | 13 passed; owned archive validation enabled |
| Source assets / release artifact / isolated-runtime cleanup | Passed |
| Source/test/build fingerprints | All 707 unchanged across final validation |
| `git diff --check` | Passed |

Final invocation succeeded in 6m7s, all 11 tasks freshly executed. Evidence: `.scratch/issue-47/final-windows-validation.log`, `final-results.json`, `final-validation-sources.json` and preserved `final-TEST-*.xml` reports. Earlier failed compilation, intentional RED and pre-review GREEN runs remain documented above.

Built fork: `DungeoneerDesktop/build/libs/game.jar`, 15,681,706 bytes, SHA-256 `952e1e4d915c7cb124978d13f9f4cba8e3046f9015195e133a9b23c7967b36a8`. Verified Ready wire, lobby slot, native screen and launcher-flow classes, build58 identifier and explicit consent-refresh method in packaged classes. Protocol52/build `mp-v108-prototype-manual-ready-58`; Campaign Save8 / Roster2 unchanged.

Owned original `delver.jar` SHA-256 unchanged: `a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae`. Existing `mp-v108-prototype` checkout only; unrelated pre-existing untracked files preserved. Owner explicitly authorized commit, push and issue closure after green automated tests. Publication precheck confirms all 707 source/test/build fingerprints, test totals and both archive hashes still match final validation. Individual owner native visual/input/gameplay acceptance results remain unreported.

## Review against baseline `50f27a8`

Repository code-review skill ran separate read-only Standards and Spec agents against task's uncommitted diff and new task files, with live #47/#40/#1 as spec sources.

### Standards

No hard documented violations. Two nonblocking judgement findings addressed: snapshot getter hides consent invalidation/report publication; `admittedClient` gameplay fixture name hides explicit consent. Clarity changes applied: named `refreshLobbyConsent` at snapshot/Ready/Start boundaries, documented snapshot refresh and `readyParticipant` fixture. Public launcher delegation remains approved test seam. Read-only follow-up confirms zero remaining hard violations or judgement findings.

### Spec

One behavior finding addressed: previously Ready pregame Host can enter FAILED without closing, yet Start gate initially omitted FAILED status. New real saved-session regression failed before guard change; explicit FAILED guard added after runtime RED. One owner-checklist correction applied: withdraw Host Ready after new Host-only minimum check, because new friend admission correctly retains Host consent. Remaining authority, reconnect, save, minimum, atomic admission/Start and active-join contracts reviewed as implemented; visual/input/gameplay acceptance remains pending. Read-only Spec follow-up confirms zero remaining findings after guard and checklist fixes.

## Owner acceptance

Pending: separate Ready and Start controls, truthful Ready on all native cards, keyboard/mouse/scaling, all-connected gate with 2–4 Participants, new versus saved minimums, reconnect and native gameplay entry. Automated checks cannot certify these native results.

## Windows play-test entry

Use separate persistent profiles for private Host/Client identities. Run each command in its own Windows PowerShell window; ordinary launcher opens native menu with read-only owned assets.

Host:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:run -PprofileRoot=C:\DelverMpProfiles\Host "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

Client:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:run -PprofileRoot=C:\DelverMpProfiles\Client "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

1. Host → New Campaign → capacity 4, Lives 5, unique Host Nickname/Avatar → lobby. Expect Host Not ready. Host Ready alone still cannot start new campaign; withdraw Host Ready before next step.
2. Client → Connect → `127.0.0.1` when both processes use same VM, port `37777`, unique Friend Nickname/Avatar. After authentication/synchronization expect both cards Not ready; Start disabled.
3. Use Ready button or Space separately on both windows. Last Ready enables Host Start without entering gameplay. Withdraw either player's Ready; Start disables. Restore consent, then Host clicks Start or presses Enter. Expect shared native gameplay.
4. Add `Client3`/`Client4` profiles with unique presentation before Start. Leave one unready and confirm ready subset cannot start. Mark all connected players Ready and explicitly start.
5. Pregame: mark everyone Ready; disconnect/reconnect one Client using same profile. Expect returning card Not ready, other consent retained and saved presentation restored. Host Start remains disabled until returning player consents.
6. Host Save & Quit; reopen saved campaign with Host alone. Expect offline reserved friends, Host Not ready, Resume disabled. Host Ready enables Resume. Resume and reconnect original Client profile: active return synchronizes without pregame Ready and retains character, health/Lives/inventory.
7. Check matching card statuses on all windows, separate controls, mouse/Space/Enter/Esc, smaller/larger window scaling and native v1.08 gameplay fidelity. Presentation editing UI remains issue #48; accepted/rejected consent invalidation is covered through current authoritative roster seam.
