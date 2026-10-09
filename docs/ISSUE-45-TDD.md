# Issue #45 — native Connect and remembered presentation

## Inspection and expected behavior

Inspected live [#45](https://github.com/ktodorow/delverengine-mp/issues/45), launcher PRD [#40](https://github.com/ktodorow/delverengine-mp/issues/40), project PRD [#1](https://github.com/ktodorow/delverengine-mp/issues/1), CCE history, domain glossary and existing launcher/session/profile paths. Work stays in existing `mp-v108-prototype` checkout. Original v1.08 engine and read-only owned assets remain product baseline: standard Delver shared by 2–4 Participants, one authoritative Host, persistent Campaign Slots and private Launcher Identity/reconnect credentials.

At inspection, Connect used an OS address prompt with shared default port/presentation, followed by generic development session screen. Required result is native in-window address/port/Nickname entry, four existing static gameplay-matching Avatar portraits with enlarged selection, Connect/Back, editable remembered endpoint/presentation, visible connection/authentication/synchronization progress, retained values on invalid input or rejection, and safe cancel/retry. Default port is 37777. Address supports direct LAN/internet hostname/IP entry through existing TCP/UDP services; no discovery/router configuration in this slice.

Nickname keeps existing trimmed 1–32 Unicode code points, maximum 64 UTF-8 bytes, no control characters. Host remains authority for case-insensitive campaign uniqueness and Avatar reservations, including absent saved slots. Client form selection is provisional. Valid identity/token reconnect restores campaign-specific presentation and retained character; defaults cannot silently overwrite it. Host/Client roles share one ordinary profile identity. Existing development profile overrides stay available.

## Approved seams and method

#40 records explicit owner approval: “Yes, use these seams.” Test existing public `DirectConnectSessionFlow` with real TCP/UDP `DirectConnectHost`/`DirectConnectClient` and temporary campaign/profile stores. Extend this one application action boundary with typed Connect setup consumed by production screen. Profile boundary tests only for distinct durable/error behavior. No widget-tree/private-helper assertions, separate screen model, or substitute networking stack.

Vertical slices: documented expectation → failing Windows test → minimal implementation → passing Windows test. Already-working regression coverage is reported as such, without invented RED. Owner handles native Windows text entry, portraits/scaling and gameplay acceptance. Agent builds/tests only. Shared authoritative lobby/automatic pregame admission/Ready are later #46/#47; existing admission controls remain until those slices.

## RED/GREEN evidence

1. **Typed real connection and durable defaults.** Expected: profile starts with port 37777; native action trims typed address/Nickname, joins actual Host over TCP/UDP, stores endpoint/preferred presentation, and reloads them with unchanged Launcher Identity. Windows RED: missing `ConnectSetup`/`prepareConnect`/`openClient` action API; compilation failed before any session test (`.scratch/issue-45/red-01.log`). Added typed action with existing nickname/Avatar rules, bounded hostname/IP plus separate port validation, and atomic endpoint/profile preferences. Windows GREEN: 1 real TCP/UDP/profile test passed; build successful in 51s (`green-01.log`).

2. **Damaged noncritical defaults remain editable.** Expected: malformed endpoint or presentation preference falls back independently, reading preserves damaged bytes and private identity, no listener opens. Windows RED: 3 tests, 1 failure (`red-02.log`, 48s), malformed Properties Unicode escape escaped fallback at `prepareConnect`. Loading noncritical preferences now catches malformed/read failures independently and returns editable defaults without rewriting files. Windows GREEN: all 3 tests passed in 48s (`green-02.log`). Additional typed-input/Unicode/IPv6 tests check already-implemented validation as regression coverage, not invented RED.

3. **Host-validated conflict edit/retry.** Expected: case-insensitive Host nickname conflict and reserved Avatar reject provisional input; defaults/fields retain each attempt, same flow accepts corrected real Client claim, stale rejected peers cannot enter gameplay. Windows RED: 4 tests, 1 failure, 48s (`red-03.log`): corrected attempt blocked by prior rejected peer. Flow now closes terminal Client attempt without releasing native form, preserves pending/active-session guard, replaces retry request and keeps stale-peer entry check. Windows GREEN: all 4 tests passed in 49s (`green-03.log`).

4. **Progress and cancellation before entry.** Expected: real stalled TCP handshake reports authentication; Cancel closes peer, clears pending retry and rejects stale entry; pending Host claim cancellation releases claim, then same flow reconnects and joins successfully. Windows RED: missing `cancelClient`/`getClientProgress` API, compilation failed in 43s (`red-04.log`). Added actual-session progress projection and pre-entry Client-only cancellation, clearing retry without disposing native form. Windows GREEN: all 5 tests passed in 47s (`green-04.log`).

Additional real-session/profile regressions now cover Ready-before-native-entry Cancel, wrong-token rejection followed by valid retained-slot return with conflicting provisional defaults, same movement entity/identity/token/presentation, real stalled-handshake timeout, shared Host/Client identity, and actual preference-write failure closing opened Client. These exercise implemented/existing contracts; no invented RED. Focused Windows regression run: all 9 tests passed, 58s (`regressions.log`). Native view added afterward; complete final-source validation reported below.

Native production adapter now replaces OS address prompt with original forest/pixel-font/panel/button form, typed address/port/Nickname, four static gameplay-resolved/tinted portraits, enlarged selection and F1–F4 selection. Existing Host resolver is shared; form retains input through validation/rejection and stays visible for actual connection/authentication/synchronization/waiting status. Posted navigation and gameplay entry check current screen/peer; Cancel ends pending request. Native gameplay entry uses existing movement/Spectator readiness and application lifecycle; Client return opens remembered Connect form. No LAN discovery controls or Ready policy added.

## Final automated validation

First fresh final-source Windows run completed 673 core tests, 1 failure, 0 errors/skips in 5m2s (`full-validation.log`). Desktop/audits did not run because core task failed. Failure was existing `threePeerKnowledgeStaysPrivateAndWarmReconnectKeepsOwnFacts` fixture constructing Host, before knowledge/session assertions: `channel not registered to an event loop`, `DirectConnectHost.closeChannel` → `bind` → integration fixture `host`. Same pre-existing cleanup symptom was recorded during #44. Original bind cause is masked and remains unproven; no networking fix claimed. Captured full report retained in `.scratch/issue-45/first-full-integration.xml`; Isolated identical test passed unchanged in 20s (`bind-repro.log`), so isolated loop did not reproduce failure. No bind root-cause/fix claim; later reconnect failures and fixes documented below.

## Reconnect ordering regression

Second unchanged full Windows run completed 673 core tests, 1 failure, 0 errors/skips in 5m13s (`full-validation-final.log`). Desktop/audits again stopped at core failure. This time `savedSetupLocksRulesAndHostOnlyResumePreservesAbsentFriendForLiveReturn` failed during valid saved-slot return: `Host Party status omitted local Active Floor Entity`. Isolated case passed unchanged in 15s, so bounded repetition was needed. Original complete case reproduced failure at repetition 63 (`subset-stress.log`). Temporary diagnostics reproduced at repetitions 2, 68 and 11, then were removed from production sources.

Ranked hypotheses checked: UDP/TCP bootstrap ordering, live Party publication overtaking return baseline, and lifecycle/generation dropping valid spawn. Captured Host baseline contained two entities; it queued writes from UDP event loop. TCP event-loop tick then sent a newer Party snapshot immediately before its already-queued baseline tasks ran. Client saw connected `net-2` with no entity descriptors. No UDP entity assignment or lifecycle rejection caused this failure.

Lasting regression `repeatedColdSlotReturnKeepsEntityBaselineAheadOfLivePartyUpdates` exercises public native Connect flow and real cold Host/Client sockets, with temporary stores and 128 fresh save/store/session cycles. Expected: valid retained-slot return always reaches READY with local entity present, Friend/Avatar 2 retained and wounded character still 3 health/4 Lives. Bounded repetition exercises actual scheduler assignments; it is not a deterministic scheduler assertion. First reduced regression reused one save and passed 256 sessions (`red-05-ordering.log`); it did not reproduce original scenario. Restored fresh save/store plus denied-token retry per cycle. Windows runtime RED: 1 test, 1 failure in 40s (`red-05-ordering-runtime.log`, preserved XML `red-05-ordering.xml`), same missing-local-entity error during public Connect return. Earlier compile run found a test helper typo; corrected before runtime RED, not counted as behavior evidence. Fix now queues live reliable state on channel event loop even when publisher already runs there, preserving order behind UDP-submitted baseline writes. Strict Client entity validation remains. Windows GREEN: same regression passed all 128 real fresh save/store/session cycles, 1 test, 0 failures/errors/skips, 1m36s build / 54.149s test (`green-05-ordering.log`, preserved XML `green-05-ordering.xml`). Original diagnostic completed 200 cycles without entity-ordering failure (`subset-stress-green.log`, 1m53s). It reported one different `expected 3 / actual 4` fixture health mismatch at repetition 179 and continued, so this is target-specific confirmation, not 200 clean complete scenario passes. Cause/stack was not established in that diagnostic; health regression below separately diagnoses it. Lasting 128-cycle regression is strict and passed all assertions. Temporary diagnostic source/init script archived as `.txt` outside source sets; generated diagnostic test class removed. Final complete validation passed as recorded below.

## Health preservation RED/GREEN

Third fresh full Windows run: 674 core tests, 1 failure, 0 errors/skips in 5m18s (`full-validation-queue-fix.log`, report `third-full-launcher.xml`). Entity readiness passed, but strict repeated return regression saw 4 health instead of saved 3. Desktop/audits stopped at core failure.

Ranked hypotheses: older buffered tick combat overwriting newer native damage; mismatched saved Party/combat state; fixture wound occurring before respawn finished. Expanded public regression verifies fresh saved Party and combat both contain 3 health before reconnect and reports returned Host/Client state. Runtime RED reproduced before reconnect at fresh-save cycles 41 and 94 (`health-order-diagnostic.xml`, `health-fixture-diagnostic.xml`). Diagnostic captured wound completing with Party/combat health 3 at sequence 7, then older buffered combat sequence 6 overwriting sequence 7. Save then contained health 4. This is publication ordering, not an ignored wound or identity/presentation change.

Fix publishes combat cache monotonically with atomic compare-and-set outside Host monitor, preserving existing encounter/Host lock boundary. Host Party update also rejects snapshot superseded while waiting for Host monitor. No combat, Lives, save-format or protocol rule change. Temporary production/test probes removed. Windows GREEN: same strict regression passed all 128 fresh save/store/session cycles, 1 test, 0 failures/errors/skips, 1m41s build (`green-06-health.log`, `green-06-health.xml`). Final fresh whole Windows validation passed, details below.

## Final Windows result

Final fresh run, with both publication fixes and native adapter: **674 core + 22 Desktop tests, 0 failures, 0 errors, 0 skips**, successful in **6m12s** (`.scratch/issue-45/full-validation-combat-fix.log`, counts `final-results.json`). All nine new native Connect action/profile tests passed; strict fresh saved-slot return regression passed all 128 cycles including saved Party/combat health checks. Source assets, release artifact and isolated-runtime-cleanup audits passed.

Executed in existing Windows 11 Parallels VM from same checkout:

```cmd
gradlew.bat Dungeoneer:test DungeoneerDesktop:test DungeoneerDesktop:verifyOpenSourceDist --rerun-tasks --no-daemon --console=plain
```

`OWNED_GAME_COPY_TEST` pointed to read-only owned v1.08 archive. Fork `DungeoneerDesktop/build/libs/game.jar` rebuilt (15,665,846 bytes); archive contains new Connect screen, endpoint store and typed setup classes. Fork SHA-256: `4adace0c37af47052fb89d559992d354b987ade09ef16f84a4a66b0cd2da4925`.

Owned `delver.jar` SHA-256 remains `a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae`, matching pre-work value. Protocol 50/build 56/save 8/roster 2 unchanged. Source/test hashes matched files captured before final run; `git diff --check` passed. No source/test edits after validation. Temporary probes removed and diagnostic harness kept only as `.txt` in task scratch, outside source sets. Existing checkout/branch `mp-v108-prototype`; owner authorized commit, push and issue closure after final validation. No agent gameplay launch; native Windows acceptance remains owner-run.

## Owner acceptance

Pending owner Windows UI/gameplay checks. Automated session evidence cannot certify native portraits, text input, audio or gameplay.

1. Ordinary launch → original title → Connect. Type address, port and Nickname; check Tab, mouse, Enter, Escape, selection highlight, four static portraits and enlarged selected preview at multiple window sizes.
2. Connect real Host on LAN/direct hostname/IP endpoint. Watch connecting, authentication, synchronization and waiting-for-Host feedback; continue into original shared campaign in same window. Existing Host approval/Start controls remain until #46/#47.
3. Enter empty/invalid port/Nickname and conflicting Nickname/Avatar. Check concrete reason, retained fields and edited retry. Test unreachable Host and TCP/UDP failure.
4. Back/Cancel during connection and just before entry; cancelled attempt cannot enter gameplay later. Connect again in same window.
5. Restart Client profile; endpoint/Nickname/Avatar remain editable. Switch same profile between Host/Client roles; ordinary identity stays same. Use distinct existing development profiles for two instances on one VM.
6. Reconnect saved character with different provisional defaults; campaign Nickname/Avatar, character, identity/token ownership retained. Client Leave/Host stop returns Client to Connect with remembered fields.

Windows PowerShell ordinary Client launch:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:run -PprofileRoot=C:\DelverMpProfiles\Client "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

Use existing Host profile with same ordinary command and `-PprofileRoot=C:\DelverMpProfiles\Host`, then Host → Campaigns. Agent does not launch gameplay instances.
