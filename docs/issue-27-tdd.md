# Issue #27 — Party travel

## Contract and confirmed seams

Inspected [#27](https://github.com/ktodorow/delverengine-mp/issues/27), then
[parent PRD #1](https://github.com/ktodorow/delverengine-mp/issues/1), native
contract #38, #25/#26 integration and ADR 0005/0007/0008/0014.
Owner confirmed public session, native travel/reconstruction and atomic save/cold
resume boundaries on October 8, 2026. Same checkout and `mp-v108-prototype`.
Windows 11 VM runs automated Java 8 checks; owner performs gameplay acceptance.

Living activator starts travel at native reach. All connected living Participants
gather within three world units; then three unpaused seconds count down. Leaving
radius after countdown starts or explicit cancellation cancels. Downed members
and Spectators do not gate gathering. Loading holds shared simulation until peers
reconstruct destination and acknowledge its generation and content fingerprint.

Abandonment consumes current Life immediately, applying existing death scatter
and gold rules on source. Remaining Lives respawn at destination at 50% health.
Never-visited destination grants every exhausted/bodyless Spectator a Fresh
Return, including slots exhausted by abandonment and disconnected Spectators:
starting Lives, full native health, independently rolled normal starter kit,
zero gold/XP, level one, base stats. Old gear and unclaimed scatter disappear;
personal Potion/Map Knowledge stays. Revisits never grant Fresh Returns.
Source and destination, including Fresh Returns, form one atomic Campaign Save.

Newer #27 owner decision and ADR 0005 supersede older PRD paragraphs claiming
no Fresh Return and immediate Late Participant character catch-up. Existing
death-scatter owner decision supersedes selected-item-only PRD wording.

## RED / GREEN evidence

First slice: real Client starts travel; Host waits for distant living member,
begins countdown once gathered, then Client cancellation is visible to both.
Expected state comes from contract above, not private implementation fields.

Windows first RED: missing travel API, `BUILD FAILED in 4s`. Fixture corrected
to existing `setNativeParticipantPosition` before GREEN. Windows GREEN: real
TCP/UDP gathering/countdown/cancel test passed, `BUILD SUCCESSFUL in 13s`.

Next slice: loading pause cannot be released through Host pause controls before
native destination reconstruction/readiness.

Loading pause RED: Host unpause bypassed loading, one failed test, `BUILD FAILED
in 11s`. Guard also prevents regular render-loop native-ready call from ending
travel instability. GREEN alongside disconnected Fresh Return: `BUILD SUCCESSFUL
in 12s`.

Disconnected Fresh Return RED: missing atomic-arrival API, `BUILD FAILED in 4s`.
Fixture corrected to valid bodyless disconnected status. GREEN observes saved
character inside destination installer, proving reset belongs to initial atomic
write; old gear gone and new kit present before live installation.

Abandoned last Life RED: Windows test failed (`expected Lives 3, actual 1`, 8s). Detached plan now applies `DeathDropRules` and clears Downed/effects before first-arrival Fresh Return. GREEN: same test passed in 10s. Intermediate compile needed missing `ItemProperties` import; saved fixture has positive combat health with explicit Downed state, so abandonment derives authoritative Lives condition rather than HP alone.

Next expectation: destination pause releases only after Host and every connected observer acknowledge current destination generation; stale and duplicate acknowledgements have no effect.

Generation readiness RED: missing public destination/ack seam (compile failed, 4s). GREEN: real TCP destination announcement, old-generation refusal, Host + Client readiness and duplicate handling passed in 12s. Fixtures corrected to provide native checkpoint and traversable tiles. Intermediate compile errors fixed in wire local name and client status construction.

Native generation expectation: standard first generated floor from `Game.buildLevelLayout()` must produce identical fingerprint/stairs across peer graphics settings and RNG history with shared seed.

Native generation RED: missing completed Shared Floor fingerprint, 21s, after headless GPU map hook fixed. First implementation exposed room RNG mismatch. Native `DungeonGenerator` now seeds `RoomGenerator` only for shared construction. GREEN: owned jar native generation test passed in 20s; native first generated floor has down stairs, and peers match presence of up stairs. No assertion forces extra stairs absent from native content.

Native command expectation: stairs and TriggeredWarp submit Party intent while source Game Level remains installed.

Native commands RED: zero Party requests instead of stairs + warp, 9s. GREEN: native use/trigger submits stable portal intent without swapping source Level, 16s. Headless trigger fixture uses constructor-free native object to avoid unrelated localization setup.

Native recipe expectation: standard campaign definitions come from `Game.buildLevelLayout`; native warp keeps appearance/spawn settings across bounded scalar recipe round trip; both build through native loading and complete shared fingerprint.

Native recipe RED: missing recipe/fingerprint API (5s). GREEN: owned standard dungeon and native warp recipe round trip/build passed (24s). Recipe stores native appearance, spawn flags, objective/audio settings and construction character level; source owned files remain read-only.

Scene expectation: Host native down stairs enter next native campaign definition; ascent restores played source terrain and arrives at its down stairs. Party stays paused for bridge readiness.

Native scene GREEN: owned down/ascent and modified open terrain restore passed (30s). Fixture corrected stair ceiling/floor entity heights to native Player feet and avoided solid tiles, whose native serializer omits floor height. Next RED requires runtime scene installer to attach existing Player and suppress immediate stair re-entry.

Runtime installer RED: missing native scene method (4s). GREEN: native scene/down/ascent installer passed (32s) after import qualification fixed. Next expectation: Fresh Return erases only own unclaimed scatter in source and all dormant floors, including native checkpoint roots/timers; save round trip retains provenance and disconnected character cold-joins fresh.

Scatter RED: missing save provenance API (4s). GREEN: ownership save round trip, own active/dormant/native scatter cleanup and disconnected cold join passed (8s), after native collision supplied to resumed test Host. Format 7 persists only item ID + Slot provenance; format 2–6 has empty defaults. Next RED: movement/combat/revival must carry generation; malformed travel messages rejected.

Wire RED: missing generation API (4s); GREEN round trip + truncated travel rejection (6s), fixture changed to native directed melee. Delayed-packet RED: old combat reached destination native queue (8s); GREEN real TCP/UDP old generation refusal, current combat accepted and peer readiness (21s). Next expectation: stored native recipe reconstructs original floor despite later seen-area/unique content history.

Construction-history RED: rebuilt native fingerprint differed after later history (19s). GREEN: bounded recipe preserves native construction history; reconstruction uses original facts and merges newly found native facts back into Party (23s). Next expectation: refused atomic write leaves Downed source/Lives/gold/save intact; disconnected Downed last Life returns Fresh while frozen body remains reclaimable during reconnect grace.

Rollback/reconnect RED: destination exposed frozen Slot as disconnected (16s), then connected after body fix (20s). Downed presentation masks reconnect state, so travel now checks Host grace ledger. GREEN: refused write preserves source and exact primary bytes; Fresh body remains frozen/reclaimable and reconnect receives full character (19s). Absent Fresh Slots also reset live Lives ledger.

Full-suite regression RED: 633 core tests, 8 failures (3m29s). Item cleanup tried removing world-only binding from constructor-free Player fixture; now removes only actual inventory/equipment. Checkpoint fixture now supplies valid native bytes, matching earlier corrupt-checkpoint rejection. Legacy fixtures strip new empty provenance footer and expect format-7 backup name. Next expectation: starting owned tutorial retains native recipe; cold observer reconstructs at original construction character level.

Startup tutorial RED: missing native recipe (21s). GREEN: startup recipe, cold observer at different character level, all migration/backup and item/checkpoint regressions pass together (18 tests, 23s). Next expectation: native destination generation leaves source Party progression unchanged until atomic save/scene commit, then installs construction facts. Added explicit format-6 predecessor migration coverage.

Atomic construction RED: destination native build changed source progression (17s). Detached build keeps source unchanged; initial candidate save includes constructed facts. GREEN: rollback/build/commit, reconstruction history, native down/ascent and format-6 migration pass (4 tests, 43s). Runtime portal scan refreshes newly materialized stairs and uses body feet height. Intermediate compile added missing transient candidate facts field. Next expectation: connected bodyless Late Spectator returns Fresh even when durable record predates live Spectator state.

Late Spectator additional coverage passes on first run (10s): durable bodyless reservation receives destination body/Lives/native health. Next expectations: visited arrival never rolls Fresh Return (last Life stays Spectator; remaining Life arrives at 50% HP); native branch warp returns to preserved parent, with distinct parent areas despite Java hash collisions.

Branch RED: missing bounded parent/portal area key (compile, 4s). GREEN: native dungeon down/ascent, owned shop branch entry/return and distinct parent key test pass (40s). Visited Downed RED exposed zero remaining Lives passed as starting-Lives parameter (8s); corrected registration uses Campaign starting Lives before restore. GREEN: last Life stays Spectator, remaining Life returns at half HP; bodyless Late Fresh Return regression also passes (12s). Next expectation: adding initial-floor recipe preserves predecessor prepared tutorial construction so format-6 Campaign cold resume retains matching content.

Predecessor construction RED: tutorial recipe fingerprint differed (16s). GREEN: prepared source recipe retains original owned tutorial construction; campaign/warp recipes keep native standard loading (2 tests, 29s). Recipe also preserves construction character level without changing observer character. Legacy saved source gains initial recipe after native checkpoint restore.

## Final Windows verification

October 8, 2026, Windows 11 Parallels VM, Java 8, supplied owned jar:

```cmd
set "OWNED_GAME_COPY_TEST=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" && pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat Dungeoneer:test DungeoneerDesktop:test DungeoneerDesktop:classes --daemon --console=plain
```

`BUILD SUCCESSFUL in 3m 51s`. JUnit XML reports 638 core tests and 16 desktop
tests: 654 passed, zero failures/errors/skips. Includes owned native construction,
real TCP/UDP travel, source-generation rejection, atomic rollback, Fresh Returns,
visited abandonment, source/branch restore and format-6 migration.
Desktop compiled successfully. `git diff --check` passed.

Automated gameplay windows were not launched. Owner acceptance below remains
pending; automated checks do not establish audiovisual parity.

## Owner gameplay checklist

Use supplied Host/Client commands unchanged, separate profiles and same owned jar.

1. Tutorial outward warp and Camp stairs: Client and Host can initiate; distant living teammate shows gathering. Gather for three seconds. Press C or leave radius during countdown to cancel, then retry.
2. Both windows reach same native destination, at expected stairs/warp marker. No extra input needed to clear loading; arrival does not immediately retrigger stairs. Party HUD and pause follow both windows.
3. Descend, alter source world, ascend; verify source terrain, doors, killed enemies and drops stay changed. Enter/exit native branch and verify parent return point.
4. Leave Downed teammate behind: remaining Life arrives at half HP; carried scatter stays on source, worn/wielded gear follows existing death rules. Last Life on visited area stays Spectator.
5. First arrival with exhausted or Late Spectator: normal independent new starter, full HP/starting Lives, zero gold/XP/base stats. Old own gear/unclaimed scatter gone; private Potion/Map Knowledge retained. Repeat on visited floor: no Fresh Return.
6. Disconnect Spectator before first arrival; reconnect, then Save and Quit/cold resume both profiles. Fresh character and current area must persist. Disconnect during Downed reconnect grace; destination body stays frozen until authenticated reclaim.

Automated checks cover session state, owned native reconstruction and durable state. Owner confirms audiovisual/gameplay parity with original Delver.
