# [![delverengine](.media/logo.svg?sanitize=true)](https://github.com/interrupt/delverengine)
[![License: Zlib](https://img.shields.io/badge/License-Zlib-lightgrey.svg)](https://opensource.org/licenses/Zlib) [![Discord](https://img.shields.io/discord/266998536632139776.svg?logo=discord&logoColor=white&logoWidth=20&labelColor=7289DA&label=Discord&color=17cf48)](https://discord.gg/gyhmH5f)

# Delver Engine Open Source
Delver engine and editor source code release

This source release does not contain or cover the game data from Delver, the game data remains subject to the original copyright and applicable law.

## Building
To compile on your own ensure you have installed [Temurin JDK 8](https://adoptium.net/temurin/releases/?version=8). Open a terminal to the repo root and run the following commands:

### v1.08 multiplayer prototype baseline

Prototype branch `mp-v108-prototype` starts at engine commit `9083f5e9c23b63fdc67ae2ce4cbeb374692d10be`. Keep modern `master` separate from prototype work.

Windows x64 prerequisites:

- 64-bit Git for Windows.
- 64-bit JDK 8. `java -version` and `javac -version` must both report Java 8.
- PowerShell or Command Prompt. Gradle 4.8.1 downloads through checked-in wrapper.
- Drive-letter checkout path such as `C:\\src\\delverengine-mp-v108`. In a VM, prefer a guest-local clone; otherwise map shared folders first because `cmd.exe` cannot use UNC working directories.
- No Delver installation or commercial game data is needed for baseline tests. Baseline uses only repository-owned open-source assets.

From clean checkout, build distribution and run automated smoke checks:

```bat
.\gradlew.bat clean smokeTest --no-daemon
```

Launch open-source test level directly; close game window to stop task:

```bat
.\gradlew.bat DungeoneerDesktop:runTestLevel --no-daemon
```

`smokeTest` loads `Dungeoneer/assets/levels/test-level.bin`, validates core open-source data and owned-copy boundaries, builds desktop JAR, rejects added, removed, or modified asset files before packaging, and audits artifact for unexpected game data or compiled classes, commercial archives, Steam files, saves, and private cache content.

### Headless Host-session harness

Run the focused authoritative-session scenarios on Windows:

```bat
.\gradlew.bat Dungeoneer:test --tests com.interrupt.dungeoneer.multiplayer.host.HeadlessHostSessionHarnessTest --no-daemon
```

The harness loads only repository-owned test-floor metadata, advances a controlled 60 Hz Host clock, sends synthetic commands through `HostSessionCommandGateway`, and captures snapshots, events, disconnects, transitions, and persisted state through in-memory adapters. Commands carry a stable `ParticipantId`; authoritative remote character state remains separate from original global `Player`, while local first-person play uses `LocalPlayerCompatibilityAdapter`. Participant-scoped teleports and shared Party progression are covered without graphics, audio, or an Owned Game Copy.

### Direct Connect test floor

Open two terminals on Windows. Separate profile roots simulate two installations on one development VM. Start Host with Campaign Capacity, Campaign identity, Nickname, and one of four owned humanoid Avatar identities:

```powershell
.\gradlew.bat DungeoneerDesktop:runDirectHost --% -PsessionPort=37777 -PcampaignCapacity=2 -PcampaignId=friends-test -Pnickname=Host -Pavatar=humanoid-1 -PprofileRoot=C:\DelverMpProfiles\Host --no-daemon
```

Start client with Host address, same port, a different profile root, Party-unique Nickname, and available Avatar:

```powershell
.\gradlew.bat DungeoneerDesktop:runDirectClient --% -PsessionAddress=127.0.0.1 -PsessionPort=37777 -Pnickname=Friend -Pavatar=humanoid-2 -PprofileRoot=C:\DelverMpProfiles\Friend --no-daemon
```

`--%` keeps Windows PowerShell from splitting a dotted address while handing arguments to `gradlew.bat`. For a client in the same Windows VM, `-PsessionAddress=127.0.0.1` can instead be omitted because loopback is the default.

Client waits for explicit Host approval. In Host window, press `A` to approve displayed pending claim or `R` to reject it. After approved clients finish TCP and token-bound UDP setup, press `Enter` to start repository-owned test floor. Capacity three or four accepts more clients before `Enter`; give each separate profile root, Nickname, and unused `humanoid-1` through `humanoid-4` choice.

Each profile creates one private random Launcher Identity. Host persists approved Campaign Slot ownership under isolated multiplayer profile and client stores per-campaign reconnect credential there; returning identity reclaims same slot automatically even if Nickname or Avatar changes. IP address, Nickname, and Steam identity never own slot. Build mismatch, content mismatch, full roster, occupied slot, malformed handshake, and disconnect reason remain explicit. No engine entity, save graph, commercial asset, archive path, or asset byte is serialized or transmitted.

Run bounded-codec and live loopback coverage without graphics:

```powershell
.\gradlew.bat Dungeoneer:test --tests "com.interrupt.dungeoneer.multiplayer.network.*" --no-daemon
```

### Owned v1.08 tutorial

Owned Game Copy remains outside checkout and build output. Launcher can detect common Steam or GOG locations, browse to `delver.jar`, or accept explicit path. It hashes sorted whitelisted asset paths and bytes into normalized content identity before mounting approved data read-only. ZIP order, timestamps, compression, classes, native libraries, Steam files, JAR metadata, mods, and original saves do not affect identity and are never mounted. Unsafe archive paths reject entire copy.

In Windows VM, keep source checkout on guest-local drive such as `C:\src\delverengine-mp-v108`. Keep original Delver installation separate. Never copy `delver.jar` into repository.

First inspect owned archive and print safe fingerprint metadata:

```powershell
.\gradlew.bat DungeoneerDesktop:inspectOwnedCopy '-PownedCopy=C:\Program Files (x86)\Steam\steamapps\common\Delver\delver.jar' --no-daemon
```

Share only printed normalized manifest SHA-256 plus storefront and displayed game version when certifying another archive. Never share `delver.jar`, extracted assets, or private cache content. Registry in `KnownV108OwnedGameCopies` stores only variant ID, storefront, game version, and normalized SHA-256; adding storefront entry never stores commercial files. Unknown copies remain rejected with certification instructions.

Launch owned tutorial using auto-detection and Browse fallback:

```powershell
.\gradlew.bat DungeoneerDesktop:runOwnedTutorial --no-daemon
```

Or pass explicit archive path:

```powershell
.\gradlew.bat DungeoneerDesktop:runOwnedTutorial '-PownedCopy=C:\Program Files (x86)\Steam\steamapps\common\Delver\delver.jar' --no-daemon
```

Multiplayer settings, cache, identities, saves, and logs live under `%LOCALAPPDATA%\Delver Multiplayer`. Original Delver installation and single-player save folders are not written or imported. Participant compatibility exchanges only normalized content identity; owned archive paths, raw archive hashes, extracted bytes, and cache content stay local.

Run release guard directly when checking package changes:

```bat
.\gradlew.bat DungeoneerDesktop:verifyReleaseArtifacts --no-daemon
```

### Game
 
_Running:_ `gradlew DungeoneerDesktop:run`  
_Building:_ `gradlew DungeoneerDesktop:dist`  

### Editor

_Running:_ `gradlew DelvEdit:run`   
_Building:_ `gradlew DelvEdit:dist`  

## License

This source code release is licensed under the zlib Open Source license. [See LICENSE.txt for more information.](LICENSE.txt)

## Notes

Delver is a Java project most easily built via Gradle. Import the Gradle project into your IDE of choice.

This is built on the LibGDX game framework, more information on LibGDX is available at https://libgdx.badlogicgames.com/

For discussion and help, check out the [Official Delver Community Discord](https://discord.gg/gyhmH5f)

### Main Applications

Run configurations for IntelliJ have been included, for manual setup use the following:

Game: `DungeoneerDesktop/src/com/interrupt/dungeoneer/DesktopStarter.java`

Editor: `DelvEdit/src/com/interrupt/dungeoneer/EditorStarter.java`

Working directory: `Dungeoneer`

Resources directory: `Dungeoneer/assets`
