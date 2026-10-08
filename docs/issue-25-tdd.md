# Issue #25 — live Late Participant admission

## Contract inspected

Sources: [issue #25](https://github.com/ktodorow/delverengine-mp/issues/25), then
[main PRD #1](https://github.com/ktodorow/delverengine-mp/issues/1),
[Fresh Return #27](https://github.com/ktodorow/delverengine-mp/issues/27), and
[native replication #38](https://github.com/ktodorow/delverengine-mp/issues/38).

September 28 owner decision in #25 overrides older PRD catch-up rules. New
identity claims unused capacity automatically during live play. Existing
Participants keep playing. Joiner reconstructs current authoritative floor,
acknowledges baseline and ordered catch-up, then becomes Spectator at one Host
tick. Spectator has no body, world input, rewards, or private knowledge from
other slots. Chat and living-Participant view cycling remain available.

At next first arrival on a never-visited floor, Late Participant makes Fresh
Return: native starter kit, zero gold, level one, zero XP/stat upgrades,
campaign starting Lives, full health, shared Party Progression, and safe
destination Respawn Point. Personal Potion and Map Knowledge start empty.
Visited-floor returns cannot grant a character or replenish Lives.

Admission must handle occupied/full capacity, terminal campaigns, compatible
build/content, authenticated reconnect, duplicate claims, transport interruption,
timeout, and obsolete generations without duplicate ownership or state replay.
Ongoing native effects restore remaining phase/time without original start cues.

## Confirmed test boundaries

Owner confirmed October 8, 2026:

- Public headless Host/session commands and visible snapshots/events.
- Real Host/client TCP/UDP admission and reconnect.
- Wire decoding and malformed/stale external messages.
- Native world reconstruction and Fresh Return outputs.

Use existing checkout and `mp-v108-prototype` branch. Preserve existing local
untracked work. Owned archive remains read-only and outside fixtures. Owner
performs gameplay; agent may compile and run automated tests in Windows VM.

## Inspection findings

- `DirectConnectHost.acceptHello` rejects new identities after session start.
- Active-floor claim path only accepts existing reconnect records.
- Existing reconstruction sends component states without an admission
  baseline/catch-up acknowledgement barrier.
- Host transition output is empty. #27 remains open; Fresh Return and actual
  Party travel integration must be distinguished from current-floor admission.
- #38 supplies native current-state/cue distinction and floor-generation
  cleanup; admission must reuse those paths.

## Red / green evidence

First regression, before implementation, Windows 11 / Java 8:

```text
gradlew.bat Dungeoneer:test --tests '*DirectConnectIntegrationTest.liveLateParticipantAutomaticallyJoinsAsSpectatorWithoutHostPause' --no-daemon
1 test completed, 1 failed
Timed out waiting for READY; last state was REJECTED: NOT_IN_LOBBY:
Active Floor accepts only an authenticated Campaign Slot reconnect.
BUILD FAILED in 56s
```

Test starts Host and existing Participant, then joins new identity during play.
Expected: automatic slot 3, Spectator on both clients, no body, ongoing Host
ticks, no pause, empty personal knowledge, and one new roster entry. Production
code unchanged before this failing behavioral run.

Same regression after first implementation: `BUILD SUCCESSFUL in 1m 15s`,
one test, zero failures. Next slice withholds raw TCP joiner's acknowledgement;
expected Host Party snapshot excludes unready slot while baseline transfers.

Acknowledgement-barrier regression before fix: Windows `BUILD FAILED in 46s`,
one failure at `lateParticipantStaysInactiveUntilBaselineIsAcknowledged`:
`Unacknowledged join must not publish active Spectator`. Raw join received
baseline combat state but sent no acknowledgement; Host already published slot.

After barrier implementation: both live-admission and withheld-ack regressions
passed on Windows (`BUILD SUCCESSFUL in 1m 17s`). Native entry slice checks real
late client against native movement adapter: bodyless Spectator can attach,
native controls are disabled, starter inventory/gold absent, map marker hidden.

Native adapter regression failed before fix on Windows (`BUILD FAILED in 47s`):
`Native Spectator floor entry must not wait for a character body`. Fix uses
persistent Campaign Slot identity for Spectator loading, camera, and map policy.

Native entry fix: all three regressions passed on Windows (`BUILD SUCCESSFUL in
1m 18s`). Native reconstruction checkpoint test first failed at compile time
(`BUILD FAILED in 13s`): native connection/readiness API and synchronization
phase did not exist. Test specifies two distinct render completion tokens,
stale-token rejection, reliable baseline movement, announced floor, and no Host
activation before both acknowledgements. Production game connection uses this
API; headless connection acknowledges decoded state automatically.

Native reconstruction checkpoints: Windows `BUILD SUCCESSFUL in 45s`.
Mutation-gap regression then failed (`BUILD FAILED in 25s`): Party Progression
published after catch-up fence remained absent on pending joiner. Fix buffers
ordered reliable mutations during baseline (4,096-message bound), drains them
before catch-up fence, and continues reliable current-state delivery while ACK
is pending. Historical transient cues are excluded; effect snapshots are sent
with live/start presentation disabled. Movement is reliable during admission.

Mutation-gap fix passed Windows (`BUILD SUCCESSFUL in 27s`). Spectator
chat/reconnect regression failed (`BUILD FAILED in 27s`): Host required movement
descriptor for chat. Chat now uses admitted Campaign Slot; bodyless world actions
are ignored. Spectator disconnect retains retryable slot and keeps Host READY.

Chat/reconnect fix passed Windows (`BUILD SUCCESSFUL in 22s`). Generation-edge
test initially failed compilation (`BUILD FAILED in 8s`): Host had no native
completion boundary. Host now captures baseline at stable authoritative tick,
restarts pending admission for replacement generation, and ignores stale floor
completion/ACK. Native render completes Host generation after bridges apply;
client generation change clears pending render completion. Tick observation
avoids acquiring Host-session lock while holding Host admission lock.

Generation-edge test passed (`BUILD SUCCESSFUL in 20s`) after fixing initial
tick-zero movement snapshot construction. Cold-resume regression failed on
Windows (`BUILD FAILED in 16s`): saved Late Participant gained body on resume.
Bodyless zero-Lives saved slot now restores admission policy, authenticates same
slot, synchronizes again, and cannot receive body/progress before Fresh Return.
Unacknowledged reserved slot saves disconnected with zero Lives and no character.

Cold resume fix passed Windows (`BUILD SUCCESSFUL in 21s`). Timeout test first
failed compilation (`BUILD FAILED in 8s`): no pending-admission expiry pump.
60-second wall-clock admission bound runs during pause/loading, rejects only
joiner, retains authenticated slot for retry, and saves no character or Lives.
Buffer overflow uses same retryable rejection. Terminal outcome cancels pending
admission. Test advances public expiry clock, then rejoins same slot.

Timeout/retry passed Windows (`BUILD SUCCESSFUL in 30s`). Fresh Return test
first failed compilation (`BUILD FAILED in 7s`): native item bridge had no Fresh
Return boundary. New native boundary accepts stable generation and Host's
first-arrival decision, uses fresh native player template/starter rolls, and
creates body/combat/Lives/economy together. Occupied Respawn Point searches nearby
safe placement. #27 must install destination authority and supply first-arrival
classification; actual Party travel remains absent at current HEAD.


Fresh Return native boundary passed Windows (`BUILD SUCCESSFUL in 25s`). Further
regressions found duplicate claims rejected instead of reusing credentials
(`BUILD FAILED in 15s`), join-floor Fresh Return incorrectly created character,
and malformed barrier bounds escaped as unchecked exceptions (two failures,
`BUILD FAILED in 17s`). Fix reuses acceptance credentials, requires a later
stable generation, and converts invalid external fields to protocol rejection.
Live-handshake Host-phase assertion failed (`BUILD FAILED in 25s`); Host now
stays READY throughout compatible active-floor handshakes. All five related
checks passed (`BUILD SUCCESSFUL in 22s`), including ongoing native poison timer,
elapsed phase/pulse count, and absence of start cues. Four-slot combat/capacity
check also passed: occupied preference selects free slot, existing slot remains
protected, fifth identity rejects full capacity, and combat baseline converges.

Native catch-up destruction and Spectator body-lifecycle checks both failed
(`BUILD FAILED in 17s`): historical crate impact replayed, and returned teammate
body remained visible. Fix applies destruction silently while synchronizing and
routes body spawn/despawn through live/pending admission streams. Three checks
passed (`BUILD SUCCESSFUL in 23s`), including isolated malformed-peer rejection.

Native movement reconstruction test first failed compilation (`BUILD FAILED in
7s`): movement bridge had no input-free preparation API. Synchronization render
now prepares remote living bodies before item/economy/combat/Lives adapters and
ACK. After correcting headless fixture's absent window/UI setup, regression
passed (`BUILD SUCCESSFUL in 15s`): both living avatars exist, joiner body absent,
and Host still awaits checkpoint acknowledgement.

Full Windows run before final recovery slice: 600 core tests and 16 desktop
tests passed, zero failures/errors/skips (`BUILD SUCCESSFUL in 3m 17s`). Further
targeted admission/wire/native ownership run passed (`BUILD SUCCESSFUL in 1m
34s`), including seeded Host/Friend personal facts remaining absent on joiner
and Spectator pickup/pause requests ignored while subsequent chat works.

Interruption before world checkpoint regression failed (`BUILD FAILED in 15s`):
recovery snapshot had two slots but independently persisted live roster had
three; strict roster-size equality blocked resume. Active checkpoints now permit
additional reservations while every saved slot's identity/token must still
match. Unsaved reserved slots restore disconnected/bodyless with zero Lives and
no progression. Terminal snapshots retain exact roster-size validation.

Recovery reservation fix passed Windows (`BUILD SUCCESSFUL in 20s`).

## Final validation

Windows 11 / Java 8, after all production changes: 602 core tests and 16 desktop
tests passed; zero failures, errors or skips. Desktop classes rebuilt successfully.
Owned archive fixtures enabled through `OWNED_GAME_COPY_TEST`. Final result:
`BUILD SUCCESSFUL in 3m 12s`.

```text
gradlew.bat Dungeoneer:test DungeoneerDesktop:test DungeoneerDesktop:classes --daemon
```

`git diff --check` passed. Existing checkout/branch retained; no game windows
launched. After green automated validation, owner instructed commit, push, and
closure of #25. Native visual/audio acceptance remains unreported.

## Runtime integration and limits

Protocol 49 requires rebuilding Host and all clients together. Ordinary
headless clients acknowledge decoded checkpoints; production native clients
acknowledge render-thread reconstruction. Admission has 60-second timeout and
4,096 ordered-message buffer limit; rejection affects joiner, preserves
identity's reserved slot, and allows authenticated retry. Stable generation and
Host tick fence admission. Generation replacement invalidates old completion
and starts new baseline. No starter kit, economy entry, Lives or body exists
before Fresh Return, including after saved Campaign resumes.

`DirectConnectItemController.freshReturnLateParticipants(generation, firstArrival)`
is implemented and tested as #27 integration boundary. Party travel must hold
admission during loading, install destination movement/native authority,
classify first arrival from Campaign floor history, complete reconstruction,
then call boundary. Joining current generation and revisiting floor both return
zero; repeat call cannot duplicate kit, progress, Lives or body. Actual Party
travel remains unimplemented at this branch's starting HEAD, so stair-driven
Fresh Return cannot yet be accepted through gameplay. Owner requested #25
closure after automated validation; #27 still owns stair-driven integration,
and owner visual/audio acceptance remains unreported.

## Owner live-join check

Use existing Host command with `-PcampaignCapacity=3` (or `4`), then existing
Friend command. Host starts Session after Friend lobby approval. While both
play, start third client from separate Windows terminal:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectClient -PsessionAddress=127.0.0.1 -PsessionPort=37777 -Pnickname=Late -Pavatar=humanoid-3 -PprofileRoot=C:\DelverMpProfiles\Late "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

For capacity four, fourth client uses nickname `Fourth`, avatar `humanoid-4`
and profile root `C:\DelverMpProfiles\Fourth`. Capacity is fixed per existing
Campaign; choose/create Campaign with requested capacity. Same profile/identity
on reconnect must reclaim same slot. Keep separate profiles for different
Participants.

Check continued Host/Friend movement and combat, automatic live admission,
bodyless Spectator, chat (`T`), viewpoint toggle/cycling (left/right mouse), empty
personal map/potion knowledge, no world actions/rewards, current poison/fire/
projectile/bomb phase without historical impact/start sounds, disconnect/rejoin,
occupied-slot protection, and full-capacity rejection. Fresh Return gameplay
waits for #27 Party travel integration.

## Gameplay acceptance

Pending owner Windows playtests with two, three, and four Participants: active
combat join, continued Host simulation, Spectator camera/chat, no Spectator body
or world actions, floor-generation edge, Fresh Return, duplicate/reconnect, and
native ongoing-effect reconstruction. Automated checks do not certify visual
or audio parity.
