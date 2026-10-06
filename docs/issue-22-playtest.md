# Issue #22 play-test

Contract: [issue #22](https://github.com/ktodorow/delverengine-mp/issues/22),
[PRD #1](https://github.com/ktodorow/delverengine-mp/issues/1), stories 132–138 and 142.
Branch: `mp-v108-prototype`. Existing repository and launch commands; no parallel checkout.

## Owner checks

1. Start Host and Client with existing commands. Create disposable campaign. Open door,
   break prop, pick up/drop items, damage Monster, gain gold; note current state.
2. Host Esc → Save and Quit → Cancel: session continues. Confirm Save and Quit:
   both instances return to session screen; no gameplay continues. Restart and resume:
   state retained; Library offers no crash recovery.
3. Play another disposable campaign for over one minute. Record changes before next
   minute; pause Party with P for over one minute too. Force-end only Host game process
   in Windows Task Manager. Client returns to stopped session screen. Restart Host:
   Library shows unclean shutdown; Enter refuses ordinary resume; R restores newest
   valid compatible state. No list of historical snapshots. Client reclaims same slot.
4. Recovery: check doors, broken props, shops, spawners, items, Monster health/death,
   blood marks, gold, Lives and ongoing native effects. No repeated consumption, loot,
   damage, sound bursts or restarted effect durations. Resume lifecycle remains same
   as issue #21; transient attacks clear per native gameplay lifecycle matrix.
5. End disposable campaign with Party Wipe. Restart Host: read-only Campaign Archive,
   Enter shows history, R cannot revive campaign. E can export archive.
6. End session normally, restart Host to Library. E exports selected campaign to unused
   absolute path (for example `C:\Backups\friends.delvercampaign`). Existing files
   rejected. I imports export into profile without campaign of same ID. Original Host
   Launcher Identity must already exist there (use Identity Recovery File); different
   Host rejected. Existing campaign never overwritten. Original export remains intact.

Clean Save and Quit saves current state. Hard crash can lose changes after last snapshot.
No live Host migration. Session screens currently require app restart to host/rejoin.
Floor transitions and victory eligibility remain owned by subsequent gameplay issues;
storage supports both completed and defeated terminal outcomes.

## Automated checks

Windows core and desktop suites; owned integration uses restored local `delver.jar` via
`OWNED_GAME_COPY_TEST`. Full run: 548 core + 16 desktop tests passed. Final affected-code
rerun: 88 core + 16 desktop tests passed, no failures or skips (2026-10-05).
Fault checks cover failed atomic replacement, exact-byte migration
backup and failed backup, bounded rotation, corrupt newest recovery, clean shutdown gating,
live Host lock, both terminal outcomes, original Host export/import, TCP shutdown delivery,
failed native capture and minute cadence during pause. Original single-player saves untouched.

Native lifecycle policy: [native-gameplay-replication.md](native-gameplay-replication.md).
Owner confirmed all listed features work on 2026-10-06 and requested commit, push and
issue closure. Gameplay acceptance complete for issue #22 scope.
