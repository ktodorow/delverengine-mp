# Issue #41 — same-window session lifecycle

Contract: [#41](https://github.com/ktodorow/delverengine-mp/issues/41),
[launcher PRD #40](https://github.com/ktodorow/delverengine-mp/issues/40),
[project PRD #1](https://github.com/ktodorow/delverengine-mp/issues/1).
Branch: `mp-v108-prototype`; existing checkout only.

Owner authorized commit, push and issue closure on 2026-10-08. Interactive
play-test results remain unreported; checklist below preserves that validation gap.

## Task understanding

Delver v1.08 native gameplay remains baseline; multiplayer adapts authority and
Participant context. This ticket makes existing Direct Host/Client and Campaign
Library reusable in one process. It does not implement later native menus,
automatic pregame admission, player Ready, campaign naming or packaging.

Current gaps: stopped peer stays attached; stopped session screen requests process
restart; queued floor-entry work has no attempt guard; gameplay entry recreates
GameManager/renderer; gameplay controllers/input survive until application exit.

## Agreed seams and expected outcomes

Parent #40 Testing Decisions 2–5 and 8, confirmed by owner on 2026-10-08:
“Yes, use these seams.” Use one public application session-flow boundary consumed
by production GameApplication/screens, backed by actual TCP/UDP Host/Client peers
and temporary Campaign/profile stores. No substitute screen policy models.
Native graphics are an external adapter; owner verifies rendered one-window flow.

| Slice | Expected observable behavior |
| --- | --- |
| Host reopen | Close pregame Host; reopen same TCP/UDP port; fresh peer usable without process restart. |
| Client retry/cancel | Failed/cancelled attempt releases connection; retry retains request and reclaims valid slot. |
| Old callbacks | Cancelled/replaced peer cannot enter gameplay; current ready peer enters once. |
| Save/leave | Host saves before release; failed capture/write keeps live peer and previous save; Client Leave affects only Client. |
| Replacement/failure | Prior peer and native resources release before next request; bind failure remains retryable. |
| Native return | Controllers detach, overlays/input clear, old world releases; rendering initializes once per application. |
| Compatibility | Existing CLI/profile inputs remain accepted; campaign ownership and original saves remain independent. |

Tests use public session state, real connection outcomes and Campaign Save reads.
Fault injection only at external native-capture/storage/window boundaries. Keep
red → green evidence per vertical slice; missing public operations give compiler
RED, while changed existing behavior must demonstrate runtime RED.

## Evidence

1. Host reopen RED: Windows `Dungeoneer:test --tests
   com.interrupt.dungeoneer.multiplayer.launcher.DirectConnectSessionFlowTest`
   failed `compileTestJava` because public `DirectConnectSessionFlow` did not exist
   (2 missing-symbol errors, 25s). Log: `.scratch/issue-41/red-01-seam.log`.
   Earlier run also exposed incorrect compatibility fixture constructor; fixture
   corrected and RED rerun before implementation.
2. Host reopen GREEN: 1 test passed on Windows, 33s. Corrected test expectation
   from `LOBBY` to existing Host-only `LISTENING` phase; initial GREEN attempt
   demonstrated port reopen but failed that fixture expectation. Logs:
   `.scratch/issue-41/green-01.log`, `green-01-corrected.log`.
3. Next tracer: failed Host capture on Leave must keep both peers READY and
   preserve native checkpoint bytes `{7,8}`. Existing flow closes too early;
   run runtime RED before changing save behavior.
4. Save-failure RED: 2 tests run, 1 failed, 33s. Host became `FAILED` after
   capture refusal instead of remaining `READY`; `.scratch/issue-41/red-02.log`.
   Fix uses existing confirmed `saveAndQuit()` before releasing active Host;
   pregame Host still closes without trying to save an unstarted campaign.
5. Save-failure GREEN: both tests passed, 49s;
   `.scratch/issue-41/green-02.log`. Next tracer: public guarded gameplay entry,
   once per current READY attempt, rejecting pregame/cancelled/replaced peers.
6. Entry-guard RED: compiler reports absent public `enter(peer, installWorld)`
   operation (6 errors, 26s); `.scratch/issue-41/red-03.log`. Added identity and
   started-phase guard with single entry per attempt.
7. Entry-guard GREEN: 3 tests passed, 61s; `.scratch/issue-41/green-03.log`.
   Next tracer uses external native-view adapter: replacement retires old view
   and closes old listener before opening same-port next listener.
8. View-release RED: compiler rejected absent native-release adapter constructor,
   27s; `.scratch/issue-41/red-04.log`. Adapter now retires native ownership after
   successful peer close, before next request. Failed save never invokes it.
9. View-release GREEN: 4 tests passed, 58s; `.scratch/issue-41/green-04.log`.
   Production integration routes existing screens through flow; renderer retained
   across campaigns. Integration command initially placed `--tests` after compile
   task, rejected before compilation; corrected below.
10. Native-input tracer: actual GameInput scroll action must clear when flow
    retires session; tests use same external input-release adapter as production.
    Integration initially failed compilation (duplicate screen dispose method and
    package-private status constructor), both fixed before behavioral RED.
    Runtime RED: 5 tests, 1 failure at retained hotbar scroll, 60s;
    `.scratch/issue-41/red-05-input.log`. GREEN: 5 tests passed plus Windows
    Desktop source/test compilation, 56s; `.scratch/issue-41/green-05.log`.
    Native clear now resets scroll, mouse edges, touch pointers and pending keys.
11. Bind-failure tracer: occupy actual UDP port so Host TCP bind succeeds then
    startup fails. Retry must retire displayed failure and bind same TCP/UDP
    port, proving both listener cleanup and native release before next request.
    Initial runs exposed Netty propagating raw checked `BindException` through
    `Supplier.get()`, bypassing existing screen RuntimeException handlers.
    Test now explicitly asserts public launcher error reaches those handlers.
    Clean runtime RED: 1 test failed that assertion, 64s;
    `.scratch/issue-41/red-06-contract.log`. Requests now normalize checked
    connection failures; release also runs for failure/library views without peer.
    Next run reached Retry and failed real TCP bind, proving prior TCP listener
    leaked after UDP refusal (6 tests, 1 failure, 46s;
    `.scratch/issue-41/green-06-contract.log`). Host bind used Netty `sync*`, which
    rethrew checked bind error past Host's cleanup catch. `await*` plus existing
    `requireSuccess` now reports bounded error and closes all acquired listeners.
    GREEN: 6 lifecycle tests passed, 44s;
    `.scratch/issue-41/green-06-listeners.log`.
12. Added real Client regression through same flow: refused TCP connection →
    retry after Host opens → approved slot 2 → Client Leave → automatic rejoin
    using actual `ProfileReconnectTokenStore` file. Host stays READY/unpaused;
    departed/refused peers cannot enter replacement world. Passed, 34s;
    `.scratch/issue-41/client-lifecycle.log`. Earlier tracer implementation already
    satisfies this contract; no artificial failure or unnecessary new behavior.
13. Added pending-claim Cancel/retry and final Host checkpoint-before-world-release
    regressions. Review tightened production adapter: GameScreen owns attached
    controllers; partial entry retains screen for cleanup; world meshes return
    to pools; ending timers retire; ending/quit actions defer until frame completes.
    Normal single-player/editor initialization remains unchanged.
14. Integration GREEN: all 9 lifecycle tests passed; Windows Desktop source and
    test compilation passed, 63s; `.scratch/issue-41/integration-final.log`.
    Full Windows core/Desktop tests now run with `OWNED_GAME_COPY_TEST` pointing
    at owner's original v1.08 jar. Script: `.scratch/issue-41/windows-validation.cmd`.
15. Full core run: 647 tests, 5 failures, 4m8s;
    `.scratch/issue-41/full-windows-owned.log`. All failures came from legacy
    `GameManagerEscapeTest` fixture reflecting removed private peer field.
    Fixture now overrides existing public peer accessor; assertions unchanged.
    Review also retains new Host/Client request settings inside factory, after
    old Host save succeeds and before bind, so failed-bind Retry/Back has correct
    Campaign/profile/port inputs while refused old-Host save keeps old settings.
16. Fixture/integration GREEN: 7 native escape/save/travel guards plus 9 lifecycle
    tests and 16 Desktop tests passed (zero skipped), Windows, 69s;
    `.scratch/issue-41/fixture-and-integration-green.log`. Full suite rerun uses
    final source; `.scratch/issue-41/full-windows-owned-green.log`.
17. Final full Windows GREEN: 647 core tests passed, zero failures/errors/skips,
    4m11s. All 16 Desktop tests passed in preceding 69s run and remained up to date
    on final full run. Owned-copy tests enabled using original v1.08 jar.
    Total 663 tests; nine new lifecycle tests included. XML totals and owned jar
    SHA-256 recorded in `.scratch/issue-41/final-results.json`; jar hash matches
    pre-validation value. `git diff --check` clean. Desktop source/test compilation
    passed against final source. Existing checkout/branch retained; owner gameplay
    gate pending, issue remains open.

## Implemented operations

`DirectConnectSessionFlow` is production application's single public flow seam:
`open(request)`, `retry()`, `leave()`, `enter(expectedPeer, installWorld)`.
Requests close/save prior peer and release native ownership before next factory;
failed Host save retains current peer/world and prior checkpoint. Retry retains
latest request inputs including reconnect token store. Failed-open views also
retire before retry. Entry requires current READY/SYNCHRONIZING peer and runs once.

`GameApplication` adapts development startup and existing screens to flow, exposes
reusable Host/Client requests, retains initialized GameManager/renderer, and tears
down old world/controllers/overlays/input/screens. Deferred UI work checks current
screen, peer or overlay ownership. Network/gameplay rules and protocol unchanged.

## Owner Windows checks

Use existing Host/Client commands and owned `delver.jar` (read-only). CLI flags,
profile paths, nickname/avatar, capacity and port inputs unchanged.

1. Host Campaign Library: select/create Campaign, approve Client with **A**, start
   with **Enter**. Native controls/gameplay match current v1.08 multiplayer baseline.
2. Pregame Host: **Esc**, then **Y** closes lobby; **N** keeps it open. Campaign
   Library returns. Open same Campaign on port 37777 again in same window.
3. Native Host pause menu: Save and Quit confirmation returns Campaign Library.
   Resume same Campaign; check inventory/progress/world state. Failed capture/write
   retention covered by automated temporary-store/capture checks.
4. Client pending/join screen: **Esc** cancels/leaves; stopped screen **T** retries
   using same request. Also start Client before Host, then open Host and retry.
5. Native Client pause menu: Leave Session keeps Host playing; **T** rejoins existing
   slot using profile token. Host loss returns stopped screen with reason/Retry.
6. Repeat Host save/reopen and Client Leave/rejoin at least three times. Check no
   stuck movement/attack/scroll, duplicated Avatars, stale overlays/audio, second
   game window or edits to original single-player saves.
7. Party Wipe/ending returns keep window open; defeated archive remains read-only.

Owner play-test pending. Automated results do not certify visual/audio/gameplay
acceptance. Native launcher redesign and Ready/admission changes remain later
issues under #40.
