package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.owned.MultiplayerProfile;
import java.io.File;
import java.util.Properties;

/** Editable local defaults shared by Host and Client; never Campaign Slot ownership. */
public final class LauncherPresentationStore {
    private static final String PATH = "settings/multiplayer-presentation.properties";
    private LauncherPresentationStore() { }

    public static SlotPresentation load() {
        SlotPresentation defaults = new SlotPresentation("Participant", AvatarCatalog.HUMANOID_1);
        if(!MultiplayerProfile.isInitialized()) return defaults;
        File file = MultiplayerProfile.resolveWritableFile(PATH).file();
        if(!file.isFile()) return defaults;
        try {
            Properties values = AtomicProperties.load(file, "multiplayer presentation defaults");
            SlotPresentation presentation = new SlotPresentation(values.getProperty("nickname"), values.getProperty("avatar"));
            return AvatarCatalog.ownedV108Humanoids().contains(presentation.getAvatarId()) ? presentation : defaults;
        }
        catch(IllegalArgumentException | IllegalStateException invalid) { return defaults; }
    }

    public static void save(SlotPresentation presentation) {
        if(presentation == null || !AvatarCatalog.ownedV108Humanoids().contains(presentation.getAvatarId()))
            throw new IllegalArgumentException("Choose an available Avatar.");
        if(!MultiplayerProfile.isInitialized()) throw new IllegalStateException("Multiplayer profile is not initialized.");
        Properties values = new Properties();
        values.setProperty("nickname", presentation.getNickname());
        values.setProperty("avatar", presentation.getAvatarId());
        AtomicProperties.store(MultiplayerProfile.resolveWritableFile(PATH).file(), values,
                "Delver Multiplayer editable presentation defaults");
    }
}
