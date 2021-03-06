package us.myles.ViaVersion.protocols.protocol1_16to1_15_2.packets;

import com.github.steveice10.opennbt.tag.builtin.CompoundTag;
import com.github.steveice10.opennbt.tag.builtin.IntArrayTag;
import com.github.steveice10.opennbt.tag.builtin.LongArrayTag;
import com.github.steveice10.opennbt.tag.builtin.StringTag;
import com.github.steveice10.opennbt.tag.builtin.Tag;
import us.myles.ViaVersion.api.minecraft.Position;
import us.myles.ViaVersion.api.minecraft.chunks.Chunk;
import us.myles.ViaVersion.api.minecraft.chunks.ChunkSection;
import us.myles.ViaVersion.api.protocol.Protocol;
import us.myles.ViaVersion.api.remapper.PacketRemapper;
import us.myles.ViaVersion.api.rewriters.BlockRewriter;
import us.myles.ViaVersion.api.type.Type;
import us.myles.ViaVersion.api.type.types.UUIDIntArrayType;
import us.myles.ViaVersion.protocols.protocol1_15to1_14_4.ClientboundPackets1_15;
import us.myles.ViaVersion.protocols.protocol1_15to1_14_4.types.Chunk1_15Type;
import us.myles.ViaVersion.protocols.protocol1_16to1_15_2.Protocol1_16To1_15_2;
import us.myles.ViaVersion.protocols.protocol1_16to1_15_2.types.Chunk1_16Type;
import us.myles.ViaVersion.protocols.protocol1_9_3to1_9_1_2.storage.ClientWorld;
import us.myles.ViaVersion.util.CompactArrayUtil;

import java.util.UUID;

public class WorldPackets {

    public static void register(Protocol protocol) {
        BlockRewriter blockRewriter = new BlockRewriter(protocol, Type.POSITION1_14, Protocol1_16To1_15_2::getNewBlockStateId, Protocol1_16To1_15_2::getNewBlockId);

        blockRewriter.registerBlockAction(ClientboundPackets1_15.BLOCK_ACTION);
        blockRewriter.registerBlockChange(ClientboundPackets1_15.BLOCK_CHANGE);
        blockRewriter.registerMultiBlockChange(ClientboundPackets1_15.MULTI_BLOCK_CHANGE);
        blockRewriter.registerAcknowledgePlayerDigging(ClientboundPackets1_15.ACKNOWLEDGE_PLAYER_DIGGING);

        protocol.registerOutgoing(ClientboundPackets1_15.UPDATE_LIGHT, new PacketRemapper() {
            @Override
            public void registerMap() {
                map(Type.VAR_INT); // x
                map(Type.VAR_INT); // y
                handler(wrapper -> wrapper.write(Type.BOOLEAN, true)); // Take neighbour's light into account as well
            }
        });

        protocol.registerOutgoing(ClientboundPackets1_15.CHUNK_DATA, new PacketRemapper() {
            @Override
            public void registerMap() {
                handler(wrapper -> {
                    ClientWorld clientWorld = wrapper.user().get(ClientWorld.class);
                    Chunk chunk = wrapper.read(new Chunk1_15Type(clientWorld));
                    wrapper.write(new Chunk1_16Type(clientWorld), chunk);

                    chunk.setIgnoreOldLightData(chunk.isFullChunk());

                    for (int s = 0; s < 16; s++) {
                        ChunkSection section = chunk.getSections()[s];
                        if (section == null) continue;
                        for (int i = 0; i < section.getPaletteSize(); i++) {
                            int old = section.getPaletteEntry(i);
                            section.setPaletteEntry(i, Protocol1_16To1_15_2.getNewBlockStateId(old));
                        }
                    }

                    CompoundTag heightMaps = chunk.getHeightMap();
                    for (Tag heightMapTag : heightMaps) {
                        LongArrayTag heightMap = (LongArrayTag) heightMapTag;
                        int[] heightMapData = new int[256];
                        CompactArrayUtil.iterateCompactArray(9, heightMapData.length, heightMap.getValue(), (i, v) -> heightMapData[i] = v);
                        heightMap.setValue(CompactArrayUtil.createCompactArrayWithPadding(9, heightMapData.length, i -> heightMapData[i]));
                    }

                    if (chunk.getBlockEntities() == null) return;
                    for (CompoundTag blockEntity : chunk.getBlockEntities()) {
                        handleBlockEntity(blockEntity);
                    }
                });
            }
        });

        protocol.registerOutgoing(ClientboundPackets1_15.BLOCK_ENTITY_DATA, new PacketRemapper() {
            @Override
            public void registerMap() {
                handler(wrapper -> {
                    Position position = wrapper.passthrough(Type.POSITION1_14);
                    short action = wrapper.passthrough(Type.UNSIGNED_BYTE);
                    CompoundTag tag = wrapper.passthrough(Type.NBT);
                    handleBlockEntity(tag);
                });
            }
        });

        blockRewriter.registerEffect(ClientboundPackets1_15.EFFECT, 1010, 2001, InventoryPackets::getNewItemId);
        blockRewriter.registerSpawnParticle(ClientboundPackets1_15.SPAWN_PARTICLE, 3, 23, 32,
                WorldPackets::getNewParticleId, InventoryPackets::toClient, Type.FLAT_VAR_INT_ITEM, Type.DOUBLE);
    }

    private static void handleBlockEntity(CompoundTag compoundTag) {
        StringTag idTag = compoundTag.get("id");
        if (idTag == null) return;

        String id = idTag.getValue();
        if (id.equals("minecraft:conduit")) {
            Tag targetUuidTag = compoundTag.remove("target_uuid");
            if (!(targetUuidTag instanceof StringTag)) return;

            // target_uuid -> Target
            UUID targetUuid = UUID.fromString((String) targetUuidTag.getValue());
            compoundTag.put(new IntArrayTag("Target", UUIDIntArrayType.uuidToIntArray(targetUuid)));
        } else if (id.equals("minecraft:skull") && compoundTag.get("Owner") instanceof CompoundTag) {
            CompoundTag ownerTag = compoundTag.remove("Owner");
            StringTag ownerUuidTag = ownerTag.remove("Id");
            if (ownerUuidTag != null) {
                UUID ownerUuid = UUID.fromString(ownerUuidTag.getValue());
                ownerTag.put(new IntArrayTag("Id", UUIDIntArrayType.uuidToIntArray(ownerUuid)));
            }

            // Owner -> SkullOwner
            CompoundTag skullOwnerTag = new CompoundTag("SkullOwner");
            for (Tag tag : ownerTag) {
                skullOwnerTag.put(tag);
            }
            compoundTag.put(skullOwnerTag);
        }
    }

    public static int getNewParticleId(int id) {
        if (id >= 27) {
            id += 2; // soul flame, soul
        }
        return id;
    }
}
