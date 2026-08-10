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
- No Delver installation or commercial game data. Baseline uses only repository-owned open-source assets.

From clean checkout, build distribution and run automated smoke checks:

```bat
.\gradlew.bat clean smokeTest --no-daemon
```

Launch open-source test level directly; close game window to stop task:

```bat
.\gradlew.bat DungeoneerDesktop:runTestLevel --no-daemon
```

`smokeTest` loads `Dungeoneer/assets/levels/test-level.bin`, validates core open-source data, builds desktop JAR, rejects added, removed, or modified asset files before packaging, and rejects known commercial executable, archive, and Steam runtime filenames from artifact. Do not copy Owned Game Copy files into repository; later owned-data work will mount them read-only outside source tree.

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
