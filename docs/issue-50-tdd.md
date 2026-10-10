# Issue #50 — session return and failure recovery

## Contract inspected before implementation

Sources: [#50](https://github.com/ktodorow/delverengine-mp/issues/50), full launcher PRD [#40](https://github.com/ktodorow/delverengine-mp/issues/40), project PRD [#1](https://github.com/ktodorow/delverengine-mp/issues/1), `CONTEXT.md`, ADR 0004, current native launcher/session/persistence paths, CCE memory.

Existing checkout: `/Users/kristiyantodorov/Repos/delverengine-mp`; branch `mp-v108-prototype`; initial HEAD `2f59226`. Windows 11 Parallels VM supplies Java 8 and all compilation/test execution. Original installation remains read-only; its 136 files and 638 source/test/build inputs were hashed before work.

Goal: preserve original v1.08 engine/gameplay and native one-window launcher while making session shutdown, return and retry reliable. Host remains sole campaign authority. Returning to menus must retain presentation/endpoint defaults, credentials and Campaign Slots. No Host migration, character reset, duplicate gameplay policy or single-player save use.

| Action / condition | Expected observable result |
| --- | --- |
| Host pregame Close | Confirmation explains friends disconnect. Cancel retains session. Confirm stops TCP/UDP listeners; Host returns Campaigns, Clients Connect. No claim that gameplay was saved. |
| Client Leave | Only own connection stops. Connect retains endpoint/presentation. Remaining Participants continue; reserved slot/credentials survive. |
| Host Save and Quit | Latest authoritative checkpoint and clean-stop state persist before native world/resource release. Host returns Campaigns; Clients Connect. |
| Host save failure | Concrete explanation; same live session/native world stays usable; previous save remains readable and recovery protection remains. Retry possible. |
| Unexpected Host loss | Concrete failure shown before Client proceeds to editable Connect; no further gameplay ticking. |
| Input/bind/TCP/UDP/compatibility/capacity/slot/presentation/credential failure | Native progress/reason panel retains context and offers applicable Retry/Edit/Back/Browse. Credentials stay private. |
| Cancel / old callbacks / repeated attempts | Cancelled session cannot later enter gameplay or navigate replacement. Previous listeners/peers release; same endpoints reusable without restart. |

## Agreed testing boundaries

Parent #40 explicitly records owner confirmation on 2026-10-08: “Yes, use these seams.” Reuse its public launcher action/session lifecycle boundary (`DirectConnectSessionFlow`) with real TCP/UDP Host/Client sessions, temporary profile/campaign storage, existing persistence and bounded wire boundaries. Native screens consume this same flow. Filesystem/network fault injection occurs at external boundaries. No private-method/widget-tree assertions or substitute session models.

TDD proceeds in vertical slices: write behavior test, execute Windows RED, record actual failure, implement minimal fix, execute Windows GREEN, record evidence. Existing behavior may be checked as regression without manufacturing failures. Native mouse/keyboard/audio/rendering and full gameplay acceptance remain owner play-test evidence.

## Initial inspection

Existing `leave()` preserves live Host on checkpoint capture failure and releases native resources after save. Entry uses current-peer checks. Native lobby already has a Host-close confirmation and Connect keeps form controls during ordinary failures. Gaps: pregame Host close falsely says saved; clean disconnect is rendered as failure; stopped Client lobby requires manual leave; return destinations/confirmation are split across screens and are not observable through agreed high seam. Clean-stop-marker failure currently tears down after successful checkpoint write.

## RED / GREEN evidence

Raw Windows logs, input hashes, exact commands and copied JUnit XML: `.scratch/issue-50/`.

1. **Pregame Host-close reason.** Windows `red-01-clean` ran `Dungeoneer:test --tests '*SessionReturnTest.pregameHostCloseExplainsLobbyClosureWithoutClaimingGameplaySave'`: 1 runtime failure, zero errors/skips. Expected `Host closed lobby. Return to Connect.`; actual falsely claimed saved gameplay and diagnosed connection failure. Initial `red-01` also exposed a test cleanup bug (null profile restore), corrected before clean RED. Implementation distinguishes reliable Host shutdown notification from unannounced transport loss and sends accurate pregame reason. `green-01`: 1 test passed, zero failures/errors/skips, 64.81s.

2. **Confirmed Host return.** Public launcher action must produce confirmation before closing, then Campaigns destination after endpoint release; old queued confirmations must be harmless. `red-02`: compilation RED, missing `SessionReturn`, `ReturnDestination` and `returnToLauncher` at existing agreed flow boundary, 31.47s. This is explicitly compilation evidence, not a runtime failure. Added immutable action outcome to existing flow; native application/lobby consume it while existing save/teardown owns lifecycle. `green-02`: both return tests passed, zero failures/errors/skips, 64.22s.

3. **Clean-stop failure.** After successful checkpoint capture, inject an undeletable nonempty clean-stop-marker directory at filesystem boundary. `red-03`: 1 runtime failure, expected Host READY but actual FAILED, zero errors/skips, 39.38s. Prepare clean-stop marker before committing teardown, retaining campaign lock until resources close. Failure restores prior pause and keeps peers/native world. `green-03`: 6 lifecycle/persistence tests passed, zero failures/errors/skips, 64.40s.

4. **Host failure return and retained fields.** Actual failed Host reconstruction must produce failure outcome (not clean-close notice), preserve endpoint/Nickname/Avatar/reconnect credential, and block stale gameplay entry. `red-04`: 1 runtime failure (`Host recovery failure must open native failure panel`), zero errors/skips, 38.24s. Only explicit saved-session/lobby-close reasons qualify as clean returns. Native stopped Client lobby now consumes same return outcome; unexpected loss opens native reason panel with Retry/Edit/Back before editable Connect. Confirmation no longer hides a failed-save message. Native panel rendering/input still requires owner acceptance. `green-04`: all 4 return tests passed, zero failures/errors/skips, 53.05s.

5. **Existing behavior and new return outcomes.** Added regression checks for Client-only Leave with three Participants, retained durable claim/credential/fields; active Save/Quit checkpoint/clean marker observed before native-release callback and both role destinations; real unannounced TCP loss followed by Retry on same endpoint, Cancel/reconnect, and obsolete return/entry actions while replacement remains live. `regressions-01`: 31 tests across SessionReturn, DirectConnectSessionFlow, NativeConnectSetup and SharedNativeLobby passed, zero failures/errors/skips, 82.61s. Three new checks passed on first execution; these are regression evidence, not RED. Requested `*DirectConnectPersistenceTest` filter matched no suite; persistence checks were run separately in `green-03`, with full suite validation recorded below.

6. **UDP firewall regression.** TCP relay forwards actual Host/Client traffic while UDP socket deliberately drops registrations. `udp-regression`: 1 test passed, zero failures/errors/skips, 56.60s. Concrete timeout distinguishes UDP loss while TCP remains connected; fields and private credential persist; corrected endpoint reclaims same reserved identity; obsolete return cannot retire replacement. Initially passing regression, not RED. Review additionally found constructor bind-failure listener cleanup; fixed before full validation.

## Owner Windows acceptance

Pending native play-test: Host close/cancel; Client leave with remaining Party; Host Save and Quit/resume; unexpected Host loss; failed-save explanation and retry; bind/input/TCP/UDP/incompatible/full/conflicting-claim failures; repeated Back/Cancel/Edit/Retry; retained fields; one-window native menus and unchanged original installation/saves.

## Review and native play-test handoff

Independent Standards and Spec reviews compare issue-50 changes with starting commit `2f59226`, including new untracked test/document. Spec: zero actionable findings. Standards: one P3 prose-coupling concern; Host shutdown messages moved to named shared constants used by sender and flow classifier without changing wire format. Follow-up Standards review: zero remaining findings after shared constants and relay constructor failure cleanup; final supplemental Spec review: zero findings.

Windows native launch helpers (run in separate VM Command Prompt windows):

```bat
"\\Mac\Home\Repos\delverengine-mp\.scratch\issue-50\playtest-host.cmd"
"\\Mac\Home\Repos\delverengine-mp\.scratch\issue-50\playtest-client.cmd"
"\\Mac\Home\Repos\delverengine-mp\.scratch\issue-50\playtest-third.cmd"
```

Each launches fork through existing Gradle desktop run task and read-only owned-copy mount; profiles are isolated under `C:\DelverMpProfiles\Issue50\Host`, `Client`, `Third`. No additional repository checkout. No game window launched by agent. Helpers use native menus without Direct Host/Client or development-tool switches.

1. Host → Campaigns → New; choose capacity 3, unique Nickname/Avatar, port 37777. Client/Third → Connect to `127.0.0.1:37777` with distinct Nicknames/Avatars. Host Close: explanation must say friends disconnect; Keep open/Escape must preserve lobby. Confirm: Host Campaigns; both Clients Connect with exact prior fields and clean closure notice. Reopen same port in same window.
2. All mark Ready; Host Start. Client Leave: own Connect form retains fields; Host/Third gameplay continues. Rejoin original identity; retained character/claim rules apply without new pregame Ready. Confirm Host Save and Quit: Host Campaigns; Clients Connect with saved-session notice. Resume saved campaign and verify latest native world/characters.
3. During gameplay terminate only Host process from VM Task Manager. Both Clients must stop gameplay and show concrete loss panel before editable Connect. Edit/Continue acknowledges; Retry shows fresh progress; Back returns native menu. Relaunch Host using same profile; use existing recovery path, then retry Clients. Unclean recovery behavior remains existing campaign policy.
4. Use invalid port/address/Nickname; try occupied port, closed TCP endpoint, blocked UDP, incompatible build/content/protocol, full/reserved/occupied slots, conflicting Nickname/Avatar and missing reconnect credential. Verify concrete reason, retained values and applicable Retry/Edit/Back/Browse. Credentials/private identity must not appear in UI. Missing owned-copy Browse uses existing startup path.
5. Repeat Host Close, Connect Cancel during handshake/UDP/synchronization, Back, Edit and Retry. Old attempts must not enter gameplay/navigate current screen; same endpoints reopen without process restart. Check keyboard/mouse, scaling, readable pixel text, panels and one-window handoff.
6. Failed-save preservation is automated with native-capture and filesystem faults; native explanation/retry can be tested on these isolated disposable profiles by making campaign directory temporarily unwritable. Keep backups and restore permissions before retry. Verify same live world stays open; previous save remains readable.

Owner native acceptance remains pending. Automated session/assets/release checks do not certify rendering/input/full campaign completion or portable delivery.

## Final automated validation

Windows `full-01` executes fresh `Dungeoneer:cleanTest DungeoneerDesktop:cleanTest smokeTest --no-daemon --console=plain`, with `OWNED_GAME_COPY_TEST` set to external owned v1.08 archive. 727 core + 22 Desktop tests passed, zero failures/errors/skips, 397.74s. Subsequent asset audit stopped at Windows Git dubious ownership for shared Mac folder; no test failed. Verified process-local `GIT_CONFIG_COUNT=1`, `GIT_CONFIG_KEY_0=safe.directory`, `GIT_CONFIG_VALUE_0=//Mac/Home/Repos/delverengine-mp` permits this repository only, without changing global Git configuration. `audits-01` trust-only retry (13.24s) additionally rejected Mac shared-folder permission/line-ending differences. Existing issue-49 validation template already provides process-local `core.filemode=false` and `core.autocrlf=false`; same settings produced an empty Windows diff for tracked assets. `audits-02` passed distribution/asset/release audits and isolated-runtime cleanup with all three settings: exit 0, 23.28s (Gradle 22s). No asset/source/global Git configuration change; tests remain unchanged. Source input snapshot: 639 core/desktop Java/test/build files. Expected delta from initial snapshot: exactly seven production files and new eight-test SessionReturn suite. Build/protocol/save formats remain unchanged: no wire/schema change or gameplay migration required.


Final verifier `.scratch/issue-50/final-results.json`: 749 tests (727 core + 22 Desktop), 93 suites, zero failures/errors/skips; all 11 required compile/test/distribution/asset/release/runtime tasks present across test run and successful audit retry. All 639 test/audit/current source input hashes match, including eight new tests. All 136 original installation files unchanged; no new installation file. `git diff --check` passes; validation branch/HEAD: `mp-v108-prototype` / `2f5922653f28c6f23492ca48433f69d421f941d9`.

Rebuilt fork artifact: `DungeoneerDesktop/build/libs/game.jar`, SHA-256 `c087139545fd158180e20919c33451cbb6e0fb9970c9919be8c5d48ff07dd6fe`. Owned `delver.jar` SHA-256 remains `a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae`. Fork artifact uses owned archive only as read-only validated data at runtime; release audits pass exclusions.

Owner explicitly authorized commit, push and closure of issue #50 on 2026-10-10. Publication recheck confirmed all 639 validated source hashes, rebuilt fork hash and 136 original installation file hashes still match passing validation. Individual native visual/input/gameplay play-test results remain unreported; publication authorization does not substitute for those results. No additional checkout or game window launched. CCE decisions/code-area records updated during implementation; publication state also recorded separately.
