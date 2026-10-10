# Issue #49 — Active campaign entry from launcher

## Inspection and contract

Inspected live [#49](https://github.com/ktodorow/delverengine-mp/issues/49), complete launcher PRD [#40](https://github.com/ktodorow/delverengine-mp/issues/40), complete project PRD [#1](https://github.com/ktodorow/delverengine-mp/issues/1), current glossary, retained-character and active-admission code/tests. Starting commit `8ed53db703e16222dc95f649e6e56da956ec0aed`, existing `mp-v108-prototype` checkout. #44/#47/#48 already implemented here.

After Host Start, Connect bypasses pregame lobby and manual Ready. Authenticated TCP/UDP and authoritative baseline/catch-up still precede interaction. A new compatible identity claims only unused capacity and enters bodyless Spectator; UI explains the next Fresh Return at the Party's first arrival on an unvisited floor. Existing identity/token restores the same Campaign Slot and authoritative presentation, retaining standing, Downed or exhausted/bodyless Spectator state. Live grace and bleedout continue under existing Host authority. Cold subset resume followed by absent saved-slot return must be tested through fresh storage and actual sessions.

Original v1.08 engine, native presentation and read-only owned assets remain baseline. Lobby Ready is pregame consent only. Travel and Fresh Return implementations remain existing controller authority, separately owned by #27. Existing thirty-second grace and newer Fresh Return/death contracts supersede stale paragraphs in #1.

## Confirmed test seams

#40 explicitly records owner approval: “Yes, use these seams.” Use production `DirectConnectSessionFlow` public actions, real Host/Client TCP/UDP, temporary profile/campaign storage, native controller outputs where graphics permit, and existing narrow wire/persistence boundaries when necessary. No substitute launcher model or private-method assertions. Native menu rendering, input, owned portraits and gameplay role acceptance remain owner Windows play-test.

## RED/GREEN slices

### 1 — Explain active Spectator entry and Fresh Return

Expected before implementation: native Connect into running campaign opens no pregame Ready prompt, explains bodyless Spectator and first unvisited floor during synchronization and after activation. Real baseline/catch-up checkpoints still gate activation; stale acknowledgment cannot activate slot. Native local player remains incapacitated without starter wealth/body. Host keeps running; current world installs only once.

Test added first: `ActiveCampaignEntryTest.activeConnectExplainsBodylessSpectatorAndFreshReturnWhileNativeWorldSynchronizes`.

RED: Windows runtime fails at missing Spectator explanation (`ActiveCampaignEntryTest.java:47`), 1 test / 1 failure, 30s. Evidence `.scratch/issue-49/red-01.log`, retained test XML and `red-01-results.json`. Test compile succeeded; this is behavior RED.

Minimal implementation: Connect progress derives Spectator/Fresh Return explanation from authoritative own Party state during native synchronization and after activation. GREEN: Windows 1 test passed, 42s (`green-01.log`, retained XML).

### 2 — Active connection must stay out of pregame while world loads

Expected before implementation: with Host native world deliberately unstable, authenticated active Client waits in synchronization, cannot offer player Ready, and cannot install missing floor baseline. Completing existing native-world authority makes baseline available; native entry follows without pregame action. Test added first: `activeConnectWaitsForStableWorldWithoutEverOfferingPregameConsent`.

RED: Windows runtime exposes `LOBBY` instead of synchronization at line 90, 2 tests / 1 failure, 31s (`red-02.log`, retained XML).

Minimal implementation: reliable own-slot acceptance carries bounded Host-started fact; active UDP registration stays in synchronization and cannot offer pregame Ready. Launcher entry requires authoritative movement baseline, or bodyless Spectator baseline with pending native reconstruction checkpoint.

First GREEN attempt: 2 tests / 1 failure, 50s (`green-02.log`). Slice 1 waited only for synchronization phase, now legitimately reached before any role baseline exists. Corrected waiter to require its authoritative native baseline checkpoint before expecting role explanation. Loading test passed. This is test timing adjustment, not additional behavior RED.

Corrected GREEN: Windows 2 tests passed, 49s (`green-02-fixed.log`, retained XML). Native Connect and session screens now consume the same production entry gate; active session hides Ready/Start/presentation editing. Initial native reconstruction remains a noninteractive attach under existing admission controller.

### 3 — Persistent native Spectator explanation

Expected before implementation: after automatic Connect-screen closure, native Lives prompt still explains first-unvisited-floor Fresh Return, including while followed Avatar is attaching. Uses actual late session, native Player/movement/Lives outputs and idle input-device boundary. Test added first: `nativeSpectatorPromptKeepsFreshReturnExplanationAfterConnectScreenCloses`.

RED: Windows runtime returns null prompt before followed Avatar attaches (line 131), 3 tests / 1 failure, 49s (`red-03.log`, retained XML).

Minimal implementation: native Spectator prompt retains Fresh Return explanation before camera attachment, without living targets, and alongside followed-character controls. GREEN: Windows 3 tests passed, 42s (`green-03.log`, retained XML).

### 4 — Retained Downed body disables native action immediately

Expected before implementation: authenticated reconnect keeps same body, slot, token, nickname/avatar, Lives and Downed state. Conflicting provisional presentation cannot change saved choice. Native bootstrap disables local action before first tick, without Ready or gameplay pause. Test added first: `downedReturnReclaimsBodyAndDisablesNativeControlBeforeFirstTick`. Each synthetic launcher uses separate real durable profile credentials.

RED: Windows runtime fails at native Player remaining actionable (line 174), 4 tests / 1 failure, 31s (`red-04.log`, retained XML).

Minimal implementation: bootstrap derives incapacitation from authoritative retained Downed/Spectating state when applying own body baseline. Standing body remains actionable. No life/timer/presentation policy changed. GREEN: Windows 4 tests passed, 44s (`green-04.log`, retained XML).

### 5 — Active-entry wire upgrade preserves saved campaigns

Expected before implementation: build 59 / protocol 53 format-8 Campaign Save loads into build 60 without rewriting read source. Next save preserves exact prior backup and identical gameplay body bytes, including ownership/token, progress, item identity and native floor. Content mismatch rejects without altering source. Test added first: `CampaignSaveStoreTest.activeEntryWireUpgradePreservesWholeSavedGameplayBody`.

RED: Windows runtime rejects build-59 save as incompatible at line 187, 1 test / 1 failure, 29s (`red-05.log`, retained XML).

Minimal implementation: bump wire protocol to 54 / build `mp-v108-prototype-active-entry-60`; explicitly accept known format/protocol predecessors through existing bounded migration paths. Save format remains 8, roster format 2. GREEN: Windows migration test passed, 48s (`green-05.log`, retained XML).

## Retained-role regression matrix

Tests added before further implementation: live standing/wounded return; Downed disconnect followed by real unpaused bleedout and exhausted same-body return; actual saved standing/Downed/exhausted campaigns resumed with Host-only subset, persisted again, then absent friend and bodyless Spectator reclaimed through launcher actions. Each checks authoritative presentation, ownership, health/Lives, native incapacitation and lack of Ready/pause. Narrow wire test covers started flag both values, invalid flag, truncation and trailing bytes. Existing native admission/travel/persistence fixtures remain in full regression run.

First Windows run: 7 tests / 2 failures, 51s (`roles-01.log`, retained XML). Standing and immediate Downed returns pass. Exhausted same-body return fails native bootstrap (line 239); cold-return matrix times out at Ready (line 312). These are actual behavior failures, retained as additional RED evidence.

### 6 — Exhausted retained body can already lack live movement state

RED: `downedBleedoutContinuesWhileAbsentAndReturnKeepsExhaustedState` demonstrates bleedout continuing while absent, same slot/body identity reclaimed, then native bootstrap returning false. Exhausted bodies no longer appear in live movement snapshot. Minimal implementation accepts authoritative Spectating state for native incapacitated bootstrap even when retained entity lacks movement state; only genuinely bodyless Spectators clear starter inventory/wealth. GREEN: Windows isolated real bleedout/reconnect test passed, 51s (`green-06.log`, retained XML).

### 7 — Bodyless saved-slot reclaim restores authoritative presentation

Additional RED: isolated cold matrix with public-status diagnostics fails after standing campaign subset resume: bodyless return is `REJECTED / NICKNAME_TAKEN` for conflicting provisional Host nickname, 1 test / 1 failure, 38s (`red-07.log`, retained XML). Slot never reaches Ready despite correct durable credential.

Minimal implementation: existing bodyless slot reclaims with its reserved presentation, while new identity still validates provisional choice. Existing roster token validation remains required. GREEN: complete cold standing/Downed/exhausted and bodyless matrix passed Windows, 55s (`green-07.log`, retained XML).

## Refactor and broader validation

Removed obsolete screen-only floor-entry helper/test: both native screens use public session gate; actual-session tests cover pregame refusal, missing baseline and eventual native install. Native bootstrap/progress code now uses explicit domain imports. Added regressions for wrong bodyless credential preserving reservation, and all known format-8 launcher predecessors loading read-only into current build. First broader run stopped at compile: cleanup removed `java.util.List` still used by recovery UI, letting wildcard UI `List` shadow it. Restored required import; `regressions-01.log` retained. This is refactor compile error, not behavioral RED. Second broader run compiled production, then caught missing `DirectConnectProtocol` import in new predecessor regression; added import (`regressions-02.log`). Both compile-only failures remain separate from seven runtime RED/GREEN slices. Third broader run: 130 tests / 7 runtime failures, 122s (`regressions-03.log`, retained XML). Six legacy flow/Connect tests and cold friend bootstrap asserted entry immediately at network `READY`, before initial movement snapshot arrival. Native screens already wait on new shared baseline gate. Tests now wait on same public `canEnter` capability before asserting successful install; stale/pre-game/duplicate-entry refusal assertions retained. Cold failure occurred before wrong-token step; no credential failure demonstrated. No production gate weakened. Corrected broader run: all 130 launcher/wire/save tests passed, zero failures/errors/skips, 134s (`regressions-04.log`, retained XML).

### Full-suite legacy loading expectation

First fresh full Windows run: 717 core tests / 1 failure / zero errors/skips, 388s (`.scratch/issue-49/full-01.log`, retained XML/results). Fresh failure: `loadingFloorDefersAdmissionAndReplacementInvalidatesOldCheckpoint` times out waiting for pregame `LOBBY`; actual status is `SYNCHRONIZING: Synchronizing ongoing campaign.` Remaining 716 core tests pass, including seven active-entry tests and current admission/persistence coverage. Desktop/distribution tasks did not run after core failure.

Updated existing real-session fixture to expect active synchronization, assert started campaign and unavailable player Ready, then wait for positive native admission checkpoint before generation replacement. Zero checkpoint during loading, absent Party activation, obsolete generation completion, stale acknowledgment and replacement checkpoint assertions remain intact. Production unchanged. Isolated Windows regression passed, 1 test / zero failures/errors/skips, 35s (`loading-green.log`, retained XML). Second fresh full Windows run: 717 core tests / 1 failure / zero errors/skips, 380s (`full-02.log`, retained XML/results). Sole failure: native reconstruction-checkpoint fixture inspects movement immediately upon synchronization, before baseline arrival (`DirectConnectIntegrationTest.java:789`, method `nativeLateAdmissionWaitsForBothReconstructionCheckpoints`). Loading/generation test and remaining 716 core tests pass. This exposes another old phase-timing assumption, not an activation failure.

Reviewed all five native synchronization waits in same integration fixture. Four native-baseline/timeout/mutation tests now wait bounded public admission checkpoint; loading test still asserts synchronization with zero checkpoint before world becomes stable, then uses same checkpoint waiter. Waiter requires advancing checkpoint and synchronization; original movement/snapshot, Party absence, stale acknowledgment, generation replacement, mutation-gap and timeout/retry assertions remain. No production change. Combined regression run: 95 tests / 1 startup failure / zero errors/skips, 152s (`native-regressions-01.log`, retained XML/results). All changed checkpoint tests and seven active-entry tests pass. Sole unrelated button-sound fixture fails before session construction: Netty `channel not registered to an event loop` in Host bind cleanup. No button/gameplay assertion runs in failed case. Isolated button-sound test passed unchanged, 1 test / zero failures/errors/skips, 15s (`button-rerun.log`, retained XML). CCE recall records same pre-existing Host bind-cleanup symptom under #44/#45. Fresh full validation now passes all 739 tests and distribution checks; startup exception retained without inferring unseen bind cause or changing unrelated networking code.

## Final Windows validation

Fresh Windows 11 VM run from existing UNC checkout with `OWNED_GAME_COPY_TEST` pointing to original owned `delver.jar`:

```bat
gradlew.bat Dungeoneer:test DungeoneerDesktop:test DungeoneerDesktop:verifyOpenSourceDist --rerun-tasks --no-daemon --console=plain
```

- Dungeoneer: 717 tests, zero failures/errors/skips.
- DungeoneerDesktop: 22 tests, zero failures/errors/skips.
- All 11 actionable tasks executed and passed in 6m 52s; open-source assets, rebuilt distribution, release artifact audit and isolated runtime cleanup checks pass.
- All 715 captured source/test/build hashes unchanged after run; no new source paths. Original owned archive SHA256 unchanged: `a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae`.
- Fork artifact: `DungeoneerDesktop/build/libs/game.jar`, SHA256 `a45a46d1951376fb012f7e869be5331718855d77eaca29f7d0bb5fd3f1445b9e`.
- Build `mp-v108-prototype-active-entry-60`, protocol 54, Campaign Save format 8, roster format 2. Known predecessor migration preserves saved gameplay body and credentials; all peers need same current build.
- HEAD and branch unchanged from inspection. `git diff --check` passes. No commit/push, parallel checkout or game-window launch; issue #49 open for owner acceptance.

Retained evidence: `.scratch/issue-49/final-green.log`, fresh `final-green-TEST-*.xml`, `final-green-results.json`, final source manifest and `final-results.json`. Earlier failed runs remain separate evidence. One pre-existing intermittent Netty Host bind-cleanup failure occurred during combined regression; unchanged isolated rerun and final full suite pass. Underlying startup cause remains unproved and is not claimed fixed.

## Independent review

### Standards report — zero findings

Reviewed working changes against fixed baseline `8ed53db703e16222dc95f649e6e56da956ec0aed`, root instructions, domain glossary and retained-character/save ADRs. Shared public entry gate removes duplicate screen conditions. Reliable started flag remains bounded at wire boundary. Native bootstrap projects authoritative Party state without adding competing Lives authority. Explicit save migrations follow existing conventions and retain content checks. Tests exercise actual session/persistence seams and narrow wire boundary. No documented-standard breach or actionable code smell found.

Supplemental fixture review: positive entry tests wait bounded public `flow.canEnter(peer)` before successful install; pregame, stale/rejected peer and duplicate-entry refusals remain intact. Wrong-token bodyless return preserves reservation; predecessor matrix checks read-only bytes and credentials. Loading fixture preserves all obsolete/checkpoint authority assertions while matching active phase. Further native fixtures use bounded public advancing-checkpoint wait before baseline inspection, reconstruction acknowledgment or timeout simulation; snapshot, Party absence, mutation-gap and retry checks remain. No findings; review read-only.

### Spec report — zero findings

Reviewed complete #49/#40/#1 and newer retained-role/Fresh Return contracts. Active started flag bypasses pregame; shared baseline gate and existing GameScreen synchronization reconstruct native state before gameplay tick. Standing, Downed and exhausted/bootstrap behavior retains Host authority. Authenticated bodyless reclaim preserves reserved presentation. Actual cold role/bodyless matrix and byte-preserving migration cover explicit persistence requirements. No new travel, Lives or Ready policy and no actionable spec mismatch or scope creep found.

Supplemental review: bounded baseline waits distinguish network Ready from native entry capability without weakening refusal checks. Loading fixture now expects active synchronization/no Ready while retaining zero checkpoint during loading, stale generation/acknowledgment rejection and replacement-baseline assertions. No findings. Owner native acceptance remains pending; automated evidence does not certify rendering, camera input or gameplay feel.

## Owner acceptance

Pending owner acceptance. Agent builds/tests in Windows 11 VM through existing Parallels UNC checkout. No parallel repository, new branch or game-window launch.

Prepared Windows launch scripts, each runs fork from same checkout with read-only original `delver.jar` assets:

- `.scratch/issue-49/playtest-host.cmd` — existing `C:\DelverMpProfiles\Host` profile.
- `.scratch/issue-49/playtest-client.cmd` — existing `C:\DelverMpProfiles\Client` profile; use same profile for returns.
- `.scratch/issue-49/playtest-late.cmd` — separate `C:\DelverMpProfiles\Issue49Late` profile for new identity.

Owner checklist:

1. Create or resume capacity-four campaign. For retained living friend, connect Client before Start, both explicitly Ready, then Start. Wound friend, leave/reconnect from Connect: same slot, name/avatar, health/Lives/items; no pregame lobby or Ready; Host keeps running.
2. With campaign active, connect unused late profile: automatic world synchronization, bodyless Spectator, visible Fresh Return explanation through native prompt. No starter items/gold/body. Use existing travel to first unvisited floor to verify Fresh Return; visited stairs must not grant it. Travel implementation remains #27.
3. Down friend while Host survives; disconnect/reconnect during grace: same Downed body, native controls disabled immediately, existing bleedout continues. On final-Life bleedout while absent, return remains exhausted Spectator; no free heal/life reset.
4. Save and Quit, relaunch same Host profile and resume with Host alone. Start, then reconnect absent saved Client: retained standing/Downed/exhausted state and reserved presentation restore. Returning bodyless late profile remains same slot and Spectator; incorrect token cannot take slot.
5. Confirm active Connect never briefly offers pregame Ready during world loading. Native menu, owned portraits, camera controls and role presentation require owner checks; automated sessions do not prove rendering/input feel.

Initial handoff left issue #49 open for owner native checks and work uncommitted for review. Later closure authorization is recorded below.

## Owner-reported native Spectator crash — follow-up

Owner Windows play-test reaches active world, then crashes: `IndexOutOfBoundsException: index can't be >= size: 0 >= 0` at native `Hotbar.refresh:74`, called by `Game.RefreshUI` / menu refresh during `Player.tick`. Gradle reports `BUILD SUCCESSFUL` because application catches fatal game error and exits; it is not gameplay success evidence.

Earlier automated tests covered real session/role/persistence and native incapacitation, but never called native HUD refresh after bodyless bootstrap. That coverage gap invalidates claiming automated native entry safety. Both CCE connectors currently report `Transport closed`; attempted recall/search before known crash/test file reads.

Expected before follow-up implementation: real active Connect and bodyless bootstrap retain zero carried items/equipment/gold and disabled control; native `Game.RefreshUI` handles quick slots and backpack after world activation without index exception, without granting starter items. Test uses real Player, Hotbar/Hud/Stage and Host/Client/action seams; only display/GL/Batch device boundary substituted for unattended Windows run. Native graphics/camera feel still requires owner acceptance. Before production fix, exact Windows regression failed twice at `Hotbar.refresh:74` / `Game.RefreshUI` with `index can't be >= size: 0 >= 0`: `hud-red-01` (60.61s) and `hud-red-repeat` (15.87s), each 1 test / 1 failure. Logs, result JSON and JUnit XML retained in `.scratch/issue-49/`. Both runs reached native refresh through actual accepted bodyless session; neither failure was compilation/setup.


Ranked, falsifiable hypotheses after exact RED:

1. Bodyless bootstrap clears native inventory slots rather than only carried items. Native `Player.makeStartingInventory` and slot upgrades store empty slots as null entries; Hotbar indexes configured `inventorySize`. Probe changes only test-side slot shape before the same real HUD refresh; production remains unchanged.
2. Item synchronization later removes slots again. Extend regression through real item bridge `prepare` / `update`, followed by another HUD refresh, to check ongoing entry rather than bootstrap alone.
3. Hotbar must accept missing slots independently of native inventory contract. If restoring correct empty slots still crashes, inspect native UI boundary. Avoid broad UI guards before testing producer contract.

Single-variable probe `hud-slot-probe`: production unchanged, test only restores null slots before real HUD refresh. Windows 1 test / zero failures/errors/skips, 32.74s. Confirms missing native slot shape causes exact crash. Temporary probe removed from regression; real item bridge added before backpack refresh. Extended regression is run again against unchanged production before fix.

Extended regression `hud-bridge-red`: Windows 1 test / 1 failure, 32.65s; same `Hotbar.refresh:74` before item preparation. Production fix then adds only native null-slot padding after bodyless inventory clear. Equipment/gold clearing and incapacitation remain required. Regression retains no test-side repair; asserts all configured slots exist, every item null, empty quick-slot/backpack buttons, no local body, zero wealth and disabled control after real item synchronization.

First patched run `hud-green-01` is not GREEN: initial native HUD refresh passes, then extended item preparation fails because unattended fixture omitted `StringManager.localizedStrings` initialization (`Gold` constructor), 46.08s. Fix limited to fixture: empty native localization catalogue uses engine fallback labels, saved/restored alongside Options and graphics globals. Production slot fix unchanged.

GREEN `hud-green-02`: exact extended Windows regression passes, 1 test / zero failures/errors/skips, 32.89s. Test-side slot repair absent; production bodyless bootstrap supplies null slots. Real quick-slot refresh, item bridge `prepare` / `update`, and visible backpack refresh all pass. Supplemental Standards/Spec reviews report no actionable findings. Full fresh Windows core/Desktop tests and open-source distribution checks follow; source snapshot retained separately from earlier validation.

Supplemental standards review after localization fixture addition: no isolation finding; previous native catalogue reference restored without mutation. CCE decision/area writes attempted for coverage correction and regression but also fail with `Transport closed`; local TDD record remains authoritative follow-up evidence.

First full follow-up attempt (`hud-final-green`, filename is attempt label, not passing status) fails: 718 core tests / 2 failures / zero errors/skips, 376.82s; Desktop/package steps not reached. New HUD regression passes within full run. Legacy `lateSpectatorCanAttachNativeFloorWithoutReceivingStarterItems` expects zero array slots but actual correct native empty inventory has 23 null slots. Corrected to assert configured addressable slots and every item null; gold/incapacitation/map assertions retained.

Other failure: `refusedClientRetriesThenLeavesAndRejoinsSameCampaignSlot` cannot bind UDP listener, `Address already in use`. Fixture chooses port using TCP-only ServerSocket then releases it before refused client attempt. Hypotheses: untested UDP availability; refused client's ephemeral UDP bind may consume target port; another socket may consume port in setup gap. Occupying process not proven. Fixture now uses existing dual-protocol port helper, reserves UDP through refused attempt, releases immediately before Host bind, closes lease in finally. All original refusal/retry/token/reclaim/native-entry assertions retained; production network code unchanged. Focused run covers corrected legacy assertion, retry fixture and exact HUD regression before second full attempt.

Focused GREEN `hud-regressions-green`: all 3 Windows regressions pass, zero failures/errors/skips, 37.20s (exact native HUD/item path, legacy no-starter-items contract, refused-client retry/reclaim). Supplemental standards review finds no masking/isolation issue in assertion or port lease. Second complete fresh Windows run uses separately retained 715-file source snapshot `hud-final-validation-sources-02.json`.

## Final follow-up validation and owner retest

Latest authoritative run `hud-full-02`: fresh Windows 11 core/Desktop tests and open-source distribution checks pass. 740 tests (718 core + 22 Desktop), 92 suites, zero failures/errors/skips; 395.81s, Gradle 6m 35s. All 11 tasks execute, including compile/test, distribution, asset/release audit and isolated-runtime cleanup. Exact native HUD regression and corrected legacy/retry tests pass within this full run. This supersedes earlier validation status for owner-reported crash.

Verification `python3 .scratch/issue-49/verify-hud-results.py` passes: all 715 source/test/build hashes match second-run snapshot, no source paths added after snapshot, `git diff --check` passes, original owned archive checksum unchanged. Same branch `mp-v108-prototype`, same HEAD `8ed53db703e16222dc95f649e6e56da956ec0aed`; work uncommitted, no push. New Windows fork artifact `DungeoneerDesktop/build/libs/game.jar` SHA256 `539eeef660cf6b89128d628ba6a6ef45c1b198e1a00c12f3210c2fa52f1e2533`. Original `delver.jar` SHA256 remains `a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae`. Full result metadata retained at `.scratch/issue-49/hud-final-results.json`; RED/probe/setup failures and both full attempts retained alongside XML.

Owner native retest remains pending. Run same Windows command against same Late profile:

```cmd
call "\\Mac\Home\Repos\delverengine-mp\.scratch\issue-49\playtest-late.cmd"
```

Connect active campaign: enter bodyless Spectator, world stays open through first native ticks/menu refresh, quick slots/backpack show no items, no gold/body/actions granted, Fresh Return prompt remains visible. Existing Late identity may reclaim reserved Spectator slot; no profile reset needed. Check camera and existing first-unvisited-floor Fresh Return through owner play-test. `BUILD SUCCESSFUL` alone does not certify gameplay; native rendering/input acceptance and broader role checklist above remain owner checks. Later closure authorization is recorded below.

## Closure authorization

2026-10-10: owner explicitly requested commit, push and issue closure if all checks are green. Re-ran final verification before staging: 740 Windows tests / zero failures/errors/skips, required build/package tasks passed, all 715 tested source hashes match, no new source paths, original owned archive unchanged, same checkout/branch and baseline HEAD. No code changed after passing full run; documentation records this later authorization.

Closure follows owner instruction based on green automated validation. Native owner retest has not been reported; no claim of native rendering/camera/gameplay acceptance. Failed native HUD RED runs and subsequent passing regression remain documented above. Commit scope includes issue #49 implementation, tests and this TDD record; pre-existing local tooling and unrelated untracked documents remain outside commit.

## Hosted CI preference/admission race — follow-up

Initial implementation committed and pushed as `d985a63de0ca11a83343f6edf6eeb57373242ab4`. Fresh hosted Windows CI run [38052097745](https://github.com/ktodorow/delverengine-mp/actions/runs/38052097745) fails at `NativeConnectSetupTest.failedPreferenceWriteClosesOpenedClientWithoutClaimingHostCapacity`, Host roster-size assertion: local preference-write failure must not reserve Campaign Capacity. 718 core tests / 1 failure / 16 owned-copy tests skipped; Desktop/package checks not reached. Earlier full VM run remains valid historical evidence, but this CI failure blocks closure. Issue remains open.

Before production changes, pin real TCP/UDP interleaving in existing agreed Connect action seam: connection factory waits until Host admits second participant before returning. Real presentation preference target is directory, so actual filesystem write fails. Expected Host roster remains one slot, flow owns no peer, launcher identity remains unchanged. No Host policy mocks or sleeps to hide admission. CCE recall/search attempted again; both connectors still return `Transport closed`. Logs/XML captured under `.scratch/issue-49/ci-race-*`.

Pinned RED `ci-race-red-01`: 1 Windows test / 1 failure, 53.54s; `ci-race-red-02`: same failure, 15.37s. Exact assertion `expected:<1> but was:<2>` after opened Client is already `CLOSED`, disproving missing Client-close explanation. Host admission legitimately reserves durable slot; local close must not erase admitted ownership. Ranked hypotheses shared before probing: admission-before-preferences, incomplete close, filesystem failure indirectly changing roster. Expected correction: complete local preference persistence before invoking transport factory. Strengthened regression retains pinned real-admission path, Host-capacity assertion, and explicitly requires no Client opening, token, pending claim or connected participant on local setup failure; identity unchanged.

Strengthened contract RED `ci-race-contract-red`: 1 test / 1 failure, 31.81s, same Host-capacity assertion. Production fix only moves both validated preference saves before Client factory; removes ineffective post-admission close-on-write-failure. Existing durable admission/disconnect policy unchanged. Exact regression GREEN `ci-race-green-01`: 1 test / zero failures/errors/skips, 43.94s. No test-side Host roster repair or Client cancellation before assertions. Full fresh Windows run follows with separately captured 715 source/test/build hashes.

Supplemental Standards and Spec reviews: zero actionable findings. Scope limited to sequencing local setup before durable admission; validated unreachable/rejected attempts still retain editable defaults, Host remains presentation/ownership authority. Existing native HUD correction and owner play-test limitation remain. CCE decision/area recording also unavailable (`Transport closed`); this local TDD record preserves rationale and evidence.

Exact regression command, from repository on Windows:

```cmd
gradlew.bat Dungeoneer:test --tests com.interrupt.dungeoneer.multiplayer.launcher.NativeConnectSetupTest.failedPreferenceWriteCannotOpenClientOrClaimHostCapacity --no-daemon --console=plain
```

Same command executes final contract RED and GREEN above. Admission barrier deterministically exposes old ordering; with correct ordering, failed local persistence prevents factory invocation entirely. No temporary diagnostic logs or production probes retained.

Final race follow-up GREEN `ci-fix-full-01`: fresh Windows 11 core/Desktop suite and open-source distribution checks pass, 740 tests (718 core + 22 Desktop), 92 suites, zero failures/errors/skips. 414.82s, Gradle 6m 54s, all 11 tasks executed. Both exact native Spectator HUD regression and pinned preference/admission regression pass within full suite. Verification `python3 .scratch/issue-49/verify-ci-fix-results.py` confirms all 715 tested source/build hashes unchanged, no added source paths, original owned archive unchanged, `git diff --check` clean, same checkout/branch. Fork `game.jar` SHA256 `c52129522ee565fca446b680ad968af23e742f9b9a40819d79a365cf1f32d56c`. Metadata retained in `.scratch/issue-49/ci-fix-final-results.json`.

This supersedes earlier automated validation status. Owner native camera/gameplay retest remains unreported. Follow-up commit/push authorized by owner; closure remains gated on fresh hosted CI. Final hosted run and closure evidence recorded on issue #49 after that gate passes.

## Second hosted CI failure — Ready consent diagnosis

Hosted run [38053276293](https://github.com/ktodorow/delverengine-mp/actions/runs/38053276293), commit `55dfda0413ec7c37b9d667315d35d6517e954a0a`, fails 2 of 718 core tests (16 owned-copy skips). Preference/admission regression passes. Failures: `NamedCampaignSetupTest.repeatedColdSlotReturnKeepsEntityBaselineAheadOfLivePartyUpdates:556` and `DirectConnectIntegrationTest.coldResumeKeepsLateSlotBodylessUntilFreshReturn:630`. Both call real Ready-consent helper before retained-state assertions. Local full run remains historical GREEN; issue remains open pending diagnosis. Exact two-test Windows loop launched with no production changes; hosted log retained as `.scratch/issue-49/ci-02-failure.log`.

Exact local loop `ci-cold-loop-01`: 2 Windows tests / zero failures/errors/skips, 61.44s, including 128 fresh save/return cycles. Cannot infer hosted failures disappeared. Ranked falsifiable checks shared: Client never eligible to Ready vs Host missing Ready acknowledgement vs shared eight-second budget exhausted by slow admission. Hosted default SHORT logging omits assertion messages/stack, so next probe changes only core test failure logging to FULL. Assertions, deadlines and production behavior unchanged; repeat same local loop before hosted diagnostic push.

Logging-only configuration check `ci-cold-loop-full-output` succeeds but Gradle reports tests UP-TO-DATE: zero fresh test suites, not additional regression evidence. Earlier exact two-test run and full 740-test run still describe unchanged Java code. Diagnostic commit changes only Gradle failure formatting and documentation, to expose hosted failure cause; issue remains open.
