package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress;
import com.interrupt.dungeoneer.multiplayer.economy.ShopEntryState;
import com.interrupt.dungeoneer.multiplayer.economy.ShopOpening;
import com.interrupt.dungeoneer.multiplayer.items.ItemAction;
import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.Test;

import static org.junit.Assert.*;

public class EconomyWireTest {
    private final ParticipantId remote = new ParticipantId("campaign-slot-2");

    @Test
    public void personalProgressRoundTripsEveryNativeField() throws Exception {
        ParticipantProgress progress = new ParticipantProgress(remote, 91L, 1234, 567, 7,
                8, 9, 10, 11, 12, 13, 2, 21, 30, 8);

        ParticipantProgress decoded = ((DirectConnectWire.ParticipantProgressMessage)roundTrip(
                new DirectConnectWire.ParticipantProgressMessage("session", progress))).progress;

        assertTrue(progress.sameState(decoded));
        assertEquals(91L, decoded.revision);
        assertEquals(remote, decoded.participantId);
    }

    @Test
    public void sharedAndPersonalShopEntriesRoundTrip() throws Exception {
        ShopEntryState shared = new ShopEntryState(1000003L, 4L, 17L, 2, template(),
                new ItemProperties(3, 7, "of Fire", "Sharp", 1, -1), 250, false, true, null,
                "SHOP_JEFF");
        ShopEntryState personal = new ShopEntryState(1000003L, 4L, 18L, 10016, template(),
                ItemProperties.DEFAULT, 52, true, false, remote);

        ShopEntryState decodedShared = ((DirectConnectWire.ShopEntryStateMessage)roundTrip(
                new DirectConnectWire.ShopEntryStateMessage("session", shared))).entry;
        ShopEntryState decodedPersonal = ((DirectConnectWire.ShopEntryStateMessage)roundTrip(
                new DirectConnectWire.ShopEntryStateMessage("session", personal))).entry;

        assertEquals(1000003L, decodedShared.shopId);
        assertEquals(4L, decodedShared.generation);
        assertEquals(2, decodedShared.entryId);
        assertEquals("of Fire", decodedShared.properties.suffix);
        assertEquals("Sharp", decodedShared.properties.prefix);
        assertEquals(250, decodedShared.cost);
        assertEquals("SHOP_JEFF", decodedShared.achievement);
        assertTrue(decodedShared.sold);
        assertNull(decodedShared.restrictedTo);
        assertTrue(decodedPersonal.useOnBuy);
        assertEquals(remote, decodedPersonal.restrictedTo);
        assertFalse(decodedPersonal.offeredTo(new ParticipantId("campaign-slot-1")));
    }

    @Test
    public void shopOpeningRoundTripsDialogueAndGeneration() throws Exception {
        ShopOpening opening = ((DirectConnectWire.ShopOpeningMessage)roundTrip(
                new DirectConnectWire.ShopOpeningMessage("session",
                        new ShopOpening(1000001L, 3L, "jeff2.dat")))).opening;

        assertEquals(1000001L, opening.shopId);
        assertEquals(3L, opening.generation);
        assertEquals("jeff2.dat", opening.dialogueFile);
    }

    @Test
    public void purchaseAndStatChoiceUseReliableItemRequestIdentity() throws Exception {
        DirectConnectWire.ItemRequestMessage purchase = (DirectConnectWire.ItemRequestMessage)roundTrip(
                new DirectConnectWire.ItemRequestMessage("session", 12L, 7L,
                        ItemAction.PURCHASE, 1000002L, 0, 10001));
        DirectConnectWire.ItemRequestMessage stat = (DirectConnectWire.ItemRequestMessage)roundTrip(
                new DirectConnectWire.ItemRequestMessage("session", 13L, 7L,
                        ItemAction.CHOOSE_STAT, 3L, 0, 0));

        assertEquals(ItemAction.PURCHASE, purchase.action);
        assertEquals(7L, purchase.worldGeneration);
        assertEquals(1000002L, purchase.entityId);
        assertEquals(10001, purchase.quantity);
        assertEquals(ItemAction.CHOOSE_STAT, stat.action);
        assertEquals(3L, stat.entityId);
    }

    @Test
    public void protocolBoundsRejectImpossibleEconomyValues() {
        assertInvalid(() -> new ParticipantProgress(remote, 1L, -1, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8, 24, 6));
        assertInvalid(() -> new ParticipantProgress(remote, 1L, 0, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8, 65, 6));
        assertInvalid(() -> new ParticipantProgress(remote, 1L, 0, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8, 6, 7));
        assertInvalid(() -> new ShopEntryState(1L, 1L, 1L, 0, template(), ItemProperties.DEFAULT, 1, false, false, null));
        assertInvalid(() -> new ShopEntryState(1L, 1L, 1L, 1, "", ItemProperties.DEFAULT, 1, false, false, null));
        assertInvalid(() -> new ShopEntryState(1L, 1L, 1L, 1, template(), ItemProperties.DEFAULT, -1, false, false, null));
        StringBuilder longAchievement = new StringBuilder();
        while(longAchievement.length() <= ShopEntryState.MAX_ACHIEVEMENT_BYTES) longAchievement.append('a');
        assertInvalid(() -> new ShopEntryState(1L, 1L, 1L, 1, template(), ItemProperties.DEFAULT,
                1, false, false, null, longAchievement.toString()));
        StringBuilder longDialogue = new StringBuilder();
        while(longDialogue.length() <= ShopOpening.MAX_DIALOGUE_FILE_BYTES) longDialogue.append('d');
        assertInvalid(() -> new ShopOpening(1L, 1L, longDialogue.toString()));
    }

    @Test
    public void malformedProgressOnWireIsRejectedAsProtocolFailure() throws Exception {
        ByteBuf encoded = DirectConnectWire.encodeDatagram(UnpooledByteBufAllocator.DEFAULT,
                new DirectConnectWire.ParticipantProgressMessage("session",
                        new ParticipantProgress(remote, 1L, 5, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8, 24, 6)));
        try {
            int hotbarOffset = encoded.writerIndex() - 1;
            encoded.setByte(hotbarOffset, 30);
            DirectConnectWire.decodeDatagram(encoded);
            fail("Hotbar larger than inventory was accepted");
        }
        catch(DirectConnectWire.ProtocolException expected) {
            assertTrue(expected.getMessage().contains("participant progress"));
        }
        finally {
            encoded.release();
        }
    }

    private static String template() {
        StringBuilder value = new StringBuilder();
        while(value.length() < 64) value.append('a');
        return value.toString();
    }

    private static void assertInvalid(Runnable construction) {
        try {
            construction.run();
            fail("Out-of-bounds economy value was accepted");
        }
        catch(IllegalArgumentException expected) { }
    }

    private DirectConnectWire.Message roundTrip(DirectConnectWire.Message message) throws Exception {
        ByteBuf encoded = DirectConnectWire.encodeDatagram(UnpooledByteBufAllocator.DEFAULT, message);
        try {
            return DirectConnectWire.decodeDatagram(encoded);
        }
        finally {
            encoded.release();
        }
    }
}
