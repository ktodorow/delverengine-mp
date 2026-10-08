# Issue #24 owner playtest

Target: original Delver v1.08 single-player rules, personal knowledge per stable
Campaign Slot. Campaign shares potion appearance/effect mapping and physical
effects. Learning a potion or exploring floor reveals facts only to that Slot.

Use existing Windows 11 VM and checkout. Both processes require build 52 /
protocol 48. Campaign Save format 5 migrates known build-51 saves with exact
backup; old formats start personal knowledge empty because they never saved it.

Host:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectHost -PsessionPort=37777 -PcampaignCapacity=2 -Pnickname=Host -Pavatar=humanoid-1 -PprofileRoot=C:\DelverMpProfiles\Host "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" -PdevTools=true --no-daemon'
```

Client:

```powershell
cmd.exe /d /c 'pushd "\\Mac\Home\Repos\delverengine-mp" && gradlew.bat DungeoneerDesktop:runDirectClient -PsessionAddress=127.0.0.1 -PsessionPort=37777 -Pnickname=Friend -Pavatar=humanoid-2 -PprofileRoot=C:\DelverMpProfiles\Client "-PownedCopy=\\Mac\Home\Downloads\Delver.v1.08\Delver.v1.08\delver.jar" --no-daemon'
```

1. Start Campaign and separate into unexplored areas. Compare automaps: each
   shows native visible tiles around own body. Moving camera adds no exploration.
   Remote marker appears only when remote body occupies viewer's explored tile.
2. Compare same potion appearance on both inventories. Effects match. Drink on
   Client: native effect applies once. Native chance may identify potion; when it
   does, Client tooltip learns effect while Host tooltip remains unknown. Drinking
   known potion keeps normal sounds, text, history, and effect.
3. Read Identify on Client while carrying unknown potions. Client learns those
   effects; Host remains unknown. Check native read/cast sound and visible effects
   from both windows. Repeat with Host as caster.
4. Read Fill Map on Client. Client reveals floor; Host keeps own explored area.
   Remote markers follow Client's now-known tiles. Repeat on Host.
5. Down Client, then observe followed camera after final Life is lost. Neither
   Downed body nor Spectator camera learns tiles. Spectator camera has no local
   arrow masquerading as followed body; remote markers still obey personal map.
6. Reconnect Client to same Slot. Potion tooltips and map return before gameplay
   resumes. Quit cleanly and cold resume with Host only, then save. Return Client
   to existing Slot: same personal knowledge retained despite absent participant.
7. Compare future potion drops after cold resume: same appearance keeps same
   physical effect. New Slot starts without other Slot's personal discoveries.

Record observations, screenshots if useful, and native comparison against owned
single-player copy. Automated checks cover gameplay/session/save/wire outputs;
owner authorized commit, push and issue closure on 2026-10-08. Individual owner
visual/audio/playtest results were not reported; checklist remains available for
follow-up verification. Actual travel and Fresh Return execution belong to issue #27;
existing Slot knowledge is persistent data, independent of character reset.
