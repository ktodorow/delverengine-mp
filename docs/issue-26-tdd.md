# Issue #26 — Active and Dormant Floors

## Inspected contract

Inspected [#26](https://github.com/ktodorow/delverengine-mp/issues/26), then
[main PRD #1](https://github.com/ktodorow/delverengine-mp/issues/1), linked
[#27](https://github.com/ktodorow/delverengine-mp/issues/27) and native contract
[#38](https://github.com/ktodorow/delverengine-mp/issues/38). Blockers #20/#21 closed.
PRD stories 60/61 and ADR 0008/0014 require one Host-simulated area, with every
visited inactive area preserved using native Delver level serialization.

Owner confirmed test boundaries October 8, 2026: Host/session floor lifecycle,
Campaign Save round trip and native Level return, including enemies, drops,
doors, triggers, timers, stable identities and stale presentation cleanup.
Same checkout and `mp-v108-prototype` branch. Windows 11 VM runs Java 8 tests;
owner performs game playtests. Owned `delver.jar` stays read-only and external.

#26 owns floor storage, activation and dormancy. #27 owns stairs/warp countdown,
Party gathering, abandonment, destination arrival and Fresh Return. Floor
preservation must expose a usable boundary for that integration, without
enabling separate Participant worlds or local single-player travel.

## Native lifecycle requirements

| State | Dormancy / return |
| --- | --- |
| Surviving enemies, native AI/attack state, persistent corpses | Serialize; no dormant tick; return without rerolling health/loot |
| Defeated/removed enemies and destroyed props | Stay removed; identities/tombstones retained |
| Physical world items, bombs, Orb, doors, movers, triggers, tile changes | Stay on owning floor; native persistent state/timers freeze |
| Native persistent projectiles, including MagicMissileProjectile | Freeze native graph and motion; return without refiring |
| Monster statuses | Remaining native duration and pulse/instance cursor freeze; no start/damage replay |
| Participant inventory, economy, knowledge, Party facts | Campaign scoped; never copied into dormant world ownership |
| Entities marked non-persisting or native presentation replicas | Clear according to native save cleanup; no old-floor callback on destination |
| Explosion particles/animated sprite/light with native `persists=true` | Freeze native remaining state; restore existing entities without firing another explosion |
| One-shot network actions/audio | Clear queues on generation; never replay through baseline |
| Persistent world effects | Serialize native remaining state; no dormant gameplay |
| Network requests/cues and observer attachments | Generation fenced; obsolete floor presentation removed |

## TDD evidence

First regression: `NativeFloorSaveTest.dormantNativeStatusKeepsRemainingTimeAndPresentationCursor`.
Expected: native poison returns with 137 time remaining, instance 4242, elapsed
363, three prior pulses, 20 HP; no new start or damage. Test written before
production change. RED/GREEN results recorded below after execution.

Windows Java 8 RED: one test completed, one failed, `BUILD FAILED in 8s`.
Remaining timer survived, but transient native presentation instance was lost;
return assigned new instance instead of 4242. Production unchanged for RED.

Cursor fix: persistent native status cursor fields, retaining transient owner.
Windows GREEN: all five `NativeFloorSaveTest` tests pass, `BUILD SUCCESSFUL in 11s`.

Next slice: Campaign Save keeps different logical areas even when using same
content definition. Native bytes, world object state and consumed spawners
round-trip; carried items and Participant statuses stay Campaign scoped.

Save-history slice RED: Windows compile failed (six missing floor-history API
symbols), `BUILD FAILED in 4s`. GREEN after immutable floor records and format-6
codec: focused test passed, `BUILD SUCCESSFUL in 11s`.

Next slice enters Host lifecycle, using actual native Level checkpoints. Return
must retain open door, wounded survivor, defeated enemy removal, changed tile,
original identities and source-floor drop; destination drops remain dormant.
Carried item follows Slot; allocator cannot reuse dormant IDs. Cold resume must
retain both areas. Builder for visited floor must never run.

Host lifecycle RED: missing activation API, `BUILD FAILED in 3s`. GREEN: native
round-trip plus cold resume passed, `BUILD SUCCESSFUL in 7s`. Test fixture return
uses original content ID and actual saved-level collision world on cold resume;
headless rectangular collision is not valid for this native 4x4 Level.

Broader 39-test check exposed five old-format fixtures that removed only prior
format footer. Updated historical fixture lengths to also remove empty format-6
history (26 bytes); all 39 focused checks pass, `BUILD SUCCESSFUL in 11s`.

Persistent fire slice RED: return rerolled lifetime, one test failed, `BUILD FAILED
in 7s`. Fix persists runtime origin and keeps saved lifetime on `LEVEL_LOAD`.
GREEN: native floor and fire replication checks pass, `BUILD SUCCESSFUL in 9s`.

Native poison bridge slice RED: bridge replaced restored native effect, losing
private damage cadence, one test failed, `BUILD FAILED in 7s`. Fix uses native
checkpoint state for restored monsters instead of presentation DTO reconstruction.
GREEN: poison resumes damage after two ticks, keeps original instance and applies
no start callback on preparation, `BUILD SUCCESSFUL in 12s`.

Item bridge slice expects one durable drop after return, unchanged identity and
native velocity; old world binding must not materialize on destination.

Item fixture first failed lobby setup, before exercising bug. Corrected to real
TCP/UDP remote; reran unchanged original bridge: RED duplicate physical drop,
`BUILD FAILED in 14s`. Stable native item tags, destination binding cleanup and
native checkpoint binding fix passed: GREEN `BUILD SUCCESSFUL in 11s`.

Format-5/build-53 migration slice RED: previous Campaign rejected incompatible
build, `BUILD FAILED in 7s`. Add explicit predecessor migration to build-54;
content hash still checked and exact original bytes backed up before replacement.
Migration GREEN: all CampaignSaveStore checks, `BUILD SUCCESSFUL in 11s`.

Native travel fixture corrected protected field setup and avoided unrelated
localized TriggeredWarp constructor. Behavioral RED: stairs entered native screen
transition instead of staying in Party floor (`NullPointerException` in native
path, `BUILD FAILED in 11s`). Guards cover stairs, level change, warp and exit.
Saved terrain regression RED: movement restore rejected checkpoint against
pristine tiles, `BUILD FAILED in 7s`. Native Host startup now derives collision
from saved checkpoint. GREEN: six native session/escape/save checks plus saved
terrain regression, `BUILD SUCCESSFUL in 12s`.

Attack animation regression fixture uses reflective setup of native private
animation definition; assertions use public native capture. Behavioral RED:
return lost ATTACK identity/current pointer, `BUILD FAILED in 7s`. Persist native
playback ID and current animation reference, reserve loaded IDs. GREEN: native
floor and status/animation replication checks, `BUILD SUCCESSFUL in 12s`.

Connected observer slice: prior-floor world drops, door/break state and queued
trigger sounds must clear on new generation; carried inventory stays Campaign
scoped. Native reconstruction pause remains held.

Connected observer RED: old world drop stayed visible, `BUILD FAILED in 6s`.
Clear floor-owned Client state on generation, preserve carried ownership. GREEN:
real TCP/UDP floor swap, `BUILD SUCCESSFUL in 12s`.

Delayed trigger RED: remaining delay survived but original remote activator was
lost, `BUILD FAILED in 5s`. Persist bodyless identity/activation pose; rebind current
Party progression on return. Native runtime context remains transient. GREEN:
native floor, ownership and presentation routing checks, `BUILD SUCCESSFUL in 13s`.

Frozen death-drop clock check: 150 unpaused ticks on destination leave dormant
120-tick expiration unchanged; cold load and return retain same remaining clock
and item. Pass, `BUILD SUCCESSFUL in 10s`.

Queued command fixture initially used input beyond validated lead window; that
input was rejected before exercising transition. Corrected to next valid input.
Behavioral RED: prior-floor queued input ran on destination, `BUILD FAILED in 8s`.
Quiesce session, discard commands, recheck pause under session monitor and fence
delayed transport output. GREEN with headless session checks, `BUILD SUCCESSFUL in 13s`.

Carried Participant status RED: unchanged native body had poison replaced during
floor bridge attachment, `BUILD FAILED in 7s`. Track restoration by actual body
identity, so replacement bodies still restore but unchanged bodies keep native
cadence. Corrected fixture to use full `StatusEffect.tick`, which advances duration;
`doTick` only advances effect behavior. GREEN: 60 persistence/native/combat checks,
`BUILD SUCCESSFUL in 19s`.

Observer movement RED: no reliable destination position while paused, `BUILD FAILED
in 7s`. Publish destination baseline, clear prior interpolation history, retain Slot
identity and sequence fences, reject UDP until reliable baseline arrives. GREEN:
connected observer plus movement replication checks, `BUILD SUCCESSFUL in 12s`.

Global identity exhaustion RED: dormant `Long.MAX_VALUE` wrapped allocator on cold
resume, `BUILD FAILED in 7s`. Check bounds before incrementing every active/dormant
identity. GREEN: controlled rejection without ID reuse, `BUILD SUCCESSFUL in 9s`.

Native family coverage corrected initial assumption about projectile/visual cleanup:
`MagicMissileProjectile` and native explosion particles/sprite/light all currently
set `persists=true`; native `Level.preSaveCleanup` removes only non-persisting or
inactive roots (after disposal). Preserve those existing entities and remaining
motion/fuse state. Do not refire original Explosion. Existing native floor test
covers explicitly non-persisting roots, removed props and refusal of incomplete
graphs. Network generation independently clears queued explosion/action/audio cues.

Corrected native family test exposed lost bomb damage attribution: native causal
Participant string was transient, `BUILD FAILED in 7s`. Persist that scalar string
without serializing Participant body. GREEN: all native floor/explosion checks,
`BUILD SUCCESSFUL in 12s`. Compatible native field serializer accepts older assets
and checkpoints with absent new fields.

## Activation integration contract

`DirectConnectHost.activateCampaignFloor(areaKey, floorId, seed, fingerprint, build, install)`
is preservation boundary for #27. `areaKey` identifies logical visited area;
`floorId` identifies native content definition. Two areas may share definition.
Return is first-arrival flag; a revisit never calls builder and uses saved seed,
fingerprint, native graph and floor ledgers.

Caller runs on native render thread with Party pause held. Builder prepares first
arrival using Campaign mode and supplied seed, with no original single-player
save access. Native checkpoint capture and validated atomic Campaign Save happen
before live floor swap. Movement/combat/item worlds switch together; carried
inventory, Participant status and Party facts remain Campaign scoped. Incoming
source commands, transient cues and obsolete Client interpolation are discarded.

Installer must dispose source native presentation/audio, install destination as
sole `Game.level`, initialize restored graph with native `LEVEL_LOAD` semantics,
and reattach native movement/item/combat/economy/lives bridges. It may replace
default capture callback to synchronize current Party facts before future saves.
Do not rebuild returned monsters/items or rerun initial spawn/gameplay callbacks.
On installation failure Host marks failure and keeps pause; caller must abort
recovery, as existing native startup does, rather than continue partial scene.

#27 announces destination to peers, installs their native presentation, waits for
readiness and releases pause. Existing first-arrival Fresh Return boundary remains
owned by #25/#27. Native local stairs/warp/level-change/exit entrypoints are guarded
in Direct Connect so individual Participants cannot install separate floors.
Single-player entrypoints retain original behavior outside Direct Connect.

## Final validation

Windows 11 / Temurin Java 8, owned-copy checks enabled:

```text
prlctl exec 'Windows 11' cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && set "OWNED_GAME_COPY_TEST=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" && gradlew.bat Dungeoneer:test DungeoneerDesktop:test DungeoneerDesktop:classes --daemon --console=plain'
```

`BUILD SUCCESSFUL in 2m 58s`; seven Gradle tasks executed. XML reports: 619 core
tests, 16 desktop tests; zero failures, errors or skipped tests. Includes real
TCP/UDP floor cleanup, Campaign cold resume, save migration/recovery and owned-copy
asset/native gameplay regressions. `git diff --check` passes.

Existing owner Host/Client commands keep same properties. Owner can check startup,
shared current-floor gameplay and save/resume now. Interactive leave/return,
Party travel presentation and frozen-floor gameplay acceptance require #27 travel
integration; automated Host/native checkpoint tests exercise that preservation
boundary directly. No game windows launched and no owner gameplay acceptance
claimed. Owner authorized commit, push and issue closure after validation;
interactive travel integration remains tracked by #27.
