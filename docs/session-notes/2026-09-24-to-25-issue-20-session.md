# Session handoff — Issue #20: one-floor multiplayer gameplay gate

Session work: 2026-09-24; consolidated handoff: 2026-09-25, Europe/Sofia.
Owner: Kristiyan Todorov. Agent: Codex. Owner performs interactive Host/Client play-tests.

## Closure instruction — 2026-09-26

Owner explicitly requested: “close , commit and push issue #20”. This supersedes the earlier instruction
to leave issue open pending acceptance. Commit and publish build 48 / protocol 43 on existing
`mp-v108-prototype` branch, retaining 472 passing Windows tests as recorded validation.
No new 30-minute run, visual/audio acceptance log, or native Windows repeatability evidence accompanied
that instruction; do not infer those results from closure. Sections below preserve the September 25
handoff snapshot, including its then-pending status and uncommitted-file inventory.

## 1. Current state

| Item | State at handoff |
| --- | --- |
| Repository | `/Users/kristiyantodorov/Repos/delverengine-mp` |
| Branch | `mp-v108-prototype` |
| HEAD / session base | `c5e7ebc` |
| Current protocol | `43` |
| Current build | `mp-v108-prototype-floor-gate-48` |
| Latest automated result | 457 core + 15 desktop tests passed; zero failures, errors, or skips |
| Test platform | Parallels VM `Windows 11`, using external owned `delver.jar` |
| Issue #20 | Open; sustained gameplay and presentation acceptance still pending |
| Publication | Changes remain local and uncommitted; no issue closure or push performed for this work |

This handoff records discussion, findings, implementation, test evidence, and remaining work. It is a
consolidated account, not a verbatim chat transcript. Test success is not owner acceptance of the
30-minute gate. Test totals above were rechecked from saved JUnit XML while writing this document;
documentation work did not rerun the suites.

Primary references:

- [Issue #20 — Pass one-floor multiplayer gameplay gate](https://github.com/ktodorow/delverengine-mp/issues/20).
- [PRD #1 — Unofficial Multiplayer Fork](https://github.com/ktodorow/delverengine-mp/issues/1).
- [Earlier accepted session: issues #18, #19, #39](2026-09-21-to-24-issues-18-19-39-session.md).
- [Issue #20 operational gate note and timed checklist](2026-09-24-issue-20-gameplay-gate.md).
- [Native gameplay replication inventory](../native-gameplay-replication.md).
- [Local multiplayer specification](../MULTIPLAYER-SPEC.md) and [implementation plan](../IMPLEMENTATION-PLAN.md).

## 2. Original request and working agreement

Owner requested starting issue #20, reading PRD #1, inspecting prior commits and the detailed
#18/#19/#39 session document, and using CCE where possible. Work must stay in this repository and
branch. No parallel checkout, worktree, or second repository folder was created.

Owner has macOS with Parallels Desktop and runs both Windows game instances. Agent may build and run
automated tests through PowerShell/CMD and `prlctl exec`; interactive game launches remain owner-controlled.
No game instances were launched by agent during the fixes documented here.

Owned Delver v1.08 archive remains external and read-only:

- macOS: `/Users/kristiyantodorov/Downloads/Delver.v1.08/Delver.v1.08/delver.jar`
- Windows: `\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar`

No retail assets, class files, definitions, or saves were added to repository fixtures or shipped output.
Host and Client use separate multiplayer profiles, `C:\DelverMpProfiles\Host` and
`C:\DelverMpProfiles\Client`.

CCE was used when available. It failed with `Transport closed` during the recovered-arrow catalogue
work, later recovered for reconnect/stacking work, and accepted decisions and code-area records.
Both CCE endpoints failed again while preparing this handoff. Durable evidence therefore includes
repository notes, Git history/diffs, GitHub issue text, and saved test reports; failed CCE calls are not
treated as successful memory writes.

## 3. Inherited baseline — already implemented before #20

The preceding Claude session is documented separately. Its accepted features were preserved, not
reimplemented as new #20 work.

| Commit | Existing behavior |
| --- | --- |
| `7dc94fc` | #18: Lives, Downed state, interrupted/successful Revival, respawn |
| `de84a2d` | #19: Spectator, Party Wipe, Host continuation while spectating, native water movement parity |
| `bedb43a` | #39: Life-loss scatter, cause-based item damage/destruction, gold penalty, reclaimable drops; dev tools and related gameplay fixes |
| `d2473f2` | Detailed #18/#19/#39 session record |
| `c5e7ebc` | Ignore CCE cache, local MCP configuration, and Claude local settings; current HEAD |

Baseline before #20 used protocol 42 and build `mp-v108-prototype-death-drop-43`. Prior fixes already
covered duplicate landed arrow presentation, broken-arrow observer effects, lost pickup races,
client bomb lighting, and fire attachment visibility. The two arrow problems found here were separate:
missing recovered-bundle catalogue entries and missing inventory stacking.

Important inherited rules:

- Host owns simulation and accepted gameplay outcomes; Client presentation must not mint damage, loot, or spending.
- Preserve presented-revision guards around Client inventory changes and applied-health guards around health sync.
- New code must respect the prior lock lesson: do not hold Host and combat-encounter monitors together.
- Host Pause Session pauses shared time; personal inventory and other personal menus do not.
- Dev tools require explicit launch flags. Packaged release launches must not enable them.

## 4. What issue #20 requires

Issue #20 proves existing multiplayer architecture on one floor before full campaign travel and persistence.
Its current GitHub acceptance requires:

1. Two Windows instances play one open-source floor for at least 30 minutes.
2. Run includes movement, combat, monsters, damage, Downing, Revival, Lives, items, doors, chat, ping, and reconnect.
3. Simulate 100–120 ms round-trip latency and modest packet loss.
4. Observe no authoritative world divergence, duplicated action, or unrecoverable correction.
5. Malformed or stale traffic must not terminate Host.
6. Repeat on one native Windows x64 machine running two instances.
7. Exercise #38 native behavior and presentation: actual weapon variants, status application/expiry,
   resisted effects, monster actions, misses, impacts, item effects, spatial sounds, and cleanup.
8. Compare Host attacker, Client attacker, and non-attacking observer views. Exercise simultaneous and
   refreshing effects, missing snapshots, duplicate/stale traffic, pause, death/despawn, and reconnect
   mid-effect. Record visible/audible results as well as gameplay state.

### Floor and command clarification during chat

Owner asked what to test, whether that meant tutorial, and requested both launch commands. Owner then
explicitly corrected commands to use the supplied `delver.jar` assets.

Two validation tracks remain distinct:

- **Owned tutorial/native content:** use owner's `ownedCopy` commands for weapon, monster, arrow, XP,
  and reconnect reproductions. Reported Longbow/worm and stacking problems came from this play-test work.
- **Issue #20 open-source floor:** omit `ownedCopy`; complete the timed one-floor gate. Tutorial checks
  complement this requirement and do not by themselves satisfy it.

Parallels results provide development evidence. Native Windows repeatability and later validation on
two physical Windows machines are still separate requirements. No full-campaign completion is claimed.

## 5. Session sequence and build milestones

| Stage | Discussion or finding | Result |
| --- | --- | --- |
| Gate preparation | Full-floor combat snapshot exceeded TCP frame limit | Protocol 43; bounded multipart combat snapshots |
| Gate preparation | Need reproducible latency/loss conditions for owner's launch commands | Opt-in Client `networkSimulation=gate` |
| Build 45 | Longbow shot into worm produced missing item-template overlay | Register native recovered-arrow bundle templates |
| Follow-up question | What about arrows from other NPCs/monsters? | Shared missile/bundle mechanism; no worm-specific special case |
| Build 46 | Host kills opened level-up choice on nearby Client too | Owner changed rule to personal, own-kill XP |
| Build 47 | Restarted Client received generic TCP-close message | Preserve buffered rejection reason through simulator close |
| Reconnect clarification | Only Client had restarted after build 46 | Restart both processes after build changes; exact-build rejection retained |
| Build 48 | Screenshot showed arrows occupying separate inventory slots | Host-authoritative ammo merging and Client count synchronization |
| Handoff | Owner requested full session documentation | This consolidated document; gameplay acceptance remains pending |

Build suffix and protocol number are different identifiers. Builds 45–48 all use protocol 43;
sharing a protocol number does not make different builds compatible.

## 6. Discovery: full-floor combat snapshot overflow

### Failure and cause

A valid snapshot with 64 monsters / 68 combatants exceeded the 1,024-byte TCP frame limit. Ordinary
short identities were enough to reproduce the failure; this was not merely a malformed-name edge case.

`DirectConnectWireTest.fullFloorCombatStateFitsBoundedTcpFrames` failed on Windows before the fix:

```text
EncoderException: ProtocolException: TCP frame exceeded protocol size bound.
```

### Change

`DirectConnectWire` now splits large combat snapshots into ordered TCP-only fragments, message type 52.
Each fragment carries total payload size and exact offset. Encoder can emit multiple bounded buffers.
Receiver assembles at most one 16 KiB combat snapshot per connection and publishes only a complete,
validated snapshot. Small combat snapshots retain ordinary encoding.

The 1,024-byte TCP frame and UDP datagram limits remain. UDP does not use this fragmentation path.
Malformed, duplicate, out-of-order, inconsistent-total, oversized, unrelated, or interrupted sequences
fail closed. Disconnect discards partial assembly. Existing snapshot sequencing still rejects stale
completed state. Protocol changed from 42 to 43; monster cap remains 64.

### Evidence

Wire tests cover payload/frame bounds, maximum identity sizes, complete-only delivery, invalid fragments,
and interruption. Real TCP integration covers a full 64-monster floor, updates, reconnect under gate
conditions, and an independent malformed peer without killing healthy Host/session.

One initial integration assertion expected immediate EOF from malformed peer; Host correctly sent an
explicit rejection first. Test was corrected to consume rejection before EOF. This was an assertion
correction, not a production workaround.

## 7. Added development latency/loss simulation

New `DirectConnectNetworkSimulation` integrates with Client TCP and UDP pipelines.

| Setting | Behavior |
| --- | --- |
| Enable through Gradle | `-PnetworkSimulation=gate -PdevTools=true` on Client |
| Runtime property | `delver.networkSimulation=gate` |
| Delay | 55 ms inbound + 55 ms outbound; approximately 110 ms added RTT |
| UDP loss | Every 50th datagram dropped independently in each direction: 2% |
| TCP | Ordered delayed delivery; no simulated TCP message loss |
| Queue bound | At most 2,048 pending deliveries |
| Normal launches | Simulation remains opt-in |

Client startup prints:

```text
[Network simulation] Client: +110 ms RTT on TCP/UDP; 2% UDP loss each way.
```

Gradle rejects unsupported simulation values or missing `-PdevTools=true`. Tests cover delay, ordering,
loss, bounded delivery handling, and disconnect cleanup. Client launch task was also checked with a
Gradle dry-run, without launching game.

This is application-level delay/loss simulation. It does not reproduce OS-level TCP packet loss,
retransmission, congestion, or every real-network condition. Measured RTT also includes underlying
transport and scheduling time.

## 8. Owner report: Longbow hitting worm causes missing-template overlay

### Observation

Owner supplied screenshot after shooting worm with Longbow. Overlay reported Host item template missing
from local content. Reproduction identified recovered arrow bundle:

```text
fb80dc5bb5436d3ffb436c5a9434ab52f13875ee5007506dc2da32cffb887b73
```

Native type was `ItemStack`, name `Arrow`, texture 73. Existing starter bundle used name `Arrows`.

### Cause

Catalogue knew original ammo bundles and loose missiles, but native monster-hit recovery created a
different bundle template. Host announced that derived item; Client could not resolve it locally.

### Build 45 fix

- Extracted `Missile.createRecoveredStack()` from native monster-loot construction.
- `Missile.addArrowLootToMonster()` uses that shared factory.
- `DirectConnectItemController.remember()` registers recovered bundle when first registering a missile.
- Registering only once prevents recursion through missile → bundle → nested missile.
- Factory preserves native name, texture, atlas, stack type, nested missile, and collision setup.

No fabricated fallback item or retail fixture was added. Exact missing-template regression failed before
fix and passed afterward; synthetic variant regression checks the shared construction contract.

### NPC/monster arrow question

Owner asked twice whether recovered arrows from other NPCs/monsters were covered. Fix is based on native
missile and recovery-bundle type, not shooter identity or a special worm branch. Registered native missile
variants using this recovery path gain their matching bundle template regardless of who fired them.

This does not claim every NPC/monster variant was manually tested. Owner replay should include other
available arrow-producing monsters. Custom or otherwise unregistered content still has to satisfy
catalogue compatibility; missing content is not silently accepted.

### Validation

447 core + 15 desktop tests passed with external owned archive, zero failures/errors/skips. All eight
native catalogue tests passed. Owner replay after fix was requested; final visual acceptance was not
recorded as complete.

## 9. Owner rule change: XP only from each player's own kills

### Observation and decision

Owner saw level-up ability selection on both Host and Client when only Host killed monsters. Existing
behavior followed nearby shared-XP rule from PRD/#17: living nearby Participants received XP too.

Owner explicitly chose individual XP caused by own kills. This is a product-rule revision during #20,
not merely a UI bug fix or work deferred to a future issue.

### Build 46 change

`EconomyRules.experienceRecipients()` awards full native XP only to eligible living Participant matching
Host's credited killer. Removed 12-tile proximity-sharing rule. Existing native combat attribution
(`lastParticipantAttacker`) supplies credit; no new assist or damage-share scoring system was introduced.

- Nearby non-killers receive no XP for that kill.
- Distance alone does not exclude credited eligible killer.
- Downed/ineligible or unattributed kills do not fall back to giving everyone XP.
- Only recipient's personal XP, level-up choice, stats, and associated level-up healing advance.
- Existing personal progression ledger and Host-validated stat choices remain in use.
- Gold sharing is unchanged; this decision applies to combat XP.

Local specification, implementation plan, and native gameplay inventory were updated. GitHub PRD and
historical #17 issue text were not rewritten; future work must follow owner's newer explicit XP decision.
No wire-schema change was required.

### Validation

Pre-change focused run: 18 tests, five expected failures exposing shared XP/unrelated level-ups.
Tests cover Host and Client as killer, nearby observer, personal choice/healing, distant killer,
and downed/unattributed exclusions. Final build-46 run: 448 core + 15 desktop passed without skips.

Owner replay remains: stand together, let only Host kill until leveling, then swap roles. Observer's
XP and upgrade screen must not advance because of teammate's kills.

## 10. Owner report: reconnect says TCP connection closed

### Observation and crucial clarification

Both peers initially entered game. Owner closed Client, relaunched with same command, and received
`Host TCP connection closed` while Host still ran. Owner asked to retry CCE and later confirmed
**only Client had restarted after build 46**.

Three separate facts matter:

1. Running Host retains code/build identity loaded at launch. Recompiling/relaunching Client does not
   update that Host process. Exact-build mismatch can therefore explain rejection.
2. Active-floor reclamation already requires valid identity/token within ten seconds of unpaused Host
   reconnect grace. Gradle startup can exceed that window.
3. Development simulator had a confirmed bug: buffered inbound rejection could be discarded when TCP
   channel closed, leaving only generic EOF message visible.

Current rejection packet from owner's failed attempt was not captured. Existing profile logs did not
provide current evidence. Mixed build was supported by owner's restart history, but not presented as
a captured-packet certainty. Simulator failure was independently reproduced in regression tests.

### Build 47 fix

On TCP channel close, simulator drains already-received inbound TCP messages in order before forwarding
EOF. Unsent outbound deliveries fail/release; UDP buffers still discard. Scheduled delivery handling
preserves inbound messages needed for rejection diagnostics while cleaning up ownership correctly.

Regression tests cover rejection-before-EOF, buffer ownership, live build/content mismatch through gate
simulation, expired reconnect grace, and successful full-floor reconnect. A real-time delay test was
sensitive to VM/JVM startup; tests now freeze/advance embedded-channel time explicitly.

Final connection/simulation suites: 49 tests passed; desktop compilation passed. Latest build-48 full
suite also includes these tests.

### Answer to “does it still reject different builds?”

**Yes.** Exact build and content compatibility checks remain. Build 47 fixes visibility of rejection
reason, not permission for mismatched peers to join. Protocol equality alone is insufficient.

### Supported replay procedure

1. Restart both processes on same current build after code changes.
2. Start session with original separate profiles.
3. Host uses **Pause Session before Client exits**; personal inventory/menu does not count.
4. Relaunch Client with same profile and owned-copy command; retain identity/reconnect credentials.
5. Wait for slot reclaim, then Host resumes session.

Pause freezes grace and avoids racing full Gradle startup against ten seconds. After grace expires,
active-floor re-entry remains rejected even though Campaign Slot is retained. Unlimited active-floor
rejoin, new hot-join rules, and campaign reload persistence were not implemented here.

## 11. Owner report: arrows do not stack

### Observation and reproduction

Owner supplied inventory screenshot containing many single arrows in separate slots. Real TCP test
`clientRecoveredArrowsMergeIntoNativeStackWithoutLosingAmmo` reproduced failure before fix:
expected one owned ammo item, got two.

### Three causes

1. Native single-player pickup merges ammo by case-insensitive `stackType`, but multiplayer authoritative
   pickup transferred ownership only. Native merge was intentionally bypassed by physical identity path.
2. Loose recovered missiles needed conversion into proper native inventory bundles when first picked up.
3. Client clamped existing stack quantity to smaller of local and Host values, discarding Host-approved
   increases even after a merge could be accepted.

### Build 48 fix

`AuthoritativeItemWorld` records Host-only stack type and inventory-bundle template metadata. On PICKUP:

- Find compatible owned, unequipped stack by case-insensitive native stack type.
- Merge before free-slot check, so compatible ammo works with full backpack.
- Increase destination quantity and revision; mark source consumed exactly once.
- Keep distinct ammo types separate; reject overflowing merge without losing source or destination.
- If no compatible stack exists and capacity permits, convert loose missile's template to native bundle
  while retaining physical entity identity.

`DirectConnectItemController` registers metadata for native stacks and missiles, replaces loose native
missile presentation with bundle when Host state requires it, and accepts Host ammo count increases
and decreases. Ammo spending remains Host-owned. No new wire message was necessary.

### Validation

- Real TCP Client recovery: starter 8 arrows → loose arrow pickup gives 9 → recovered monster bundle
  of 2 gives 11; Host reports one stack and Client receives correct ammo count.
- First loose arrow becomes inventory bundle with same physical identity.
- Host-approved later count increases reach native inventory without generating false spending/consumption.
- Compatible pickup works with full backpack.
- Duplicate or contested pickup cannot award same source twice.
- Drop/reclaim and subsequent spending preserve merged quantity.
- Different ammo types remain separate; bounded-quantity overflow preserves items.

Full Windows run with external owned archive: **457 core + 15 desktop = 472 passing tests**,
zero failures/errors/skips. Compilation and `git diff --check` passed.

Owner was asked to restart both peers on build 48 and recover ground arrows plus monster drops with
each player. Existing separate stacks can be dropped and picked up again to merge; automatic migration
of every already-owned separate stack was not added. Manual replay remains pending.

## 12. Changed-file inventory

Paths below are relative to repository root. Existing source changes remain uncommitted.

### Production and launch configuration

| File | Session change |
| --- | --- |
| `Dungeoneer/src/com/interrupt/dungeoneer/entities/projectiles/Missile.java` | Shared native recovered-bundle factory |
| `Dungeoneer/src/com/interrupt/dungeoneer/multiplayer/economy/EconomyRules.java` | Killer-only XP |
| `Dungeoneer/src/com/interrupt/dungeoneer/multiplayer/items/AuthoritativeItemWorld.java` | Stack metadata, accepted pickup merge, source tombstone, loose-arrow bundle conversion |
| `Dungeoneer/src/com/interrupt/dungeoneer/multiplayer/items/DirectConnectItemController.java` | Derived catalogue entries, stack registration, native bundle materialization, accepted quantity increases |
| `Dungeoneer/src/com/interrupt/dungeoneer/multiplayer/network/DirectConnectClient.java` | Client simulation wiring and startup diagnostic |
| `Dungeoneer/src/com/interrupt/dungeoneer/multiplayer/network/DirectConnectProtocol.java` | Protocol 43, build 48, 16 KiB assembly bound |
| `Dungeoneer/src/com/interrupt/dungeoneer/multiplayer/network/DirectConnectWire.java` | Bounded TCP combat fragmentation/assembly |
| `Dungeoneer/src/com/interrupt/dungeoneer/multiplayer/network/DirectConnectNetworkSimulation.java` | New simulation handler, including close-time rejection delivery fix |
| `DungeoneerDesktop/build.gradle` | Validated Client gate-simulation launch option |

### Tests

| File | Coverage added or revised |
| --- | --- |
| `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/economy/EconomyRulesTest.java` | Killer-only recipient rules |
| `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/economy/NativeEconomyRegressionTest.java` | Native personal XP, choice/healing, exclusions |
| `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/items/AuthoritativeItemWorldTest.java` | Full inventory merge, idempotence, conversion, type separation, overflow, transfer/spending |
| `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/items/NativeItemCatalogueTest.java` | Exact missing arrow template and derived variant |
| `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/items/NativeOwnershipRegressionTest.java` | Native loose-arrow conversion and quantity increases |
| `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/network/DirectConnectIntegrationTest.java` | Full-floor gate/reconnect, malformed peer, rejection diagnostics, native ammo merging |
| `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/network/DirectConnectWireTest.java` | Large snapshots and malformed fragment boundaries |
| `Dungeoneer/test/com/interrupt/dungeoneer/multiplayer/network/DirectConnectNetworkSimulationTest.java` | New deterministic simulation, close ordering, and buffer tests |

### Documents and unrelated local files

Updated `docs/native-gameplay-replication.md`, local `docs/MULTIPLAYER-SPEC.md`, and local
`docs/IMPLEMENTATION-PLAN.md`; created operational gate note and this handoff. Specification/plan files
were already local/untracked; their personal-XP rule edits do not mean whole files originated here.

Do not indiscriminately stage all untracked files. Pre-existing local material includes `.agents/`,
`.claude/`, `.codex/`, `AGENTS.md`, `CLAUDE.md`, `CONTEXT.md`, `docs/adr/`, `skills-lock.json`, and
`Dungeoneer/.temp/`. New production/test simulation files and session notes are also untracked, so
eventual commit requires deliberate file selection.

## 13. Automated validation record

| Milestone | Recorded result |
| --- | --- |
| TCP overflow reproduction | 1 test, 1 expected failure before fix |
| Initial broad gate run | 445 core: 439 passed, 5 owned-content skips, 1 assertion failure; malformed-peer assertion corrected and focused rerun passed |
| Initial desktop | 15 passed; compilation passed |
| Initial owned-content rerun | All five previously skipped checks passed with external archive |
| Build 45 recovered catalogue | 447 core + 15 desktop passed; no skips |
| Personal-XP red run | 18 focused tests, 5 expected failures before rule change |
| Build 46 personal XP | 448 core + 15 desktop passed; no skips |
| Build 47 reconnect diagnostics | 49 connection/simulation tests passed; desktop compilation passed |
| Arrow-stacking red run | TCP inventory regression failed: expected 1 item, got 2 |
| Build 48 complete run | 457 core + 15 desktop passed; no failures/errors/skips; 2m 18s recorded Gradle duration |

Final complete build/test command, run from macOS:

```sh
prlctl exec 'Windows 11' cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && set "OWNED_GAME_COPY_TEST=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" && gradlew.bat Dungeoneer:test DungeoneerDesktop:test --continue --no-daemon --console=plain'
```

Saved reports:

- `Dungeoneer/build/test-results/test/`
- `DungeoneerDesktop/build/test-results/test/`
- `Dungeoneer/build/reports/tests/test/index.html`
- `DungeoneerDesktop/build/reports/tests/test/index.html`

These build outputs may be replaced by future runs. Aggregate results above preserve session evidence.
Automated native/owned-content tests do not establish rendered animation, spatial audio, or long-session
smoothness; those remain owner observations.

## 14. Exact owner launch commands — owned game assets

Run in separate Windows PowerShell windows. Restart both after build updates. Host first, then Client;
approve Friend in lobby and start session. Current expected build is 48 on both.

### Host — original owned-content command

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectHost -PsessionPort=37777 -PcampaignCapacity=2 -Pnickname=Host -Pavatar=humanoid-1 -PprofileRoot=C:\DelverMpProfiles\Host "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" -PdevTools=true --no-daemon'
```

### Client — original owned-content command, normal local connection

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectClient -PsessionAddress=127.0.0.1 -PsessionPort=37777 -Pnickname=Friend -Pavatar=humanoid-2 -PprofileRoot=C:\DelverMpProfiles\Client "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

### Client — owned content with issue #20 simulated conditions

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectClient -PsessionAddress=127.0.0.1 -PsessionPort=37777 -Pnickname=Friend -Pavatar=humanoid-2 -PprofileRoot=C:\DelverMpProfiles\Client "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" -PdevTools=true -PnetworkSimulation=gate --no-daemon'
```

Use one Client variant at a time. Host command stays same. K enables Host development menu for spawning
catalogued items/monsters and preparing scenarios; Client development flag is required by simulation
launcher validation, not a grant of Host gameplay authority.

Exact open-source-floor commands and timed checklist remain in
[operational gate note](2026-09-24-issue-20-gameplay-gate.md#owner-launches--open-source-floor).
Those commands intentionally omit `ownedCopy`; do not substitute them when owner asks specifically
for the supplied retail-asset launch.

## 15. Remaining owner checks and acceptance evidence

### Immediate replay on build 48

| Check | Required observation | Status |
| --- | --- | --- |
| Longbow → worm | Recover arrows without missing-template overlay | Owner acceptance pending |
| Other arrow-producing NPCs/monsters | Registered native recovered bundles present correctly | Owner acceptance pending |
| Ground + monster arrow pickups | One compatible stack increases; repeat as Host and Client | Owner acceptance pending |
| Full backpack | Compatible arrows merge without requiring another slot | Owner acceptance pending |
| Personal XP | Only credited killer gains XP/level-up choice; swap roles | Owner acceptance pending |
| Reconnect | Same-build/same-profile Client reclaims slot while Host Pause Session protects grace | Owner acceptance pending |
| Rejection reason | Invalid build/content or expired grace reports reason, including gate mode | Automated coverage passed; manual observation pending |

### Full issue #20 run

Use operational note's timed sequence. Record at least 30 unpaused minutes on one open-source floor,
simulated connection settings, both roles, and both visual/audio and gameplay observations. Include
doors, chat, ping, item ownership, status overlap/expiry, native impacts, Downing/Revival, Life loss,
pause/reconnect, and Host continuing as Spectator. Test Party Wipe after timed run because it ends session.
Avoid L/J floor jumps during one-floor validation.

For evidence, record build, floor/content mode, date/time, unpaused duration, attacker/observer,
steps, expected/actual result, and relevant local logs/screenshots. Do not mark issue complete from
successful connection, equal health values, or green JUnit totals alone.

### Carried gaps and later scope

- Campaign Save persistence remains #21; local XP changes do not implement saving.
- Personal knowledge/scroll integration remains #24.
- Party Transitions, abandonment, and transition handling remain #27.
- Scoped trigger work remains #28; physical Orb behavior remains #30.
- Egg/spawner duplicate replicas and persistent encounter work remain #31.
- Prior notes still flag disconnected slots with remaining Lives affecting Party Wipe eligibility,
  reconnect-grace Host → encounter lock paths, and thrown items dropping at feet without velocity.
- Dev tools remain development-only; #33 packaging must not enable their launch flags.
- If a deferred gap breaks required active-floor gate behavior, record it as gate blocker rather than
  using its future issue number to mark failing acceptance as passed.

## 16. Next-session handoff

Continue in same checkout/branch. Read this handoff and operational gate note first. Preserve local
uncommitted work and owner's personal-XP decision. Retry CCE; if unavailable, report failure accurately
and use durable session evidence rather than claiming recall succeeded.

Next concrete step is owner's build-48 replay, followed by sustained issue #20 gate evidence. Fix any
new reproducible blocker through existing Host authority and presentation boundaries. Commit, push,
and issue closure have not been performed; obtain owner's acceptance/instruction for those actions.
