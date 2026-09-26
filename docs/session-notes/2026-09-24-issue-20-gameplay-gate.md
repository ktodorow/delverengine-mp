# Issue #20 — one-floor gameplay gate

Status: owner instructed commit, push, and closure of #20 on 2026-09-26. Build 48 / protocol 43.
Latest automated validation: 457 core + 15 desktop tests passed with external owned archive.
Pending-play-test entries below preserve the evidence recorded during development; no additional timed
run or native Windows acceptance evidence was supplied with the closure instruction.

Full discussion, implementation history, file inventory, owned-content commands, and next-session handoff:
[2026-09-24 to 25 session record](2026-09-24-to-25-issue-20-session.md).

Branch/check-out: `mp-v108-prototype`, existing repository. Base: `c5e7ebc`.
Contracts: [issue #20](https://github.com/ktodorow/delverengine-mp/issues/20),
[PRD #1](https://github.com/ktodorow/delverengine-mp/issues/1),
[native inventory](../native-gameplay-replication.md),
[accepted #18/#19/#39 session](2026-09-21-to-24-issues-18-19-39-session.md).

## Gate blocker and fix

Existing 64-monster / 68-combatant snapshot exceeded 1,024-byte TCP frame limit.
Regression `DirectConnectWireTest.fullFloorCombatStateFitsBoundedTcpFrames` failed on Windows before fix:
`EncoderException: ProtocolException: TCP frame exceeded protocol size bound.`
Ordinary short identities reproduce failure; collection size, not invalid identity, causes it.

Protocol 43 / build `mp-v108-prototype-floor-gate-48` keeps 1,024-byte TCP/UDP limits.
Large combat snapshots use ordered TCP-only fragments (type 52), with total size and exact offset.
Receiver assembles at most one 16 KiB snapshot per connection and publishes only complete validated state.
Duplicate, missing/out-of-order, changed-total, oversized, unrelated, and interrupted fragments fail closed.
Disconnect discards partial state. Small snapshots keep normal encoding. Existing snapshot sequence checks
reject stale state after assembly. Monster count remains capped at 64; increasing campaign entity capacity is separate work.

## Windows automated checks

Run from macOS; tests compile/run in existing Parallels VM without launching games:

```sh
prlctl exec "Windows 11" cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat Dungeoneer:test DungeoneerDesktop:test --continue --no-daemon --console=plain'
```

New checks cover full-size snapshots (including maximum identity lengths), per-frame bounds, atomic delivery,
malformed fragments, and real TCP reconnect with 64 monsters through simulated gate conditions.
Network simulation checks both directions, reliable ordering, 2% UDP loss, and buffer cleanup on disconnect.
Results recorded below; owned-content checks need `OWNED_GAME_COPY_TEST` pointing at external `delver.jar`.

## Owner launches — open-source floor

Run each command in separate Windows PowerShell window. Host first; approve Friend in lobby, then start.
No `ownedCopy` selects repository-owned open-source test floor. Profile paths below reuse existing profiles;
unique campaign id separates gate roster. Neither command launches a parallel repository.

Host:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectHost -PsessionPort=37777 -PcampaignCapacity=2 -PcampaignId=issue20-open-floor -Pnickname=Host -Pavatar=humanoid-1 -PprofileRoot=C:\DelverMpProfiles\Host -PdevTools=true --no-daemon'
```

Client, impaired connection:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectClient -PsessionAddress=127.0.0.1 -PsessionPort=37777 -Pnickname=Friend -Pavatar=humanoid-2 -PprofileRoot=C:\DelverMpProfiles\Client -PdevTools=true -PnetworkSimulation=gate --no-daemon'
```

Client console must show `[Network simulation] Client: +110 ms RTT on TCP/UDP; 2% UDP loss each way.`
Mode adds 55 ms to inbound and outbound client messages, preserving TCP order and dropping every 50th UDP
datagram independently in each direction. This is application-level delay/loss simulation, not an OS-level
TCP retransmission or congestion test. Baseline LAN run: omit `-PnetworkSimulation=gate`.
Simulation requires explicit Client development launch; normal/release launch remains unchanged.

## Owner launches — native owned-content checks

Use original Host/Client commands with owned `delver.jar`; add `-PnetworkSimulation=gate -PdevTools=true`
to Client command. Host can use K to spawn catalogued weapons, bombs, items, and monsters.
Owned archive remains read-only. Do not export retail assets or definitions as evidence.
These native-content checks complement the open-source floor run; they do not replace its 30-minute criterion.

## Play-test sequence and evidence

Record build, floor, date, start/end, total unpaused duration, connection mode, and result per row.
Open-source gate needs at least 30 minutes on one floor. Complete all available rows there; use owned-content
run for native families unavailable in open-source catalogue. Swap attacker between Host and Friend;
other instance observes. Keep both windows visible where possible.

| Stage | Actions | Required observation |
| --- | --- | --- |
| 0–5 min | Walk, strafe, turn, corners, obstacles; water where available; chat and ping | Immediate local response, smooth remote movement, no sustained correction loop; matching positions and messages |
| 5–12 min | Sword variants, bow, wand variants; monster hits, misses, walls, doors, breakables | Same accepted damage and world outcome; native shot/impact, spatial sound, animation, cleanup visible to attacker and observer |
| 12–18 min | ICE apply/refresh/expiry, resisted effect, concurrent effects, poison/fire, potion/scroll and lit bomb | Same status and timing; no false resisted effect, duplicate damage, phantom projectile, invisible persistent fire, or restarted effect |
| 18–23 min | Contested pickup, drop/reclaim, item use; Downing, interrupted/successful Revival, bleedout/respawn | One item owner/consumption; correct Lives/health; no instant re-death, lost accepted pickup, or stale action after respawn |
| 23–27 min | Host Pause Session during effect/Downing, resume; Client reconnect mid-effect/projectile | Host timers pause; current remaining state restored quietly; no replayed damage/loot or restarted effect |
| 27–30+ min | Repeat combat/movement, inspect corpses/items/doors; increase monster population via K if needed | Host and Client stay responsive; world state agrees throughout; no large-floor snapshot loss |
| After timed run | Exhaust one slot, Host Spectator, simultaneous Downing, final Party Wipe | Living player continues; Host server remains active while spectating; wipe ends run only when no slot can return |

Reconnect: retain Client profile/identity; close/relaunch only Client while Host remains running.
Record elapsed disconnect time (inside or beyond ten-second grace). Use long-lived effect for process restart test.
Never use L/J floor jumps during timed one-floor run. Dev respawn/grant-Life can support setup but must not
substitute for natural Life-loss and wipe acceptance.

For each failure: timestamp, role, item/monster/effect, steps, expected/actual, both window observations, and
relevant local logs. Keep evidence local until owner chooses to share. One matching health value does not prove
visual/audio fidelity. Parallels validation is development evidence; native Windows repeatability and later
two-physical-machine release gate must be recorded separately.

Known follow-up scope from accepted notes: campaign saves #21; personal knowledge/scroll integration #24;
Party Transitions/abandonment #27; scoped triggers #28; Orb #30; egg/spawner duplication and persistent encounters #31.
If any such gap breaks required active-floor behavior during this gate, record as blocker rather than marking pass.

## Results

- Pre-fix Windows regression: 1 test, 1 failure (TCP combat frame overflow).
- Initial fixed Windows wire suite and desktop compilation: passed.
- Windows broad run: 445 core tests, 439 passed, 5 owned-content skips, 1 new assertion failure;
  corrected assertion to consume Host's explicit malformed-handshake rejection before EOF.
  Corrected 64-monster delayed/lossy reconnect integration rerun: passed. Production code unchanged by correction.
- Windows desktop: 15 tests passed; compilation passed.
- Windows Client launch task with gate flags: Gradle dry-run passed; no game launched.
- Owned-content rerun with external read-only archive: all 5 passed, no skips. No game instances launched.
- Owner 30-minute open-source floor run: pending.
- Owner impaired native-content visual/audio run: pending.
- Native Windows x64 repeat outside VM: pending.

## Owner play-test — longbow versus worm

Owner reported Client compatibility failure after shooting worm with Longbow on owned tutorial.
Visible missing-template prefix matches native recovered-arrow stack:
`fb80dc5bb5436d3ffb436c5a9434ab52f13875ee5007506dc2da32cffb887b73`
(`ItemStack`, name `Arrow`, texture 73).
Regression uses native `Missile.addArrowLootToMonster`, then sends resulting physical-item description
to independently prepared Client catalogue. CCE endpoints unavailable (`Transport closed`); targeted local
reads used for this follow-up.

Pre-fix Windows regression failed with the exact missing-template hash above. Root cause: catalogue
registered original `Arrows` ammunition bundles and loose `Arrow` missiles, but not singular `Arrow`
bundles created by native monster-hit recovery. Shared `Missile.createRecoveredStack()` now supplies
both native loot creation and derived local templates. Registration happens once per missile template,
avoiding recursion through the recovered bundle's nested missile. No missing-template fallback or
retail definitions were added. Build 45 identifies this catalogue fix; wire protocol remains 43.

Initial fixed Windows catalogue suite passed. Additional regression checks a synthetic ammo variant's
name, texture, atlas, stack type, nested missile, and accumulated recovered count. Full Windows suites
with external owned archive passed: 447 core tests and 15 desktop tests, zero failures/errors/skips.
All eight native catalogue tests passed; owned floor/tutorial checks ran against external `delver.jar`.
`git diff --check` passed. CCE decision/code-area writes also failed (`Transport closed`); this note
preserves diagnosis and validation. Owner replay remains pending.

Owner replay: restart both peers with the same owned-archive commands. On tutorial, shoot/kill worm
with Longbow; verify recovered arrows appear and can be picked up, with no compatibility overlay.
Repeat with Host and Client as shooter and observer.


## Owner rule revision — personal combat XP

Owner observed both upgrade screens after Host kills and requested XP caused only by each player's own
kills. This explicitly supersedes nearby shared XP in PRD #1 / completed issue #17. Local specification,
implementation plan, and native gameplay inventory reflect the revision; GitHub issue bodies remain
historical and were not edited.

Host now awards unchanged native XP amount only to the living, connected Participant credited by native
combat attribution (`lastParticipantAttacker`). Nearby non-killers and assists receive none. Missing or
ineligible killer yields no award. Existing personal ledger, stat choice, level-up healing, and Client
replica guards remain in use. Gold sharing is unchanged. No wire-schema change; build 46 identifies rule.

Windows pre-change regression: 18 tests, five expected failures showing proximity XP and unrelated
level-ups. Tests cover Host kill beside Client, Client kill beside Host, only killer's upgrade choice and
healing, distant killer eligibility, and no reward for downed/unattributed kills. Final Windows validation:
448 core tests and 15 desktop tests passed, zero failures/errors/skips, with external owned archive.
`git diff --check` passed; no game instances launched.

Owner replay: restart both peers with original owned-archive commands; stand together, let only Host kill
until leveling, then let only Client kill. Only killer's XP/level and upgrade choice should advance. Both
players may choose different stats independently. Manual acceptance remains pending.


## Owner reconnect report — hidden rejection reason

Owner reported `Host TCP connection closed` when relaunching Client while Host kept running, then
confirmed only Client had restarted after build 46. Running Host retains its older build; restarted
Client recompiles current checkout, so exact-build compatibility can reject it. No current console
logs were persisted in profile directories, so actual rejection packet was not captured.

Separate existing rule: active-floor reclamation requires valid credentials within ten seconds of
unpaused Host grace. A full Gradle launch may exceed this window. Host-controlled Pause Session before
Client exit freezes grace; personal menus do not. After grace expires, slot is retained but active-floor
re-entry is rejected. Rule remains unchanged by this diagnostic fix.

New regression proved development latency simulator dropped inbound TCP rejection buffered before
channel close. Build 47 drains already-received TCP messages in order before notifying downstream EOF;
unsent writes fail and release, UDP buffers still discard. Tests check rejection-before-EOF, buffer
ownership, live gate-mode build/content mismatch, expired-grace diagnostic, and successful full-floor
reconnect. Initial focused run: seven checks passed (including live build/content/grace messages and
full-floor reconnect), one pre-existing real-time delay test failed during cleanup. Embedded-channel
tests now freeze and advance their clock explicitly, removing JVM/VM startup timing sensitivity.
Final Windows connection/simulation suites: 49 tests passed, zero failures/errors/skips. Desktop
compilation and `git diff --check` passed. Game launch/replay remains owner-controlled.

Owner replay: restart both processes once on build 47. Start Party; Host pauses session before Client
exits. Relaunch Client with same owned-copy/profile command, wait for slot reclaim, then Host resumes.
This checks process restart without racing ten-second grace. Owner play-test remains pending.


## Owner play-test — recovered arrows occupy separate slots

Owner screenshot showed many single arrows instead of one ammunition stack. Real TCP regression
`clientRecoveredArrowsMergeIntoNativeStackWithoutLosingAmmo` failed before fix: expected one owned
item, got two. Native single-player merges ammunition by case-insensitive `stackType`; authoritative
multiplayer pickup only transferred ownership, bypassing that merge. Client also clamped existing
stack counts to their previous local value, discarding an accepted increase.

Build 48 registers Host-native stack metadata. PICKUP merges compatible ammo into an owned stack
before checking free backpack slots, increments destination quantity, and tombstones source exactly
once. Without a compatible stack, a loose missile becomes its native inventory bundle under the same
physical identity. Client materializes that bundle and accepts Host ammo quantities in both directions.
Different stack types stay separate; quantity overflow cannot lose or duplicate ammunition. Wire
protocol stays 43; both processes must restart for exact build compatibility.

Regression coverage: real TCP Client pickup of loose arrow and monster-loot bundle (8 → 9 → 11),
first loose-arrow conversion, Client count increases, full backpack merge, duplicate/contested pickup,
drop/reclaim and spending after merge, different ammo types, and bounded-quantity rejection.
Focused integration/catalogue/ownership/item-world suite passed after fix. Final Windows build 48
validation with external owned archive: 457 core and 15 desktop tests passed, zero failures/errors/skips.
Compilation and `git diff --check` passed. No game instances launched by agent.

Owner replay: restart both peers on build 48 using owned-archive commands. In tutorial, shoot and
recover arrows from ground and killed monsters as each player; compatible arrows should increase
one stack's count, including with a full backpack. Existing separate stacks can be dropped and
picked up again to merge. Manual gameplay acceptance remains pending.
