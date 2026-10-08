# Issue #24 — Potion Knowledge and Map Knowledge

Inspection date: 2026-10-06. Checkout: existing `mp-v108-prototype`.
Status: implementation complete through 14 documented RED/GREEN cycles.
Full Windows core/desktop build passed. Owner authorized commit, push and issue
closure on 2026-10-08. Individual owner audiovisual/playtest results were not
reported. Existing checkout/branch retained.

## Sources and intended behavior

- [Issue #24](https://github.com/ktodorow/delverengine-mp/issues/24): seven acceptance
  criteria covering shared potion physics/mapping, private identification and map
  knowledge, marker visibility, incapacitation, and durable slot knowledge.
- [Main PRD #1](https://github.com/ktodorow/delverengine-mp/issues/1): native gameplay
  fidelity contract; stories 75 and 78–81; personal/shared state requirements and
  session/save/wire test strategy. This is a campaign feature, not a shared automap.
- [Native fidelity #38](https://github.com/ktodorow/delverengine-mp/issues/38): reuse
  native physical effects and presentation; observing effects must not identify
  another Participant's potion or expose hidden actors.
- `docs/adr/0006-separate-slot-progression-from-party-progression.md`: Campaign Slot
  owns Potion Knowledge and Map Knowledge. Shared mapping belongs to campaign.
- [Party travel #27](https://github.com/ktodorow/delverengine-mp/issues/27), owner
  decision 2026-09-28: Fresh Return keeps existing Potion/Map Knowledge; genuinely
  new slots start empty. Travel and Fresh Return execution belong to #27. Preserve
  knowledge independently of disposable character inventory and Lives in #24.

Dependencies #9 and #21 are CLOSED. #23 is committed at `e338398` and CLOSED.
Inspection baseline: protocol 47, Campaign Save format 4.
Implemented version: protocol 48, build `mp-v108-prototype-campaign-library-52`,
Campaign Save format 5.

Required distinction: potion appearance/effect is one physical campaign fact;
whether a slot recognizes that effect is personal. Showing native poison particles
after another Participant drinks does not grant tooltip identification. Dropping,
sharing, recollecting, spectating, or reconnecting must not copy knowledge.

## Native paths and current gaps

### Potions

- `ItemManager.GetRandomPotion()` lazily copies/shuffles catalogue potions into
  `Player.shuffledPotions`, then assigns native `PotionType` values. Native lookup
  thereafter draws from that shuffled list. Mapping currently lives on native Player,
  rather than detached Campaign Save state. A saved world item carries its individual
  type, but that alone does not preserve mapping for future loot after cold resume.
- `DirectConnectItemController` transmits Host-assigned potion type in item properties.
  Existing regression covers pickup/drop/sharing preserving physical type.
- `DirectConnectCombatController.consumeNativeItem()` executes native potion effects
  on accepted consuming actor. Existing effect authority/presentation remains basis.
- `Potion.presentDrink(Player)` currently performs native 50% identification roll on
  consuming peer and appends to `Player.discoveredPotions`. It also provides personal
  sound/message/history feedback. Durable Host save does not capture this knowledge.
  A disconnect between accepted drink and client feedback must not lose Host's result.
- `Potion.GetInfoText()` consults viewer's `Game.instance.player.discoveredPotions`.
  Preserve this personal native lookup. A Potion's general `identified` flag does not
  replace the per-type discovery list.
- `Identify.doCast()` identifies carried items and discovers every carried potion type
  on owning `Player`; no Party-wide potion identification rule exists.
- Remote Identify/Fill Map scrolls currently fail before native execution:
  `consumeNativeItem()` rejects personal Player spells when target is `RemoteAvatar`.
  This blocks original identification/map gameplay for client Participants. Native
  inventory/knowledge context and personal output must reach correct Campaign Slot.

### Maps

- `Level.tick()` calls `updateSeenTiles(Player)` after tile changes or dirty map.
  Native exploration checks nearby free tiles with `canSee`, then includes adjacent
  solid/closed-height tiles. Preserve these geometry/range rules.
- Exploration writes `Tile.seen` on Level. `GlRenderer` consumes those flags and
  `dirtyMapTiles` for native map texture, doors and stairs. Level flags currently lack
  Campaign Slot ownership and explicit Downed/Spectator guard at revelation boundary.
- `FillMap.doCast()` reveals native floor tiles globally through `Game.instance.level`.
  In multiplayer, effect belongs only to accepted caster's Map Knowledge.
- Native map UI draws local marker; no private-knowledge-aware remote marker path
  currently exists. Add markers only for viewer-explored target tiles, including
  when Spectator camera follows another Participant. Body/world rendering remains
  governed by existing native visibility, not by automap exploration.
- Host native floor checkpoint includes Level state. Applying saved Host's `seen`
  flags as another slot's personal map would leak Host exploration. Restore personal
  map overlay independently of shared world checkpoint.

### Persistence and transport

- `CampaignSave.ParticipantState` stores Party status, movement, economy and Orb flag;
  no Potion/Map Knowledge. Campaign root stores no shuffled potion mapping.
- `DirectConnectHost.captureCampaign()` preserves absent saved slots. New personal
  knowledge must use same absent-slot rule and stable Campaign Slot identity, never
  connection lifetime or IP address.
- Reconnect bootstrap sends shared progress/items/effects before SessionReady. Add
  recipient-only knowledge baseline before READY, retaining campaign-wide mapping.
- Existing reliable TCP frame limit is 1024 bytes; explored maps can exceed one frame.
  State must be bounded, assembled atomically, reject malformed/stale traffic, and
  avoid sending every other slot's knowledge. Wire cannot serialize live engine graphs.
- Format 2/3/4 never saved private knowledge. Migration cannot invent another slot's
  discoveries from shared world state. Keep exact pre-migration backup and document
  honest defaults; recover mapping from reliable existing native/item data where
  possible rather than silently changing saved physical potion types.

## Proposed agreed public test boundaries

Owner confirmed through conversation on 2026-10-08; tests started after confirmation.

1. Native potion/Identify and Level/map behavior through public gameplay/controller
   entry points and tooltip/map outputs. No private-method reflection for new checks.
2. Real Host/client session, Campaign Save, reconnect and Host-only subset resume.
   Commands follow production authority path; inspect public recipient state and
   restored native outputs. Synthetic open-source fixtures; no retail classes/assets
   committed. Include two observers for private versus shared state distinction.
3. External wire codec: bounded fragmentation, malformed input, duplicate/stale state,
   floor/session identity checks and compatibility changes.

## RED → GREEN slices and expected outcomes

Work vertically: one failing behavior, minimal implementation, focused GREEN run;
then next behavior. Do not write a bulk suite against imagined internal APIs.

| Slice | Expected observable result | Current RED target |
| --- | --- | --- |
| Native remote Identify | Client's accepted scroll learns its carried potion; Host/other observer tooltip stays unknown; scroll spent once | Remote Player spell rejected |
| Drink discovery | Native chance, Host durable result, owning tooltip only; duplicate acceptance neither rerolls nor spends twice | Discovery currently client feedback only |
| Shared mapping | Same appearance keeps effect after sharing, reconnect and cold resume, including subsequent native loot | Mapping absent from Campaign Save |
| Private exploration | Same floor, separated living Participants: each sees only personally revealed tiles; native geometry preserved | `Tile.seen` is Level state |
| Map spell | Accepted Fill Map reveals caster's map only, with original native presentation | Remote rejected; native effect writes shared level flags |
| Incapacitation/markers | Downed/Spectator gain no tiles, even following living camera; remote marker only in viewer's explored tile | Reveal boundary lacks slot/state policy; remote marker absent |
| Durability | Distinct slot knowledge retained after save/load, reconnect and subset save; new slot empty; personal knowledge independent of inventory/Lives | No knowledge save fields/bootstrap |
| Bounds/lifecycle | Invalid, partial, duplicated or obsolete messages cannot expose partial map or attach knowledge to another slot/floor | No knowledge wire contract |

Exact RED command/output and GREEN command/counts will be recorded here after each
cycle. Compilation failure alone does not establish regression reproduction: prefer
first assertion failure through existing native/session path.

## Baseline evidence

Windows 11 Parallels VM, same Mac shared checkout, owned-copy checks enabled:

```text
prlctl exec 'Windows 11' cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && set "OWNED_GAME_COPY_TEST=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" && gradlew.bat Dungeoneer:test DungeoneerDesktop:test --rerun-tasks --no-daemon --console=plain'
```

Fresh rerun completed: `BUILD SUCCESSFUL in 3m 33s`, seven tasks executed.
Baseline XML results: 565 core + 16 desktop tests, zero failures/errors/skips.
Local command output: `/tmp/issue-24-baseline.log`.
No game instances launched; original installation and single-player saves untouched.
Existing owner Host/client launch commands remain gameplay acceptance entry points.

## Execution evidence — 2026-10-08

### Slice 1: remote native Identify acceptance

Windows command uses existing checkout:

```text
prlctl exec 'Windows 11' cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat Dungeoneer:test --tests "*DirectConnectIntegrationTest.remoteParticipantCanUseNativeIdentifyWithoutTeachingHost" --no-daemon --console=plain'
```

RED: 1 test, 1 failure at assertion `Native Identify must work for remote living
Participant` (`DirectConnectIntegrationTest.java:106`), BUILD FAILED in 55s.
Log: `/tmp/issue-24-red1.log`. Remote Actor rejection reproduced before any
production change.

Implementation: remote Identify executes with detached native Player inventory
context supplied by Host item resolver. Host Player discovery list stays separate.
GREEN: 1 test passed, BUILD SUCCESSFUL in 1m21s.
Log: `/tmp/issue-24-green1.log`.

Slice 2 adds actual carried potion and checks recipient native discovery/tooltip
through real Host/client session. Acceptance alone does not prove learning delivery.

### Slice 2: owning tooltip after remote Identify

Target: `DirectConnectIntegrationTest.remoteIdentifyUpdatesOwningTooltipOnly`.
First fixture attempts exposed missing headless HUD setup and corrected HUDManager
package; neither counts as behavioral RED. With UI boundary initialized, RED failed
at `Owning native tooltip must learn carried potion` (`:167`), 1 test / 1 failure,
BUILD FAILED in 1m10s. Log: `/tmp/issue-24-red2.log`.

Implementation: detached per-slot potion mask, Host-only publication, recipient-only
reliable snapshot, session/slot/generation checks, and native discovery-list restore.
Native Identify still handles carried items and original personal history.

Windows environment interruption: system-profile Gradle daemon stalled in native
`ZipFile.open` while loading Gradle services, before compilation. Stopped only that
test daemon. VM stayed running; guest process inspection and commands worked.
User-cache retry reached compiler. Single-JVM test invocation avoids separate
daemon startup using process-only `JAVA_OPTS=-Xmx1500m` and
`-Dorg.gradle.jvmargs=`. No VM settings or repository layout changed. Temporary
Mac JDK archive downloaded as fallback; not installed globally or used for gameplay.

Slice 2 GREEN: both Identify tests passed on Windows, BUILD SUCCESSFUL in 1m11s.
Log: `/tmp/issue-24-green2-single-jvm.log`. Subsequent Windows runs use single-JVM
process flags above. Native UI fixture preserves/restores globals after each check.

### Slice 3: accepted drinking owns durable discovery

`acceptedDrinkLearnsOnHostBeforeConsumerFeedback` sends identical consume request
twice through real client/Host item authority. Native lucky restore potion must
produce personal mask 16 for slot 2, mask 0 for Host, one consumed identity, and
owning native tooltip restored before any `presentDrink` feedback runs. Seed 0
provides original native discovery chance's successful draw.

RED: 1 test / 1 assertion failure at Host discovery mask (`:227`), expected 16,
actual 0, BUILD FAILED in 43s. Log: `/tmp/issue-24-red3.log`.
Initial fixture compilation used assignment to final `Game.rand`; corrected to
public `setSeed` before behavioral run. Native chance now runs once on Host after
accepted effect; client feedback receives learned result. Elixer retains native
special behavior and never gains ordinary potion discovery roll.

Slice 3 GREEN: all three native/session regressions passed on Windows,
BUILD SUCCESSFUL in 1m7s. Log: `/tmp/issue-24-green3.log`.

### Slice 4: cold resume and subset save

`potionKnowledgeSurvivesColdSubsetSaveAndReturningSlot` seeds known slot 2 discovery
through public native-state publication, saves campaign, cold-resumes Host alone,
saves absent slot again, then reconnects owning client in new Host session.
Expected mask 16 retained for slot 2, Host remains 0, personal baseline arrives
before client READY. This tests save boundary separately from accepted-drink path.

RED: cold subset resume failed at personal mask (`:96`), expected 16, actual 0;
1 test / 1 assertion failure, BUILD FAILED in 28s. Log: `/tmp/issue-24-red4.log`.
Implementation adds personal facts to immutable ParticipantState, format 5 save
extension, stable-slot restore, and existing absent-participant preservation path.

Slice 4 GREEN: four regressions passed on Windows, BUILD SUCCESSFUL in 47s.
Log: `/tmp/issue-24-green4.log`.

### Slice 5: private native Fill Map

`remoteFillMapRevealsCasterWithoutRevealingHost` uses native FillMap spell through
accepted remote controller path, then recipient item bridge/native tile outputs.
Expected caster sees both floor corners; Host retains unexplored corners.

RED: native remote Fill Map rejected (`:252`), 1 assertion failure,
BUILD FAILED in 26s. Log: `/tmp/issue-24-red5.log`.
Implementation captures/restores native tile overlay around caster's accepted
spell, retains immutable per-floor bitsets under owning slot, and applies recipient
map to native renderer inputs. Shared floor checkpoint flags do not grant knowledge.

Slice 5 GREEN: five regressions passed on Windows, BUILD SUCCESSFUL in 49s.
Log: `/tmp/issue-24-green5.log`.

### Slice 6: personal native exploration

`separatedParticipantsExploreNativeTilesPersonally` places living participants apart
through Host movement authority, then updates production native bridge. Expected
original visible tiles learned per slot, Host native overlay excludes remote area,
and Downed movement/followed camera cannot reveal new tiles.

Slice 6 RED: native Host exploration assertion failed (`:100`), 1 test / 1 failure,
BUILD FAILED in 31s. Log: `/tmp/issue-24-red6.log`. Initial missing headless string
fixture corrected before behavioral RED. Native Level reveal now runs per accepted
Host transform, using connected/living guard; native camera tick cannot grant tiles.

Slice 6 GREEN: six regressions passed on Windows, BUILD SUCCESSFUL in 59s.
Log: `/tmp/issue-24-green6.log`. Corrected native numeric Entity ID conversion during
implementation compile; final run verifies behavioral assertions.

### Slice 7: bounded atomic personal maps

`personalFloorMapUsesBoundedFramesAndArrivesAtomically` sends real 128x128 explored
map through production TCP codec. Expected frames at most 1024 bytes; recipient
state appears only after final fragment.

Slice 7 RED: real encoder rejected map above single-frame limit (`:64`),
1 test / 1 failure, BUILD FAILED in 28s. Log: `/tmp/issue-24-red7.log`.
Initial DTO field typo corrected before behavioral run. Personal knowledge now
uses bounded contiguous TCP fragments, declared-kind check, atomic completion,
and disconnect disposal shared with existing complete snapshots.

Slice 7 GREEN: full wire class passed on Windows, BUILD SUCCESSFUL in 50s.
Log: `/tmp/issue-24-green7.log`.

### Slice 8: durable shared potion mapping for future loot

`futureNativePotionLootKeepsCampaignMappingAfterColdResume` records all seven
native appearances, cold saves/resumes, then rolls future Host and returning-client
loot with unrelated RNG histories. Effects must match original campaign mapping;
receiving mapping must not identify any potion.

Slice 8 RED: future Host potion appearance/effect assertion failed (`:106`),
1 test / 1 failure, BUILD FAILED in 33s. Log: `/tmp/issue-24-red8.log`.
Initial fixture corrected lobby API and required first remote approval; neither
counts as behavioral RED. Mapping now persists at campaign root, bootstraps to
returning peers, and restores native shuffled catalogue without private discovery.
Native shuffle still consumes original RNG draw count; known mapping controls
effects and original type order afterward. Legacy saved physical facts seed mapping.

Slice 8 GREEN: seven native/session + 45 wire tests passed on Windows,
BUILD SUCCESSFUL in 1m0s. Log: `/tmp/issue-24-green8.log`.

### Slice 9: personal automap marker output

`automapMarkersRequireViewersExploredTile` checks production renderer's public
controller output seam: unknown remote tile hidden, known tile shows exactly one
remote marker, moving camera does not authorize unknown tile. New output seam
initially returns empty list so missing marker behavior can fail by assertion.

Slice 9 RED: known-tile remote marker missing (`:100`), 1 test / 1 assertion failure,
BUILD FAILED in 1m19s. Log: `/tmp/issue-24-red9.log`. Controller now filters accepted
transforms using viewer's floor bitset; native renderer draws same filtered output.
Camera crop applies after privacy filter.

Slice 9 GREEN: marker and personal exploration regressions passed on Windows,
BUILD SUCCESSFUL in 1m20s. Log: `/tmp/issue-24-green9.log`.

### Slice 10: Spectator native follow camera

`spectatorFollowKeepsPersonalMapAndHidesCameraMarker` exhausts real client's last
Life while Host remains alive, then runs native Level tick at followed Host position.
Expected no tiles, no unexplored remote marker, and no native local arrow standing
in for followed remote body. New local-arrow output seam starts with native true.

Slice 10 RED: native local-arrow privacy assertion failed (`:114`), 1 test /
1 assertion failure, BUILD FAILED in 1m32s. Log: `/tmp/issue-24-red10.log`.
Prior assertions already proved Spectator camera tick adds no tiles and cannot
show unknown remote marker. Local native arrow now omitted for Spectators;
followed body appears only through same explored-tile remote marker filter.

Slice 10 GREEN: Spectator/native tick, remote marker, and exploration tests passed
on Windows, BUILD SUCCESSFUL in 1m16s. Log: `/tmp/issue-24-green10.log`.

### Slice 11: accepted personal spell presentation identity

Extends `remoteIdentifyUpdatesOwningTooltipOnly` with real carried physical scroll
and public native presentation output. Original native caster/item identity must
reach peers while identification remains personal. Detached Player context currently
has no source identity and branch bypasses physical scroll presentation scope.

Slice 11 RED: expected caster/item identity assertion failed (`:373`), 1 test /
1 failure, BUILD FAILED in 44s. Log: `/tmp/issue-24-red11.log`.
Detached caster now carries stable participant identity; accepted physical scroll
identity remains scoped around original native cast and restored in finally.

Slice 11 GREEN: native Identify/tooltip/presentation regression passed on Windows,
BUILD SUCCESSFUL in 1m2s. Log: `/tmp/issue-24-green11.log`.

### Slice 12: existing Campaign build migration

`previousBuildMigratesWithoutInventingPersonalKnowledge` supplies actual format-4 /
protocol-47 / build-51 bytes. Expected load into build 52, exact original-byte
backup, retained character state, and empty personal facts never saved previously.
Existing format-2/3 fixtures now omit format-5 tail as well as later shared facts.

Slice 12 RED: load rejected known predecessor as incompatible engine build (`:56`),
1 test / 1 failure, BUILD FAILED in 42s. Log: `/tmp/issue-24-red12.log`.
Initial test compile typo corrected before behavioral RED. Protocol now 48/build 52.
Only explicit build-51 format-2/3/4 predecessor upgrades; owned content checks
remain exact. Migration still requires exact backup before replacing original.

Slice 12 GREEN: full Campaign Save Store test class passed on Windows,
BUILD SUCCESSFUL in 1m11s. Log: `/tmp/issue-24-green12.log`.

### Slice 13: reject unversioned personal facts

`unversionedPersonalFactsAreRejectedBeforeDelivery` mutates valid wire payload to
revision zero while retaining discovery. Expected ProtocolException before public
delivery; otherwise Client silently ignores nonempty facts as initial empty revision.

Slice 13 RED: valid codec delivered revision-zero discovery; expected rejection
assertion failed, 1 test / 1 failure. Log: `/tmp/issue-24-red13.log`.
Personal DTO now requires positive revision for nonempty facts, at both save and
wire decode boundary. Empty initial revision remains valid.

Slice 13 GREEN: complete wire class passed on Windows, BUILD SUCCESSFUL in 1m12s.
Log: `/tmp/issue-24-green13.log`. Additional malformed map/fragment/disconnect
cases are conformance checks of already implemented bounds, not invented REDs.

### Post-GREEN conformance

Extended cold subset save test with disjoint Host/absent-Slot floor bitsets.
Added real three-peer targeted privacy, stale/duplicate publication, warm reconnect
baseline assertions. These exercise requirements already implemented through
earlier RED/GREEN slices. Owner checklist: `docs/issue-24-playtest.md`.

Conformance first run stopped at avatar conflict in three-peer fixture; fixed third
avatar to owned humanoid 3. Rerun passed, log `/tmp/issue-24-conformance.log`.

### Slice 14: native Identify inventory scope

Existing native Identify regression now carries real armor equipped through
authoritative world API before remote cast. Expected native backpack-only scan;
worn armor remains unidentified. Detached remote inventory currently includes it.

Slice 14 RED: backpack-only assertion failed (`:416`), 1 test / 1 failure,
BUILD FAILED in 44s. Log: `/tmp/issue-24-red14.log`. Remote native inventory now
excludes consumed and equipped items; original Identify scan remains unchanged.

Slice 14 GREEN: native Identify scope/tooltip/effect identity regression passed
on Windows, BUILD SUCCESSFUL in 1m19s. Log: `/tmp/issue-24-green14.log`.

### Review cleanup

Removed unreachable generic Identify publication; personal branch already owns it.
Native exploration filters unchanged body/revision before capturing full map;
cache includes stable floor and world generation. Existing exploration, map fill,
spectator, marker and reconnect tests cover resulting behavior.

## Final Windows validation

2026-10-08, same Windows 11 VM/shared checkout; owned-copy checks enabled:

```text
prlctl exec 'Windows 11' cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && set "JAVA_OPTS=-Xmx1500m" && set "OWNED_GAME_COPY_TEST=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" && gradlew.bat Dungeoneer:test DungeoneerDesktop:test --rerun-tasks -Dorg.gradle.jvmargs= --no-daemon --console=plain'
```

BUILD SUCCESSFUL in 4m54s; seven tasks executed. Fresh XML totals: 581 core +
16 desktop tests, zero failures/errors/skips. OwnedSharedFloorBuildTest ran all
seven tests; OwnedTutorialSmokeTest ran all three. Log: `/tmp/issue-24-full.log`.
This validates final production changes, including review cleanup. Last test-only
conformance addition checks adjacent wall revelation and hidden room behind wall;
focused Windows test passed, BUILD SUCCESSFUL in 49s. Log:
`/tmp/issue-24-geometry.log`. No production changes followed full suite.

`git diff --check` passes. Owner visual/audio/gameplay acceptance uses unchanged
commands in `docs/issue-24-playtest.md`; no game processes launched by this work.
Original single-player installation/saves untouched. Owner authorized publication
and issue closure after receiving implementation and validation results.
