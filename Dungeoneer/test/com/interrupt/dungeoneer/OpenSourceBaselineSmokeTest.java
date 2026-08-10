package com.interrupt.dungeoneer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.files.FileHandle;
import com.interrupt.dungeoneer.game.GameData;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import com.interrupt.utils.JsonUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OpenSourceBaselineSmokeTest {

    private static HeadlessApplication application;
    private final File assetsDirectory = new File("assets");

    @BeforeClass
    public static void startHeadlessRuntime() {
        application = new HeadlessApplication(new ApplicationAdapter() { });
    }

    @AfterClass
    public static void stopHeadlessRuntime() {
        if(application != null) application.exit();
    }

    @Test
    public void loadsOpenSourceTestLevel() {
        File testLevelFile = new File(assetsDirectory, GameApplication.OPEN_SOURCE_TEST_LEVEL);
        assertTrue("Open-source test level is missing", testLevelFile.isFile());

        Level level = KryoSerializer.loadLevel(new FileHandle(testLevelFile));

        assertNotNull("Open-source test level could not be deserialized", level);
        assertTrue("Test level width must be positive", level.width > 0);
        assertTrue("Test level height must be positive", level.height > 0);
        assertNotNull("Test level tiles are missing", level.tiles);
        assertEquals("Test level tile count does not match dimensions", level.width * level.height, level.tiles.length);
    }

    @Test
    public void loadsCoreDataWithoutOwnedGameCopy() {
        File gameDataFile = new File(assetsDirectory, "data/game.dat");
        assertTrue("Open-source game data is missing", gameDataFile.isFile());

        GameData gameData = JsonUtil.fromJson(GameData.class, new FileHandle(gameDataFile));

        assertNotNull("Open-source game data could not be deserialized", gameData);
        assertEquals("player.dat", gameData.playerDataFile);
        assertTrue(new File(assetsDirectory, "data/" + gameData.playerDataFile).isFile());
    }
}
