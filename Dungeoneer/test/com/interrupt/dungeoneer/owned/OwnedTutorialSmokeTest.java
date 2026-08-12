package com.interrupt.dungeoneer.owned;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.files.FileHandle;
import com.interrupt.dungeoneer.game.GameData;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import com.interrupt.utils.JsonUtil;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OwnedTutorialSmokeTest {
    private static HeadlessApplication application;

    @BeforeClass
    public static void startHeadlessRuntime() {
        application = new HeadlessApplication(new ApplicationAdapter() { });
    }

    @AfterClass
    public static void stopHeadlessRuntime() {
        OwnedGameCopyMount.unmount();
        if(application != null) application.exit();
    }

    @Test
    public void materializesTutorialFromValidatedOwnedCopy() throws Exception {
        String ownedCopyPath = System.getenv("OWNED_GAME_COPY_TEST");
        Assume.assumeTrue("Set OWNED_GAME_COPY_TEST to run retail integration test.",
                ownedCopyPath != null && !ownedCopyPath.trim().isEmpty());

        OwnedGameCopy ownedGameCopy = KnownV108OwnedGameCopies.validator().validate(new File(ownedCopyPath));
        OwnedGameCopyMount.mount(ownedGameCopy);

        FileHandle gameDataFile = OwnedGameCopyMount.resolve("data/game.dat");
        assertNotNull("Validated copy did not mount data/game.dat", gameDataFile);
        assertNotNull("Validated copy did not resolve retail UI audio path",
                OwnedGameCopyMount.resolve("audio//ui/ui_equip_item.mp3"));
        GameData gameData = JsonUtil.fromJson(GameData.class, gameDataFile);
        assertNotNull("Validated copy did not deserialize game data", gameData);
        assertNotNull("Validated copy does not define tutorial", gameData.tutorialLevel);

        Level tutorial = gameData.tutorialLevel;
        assertNotNull("Tutorial does not reference a level file", tutorial.levelFileName);
        FileHandle tutorialFile = OwnedGameCopyMount.resolve(tutorial.levelFileName);
        assertNotNull("Tutorial level file was blocked or missing", tutorialFile);
        tutorial = KryoSerializer.loadLevel(tutorialFile);

        assertNotNull("Tutorial level file could not be deserialized", tutorial);
        assertTrue("Tutorial width must be positive", tutorial.width > 0);
        assertTrue("Tutorial height must be positive", tutorial.height > 0);
        assertNotNull("Tutorial tiles were not materialized", tutorial.tiles);
    }
}
