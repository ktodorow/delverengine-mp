# Issue #23 — Party Progression and Party Keys

## Contract inspected before implementation

Issue #23 is implementation step 22, not PRD story 23. Scope: PRD #1 stories
75–77, 83 and shared history on return; native gameplay contract #38; ADR 0006
and 0011. Host owns campaign facts. Every Campaign Slot observes same quests,
story consequences, secrets, unique-item history, world flags, tutorial completion,
ordinary key count and victory. Inventory, gold, upgrades and personal knowledge
remain Slot-owned. Activator retains dialogue UI; native shared consequences run
once on Host. Observer presentation cannot advance story or spend resources.

Current gaps found through CCE and native paths:

- Keys already pool and Door spends on Host, but Campaign Save drops key count.
- Native Progression holds story/message/unique/tutorial facts outside Campaign Save.
- Remote trigger contexts use Host's native Progression; clients keep private copies.
- Secret discovery credits Host's PlayerHistory even for remote activation.
- TriggeredMessage advances messagesSeen from presentation, including client replay.
- ProgressionTrigger writes ONCE flag after downstream chain, allowing re-entry.

[Issue #23](https://github.com/ktodorow/delverengine-mp/issues/23),
[PRD #1](https://github.com/ktodorow/delverengine-mp/issues/1),
[native contract #38](https://github.com/ktodorow/delverengine-mp/issues/38).

## Agreed test boundaries

Owner confirmed PRD boundaries on 2026-10-06: real Host/client actions and observable
state, native trigger/key/world consequences, Campaign Save round trips, bounded wire
input. Use native engine logic and open-source fixtures. Owned archive read-only for
local integration checks; no retail code or assets committed.

## TDD evidence

Each behavior follows failing test → minimum implementation → passing rerun.

Final native scope review also requires BasicTrigger and ButtonModel messages to reach
remote activator, while ButtonModel positional sound reaches both observers once.
Expected: no Host message for remote activation; one sound per observer and one private
presentation for activator. RED Windows run: three tests, three failures. Both messages
expected targeted delivery but received none; other observer expected one button sound,
received zero. Log: `/tmp/delver-issue-23-red-basic-button.log`. GREEN: targeted Windows native
routing, sound and delayed-context checks: 12 pass, zero failures/skips; `/tmp/delver-issue-23-green-basic-button.log`.

| Slice | Expected result | Red | Green |
| --- | --- | --- | --- |
| Cold resume with absent key collector | Collect three, spend one, resume Host alone: two keys remain | Windows: expected 2, actual 0; 1 test failed | Windows: 1 passed |
| Native story history | Flags, dialogue, unique items/tiles, areas and tutorial survive resume; Slot gold/upgrades unchanged | Windows: expected opened, actual null; 1 test failed | Windows: story + key regressions passed |
| Two native observers | Complete shared history larger than one TCP frame arrives on both clients; personal gold unchanged | Windows: expected opened, actual null; 1 failed | Windows: both observers pass |
| ONCE chain re-entry | Commit flag before downstream callback; duplicate advances once | Windows: expected 1 consequence, actual 2 | Windows: passed |
| Remote dialogue | Host records dialogue history without displaying UI | Windows: expected 0, actual null | Windows: passed |
| Observer touch authority | Client touch cannot execute native story | Windows: expected WAITING, actual RESETTING | Windows: passed |
| Remote secret scope | Party records discovery without crediting Host personal history | Windows: expected 0 Host secrets, actual 1 | Windows: passed |
| Shared sound | Activator and other observer each receive one native world cue | Windows: expected 1 non-activator cue, actual 0 | Windows: passed |
| Native bootstrap order | Saved dialogue selects next native message; bridge attach keeps newly initialized facts | Windows: expected last.dat, actual first.dat | Windows: passed |
| Native victory capture | Final capture archives COMPLETED with Party victory true | Windows: expected COMPLETED, actual ACTIVE | Windows: passed |
| BasicTrigger/ButtonModel scope | Private activator message, shared positional button sound | Windows: 3 tests failed, messages absent and sound count 0 | Windows: passed |
| Bounded transport and migration | No partial/oversized/malformed Party state; formats 2/3 retain old floor and backup exact original | Regression coverage | Windows: passed |
| Direct replica callbacks | Explicit native callback cannot write story; camera-driven copies cannot run local effects | Windows: client callback wrote did_trigger | Windows: passed |
| Native tutorial flag | Host tutorial start marks native seen flag once; both observers share it | Windows: expected true, actual false | Windows: passed |

Protocol 47 adds bounded, revisioned Party snapshots. Campaign Save format 4 adds
Party keys and native shared facts; formats 2/3 read with empty defaults for facts
those formats never retained. Resume restores facts before native dialogue/loot init.
Client trigger copies no longer execute gameplay. Host targets their UI explicitly.

Native tutorial rule verified in `Game.Start`: `sawTutorial = true` precedes tutorial
level load. Co-op preserves this rule as one Party flag. Shared travel and final Orb
escape policy remain #27/#30; #23 provides their saved/shared state.

First complete Windows run: 561 core tests, zero skips, five failures. One old fixture
assumed client executed touch UI; one proxy omitted new Party snapshot response; three
delayed trigger checks exposed new guard dereferencing nullable Level. Fixtures and
nullable guard corrected. Second full run: 562 core tests, zero skips, three delayed-context
failures caused by leaked global client Game from owned-copy fixture. Owned fixture now
restores Game; explicit-context tests isolate their global fixture. Next full run passed 562 core and
16 desktop tests, zero failures/errors/skips, including owned-copy checks. Final native
scope review added three BasicTrigger/ButtonModel regressions. Final full Windows run:
565 core tests + 16 desktop tests, zero failures, errors or skips. Owned-copy suites ran
with original archive enabled. Build successful; final log:
`/tmp/delver-issue-23-full-windows-complete.log`. `git diff --check` passed.

## Reproduce automated checks

Run from Windows PowerShell in same shared checkout:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && set "OWNED_GAME_COPY_TEST=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" && gradlew.bat Dungeoneer:test DungeoneerDesktop:test --no-daemon --console=plain'
```

Targeted RED/GREEN runs select tests via `--tests`; full run also compiles desktop.
Core regression seams: `DirectConnectCampaignPersistenceTest`,
`DirectConnectIntegrationTest`, `TriggerPresentationRoutingTest`,
`DirectConnectWireTest` and `CampaignSaveStoreTest`. Native owned-copy checks enabled
by `OWNED_GAME_COPY_TEST`; no owned data copied into repository.

## Owner gameplay acceptance

Owner accepted implementation and explicitly requested commit, push and issue closure on
2026-10-06. Individual Windows Host/client gameplay-check results were not provided;
automated evidence is separate from manual audiovisual validation. Existing launch
commands retained. [Owner checklist](issue-23-playtest.md).
