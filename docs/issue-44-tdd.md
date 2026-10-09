# Issue #44 — campaign cards and saved Host setup

## Inspection and intended result

Inspected live [#44](https://github.com/ktodorow/delverengine-mp/issues/44), launcher PRD [#40](https://github.com/ktodorow/delverengine-mp/issues/40), and project PRD [#1](https://github.com/ktodorow/delverengine-mp/issues/1) before implementation. Same `mp-v108-prototype` checkout; no parallel repository. #43 is closed and its named setup/storage implementation is current HEAD.

Product remains original v1.08 Delver shared by 2–4 Participants: one native authoritative Host, one Active Floor, persistent private Campaign Slots, read-only owned assets and separate multiplayer profile. #44 changes campaign selection/setup and fixes saved-subset return exposed by its session test; native gameplay and private ownership rules remain authoritative. Newer accepted contracts supersede stale approval/death/grace paragraphs in parent/glossary.

Required behavior:

- Unlimited independent campaigns; three large native save-style cards per visible page, navigation beyond three and twelve.
- Friendly name (existing private campaign ID only as legacy display fallback), authoritative floor, persistent claimed slots/capacity, last **saved** time, and Resume/Recover/read-only Archive state. No invented playtime or last-play date. Unsaved campaigns have unavailable floor/save time.
- Resume/Recover goes through existing shared Host setup before binding. Saved name, capacity and starting Lives are locked. Existing campaign-specific Host presentation is restored rather than overwritten by launcher defaults. Port remains editable; failed bind keeps setup alive.
- Saved Host-only subset resume preserves absent friends' slots, credentials, presentation and character state. Terminal outcomes cannot ordinarily resume; recovery follows existing unclean-shutdown rules.
- Names, summaries and ownership survive reload and backed-up migrations. Single-player storage stays independent.

Current gaps: small scrolling rows lack floor/time; Resume immediately binds; `CampaignLibrary.resume` rewrites Host presentation from current launcher defaults. Existing unlimited storage and campaign migration/recovery/terminal protections should be reused.

## Approved seams and TDD method

#40 records owner confirmation: “Yes, use these seams.” Use existing public `DirectConnectSessionFlow` application/session boundary with real `DirectConnectHost`/`DirectConnectClient` and temporary profiles/campaign files. Use `CampaignLibrary` / `CampaignSaveStore` only for distinct durable summary, legacy migration and error contracts. No widget-tree/private-helper assertions or substitute screen models. Native selection/navigation/scaling and gameplay acceptance belong to owner.

One vertical slice at a time: expected public behavior → Windows RED → smallest implementation → Windows GREEN. Record real failures; already-working regression coverage receives no invented RED.

## RED/GREEN evidence

1. **Campaign Host presentation survives selection.** Test existing public Library resume with stored Explorer/Avatar 3 and differing launcher Host/Avatar 1 defaults. Expected: original campaign name/rules/Host presentation/identity/token survive; no session opens during selection. Windows RED: 10 tests, 1 failure at presentation assertion, 32s (`red-01.log`). Selection now returns validated existing roster without rewriting Host from launcher defaults. Windows GREEN: 10 tests passed, 50s (`green-01.log`).

2. **Honest unlimited Library summaries.** New public summary observations tested through real Host/Client save and 16 additional roster-only campaigns. Expect 17 entries, exact saved floor/time/rules/claimed count and unavailable unsaved floor/time; browsing must not rewrite save bytes/time. Windows RED: summary API absent (`getFloorId`, `getLastSavedTime`, `getStartingLives`), compilation failed in 21s (`red-02.log`). Display-only inspection now uses bounded save codec without compatibility migration or file writes; floor/Lives/name derive from save, claimed count from current durable roster, last-saved time from selected save file. Windows GREEN: 11 tests passed, 55s (`green-02.log`).

3. **Locked setup and saved subset resume.** Real cold saved campaign selection must prepare shared setup without listener, restore campaign Host presentation/rules, open listening lobby, start with Host alone, retain offline character/ownership, then accept real absent friend live return into same slot. Windows RED: saved setup actions absent (`prepareCampaign`, `openSavedCampaign`, resume request identity), compilation failed (`red-03.log`). Test getter typo corrected to existing `getRemainingLives`. Selection now restores durable metadata; shared flow revalidates original roster/locked settings and opens real Host only on Open Lobby, without releasing failed setup. First GREEN attempt reached new flow but test dereferenced absent optional economy progress in minimal session fixture (`green-03.log`, 59s). Corrected observation to actual retained health/Lives rather than assuming native economy was seeded; further runs confirmed preserved credentials but exposed an existing Host bug: absent saved friend was classified as new Late Participant after Host-only Start (`green-03-diagnostic.log`, 43s, RECONNECT_DENIED; explicit requested slot 2 still failed in `green-03-contract.log`, 41s). This was a real RED, not a request-number issue. Host now recognizes retained saved slot through existing authenticated reconnect contract, then registers saved health/Lives on return without resetting current floor. Windows GREEN: 12 tests passed, 54s (`green03-fix.log`), including retained character observations before and after real Client return.

4. **Saved setup edits and bind retry.** RED: absent `withHostOptions` API, Windows compilation failed in 28s (`red04.log`). Immutable saved request now permits only explicit Host Nickname/Avatar/port edits; shared flow validates against all reserved slots and persists presentation only after successful bind. Fixture uses precomputed free port inside exception-free validation lambdas. Windows GREEN: 13 tests passed, 55s (`green04.log`). Real UDP conflict retains saved roster/credentials, current defaults, form request and view; duplicate reserved nickname/avatar reject; successful retry keeps name/capacity/Lives and friend's token/presentation across save/reload.

5. **Corrupt primary remains recoverable in Library.** Real saved campaign receives internal recovery snapshot and unclean marker; primary is then truncated. Expected: broken card and other campaign remain visible; invalid floor/time unavailable; ordinary Resume blocked; Recover restores private credentials and prepares locked setup without listener. Windows RED: 14 tests, 1 failure at Library list, 29s (`red05.log`). Library now isolates invalid save summaries, retains roster metadata and recovery state, and displays unavailable saved details. Windows GREEN: 14 tests passed, 53s (`green05.log`). Recovery still uses existing newest-valid compatible snapshot policy.

6. **Terminal outcome without archive marker.** Expected: terminal outcome in valid primary save is enough to show read-only Archive and refuse saved setup, even if archive file/outcome marker has not yet been written. Display-only inspection must not mutate that file or manufacture recovery/resume from it. Test moves real terminal record back to primary path and removes outcome latch, then exercises Library and shared setup. Windows RED: 18 tests, 1 failure at terminal card classification, 32s (`red06.log`). Library now derives Archive from saved terminal outcome as well as durable latch/path; ordinary Resume checks saved outcome before preparing setup. This covers authoritative outcome, not just archive filename; listing/selection must preserve exact primary bytes and leave missing marker/archive untouched. Windows GREEN: 18 tests passed, 44s (`green06.log`), including both terminal outcomes.

Native production views now consume tested public flow: three original `save-select` cards per page; mouse selection, Up/Down auto-page, Left/Right or Page Up/Page Down; remembered selection on setup Back; contextual Resume/Recover/Archive; existing Export/Import under Manage Campaigns. Shared Host form locks saved name/capacity/Lives and permits explicit Host presentation/port edits. This adapter work is compiled with Windows suite; visual/gameplay acceptance remains separate.

Regression additions exercise both Completed/Defeated archive cards and ordinary-resume denial, legacy roster ID fallback through Library migration with exact backups, and real wrong-token rejection before valid reserved-slot return. Initial regression run: 18 tests passed in 54s (`regressions.log`). Subset fixture then strengthened through real native damage: simultaneous Downing spends one Life, respawn restores half health, further damage leaves friend at **3 health / 4 Lives** from **5 starting Lives**. Those values survive offline subset save and authenticated return. Strengthened run: 18 tests passed in 33s (`wounded-regression.log`). These extend existing GREEN contracts; no artificial RED claimed.

First full fresh Windows run: 664 core tests, one unrelated integration fixture failure in 4m48s (`full-validation.log`), before any desktop/release tasks ran. Exact failure: `fourSlotsJoinDuringCombatProtectOccupantsAndRejectFullCapacity` failed inside pre-existing ephemeral bind cleanup (`DirectConnectHost.closeChannel`), reporting `channel not registered to an event loop`. No #44 admission/resume code had run in that fixture. Isolated identical fixture passed unchanged in 14s (`bind-repro.log`). Underlying failed UDP bind cause was masked by cleanup; not proven or silently reported fixed. No unrelated networking change added. Unchanged full rerun passed in 5m7s (`full-validation-final.log`): 664 core +22 Desktop, zero failures/errors/skips, source and release audits passed. Subsequent final review found terminal-outcome/no-marker edge covered in slice 6; fresh final source validation after that fix is recorded below.


## Final automated validation

Fresh Windows VM validation after slice 6 and all final source/test edits:

```text
gradlew.bat Dungeoneer:test DungeoneerDesktop:test DungeoneerDesktop:verifyOpenSourceDist --rerun-tasks --no-daemon --console=plain
BUILD SUCCESSFUL in 5m 9s
11 actionable tasks: 11 executed
Dungeoneer:        664 tests, 0 failures, 0 errors, 0 skipped
DungeoneerDesktop:  22 tests, 0 failures, 0 errors, 0 skipped
```

`OWNED_GAME_COPY_TEST` pointed to owner's Windows-accessible read-only original archive. Source ownership and release artifact audits passed. Windows Git emitted existing line-ending warnings during audit; no tracked assets changed. Final log: `.scratch/issue-44/full-validation-complete.log`; parsed counts/hash: `.scratch/issue-44/final-results.json`.

Fork jar rebuilt at `DungeoneerDesktop/build/libs/game.jar`. Original archive SHA-256 remains `a2d58e87b09f588ff8389508e43accf7d3c6ce949b5aa4d31d6380574ec095ae`. Build 56 / protocol 50 / Campaign Save format 8 / roster format 2 unchanged. `git diff --check` passes. Same checkout/branch; unrelated existing untracked files preserved. Owner subsequently authorized commit, push and closure of #44 after reviewing final automated results. No source/test changes since final validation. No game instances launched; native owner acceptance below remains pending.

## Owner Windows acceptance

Pending. Automated tests cannot certify native menu appearance, mouse/keyboard navigation or gameplay. Owner authorized publication and issue closure; individual manual acceptance results remain unreported. Agent does not launch game instances.

Owner checks after final validation:

1. Ordinary native launch → Host → Campaigns. Check original forest, pixel text and save-card borders; three large cards per page. Check mouse, Up/Down, Left/Right or Page Up/Page Down, Enter, Escape, scaling and selected-card highlight.
2. Keep more than three campaigns in multiplayer profile. Visit every page, including last partial page, and return from setup. Storage is unlimited; page count never limits creation. Do not use original single-player saves as fixtures.
3. Inspect friendly names, floor, claimed slots/capacity, Last saved and Resume/Recover/Archive state. Offline friend still counts as claimed; connected count is not substituted. Unstarted campaigns show unavailable floor/time. No Last played or playtime claim.
4. Select saved campaign → shared Host setup. Name/capacity/Lives locked; Host Nickname/Avatar restored from campaign, port editable. Back opens no listener. Open Lobby waits for explicit existing Host Start.
5. Resume with Host alone; absent friend keeps slot/character and returns using existing credentials. Verify native inventory/health/Lives/world state after actual gameplay. Current shared lobby/Ready redesign remains later #46–#47.
6. Occupy TCP or UDP port, attempt Open Lobby, then edit/retry. Setup fields remain; no campaign replacement or lost reserved slot. Duplicate Nickname/Avatar against absent friend remains rejected.
7. Unclean campaign shows Recover and goes through setup after recovery; terminal Completed/Defeated shows read-only Archive and refuses ordinary resume. Check legacy campaign fallback and exact migration backups without mutating original installation.

Native Host launch from Windows PowerShell:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:run -PprofileRoot=C:\DelverMpProfiles\Host "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

Existing Direct Host/Client commands remain supported. Ordinary `DungeoneerDesktop:run` exercises title/menu/card flow. Owner chooses gameplay instances; agent runs automated tests only.
