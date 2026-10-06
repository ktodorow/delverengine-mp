# Issue #23 — Owner Windows play-test

Status: owner accepted implementation and requested commit, push and issue closure on
2026-10-06. Individual gameplay-check results were not provided. Same checkout and branch;
existing Host/client launch commands retained. Use original commands supplied for port 37777, capacity 2, separate
`C:\DelverMpProfiles\Host` / `C:\DelverMpProfiles\Client`, and owned `delver.jar`.
Both processes must use current protocol 47 build.

## Expected behavior

Host owns Party keys and native Party story/history. Inventory, gold and upgrades stay
with each Campaign Slot. Client presents Host-triggered UI and world sounds without
executing native story chains. Client who touches tutorial trap must see its own message;
Host must not see that client's overlay. Both see shared world consequences once.

Native `sawTutorial` is one Campaign fact. Delver's native single-player rule marks it
when tutorial starts; Co-op follows same rule. Native victory is one shared fact and saves
as completed archive. Party travel and final Orb escape policy remain issues #27/#30;
this change supplies shared state those policies consume.

## Checks

1. Start Fresh Campaign; join Friend. Friend activates tutorial touch trap and uses sign.
   Confirm native trap/world consequence once, Friend UI once, Host UI unaffected.
   Reverse roles for next available trigger.
2. Friend collects ordinary key; Host opens native locked door. Repeat with roles reversed.
   Confirm one key spent and both see/hear door outcome once. Repeat Use on open door:
   no second spend, duplicate spawn or story consequence.
3. Give characters different inventory/gold/upgrades through normal available gameplay.
   Confirm one character's pickups or upgrades do not replace other's character state.
4. Use native story/dialogue trigger or discover secret. Host chooses Save and Quit.
   Cold resume with Host alone first, then rejoin Friend. Shared world/history and unspent
   keys remain; absent Slot retains personal state. Resuming must not replay old world sound.
5. Disconnect and reconnect Friend after shared consequence. Current Party/world facts
   arrive, one-shot sounds and dialogue UI do not replay. Activate next eligible native
   trigger; both see its new shared consequence, only activator sees local UI.
6. Repeat native ONCE trigger activation when available. Confirm no extra consequence,
   key spend, duplicate secret count or second story advancement.

If selected floor lacks keys/locked door or secret, use owned floor containing native
objects or Host dev tools already available. Automated native fixtures cover those paths.
Do not use local single-player save slots as Co-op Campaign storage.

## Record result

For each failing check: Host/client role, selected floor, exact action, expected/observed
result, whether fresh or resumed Campaign, and relevant Host/client log. Checklist retained
for gameplay validation; owner authorized closure without recording individual results.
Automated evidence: [TDD record](issue-23-tdd.md).

## Launch commands (Windows PowerShell)

Host:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectHost -PsessionPort=37777 -PcampaignCapacity=2 -Pnickname=Host -Pavatar=humanoid-1 -PprofileRoot=C:\DelverMpProfiles\Host "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" -PdevTools=true --no-daemon'
```

Client:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectClient -PsessionAddress=127.0.0.1 -PsessionPort=37777 -Pnickname=Friend -Pavatar=humanoid-2 -PprofileRoot=C:\DelverMpProfiles\Client "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```
