package com.interrupt.dungeoneer.multiplayer.network;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class DirectConnectCompatibilityTest {
    @Test
    public void openSourceIdentityIsDeterministicAndContentDerived() {
        byte[] floor = "repository-owned test floor".getBytes(StandardCharsets.UTF_8);

        DirectConnectCompatibility first =
                DirectConnectCompatibility.forOpenSourceTestFloor(floor);
        DirectConnectCompatibility second =
                DirectConnectCompatibility.forOpenSourceTestFloor(floor);
        DirectConnectCompatibility changed =
                DirectConnectCompatibility.forOpenSourceTestFloor(
                        "changed test floor".getBytes(StandardCharsets.UTF_8));

        assertEquals(DirectConnectProtocol.BUILD_ID, first.getBuildId());
        assertEquals(DirectConnectProtocol.OPEN_SOURCE_TEST_CONTENT_FORMAT,
                first.getContentFormat());
        assertEquals(first.getContentIdentity(), second.getContentIdentity());
        assertNotEquals(first.getContentIdentity(), changed.getContentIdentity());
        assertTrue(first.getContentSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    public void normalizedAssetIdentityIgnoresIterationOrderButCoversEveryPathAndByte() {
        Map<String, byte[]> firstOrder = new LinkedHashMap<String, byte[]>();
        firstOrder.put("data/game.dat", bytes("game"));
        firstOrder.put("levels/test-level.bin", bytes("floor"));

        Map<String, byte[]> reverseOrder = new LinkedHashMap<String, byte[]>();
        reverseOrder.put("levels/test-level.bin", bytes("floor"));
        reverseOrder.put("data/game.dat", bytes("game"));

        Map<String, byte[]> changed = new LinkedHashMap<String, byte[]>(reverseOrder);
        changed.put("data/game.dat", bytes("changed game"));

        assertEquals(DirectConnectCompatibility.forNormalizedOpenSourceAssets(firstOrder)
                        .getContentIdentity(),
                DirectConnectCompatibility.forNormalizedOpenSourceAssets(reverseOrder)
                        .getContentIdentity());
        assertNotEquals(DirectConnectCompatibility.forNormalizedOpenSourceAssets(firstOrder)
                        .getContentIdentity(),
                DirectConnectCompatibility.forNormalizedOpenSourceAssets(changed)
                        .getContentIdentity());
    }

    @Test
    public void packagedIndexProducesSameIdentityForGameAndLauncherSources()
            throws Exception {
        final Map<String, byte[]> assets = new LinkedHashMap<String, byte[]>();
        assets.put("data/game.dat", bytes("game"));
        assets.put("levels/test-level.bin", bytes("floor"));
        String index = "# generated\n./\n./data\n./data/game.dat\n"
                + "./levels\n./levels/test-level.bin\n./save\n./save/private.dat\n";

        DirectConnectCompatibility indexed =
                OpenSourceTestCompatibility.fromPackagedIndex(index,
                        new OpenSourceTestCompatibility.AssetSource() {
                            @Override
                            public boolean exists(String path) {
                                return assets.containsKey(path);
                            }

                            @Override
                            public byte[] read(String path) {
                                return assets.get(path);
                            }
                        });

        assertEquals(DirectConnectCompatibility.forNormalizedOpenSourceAssets(assets)
                .getContentIdentity(), indexed.getContentIdentity());
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
