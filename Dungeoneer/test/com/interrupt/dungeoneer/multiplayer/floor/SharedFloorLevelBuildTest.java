package com.interrupt.dungeoneer.multiplayer.floor;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.gfx.GlRenderer;
import com.interrupt.dungeoneer.gfx.TextureAtlas;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.ModManager;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.dungeoneer.generator.GenTheme;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import com.interrupt.managers.EntityManager;
import com.interrupt.managers.ItemManager;
import com.interrupt.managers.MonsterManager;
import com.interrupt.managers.StringManager;
import com.interrupt.utils.JsonUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.io.File;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Real native Level.loadFromEditor on the open-source test floor, built as two different peers. */
public class SharedFloorLevelBuildTest {
    private final File assets = new File("assets");
    private Application previousApp;
    private Game previousGame;
    private ModManager previousMods;
    private EntityManager previousEntities;
    private MonsterManager previousMonsters;
    private int previousDetail;
    private float previousQuality;
    private java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings;
    private GlRenderer previousRenderer;
    private com.badlogic.gdx.utils.ArrayMap<String, TextureAtlas> previousAtlases;
    private com.badlogic.gdx.utils.ArrayMap<String, TextureAtlas> previousSpriteAtlases;

    /** Floor building reads renderer flags and light colours; unit tests have no GL context. */
    static class HeadlessRenderer extends GlRenderer {
        @Override public void setLevelToRender(Level level) { }
        @Override public com.badlogic.gdx.graphics.Color GetLightmapAt(float x, float y, float z) {
            return com.badlogic.gdx.graphics.Color.WHITE;
        }
        @Override public com.badlogic.gdx.graphics.Color GetLightmapAt(Level level,
                float x, float y, float z) {
            return com.badlogic.gdx.graphics.Color.WHITE;
        }
    }

    /** Decals and tiles ask atlases for sizes only; unit tests load no textures. */
    static class HeadlessAtlas extends TextureAtlas {
        @Override public void loadIfNeeded() { }
        @Override public com.badlogic.gdx.math.Vector2 getClippedSizeMod(int num) {
            return new com.badlogic.gdx.math.Vector2(1f, 1f);
        }
    }

    /** Every atlas name resolves; the first name backs tiles that omit an atlas. */
    static com.badlogic.gdx.utils.ArrayMap<String, TextureAtlas> headlessAtlases() {
        final TextureAtlas atlas = new ObjenesisStd().newInstance(HeadlessAtlas.class);
        com.badlogic.gdx.utils.ArrayMap<String, TextureAtlas> atlases =
                new com.badlogic.gdx.utils.ArrayMap<String, TextureAtlas>() {
                    @Override public TextureAtlas get(String key) { return atlas; }
                };
        atlases.put("t1", atlas);
        return atlases;
    }

    @BeforeClass public static void loadNativeMath() {
        // Entity initialization uses libgdx native matrix math.
        com.badlogic.gdx.utils.GdxNativesLoader.load();
    }

    @Before public void installOpenSourceContent() {
        previousApp = Gdx.app;
        previousGame = Game.instance;
        previousMods = Game.modManager;
        previousEntities = EntityManager.instance;
        previousMonsters = MonsterManager.instance;
        previousDetail = Options.instance.graphicsDetailLevel;
        previousQuality = Options.instance.gfxQuality;
        previousStrings = StringManager.localizedStrings;
        StringManager.localizedStrings = new java.util.HashMap<>();
        previousRenderer = GameManager.renderer;
        previousAtlases = TextureAtlas.cachedRepeatingAtlases;
        previousSpriteAtlases = TextureAtlas.cachedAtlases;
        TextureAtlas.cachedRepeatingAtlases = headlessAtlases();
        TextureAtlas.cachedAtlases = headlessAtlases();
        GameManager.renderer = new ObjenesisStd().newInstance(HeadlessRenderer.class);
        Gdx.app = (Application)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { Application.class }, (proxy, method, arguments) -> null);
        Game.modManager = new ModManager() {
            @Override public GenTheme loadTheme(String filename) {
                return JsonUtil.fromJson(GenTheme.class, new FileHandle(new File(assets, filename)));
            }
        };
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.itemManager = JsonUtil.fromJson(ItemManager.class, data("items.dat"));
        game.monsterManager = JsonUtil.fromJson(MonsterManager.class, data("monsters.dat"));
        game.entityManager = JsonUtil.fromJson(EntityManager.class, data("entities.dat"));
        Game.instance = game;
        EntityManager.instance = game.entityManager;
        MonsterManager.instance = game.monsterManager;
    }

    @After public void restore() {
        Gdx.app = previousApp;
        Game.instance = previousGame;
        Game.modManager = previousMods;
        EntityManager.instance = previousEntities;
        MonsterManager.instance = previousMonsters;
        Options.instance.graphicsDetailLevel = previousDetail;
        Options.instance.gfxQuality = previousQuality;
        StringManager.localizedStrings = previousStrings;
        GameManager.renderer = previousRenderer;
        TextureAtlas.cachedRepeatingAtlases = previousAtlases;
        TextureAtlas.cachedAtlases = previousSpriteAtlases;
    }

    @Test public void peersWithDifferentLocalSettingsBuildDifferentVanillaFloors() {
        SharedFloorFingerprint host = vanillaFloor(4, 1.5f, 11);
        SharedFloorFingerprint client = vanillaFloor(1, 0.25f, 29);

        // The reported bug: each peer decorates its own floor from local settings and randomness.
        assertNotEquals(host, client);
    }

    @Test public void sharedSeedBuildsIdenticalFloorsDespiteLocalSettingsAndHistory() {
        SharedFloorFingerprint host = sharedFloor(0x5EEDL, 4, 1.5f, 11);
        SharedFloorFingerprint client = sharedFloor(0x5EEDL, 1, 0.25f, 29);

        assertEquals(host.describeDifferences(client), host, client);
        int decoration = host.getCount(SharedFloorFingerprint.Category.SCENERY)
                + host.getCount(SharedFloorFingerprint.Category.SOLID_SCENERY);
        assertTrue("Test floor must exercise native decoration", decoration > 0);
    }

    @Test public void sharedBuildRestoresLocalGraphicsSettingsAndClearsItself() {
        Options.instance.graphicsDetailLevel = 1;
        Options.instance.gfxQuality = 0.25f;
        Level level = testFloor();
        level.sharedFloorBuild = new SharedFloorBuild(3L);

        level.loadFromEditor();

        assertEquals(1, Options.instance.graphicsDetailLevel);
        assertEquals(0.25f, Options.instance.gfxQuality, 0f);
        assertNull(level.sharedFloorBuild);
    }

    private SharedFloorFingerprint vanillaFloor(int detail, float quality, int history) {
        Level level = peerFloor(detail, quality, history);
        level.loadFromEditor();
        return SharedFloorFingerprint.capture(level);
    }

    private SharedFloorFingerprint sharedFloor(long seed, int detail, float quality, int history) {
        Level level = peerFloor(detail, quality, history);
        SharedFloorBuild build = new SharedFloorBuild(seed);
        level.sharedFloorBuild = build;
        level.loadFromEditor();
        return build.getFingerprint();
    }

    private Level peerFloor(int detail, float quality, int history) {
        Options.instance.graphicsDetailLevel = detail;
        Options.instance.gfxQuality = quality;
        for(int index = 0; index < history; index++) Game.rand.nextInt();
        return testFloor();
    }

    private Level testFloor() {
        Level level = KryoSerializer.loadLevel(new FileHandle(new File(assets,
                GameApplication.OPEN_SOURCE_TEST_LEVEL)));
        if(level.theme == null) level.theme = "TEST";
        Game.instance.level = level;
        return level;
    }

    private FileHandle data(String name) {
        return new FileHandle(new File(assets, "data/" + name));
    }
}
