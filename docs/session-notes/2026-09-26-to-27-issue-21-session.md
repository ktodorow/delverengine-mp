# Issue #21 implementation and playtest session

**Session dates:** 2026-09-26 to 2026-09-27

**Repository:** `ktodorow/delverengine-mp`

**Branch:** `mp-v108-prototype`

**Issue:** [#21 — 20: Create, save, and resume Co-op Campaigns](https://github.com/ktodorow/delverengine-mp/issues/21)

**Product source:** [#1 — Multiplayer PRD](https://github.com/ktodorow/delverengine-mp/issues/1)

**Status at handoff:** implementation and automated regression coverage present in shared working tree; no commit created; owner gameplay acceptance still in progress.

## Executive summary

Issue #21 establishes durable co-op continuity. Host owns a Campaign Library and authoritative Campaign Save. Host can create a Campaign, close it, relaunch with same profile, select saved Campaign, and resume saved participant and world state. Client never writes authoritative Campaign state.

Session also clarified active-session admission. Returning player must reconnect without pausing Host. New player may hot-join active floor when Campaign has unowned slot. Host approves valid claim automatically. Join is blocked or delayed during unsafe floor transition until Host can send coherent authoritative snapshot.

Owner playtesting exposed six concrete faults beyond initial implementation:

1. Host and Client could report content mismatch when not launched against same owned archive.
2. Saved Owned Game Copy tutorial Campaign could fail after listener bind, then leak port `37777`.
3. Reconnecting Client temporarily changed Host global phase and paused gameplay.
4. Reconnect used large center-screen “Direct Connect stopped” text instead of small presence notice.
5. Client missed native monster blood/hit presentation and cold-resumed world interaction state.
6. Cold-resumed dead monster appeared as corpse on Client but disappeared on Host.

Each reproducible code fault above was fixed and covered by focused tests. Latest focused and desktop suites pass. Full core run had 11 real-time `DirectConnectIntegrationTest` deadline/socket failures under loaded Windows VM; changed-path focused suites passed, so those full-suite failures remain timing evidence rather than accepted issue #21 regressions.

## Scope and source-of-truth findings

Issue numbering is easy to confuse:

- GitHub issue **#21** has sequence title **“20 — Create, save, and resume Co-op Campaigns.”**
- It is not “PRD User Story 21.”
- It combines durable-campaign requirements from PRD into persistence foundation needed before campaign travel/progression work.
- Direct blocker dependency closure covers GitHub issues **#2 through #8**. Review found all declared direct and transitive blockers closed and their commits present in branch ancestry.
- Repository convention did not require assignment, label change, or work-in-progress comment before implementation.

Detailed source analysis remains in:

- [`research_notes/Issue 21 campaign travel/issue21_contract.md`](../../research_notes/Issue%2021%20campaign%20travel/issue21_contract.md)
- [`research_notes/Issue 21 campaign travel/prd_story.md`](../../research_notes/Issue%2021%20campaign%20travel/prd_story.md)
- [`research_notes/Issue 21 campaign travel/blockers_readiness.md`](../../research_notes/Issue%2021%20campaign%20travel/blockers_readiness.md)
- [`reports/Issue 21 campaign travel.md`](../../reports/Issue%2021%20campaign%20travel.md)

## Working constraints

- Use existing repository folder and current branch. No parallel checkout or second repository folder.
- macOS hosts source checkout. Windows 11 Parallels VM builds and runs game through `\\Mac\Home\Repos\delverengine-mp`.
- Owner performs visual/gameplay test. Agent does not launch Host or Client game windows.
- Owned `delver.jar` stays external and read-only:
  - macOS: `/Users/kristiyantodorov/Downloads/Delver.v1.08/Delver.v1.08/delver.jar`
  - Windows VM: `\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar`
- Separate profiles prevent Host/Client identity and reconnect-token collision:
  - Host: `C:\DelverMpProfiles\Host`
  - Client: `C:\DelverMpProfiles\Client`
- Code Context Engine was used for code retrieval and cross-session decisions.
- Existing working-tree changes belong to owner and must not be reset or discarded.

## Product model agreed during session

### Campaign Library

Host launch without explicit `-PcampaignId` opens Campaign Library. Library:

- lists roster-only Campaigns as `Not started`;
- lists Campaigns with checkpoint as `Saved Campaign`;
- shows locked capacity and claimed-slot count;
- creates new Campaign with `[N]`;
- selects with Up/Down;
- hosts selected Campaign with Enter.

Explicit `-PcampaignId` remains automation/developer bypass. Listener starts only after Campaign selection, preventing connection from binding to wrong Campaign.

### Campaign ownership and authority

- Host profile owns Campaign roster and save.
- Host alone writes authoritative checkpoint.
- Client receives snapshots and reconnect credentials but does not write Campaign Save.
- Capacity becomes Campaign property and remains locked after creation.
- Existing owned slot belongs to authenticated player identity.
- Missing saved participant stays inert until owner returns.
- Campaign may resume with subset of saved participants.

### Save boundary

Campaign Save persists co-op state needed to continue same run, including:

- Campaign identity, capacity, starting lives, and outcome;
- floor identity, generation seed/fingerprint, and native generation state;
- claimed slots and participant state;
- participant position/movement, health/lives/progression, inventory/economy, and relevant status;
- physical item identities and authoritative item state;
- monster/combat state and actor status effects;
- doors, breakables, keys, and related interactable state;
- party communication needed by active session.

Save format uses `DMPS` magic, bounded decoding, and maximum size of 16 MiB. Corrupt, future, protocol-incompatible, build-incompatible, or content-incompatible save must fail before playable state is exposed.

### Original single-player saves

Co-op Campaign never imports, resumes, or overwrites original Delver single-player save slots. Prepared multiplayer floors use Campaign mode, not editor mode or native single-player save flow. Owned archive supplies content only.

### Reconnect and hot-join policy

Conversation changed initial conservative behavior into following contract:

- Returning player may reconnect to owned Campaign Slot during active floor.
- Host must continue simulating while that connection authenticates and registers UDP.
- Reconnect within grace and return after grace use same visible presence wording: `<nickname> joined`.
- Disconnect shows `<nickname> left`.
- Presence appears in top-left party communication feed, not as large center-screen stop overlay.
- New unknown player may hot-join active floor if Campaign has unowned slot.
- Host automatically approves valid slot claim; no manual approval prompt.
- Capacity still applies. Full Campaign rejects new claim.
- Existing owned slot cannot be stolen by different identity.
- Mid-floor join receives Host's full current authoritative state before control becomes READY.
- Join must not cross an incomplete floor transition. Admission waits or rejects until generation/state snapshot is coherent.

This is intentionally permissive hot-join. Earlier concern was not that hot-join is inherently wrong, but that partial state transfer can create duplicated items, missing dead actors, stale doors, divergent seeds, or unsafe spawn during floor replacement. Chosen response is complete authoritative synchronization plus transition guard, not blanket “lobby only” restriction.

## Chronological conversation and findings

### 1. Start and inspect issue #21

Requested work began with PRD issue #1, complete issue #21, comments, and closed blockers. Inspection established persistence-foundation scope, closed blocker readiness, existing branch suitability, and no need for another checkout.

### 2. Gradle restart and “new build” question

`--no-daemon` means Gradle starts a disposable JVM and stops it after task. “A new JVM will be forked” does not mean new multiplayer build identity. Gradle recompiles changed inputs and otherwise reuses up-to-date outputs.

Restarting same Host command is safe when old Host has exited. Starting second Host while first still owns port is not safe and produces `java.net.BindException: Address already in use`.

### 3. Reconnect behavior

Client could join before Campaign start but could not return after closing while Host remained active. Discussion separated:

- **reconnect:** known player reclaiming already owned slot;
- **hot-join:** new player claiming available slot after play started.

Owner requirement: Host must not pause for either healthy active-floor reconnect or valid hot-join.

### 4. Capacity four and late player

With capacity four, Host plus one Client leaves two possible unowned slots. Chosen behavior allows new player to claim one automatically during active floor after compatibility/authentication checks and safe snapshot preparation.

### 5. Campaign Library visibility and content mismatch

Host screenshot showed listener for `open-source-test`, capacity 2, claimed 2. Client screenshot showed:

```text
CONTENT_MISMATCH: Content mismatch: normalized content identity differs from Host.
```

Meaning: peers did not advertise same normalized content identity. Typical cause is one launch using Owned Game Copy and other using open-source/default content, or different owned archive path/content. Both commands must point to same `delver.jar`. Diagnostic text was improved to explain this directly.

### 6. Old Campaign entries

Campaign Library showed historical records such as `friends-test`, `issue11-test`, `issue15-v19`, `issue20-open-floor`, `issue9-manual-1`, `issue9-validation`, and `open-source-test`. They were saved test profiles, not required game assets. Deleting them only removes multiplayer Campaign records for that profile.

### 7. Saved Owned tutorial failed, then port remained occupied

First Enter displayed:

```text
Owned floor requires Owned Game Copy content and a levels/*.bin path.
```

Second Enter crashed with:

```text
java.net.BindException: Address already in use: bind
```

Root cause had two parts:

1. Save persisted canonical symbolic tutorial token `owned-game-copy-tutorial`; owned-floor launcher accepted only `levels/*.bin` path.
2. Host listener had already bound before later startup failure. Failure returned to library without closing listener, leaving port `37777` occupied.

Fix normalizes saved tutorial token to normal Owned tutorial launch and closes any partially started Host when later startup step fails.

Serializer warnings about constructing `Trigger` were visible but were not cause of port crash.

### 8. Reconnect paused Host and showed false stopped overlay

During successful Client return, Host displayed:

```text
Direct Connect stopped: Friend owns Campaign Slot 2. Waiting for authenticated UDP registration...
```

Root cause: per-connection reconnect temporarily moved global Host phase from `READY` to `REGISTERING_UDP`. Gameplay loop intentionally advances only in `READY`, so healthy Host paused and UI correctly—but misleadingly—treated session as stopped.

Fix keeps global Host phase `READY` after session starts. Returning connection performs UDP authentication in its own connection state. Actual connection/session failures still use prominent failure UI.

### 9. Presence-message UX

Owner rejected large center-screen message for normal join/rejoin. Presence now uses Host-authored system messages in top-left party feed:

```text
Friend joined
Friend left
```

Rejoin deliberately uses same `joined` wording. Protocol moved to version 44 because party-chat wire payload gained system-message flag. Build ID remains `mp-v108-prototype-campaign-library-51` to preserve issue #21 Campaign Save compatibility.

### 10. Blood missing on Client and resumed world not interactive

Owner observed:

- Host damaged monster and saw native bleeding/hit animation; Client did not.
- Saved Campaign resumed at saved position with saved health/items, but Host and Client could not interact correctly with world.

Fixes:

- Replica damage presentation calls native hurt animation and monster `hitEffect` without mutating authoritative HP. Client now gets visual blood burst while Host remains combat authority.
- Cold-resume synchronization uses canonical current-session snapshot before Client becomes READY: generation, monster spawns/effects/dynamics, keys/economy, doors, breakables, physical items, and communication state.
- Resumed Host item setup discards rebuilt default physical items, registers capacity, restores saved physical identities, and reconstructs Host-only metadata. This prevents fresh/default items coexisting with restored ones.
- Immutable `resumedCampaign = saved != null` distinguishes cold resume from newly created checkpoint. Earlier check against later non-null durable save accidentally treated fresh Campaign as resumed.

### 11. Client showed monster/corpse absent on Host

Screenshot showed both HUDs at `ENEMIES ALIVE 2/3`, while only Client displayed green dead-monster corpse. Read-only inspection of Host save confirmed three combatants with health `24`, `0`, and `4`. Therefore object was saved dead enemy presentation, not extra live Client-only monster.

Root cause:

- Client network death recovery already rebuilt corpse.
- Host cold-resume path only marked saved dead Monster inactive.
- Host therefore lost corpse presentation while Client correctly reconstructed it.

Fix silently reconstructs Host corpse, including saved gib outcome, before restoring saved animation/effect cursor. It does not replay death particles, sound, loot, XP, or triggers.

### 12. Follow-up: after resume Client still saw enemies Host did not (any enemy)

Corpse fix above did not resolve owner report. Deeper inspection (Claude session, 2026-09-27) found corpse explanation incomplete: `campaing2` saved `monster:1` as **gibbed** (no corpse), and `campaing3` saved both slimes dead, yet Client still showed live enemies.

Evidence:

- Saved floor fingerprint for owned tutorial: `monsters 0`. Tutorial places **no** native Monster; every enemy is a late arrival.
- Tutorial level dump (`levels/tutorial.bin`): two `MonsterSpawner DUNGEON/SLIME` with `waitForTrigger=false` at (45.5, 23.5) and (46.35, 24.27) — saved `monster:1`/`monster:2` — plus `SURPRISE/SPIDER` spawners fired by trigger chain `SPIDER_SPAWN_DELAY → SPIDER_SPAWN`. Dev-menu (K) monsters are late too.
- Combat controller attaches before first tick, so spawner Monsters are late on both peers; Host keys/announces them (`NativeMonsterSpawn`).

Root cause, two halves:

1. **Client spawned its own enemies.** `MonsterSpawner` had no multiplayer gate; Client only disabled ambient `Level.spawnMonsters`. Restarted Client's tutorial spawners (and locally touched triggers) created untracked native Monsters with local AI. Host never had them.
2. **Host could not rebuild late enemies.** Save kept id/health/position but not *what* Monster (theme/name), and `nativeMonsterSpawns` was not persisted. Rebuilt Host floor lacked dev-menu/triggered Monsters (HUD still counted them), while level-start spawners re-fired and were mapped onto saved ids by spawn order (accidental).

Fix:

- `MonsterSpawner.spawn` asks new `Level.nativeMonsterSpawnerListener`: Client always refuses (Host announces every Monster); Host refuses spawners whose stable placement key is saved as spent, and records newly spent ones.
- Client discards any active hostile Monster Host never announced (`discardUnannouncedNativeMonsters`).
- Campaign Save format 2 persists Host `NativeMonsterSpawn` records and spent spawner keys. Resumed Host recreates saved late Monsters natively under saved ids before first tick (saved health/position/corpse applied on bind), replays spawns to Clients, and gives new arrivals ids above saved ones.
- Format 1 saves are rejected with "create a new Campaign"; they lack Monster identity and cannot be rebuilt faithfully.

Known remaining limit (#31 scope): Host trigger `PLAYER_TOUCHED` fires only for Host's own `Player`, not a Client `RemoteAvatar`, so a trap touched only by Client spawns nothing on either peer.

### 13. Esc only flickered the mouse; no pause menu

Owner: Esc must open native pause menu (Back, Options, Quit) without pausing the co-op world; P stays the Party pause.

Root cause: Direct Connect deliberately keeps ticking the world under a pausing overlay (`GameScreen`), so `GameManager.tick` ran its Esc code on the next frame while the key was still held and popped the `PauseOverlay` it had just opened. The 2026-09-21 fix (`2fbfbd8`) set `Game.ignoreEscape` after opening, but the close-top-overlay branch never read that flag. Single-player never hit this because `GameManager.tick` does not run under a pausing overlay; that overlay owns Esc.

Fix: `GameManager.handleEscape` leaves Esc to an overlay that would pause single-player (`OverlayManager.shouldPauseGame()`), matching native ownership. Esc polling moved to `GameManager.pollEscape()`; `GameScreen` also polls it in Direct Connect while the world is held still (Party pause via P, stopped session), so the menu and Quit stay reachable. Local input already freezes under the menu; world, Host simulation and other players keep running. Regression tests in `GameManagerEscapeTest`; mutation without the gate reproduces the flicker.

### 14. Saved Campaign: doors would not open/close, breakables would not break

Only after resume; fresh Campaigns were fine. `DirectConnectItemController.restoreWorldObjects` applied saved Door/Breakable snapshots on the **Host** through the client replica methods: `Breakable.applyNetworkSnapshot` sets `nativePresentationReplica` (its `tick` then returns before `onBreak`), and `Door.applyNetworkSnapshot` stores `networkSnapshot` (its `tick` re-applies that frozen state every frame). Host lost authority over every saved door and breakable, so neither peer could change them.

Fix: `Door.restoreAuthoritativeSnapshot` / `Breakable.restoreAuthoritativeSnapshot` restore saved outcome while staying native (door keeps its built closed placement as `startLoc`, animation timing matches native open/close). Client replica path unchanged. Regression: `DirectConnectCampaignPersistenceTest.coldResumeKeepsSavedDoorsAndBreakablesUnderHostAuthority` (fails with old replica calls).

Still not persisted by curated save (reset on resume): triggers/buttons/levers and trigger-driven item/entity spawners, elevators/movers, shop stock, trap state, secrets/map knowledge (#24), decals. Native Delver save avoids this class of gap by serializing the whole Level; see design note in session answer.

### 15. Host floor checkpoint like the single-player save (ADR 0014)

Owner asked why co-op save cannot work like the original save. Answer: single-player serializes its one world; co-op keeps one world per peer with shared ids, and the first-cut Campaign Save rebuilt the floor and reapplied a hand-picked state list, so every unlisted or misapplied family broke on resume.

Implemented (save format 3):

- `NativeFloorSave.capture` serializes the running Host floor with Delver's `KryoSerializer`, leaving out what `preSaveCleanup` would drop (non-persisting, inactive, replicas, players) without disposing anything; compressed, bounded to 12 MiB (owned tutorial ~58 KB). `restore` loads it; `Level.loadForCampaign` then runs `init(LEVEL_LOAD)` like a loaded single-player level.
- `Entity.multiplayerIdentity` (serialized) records each bound id: `object:<id>` (item controller world objects), `monster:N` (tracked Monsters), `hazard:N` (traps). A restored floor reuses them, so moved/opened objects keep the ids clients derive from their fresh build.
- Host: `setNativeFloorCapture` (render thread, set on floor entry), `takeRestoredNativeFloor`, checkpoint before floor entry keeps last saved floor. `GameApplication.enterDirectConnectFloor` loads the checkpoint instead of building; pristine level still supplies item templates; saved fingerprint verifies clients.
- Restored floor skips per-family reapply (doors/breakables, Monster state, late Monster recreation); ledger items, Participants, combat record and effects still restore as before. Clients unchanged: build from own copy, receive snapshots and replayed spawns; never receive the serialized floor.
- Format 2 saves load and use the old rebuild path; format 1 rejected.

Tests: `NativeFloorSaveTest`, owned `OwnedSharedFloorBuildTest.builtTutorialCheckpointReloadsLikeASinglePlayerSave` (real tutorial build → checkpoint → reload keeps world objects, traps, spawners, door state and ids), controller/persistence/store tests for ids, checkpoint retention and format 2/3.

### 16. `camp2` playtest: Client corpse bright, no blood; trap not firing from Client

Owner screenshots (Host left, Client right) after resuming `camp2`: same slime death frame on both, but Host's sits dark in a green blood pool, Client's is bright with no pool; Client showed no blood when Monsters were hit; tutorial spider trap fired only for Host.

Findings:

- **Bright corpse (live and resume):** `Corpse.setNetworkReplica(true)` cleared `isDynamic`; `DrawableSprite` treats a non-dynamic entity as static and the renderer skips lightmap for static sprites, so client corpses drew unlit. Replica tick never runs physics anyway; replica now stays dynamic (velocity zeroed).
- **No blood on Client:** native `Monster.hit` leaves a splatter decal, native `die` a blood pool, and native `tick` drips blood below half health; replica paths only played hurt animation/particles and the corpse. Splatter and pool code now shared (`spawnBloodSplatter`, `spawnBloodPool`); replica damage presentation leaves the splatter, replica death (live or rejoin recovery) the pool, and wounded replicas bleed.
- **Trap from Client:** native touch triggers query only solid entities; a client `RemoteAvatar` is not solid, so Host never fired. Host item controller now fires `PLAYER/ACTOR/ANY_TOUCHED` triggers for each overlapping, standing remote avatar with that Participant as activator (native AABB). Context flag `ParticipantContext.presentedByActivator` marks such chains: the client's own copy shows screen-only effects, so Host skips `Trigger.message`, message/dialogue overlays (still continuing `triggerIdAfter`), flash, music, ambient sound and achievements; a remote touch never runs `TriggeredWarp` (Party travel is #27).

Still open: blood/sword decals made before a save exist on Host (checkpoint) but not on a rejoining client; client use of message/sign triggers still shows on Host's screen (ADR 0011 targeted presentation).

### 17. Rejoining client gets Host's floor marks; used signs show on the activator's screen

- **Decals:** Host combat controller keys decals in level entities at attach (`decal:floor` for built, restored `decal:N` recorded and announced) and records each new play-time mark (`decal:N`, not announced: live clients drew it). `DirectConnectHost` keeps the current floor's marks (512, cleared on floor change) and replays them in `sendCurrentSessionState`; client queues them by world generation and rebuilds `ProjectedDecal`s. Rejoin recovery no longer makes its own pool.
- **Trigger presentation:** screen-only effects moved into `Trigger.presentToActivator(value, continuesChain)`; `presentForActivator` shows it for own player, delivers it via Host (`deliverTriggerPresentation` → `TriggerPresentationMessage`) to a client that used the trigger, and skips a client that walked into it. Client item controller presents on its own trigger copy with `continuesChain=false`; Host continues message/dialogue `triggerIdAfter` immediately for a client activator.
- **Protocol 45** (wire types 53, 54). Campaign Save reader now refuses only saves from a newer protocol; layout is versioned by save FORMAT, so existing format-3 saves (`camp2`) still load.

Tests: `NativeDecalMessage`/`TriggerPresentationMessage` wire round-trips, Host decal keying/announce and client rebuild, trigger routing (own/used/touched), Host+client integration (joining client gets marks; presentation reaches its activator), live vs rejoin pool.

### 18. Every Participant got Host's random starting kit

Owned `data/player.dat` starting kit: Iron dagger, Leather armor, worn Leather pants (fixed) plus one random potion, wand and food (`ItemSpawner` rolls). Only Host's native Player rolled it; `DirectConnectItemController.attach` then copied Host's rolled items (`ItemManager.Copy`) for every remote Participant, so all kits matched. PRD stories 85/86 require a normal kit per character; nothing tracked per-character randomness.

Fix: new Campaign attach keeps Host's own kit and calls `grantStarterKit(participant, hostKit)` for each other Participant, which loads a fresh player template and rolls it by `Player.init` rules (`rollStartingKit`: armor worn and registered first, rest carried). Missing template falls back to copies. Resumed Campaigns keep saved kits. Late Participant admission (#25) can reuse `grantStarterKit`; late joiners currently receive no kit.

First full run failed three production-controller integration tests: `OwnedTutorialSmokeTest` left retail `Game.gameData`/`Game.modManager`/`EntityManager` installed, so later fake-catalogue tests rolled kits their client could not materialize. That test now restores those globals; a roll that throws or yields nothing falls back to copies. The same smoke test shows an owned client catalogue materializes Host's rolled starter items.

### 19. Resume crashed loading the floor checkpoint; tutorial had lost 431 of 530 entities

Owner log (`camp-21issue`): `[Serializer] Error constructing instance of class: ...Trigger` / `...Bow`, cascading `IndexOutOfBoundsException` in reference reads, then `KryoException: Encountered unregistered class ID: 63` from `NativeFloorSave.restore` at floor entry.

Cause: Trigger (field initializer `useVerb`), TriggeredShop and some items read `StringManager` while deserializing. Direct Connect floor entry loads levels before anything initializes localized strings; the capture-time load-back check passed only because the running game had them. Delver's `ArraySerializer` then stops the entity list at the first entity it cannot build and later reference ids break.

Same cause, pre-existing since multiplayer used the owned tutorial: `loadDirectConnectLevel` initialized strings only for `levels/*.bin` floors, so the tutorial loaded 99 of 530 entities (no Triggers) on every peer. Consistent across peers, so fingerprints matched and nobody saw it.

Fix: `loadDirectConnectLevel` initializes strings for every owned floor before loading; an unreadable checkpoint falls back to rebuild-and-reapply with an error log instead of crashing. Regression: owned `OwnedTutorialSmokeTest.ownedTutorialKeepsEveryEntityThoughLocalizedStringsWereNotLoadedYet` (530 entities, 9 Triggers).

Consequence: existing tutorial Campaigns (`camp-21issue`, `camp2`, ...) were built and fingerprinted from the truncated floor. Host can load them, but a client now builds the full floor and is rejected as a floor mismatch; start a new Campaign. The full tutorial also brings back its triggers, messages and props, which multiplayer never exercised before.

### 20. Climbing walls, shaking in water, stuck at the ladder (Host and Client)

Owner videos (new Campaign on the full tutorial): Host walks into a grass-topped stone block and ends up standing on it; Host and Client bob about three times a second in the forest pool; Client in the water pit cannot climb the ladder out. Both instances show all three.

Cause: every peer predicts its own Participant with native `Player` physics and `LocalMovementReconciler` pulls it toward the Host's movement simulation. That simulation was a simplified model, so each rule it lacked became a correction:

- Jump: `JUMP_SPEED 3.2`, `GRAVITY 9.8` peaked at 0.52 against native 0.38 and kept full air control, so the Host jumped onto ledges the native Player cannot and dragged it up (clip 1).
- Water: Host movement loads the floor file raw and never runs `Tile.init`, so its tiles had no `TileData` (`raw water tiles = 0` against 44 live). Host kept Participants 0.4 above the native water floor at land speed; correction lifted them, native gravity dropped them (clip 2, measured three-hertz waterline oscillation). The #19 water model only ever ran in tests.
- Ladder: the tutorial's one Ladder (48.6, 15.0) was among the 431 entities lost before section 19; Host treated it as a plain solid box (clip 3).
- Once water worked, a latent bug surfaced: Host took the in-water step height at the target position from the highest water tile it touched, so a waterfall column (floor 7.66 beside the 2.79 pool) let it climb five units.
- Also found: slopes, air friction and surface friction were not modeled; a jump pressed on a frame with no input sample (most frames above 60 fps) never reached Host; `applyInitialAuthoritativeState` put per-second snapshot velocity into native per-tick `xa`.

Evidence: an owned differential walks native `Player.tick` (headless, with `Ladder.tick` and the collision hash as `Level.tick` does) and Host movement from every open tutorial tile in eight directions, walking and jumping. Before: about 1,800 of 9,184 walks diverged. After: 0 of 9,984.

Fix (ADR 0015): `AuthoritativeMovementSimulation.step` is a port of native `Player.tick` movement physics; `MovementCollisionWorld` gained native tile, slope, friction, object and ladder queries; `LevelMovementCollisionWorld.adoptTileRules` copies tile rules from the live floor at Host floor entry; `NativeMovementObstacles` adds ladder climb areas; clients latch jump presses; snapshot velocity is the move velocity.

Regression: owned `OwnedSharedFloorBuildTest.hostMovementFollowsNativePlayerAcrossTheTutorial` (the differential, which also requires at least 40 water tiles) and `hostClimbsTheTutorialPitLadderAndStaysOffTheWaterfallLikeNativePlayer`; `LadderMovementTest`, `WaterMovementTest.waterfallAheadNeverLiftsParticipantOutOfPool`, `AuthoritativeMovementSimulationTest.jumpRisesAsHighAsNativePlayerJumpAndLands`, `DirectConnectMovementControllerTest.jumpPressedOnAFrameWithoutInputSampleReachesTheNextSample`, `LevelMovementCollisionWorldTest.hostCopyTakesWaterAndClosedTilesFromTheInitializedLiveFloor`. Test caveat: `TileManager` loads its data once, when `Tile` is first touched; in a mixed test run that happens before the owned copy is mounted, so the owned floor test now installs owned tile data itself (the game mounts the owned copy in its launcher first).

Follow-up (owner: fix remaining gaps unless a future issue owns them; none did):

- Walk speed: Host movement used base Speed 4. The combat controller now sends each Participant's native `Player.getUnaffectedWalkSpeed()` (Speed stat, equipment, and for remote Participants the Host shadow Player plus the wielded item) four times a second; status effects still multiply it.
- Monster bodies: replicas were non-solid since #15, so clients walked through Monsters, the Host's own Player did not, and Host Monsters walked into remote Participants. Now Host movement copies Monster bounds every frame (native Actor bounce on top), and `Level.movementBodies` (consulted only for a moving local Player or native Monster) makes Host Monsters stop at remote Participants' current Host positions and a client's Player stop at living replica Monsters. Traps, triggers, projectiles, explosions and weapon sweeps never see these bodies. Life-exhausted Spectators leave no body. Regression: `NativeMonsterBodyTest`, `NativeWalkSpeedTest`.



### 21. Still climbing walls; Client cannot set off the spike trap; end door stays shut on Client

Owner: both instances still climb the wall; Client stepping on the tutorial spike trap does nothing; the orb room button opens the door on Host and lets Client walk through, but Client still sees it closed.

Wall climb: native `Player.tick` stands a Player on the highest floor its box overlaps (step-up branch uses `maxFloorHeight`), however high. `DirectConnectMovementController.applyCorrection` added Host corrections to x, y and z with no collision check. Owned proof (`nativePlayerNudgedIntoTheSpawnBlockIsLiftedOntoItWholesale`): at the spawn block (33,29) a Player touching the face stays at 0.17, one pushed 0.001 inside is at 1.59 after one native tick. Any sub-millimetre disagreement toward a wall (frame-rate integration, monster timing) lifted the Player; the next corrections dropped it again. A native-only scan of the tutorial found no dry-land climb, and a Player overlapping a slime is not lifted either. Fix: corrections move the box only where native walking could (tile step height of the local Player, no solid object), z first; a snap whose replayed target is blocked uses the Host position.

Spike trap: tutorial `SPIKES` (TRIGGERED Spikes) is fired by a pressure-plate `Mover` (ON_ANY_TOUCH, PRESSURE, sinks 0.03, `triggersIdWhenDone=SPIKES`), not a Trigger, so section 16's remote touch triggers never covered it. Native Movers start only from `encroached(Player)` and keep a plate down from solid bodies standing on them. Fix: Host presses Movers remote Participants stand on or walk into, and Host Movers see remote Participants through `Level.movementBodies`.

End door: the orb room "door" is Mover `CRUSHER` (ON_TRIGGER, rises 1.0) fired by a `ButtonModel`. Movers were never replicated, so the client's stayed shut while Host movement let the client through. Fix: Movers are world objects; `MOVER_STATE` (wire 55, protocol 46) carries transform and motion; client Movers are replicas that never start from local touches or triggers.

Consequence: a floor checkpoint saved before this build has no Mover ids; a Mover that had already moved (an opened CRUSHER) gets a different id on Host than on clients and stays shut for them. Start a new Campaign.

### 22. Tutorial finale: after 50-100 Monsters the client saw none, then "Invalid native dynamic state"

Owner stress test (Host in godmode, orb finale spawning wave after wave): the client stopped seeing Monsters, alive or spectating, while still seeing their magic; then the Host stopped the session with "Native presentation compatibility failure: Invalid native dynamic state."

Invisible Monsters: every Monster collection was bounded at 64 and never released a dead Monster (combat controller map, encounter, Host late-spawn list, Host and client effects tables). HUD "ENEMIES ALIVE 1/64" was 1 alive of 64 registered. The 65th Monster to enter the floor was never announced, so the client (which discards unannounced Monsters) never had it; its projectiles still replicated through native dynamic state. Fix: when a new Monster needs a slot, the one dead longest (at least 120 frames) retires from the controller, encounter, spawn list and effects; ids count past 64; clients prune effects of dead or vanished Monsters. The effects tables would otherwise have been the next crash.

Crash: one projectile Host could not describe (a value NaN or beyond one million, or an unsupported field) made `publishNativeDynamic` call `failNativePresentation`, ending the session for everyone; the exact value was not logged. Several other cosmetic paths did the same, and clients ended the session when a burst overflowed a 128-entry presentation queue. Fix: cosmetic presentation failures are logged once (with cause) and skipped, a previously drawn projectile is retired on clients, the in-flight bound is 256, and client queues drop their oldest cue. Content mismatches remain fatal.

### 23. Saving after the finale: "Saved native effect actor is absent from combat state"

Owner repeated the stress test (Monsters now visible), then quit the Host with the client dead and spectating. Host crashed in `GameApplication.dispose` → `DirectConnectHost.close` → `persistCampaign` → `CampaignSave.<init>` with "Saved native effect actor is absent from combat state." No save was written, so camp3 reopened from its last good checkpoint (its creation save): no items, fresh floor. That progress is lost.

Cause: the Host encounter always holds the legacy shared combatant (`shared-spider-1`), so it has 63 slots for native Monsters, but the combat controller allowed 64. The 64th tracked Monster never bound to the encounter, yet its status effects (finale magic) were recorded under its id. Campaign Save requires every non-Participant effect actor to be in the saved combat state, rejected the checkpoint, and `close()` rethrew. Fix: the controller caps native Monsters at `CombatSnapshot.MAX_NATIVE_MONSTERS` (63), and a checkpoint keeps only effects of Participants and of Monsters in the saved combat state, so a stray entry can never cost the Campaign its save.

Dead spectator on resume (owner question): a Life-exhausted Participant stays a Spectator for the rest of the Campaign (spec: no Lives regained). Saved while connected, it is recorded as Spectating with 0 Lives and resumes as a Spectator. Saved after it had left, presence is recorded as Disconnected with 0 Lives; resume used to read only presence, restored it as Standing with 0 Lives, and `AuthoritativeLives.restore` threw, so Host could not start that save while the friend was in the lobby. Resume now derives Exhausted from 0 Lives and clears bleedout/revival for anyone not Downed. A slot absent when the Host starts a resumed Campaign cannot join that session (only reconnects are accepted after start), so every returning friend passes through this restore. Tests: `spectatorSavedWhileDisconnectedResumesAsSpectator`, `effectsOfAMonsterWithoutACombatSlotNeverCostTheCampaignItsSave`.

### 24. Owner rule: spectating ends on the next new floor (Fresh Return)

Owner decision 2026-09-28, answering "a Spectator stays one for the rest of the Campaign": a Spectator returns when the Party first arrives on a floor it has never visited, as a new character with the campaign's starting Lives, a freshly rolled starter kit, no gold, level one, no XP or stat upgrades. Owner picked: only first arrival on a never-visited floor counts (no Lives from stair trips); a slot exhausted by being abandoned Downed at a transition returns right away at a new destination; Potion Knowledge and Map Knowledge are kept. Also recorded: equipped weapon/armor and unclaimed scatter on earlier floors are lost ("all is lost"). Recorded as **Fresh Return** in CONTEXT.md, spec "Lives, downing, and respawning", ADR 0005 and 0007, and issue #27 (Party floor travel, not built yet). No code change in #21: resume keeps a Spectator spectating on the same floor, which the rule allows.

Late Participants (owner decision, same day): the old catch-up (roster's lowest Lives and level) would have admitted a newcomer with 0 Lives while any slot spectated. A new friend now joins as a Spectator and enters at the Party's next Fresh Return exactly like a returning Spectator (starting Lives, starter kit, level one, no gold; empty knowledge). Recorded in spec "Characters joining an existing campaign", CONTEXT.md, ADR 0005 and 0006, issues #25 and #27.

### 25. Resumed finale save: "Native monster effects exceed encounter bound"

Owner resumed the finale save (Host log: `IllegalArgumentException: Native monster effects exceed encounter bound` from `DirectConnectHost.synchronizeNativeActorEffects` ← `DirectConnectCombatController.synchronizeNativeMonster`, render thread, Host exited).

Cause: the save held a combat slot and an effects entry (every tracked Monster has one, empty or not) for up to 63 Monsters, most of them dead. The floor checkpoint keeps only active entities, and a dead Monster is inactive (its Corpse is what persists), so on resume the controller tracked none of the dead ones. Only tracked Monsters are ever retired, so their encounter slots and effects entries stayed forever; the next few finale spawns pushed the effects table past 64 + 4 and the Host threw on the render thread. The same orphans also filled the encounter, whose `bindNativeMonster` silently drops a Monster when full.

Fix: after a resumed floor is attached, the Host controller adopts every saved Monster combatant it has no native Monster for. Living ones (no body to simulate) retire at once; dead ones count against the 63 slots and are the first to give a slot up, oldest id first, once resumed clients have had two seconds to draw their corpses. A full effects table now logs once and skips that actor's status visuals instead of throwing.

Known limit (unchanged): a client that joins after a Monster from the floor's own build was retired rebuilds that Monster alive and never hears of it again; only floors with more than 63 Monsters over their life are affected.

### 26. Monster magic flew through the client; melee still killed it

Owner (cam4, resumed, tutorial orb room with skeletons): the client was hit by enemy magic with no effect, then a melee attack damaged and killed it.

Cause: on the Host a remote Participant is a non-solid `RemoteAvatar`, kept out of the Level spatial hash. Native `Projectile.tick` finds targets with `Level.checkEntityCollision(..., checking = null, ...)` and `Missile` with `getEntitiesAlongLine`, both spatial-hash only, and neither had a multiplayer hook (explosions and spikes did). So every Monster or world projectile and arrow flew through clients on the Host and hit the wall behind; clients saw the magic pass through them. Melee works because `Monster.tryDamageHit` damages its target (the Avatar) directly. Not specific to resumed campaigns or the door.

Fix: `Level.nativeProjectileTargets` (Host only, set by the combat controller) adds remote Avatars to the native projectile box test and the Missile line candidates. A Participant's own shot never hits a teammate (the damage rules already dropped such damage), a Spectator leaves no body, a Downed body still stops shots like the Host's own Downed Player. Hits go through the existing Avatar damage path (magic resistance, status, death cause).

Melee from below: native melee needs the Monster within its reach in 3D (height included, `tryDamageHit`), measured on the Host against where the Host draws the Avatar. Standing a little above a skeleton is still in reach, as in single-player. Not changed; a video with the heights would tell whether Host and client disagreed about where the client stood.

### 27. Owner rules: a player who left does not hold off the Party Wipe; reconnect grace 30 s

#19 had any slot with Lives block the Party Wipe, so a friend who left kept everyone else spectating an empty floor until they came back (test `partyWipeOnlyWhenNoSlotCanReturn`). That contradicted the spec ("no active slot can return") and glossary ("absent slots do not count toward defeat"). Owner decision 2026-10-05: the Campaign ends when everyone still in play is out of Lives; a player who left has nothing to rejoin. `AuthoritativeLives.isPartyWiped(present)` now takes the same present set as simultaneous Downing (connected or inside reconnect grace), so a disconnect delays the wipe at most by the grace. A defeated Campaign already refuses to be hosted again. Owner also raised the reconnect grace from 10 to 30 seconds (`RECONNECT_GRACE_TICKS`). Tests: `slotThatLeftDoesNotKeepTheCampaignAlive`, `playerWhoLeftDoesNotKeepTheCampaignAliveWithTheirLives`.

## Implementation changes

### New Campaign persistence components

- `CampaignLibrary` — create/resume/list boundary over Campaign roster storage.
- `CampaignSave` — bounded authoritative checkpoint model.
- `CampaignSaveStore` — durable validation and atomic save/load boundary.
- `CampaignLibraryScreen` — Host Campaign selection/creation UI.
- `CampaignRosterStore` now owns related save store and Campaign directories.

### Host lifecycle and save flow

- Host launch routes to Campaign Library when no explicit Campaign ID exists.
- Host listener starts after Campaign selection.
- Save entrypoints redirect multiplayer Host to Campaign checkpoint.
- Client save entrypoints do not write authoritative save.
- Host close checkpoints active Campaign.
- Host can cold-resume saved Campaign with subset of participants.
- Current state is emitted before resumed or hot-joined Client becomes READY.
- Later startup failure closes listener and releases TCP/UDP port.

### Participant and world restoration

- Authoritative movement exposes restorable participant state.
- Lives/progression state supports durable restoration.
- Item controller restores saved physical identities without duplicate default items.
- Combat encounter restores actor combat state and native monster effects.
- Status-effect snapshot captures/restores multiplayer-relevant cursor/state.
- Doors, breakables, economy/keys, items, and communication state join canonical session snapshot.
- Saved dead Monster gets silent Host corpse reconstruction.

### Networking and UX

- Active reconnect no longer downgrades global Host phase.
- Party chat delivery gained trusted system-message bit.
- Protocol version is 44.
- Host broadcasts joined/left presence entries to party communication feed.
- Content-mismatch rejection explains need for same owned archive and content mode.
- True failures remain visible; healthy admission is not presented as stopped session.

### Native presentation

- Client monster damage uses native hurt and hit effect presentation without applying authoritative damage locally.
- Host and Client cold-resume corpse presentation now converges.

## Main files touched

New issue #21 files:

- `Dungeoneer/src/com/interrupt/dungeoneer/multiplayer/lobby/CampaignLibrary.java`
- `Dungeoneer/src/com/interrupt/dungeoneer/multiplayer/lobby/CampaignSave.java`
- `Dungeoneer/src/com/interrupt/dungeoneer/multiplayer/lobby/CampaignSaveStore.java`
- `Dungeoneer/src/com/interrupt/dungeoneer/screens/CampaignLibraryScreen.java`
- `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/lobby/CampaignLibraryTest.java`
- `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/lobby/CampaignSaveStoreTest.java`
- `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/network/DirectConnectCampaignPersistenceTest.java`

Major modified areas:

- application/lifecycle: `GameApplication`, `GameManager`, `Game`, `GameScreen`, desktop launch options/tasks;
- network: `DirectConnectHost`, `DirectConnectClient`, `DirectConnectProtocol`, `DirectConnectWire`;
- state: movement, lives, items, combat, status effects, level/interactables;
- presentation: `Monster`, `Corpse`, renderer, party communication;
- regression tests across integration, wire, combat, communication, escape/lifecycle, and desktop launch behavior;
- `docs/native-gameplay-replication.md`.

At session end tracked diff contained 33 modified files, about 1,190 insertions and 115 deletions, plus new issue #21 files and research/report documents. Shared tree also contains pre-existing untracked project-support files. Do not use destructive reset or blanket clean.

## Regression coverage added or emphasized

Notable tests include:

- Campaign Library creates and resumes locked-capacity Campaign without replacing roster.
- Campaign Save store round-trips supported state and rejects incompatible/corrupt input.
- Cold resume sends saved generation/world state before Client interaction.
- Cold resume reuses saved physical items instead of registering fresh duplicates.
- Replica damage presentation includes native blood burst.
- Active reconnect keeps Host READY and preserves playable simulation.
- Party system presence flag round-trips on wire and cannot be confused with player chat.
- Cold-resume Host rebuilds saved Monster corpse seen by Client.
- Reconnect death recovery restores corpse animation cursor without replaying death burst.
- Desktop task/launch-option coverage keeps direct Host/Client commands stable.

Latest corpse regression was developed red-first:

```text
DirectConnectCombatControllerTest.coldResumeHostRebuildsSavedMonsterCorpseSeenByClient
```

It failed twice before fix, then passed with existing Client recovery regression.

## Validation performed

Latest focused/broader Windows validation passed:

- `DirectConnectCombatControllerTest`
- `NativeStatusReplicationTest`
- `DirectConnectCampaignPersistenceTest`
- `DungeoneerDesktop:classes`
- `DungeoneerDesktop:test`
- `git diff --check`
- debug-marker scan (`[DEBUG-...]`) clean

Earlier combined changed-path Windows gate also passed Host saved-item identity, Client generation/state resume, blood presentation, production shop interaction, and desktop classes/tests in approximately 2m38s.

Full core run reported 471 tests, 11 failures, 5 skipped. Failures were `DirectConnectIntegrationTest` real-time/deadline/socket cases under loaded VM. Focused changed-path suites passed, so issue remains pending owner gameplay evidence rather than being declared complete from full-suite result.

## Windows playtest commands

Run one Host only on port `37777`.

### Host

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectHost -PsessionPort=37777 -PcampaignCapacity=2 -Pnickname=Host -Pavatar=humanoid-1 -PprofileRoot=C:\DelverMpProfiles\Host "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" -PdevTools=true --no-daemon'
```

### Client

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectClient -PsessionAddress=127.0.0.1 -PsessionPort=37777 -Pnickname=Friend -Pavatar=humanoid-2 -PprofileRoot=C:\DelverMpProfiles\Client "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

Both commands must use same `-PownedCopy`. Do not mix owned-content Host with open-source-content Client.

## Save locations and cleanup

Campaign data lives under Host profile:

```text
C:\DelverMpProfiles\Host\saves\campaigns\<campaign-id>\roster.properties
C:\DelverMpProfiles\Host\saves\campaigns\<campaign-id>\campaign.save
```

`roster.properties` holds durable slot ownership. `campaign.save` appears after playable Campaign checkpoint.

Client profile stores its own identity/reconnect material under:

```text
C:\DelverMpProfiles\Client
```

Old Campaigns are not required. Stop Host and Client before deleting. To remove one test Campaign:

```powershell
Remove-Item -Recurse -Force 'C:\DelverMpProfiles\Host\saves\campaigns\<campaign-id>'
```

Deleting entire `C:\DelverMpProfiles` resets all multiplayer Campaigns, profile identities, reconnect credentials, settings, and owned-copy cache. It does not delete source repository or external `delver.jar`. This is destructive and should only be used when full clean-room profile is intended:

```powershell
Remove-Item -Recurse -Force 'C:\DelverMpProfiles'
```

## Error interpretation quick reference

### `CONTENT_MISMATCH`

Host and Client normalized content identities differ. Launch both from same source revision and same owned `delver.jar`; do not mix content modes.

### `Address already in use: bind`

Another process still owns requested port. Usually existing Host is running or failed Host leaked listener. Current fix closes listener on late startup failure. If reproduced, identify existing port owner before starting another Host.

### Gradle forks new JVM

Expected with current Gradle/JVM settings and `--no-daemon`. It does not create new Campaign, new multiplayer identity, or incompatible build by itself.

### Serializer `Trigger` construction warnings

Observed during owned-level load. They were not cause of port bind failure. Keep as separate content/serializer diagnostic if they affect gameplay.

## Remaining owner acceptance checklist

### Fresh Campaign

- [ ] Start Host with clean or known Host profile.
- [ ] Create new capacity-2 Campaign from library.
- [ ] Confirm Client joins with same owned archive.
- [ ] Confirm both peers can move, attack, use doors/breakables, pick up/drop items, and use shop/interactions.

### Presence and non-pausing reconnect

- [ ] Close Client while Host remains in active floor.
- [ ] Confirm Host continues moving, fighting, and interacting without pause.
- [ ] Confirm top-left feed shows `Friend left` and no center-screen stop overlay.
- [ ] Relaunch Client inside reconnect grace.
- [ ] Confirm top-left feed shows `Friend joined` and Host never stops.
- [ ] Repeat after grace expiry; owned slot and participant state must still return safely.

### Visual and world convergence

- [ ] Host damages living monster; Client sees native hurt/blood presentation.
- [ ] Kill monster; both peers agree on alive counter and corpse/gib result.
- [ ] Mutate item, door, breakable, key/economy, health, gold, inventory, and positions.
- [ ] Confirm both peers see same authoritative state before shutdown.

### Save and cold resume

- [ ] Close Host cleanly after mutations.
- [ ] Relaunch same Host command and select saved Campaign.
- [ ] Confirm Host resumes saved floor, position, health/lives, inventory, economy, items, monsters/corpses, doors, and breakables.
- [ ] Confirm Host can interact immediately after resume.
- [ ] Join Client after Host cold resume.
- [ ] Confirm Client receives same world and can interact only after complete snapshot.
- [ ] Confirm dead saved monster corpse is visible consistently on Host and Client without replayed death burst, loot, XP, sound, or triggers.

### Capacity-four hot join

- [ ] Create capacity-4 Campaign.
- [ ] Start floor with Host and one Client.
- [ ] Join new third player during stable active floor.
- [ ] Confirm Host auto-approves available slot; no manual prompt.
- [ ] Confirm existing players do not pause.
- [ ] Confirm newcomer receives full current world before gaining control.
- [ ] Confirm fourth slot works and fifth player is rejected as full.
- [ ] Attempt join during floor transition; confirm admission waits/rejects safely and never exposes partial old/new floor.

### Failure boundaries

- [ ] Launch mismatched content once and confirm clear rejection text.
- [ ] Confirm different identity cannot steal owned Campaign Slot.
- [ ] Confirm corrupt/incompatible save rejects before world becomes playable.
- [ ] Confirm a second Host on same port gets clear bind failure while first Host remains unaffected.

## Key engineering decisions and rationale

| Decision | Rationale |
| --- | --- |
| Host owns Campaign Save | Single authority avoids conflicting checkpoints and Client-side world forks. |
| Client never writes authoritative save | Prevents stale/disconnected peer overwriting Host truth. |
| Campaign Slot persists beyond connection grace | Network timeout is not loss of durable participant ownership. |
| Active reconnect keeps Host globally `READY` | One peer authenticating must not stop simulation for everyone. |
| Host auto-approves valid free-slot claim | Explicit owner requirement; keeps hot-join frictionless. |
| Full snapshot before hot-join control | Prevents partial-world interaction and duplicated/missing state. |
| Guard floor transition | Generation replacement is unsafe point for snapshot coherence. |
| Presence uses Host-authored system chat | Unobtrusive, visible to all peers, and not forgeable as player message. |
| Protocol bumped to 44, later 46 | Wire format changed (system-message flag, then Mover state); mixed binaries must reject. |
| Build ID unchanged | Preserve existing issue #21 Campaign Save compatibility. |
| Rebuild saved Host corpse silently | Match Client presentation without replaying one-shot death consequences. |
| Keep original saves isolated | Co-op persistence must not risk owned single-player save data. |

## Current handoff state

- Branch `mp-v108-prototype`; owner asked on 2026-10-05 to commit, push and close #21 without waiting for a further retest.
- Protocol is 46 (Mover state, wire message 55); Campaign Save format 3 (Host floor checkpoint, ADR 0014); Host movement runs native Player physics (ADR 0015).
- Final validation: 538 core and 16 desktop Windows tests; all pass except the 10 owned-copy tests, which could not run because the owned `delver.jar` is no longer at `~/Downloads/Delver.v1.08/Delver.v1.08/`. Those 10 passed in the previous full run the same day, before the last two changes (Party Wipe present set, 30-second grace), which do not touch floor building.
- Not retested in play by the owner: saving after the finale (section 23), resuming it (25), Monster magic hitting clients (26), Party Wipe and grace (27), disconnected Spectator resume (23).
- Moved out of #21: joining a running session as a new or absent roster member (#25, joins as Spectator), floor travel and Fresh Return (#27). The "Capacity-four hot join" checklist above belongs to #25.
- Known limit: a client that reconnects on a floor that has had more than 63 Monsters may see a retired original Monster alive.
