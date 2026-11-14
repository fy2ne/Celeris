package dev.fy2ne.celeris.netty;

import java.lang.reflect.Method;
import java.util.Locale;

public final class PacketClassifier {
    public static final int LANE_EXPRESS = 0;
    public static final int LANE_BULK = 1;

    static final int KIND_AUTO = 0;
    static final int KIND_EXPRESS = 1;
    static final int KIND_BULK = 2;
    static final int KIND_BUNDLE_DELIMITER = 3;
    static final int KIND_BUNDLE_CONTAINER = 4;
    static final int KIND_BARRIER = 5;
    static final int KIND_STATUS = 6;

    private static final int BULK_SIZE_THRESHOLD = 3072;
    private static final int MAX_IMPLICIT_BUNDLE_PACKETS = 512;

    private static final class PacketProbe {
        volatile int resolvedKind;
        volatile boolean resolved;
        final Method typeMethod;

        PacketProbe(int kind, boolean resolved, Method typeMethod) {
            this.resolvedKind = kind;
            this.resolved = resolved;
            this.typeMethod = typeMethod;
        }
    }

    private static final ClassValue<PacketProbe> PROBES = new ClassValue<>() {
        @Override
        protected PacketProbe computeValue(Class<?> type) {
            String simple = type.getSimpleName().toLowerCase(Locale.ROOT);
            int byName = classifyByName(simple);
            if (byName == KIND_AUTO) {
                Class<?> enc = type.getEnclosingClass();
                if (enc != null) {
                    byName = classifyByName(enc.getSimpleName().toLowerCase(Locale.ROOT));
                }
            }
            if (byName != KIND_AUTO) {
                return new PacketProbe(byName, true, null);
            }
            // fabric intermediary names (class_2670) lose class names; fall back to Packet.type() id
            Method found = findPacketTypeMethod(type);
            return new PacketProbe(KIND_AUTO, found == null, found);
        }
    };

    private boolean inBundle;
    private int bundlePacketsSeen;

    public int classifyJavaPacket(Object pkt) {
        if (pkt == null) {
            return KIND_AUTO;
        }
        PacketProbe probe = PROBES.get(pkt.getClass());
        if (probe.resolved) {
            return probe.resolvedKind;
        }
        int kind = KIND_AUTO;
        try {
            Object typeObj = probe.typeMethod.invoke(pkt);
            if (typeObj != null) {
                String id = typeObj.toString().toLowerCase(Locale.ROOT);
                if (id.indexOf(':') >= 0) {
                    kind = classifyByResourceId(id);
                }
            }
        } catch (Throwable ignored) {
        }
        probe.resolvedKind = kind;
        probe.resolved = true;
        return kind;
    }

    public int resolveLane(int taggedKind, int serializedBytes) {
        return resolveLane(taggedKind, serializedBytes, false);
    }

    public int resolveLane(int taggedKind, int serializedBytes, boolean bulkPending) {
        if (taggedKind == KIND_BUNDLE_DELIMITER) {
            inBundle = !inBundle;
            bundlePacketsSeen = 0;
            return LANE_EXPRESS;
        }
        if (inBundle) {
            // guard against dropped closing delimiters from upstream plugins
            if (++bundlePacketsSeen <= MAX_IMPLICIT_BUNDLE_PACKETS) {
                return LANE_EXPRESS;
            }
            inBundle = false;
            bundlePacketsSeen = 0;
        }
        if (taggedKind == KIND_BUNDLE_CONTAINER || taggedKind == KIND_EXPRESS || taggedKind == KIND_STATUS) {
            return LANE_EXPRESS;
        }
        if (taggedKind == KIND_BULK) {
            return LANE_BULK;
        }
        if (taggedKind == KIND_BARRIER) {
            return bulkPending ? LANE_BULK : LANE_EXPRESS;
        }
        return serializedBytes >= BULK_SIZE_THRESHOLD ? LANE_BULK : LANE_EXPRESS;
    }

    public boolean isInBundle() {
        return inBundle;
    }

    private static int classifyByName(String name) {
        if (name.contains("bundledelimiter")) {
            return KIND_BUNDLE_DELIMITER;
        }
        if (name.contains("bundlepacket") || name.equals("clientboundbundlepacket")) {
            return KIND_BUNDLE_CONTAINER;
        }
        if (name.contains("chunkbatchstart")
            || name.contains("chunkbatchfinished")
            || name.contains("forgetlevelchunk")
            || name.contains("unloadchunk")
            || name.contains("finishconfiguration")
            || name.contains("startconfiguration")
            || name.equals("clientboundloginpacket")
            || name.contains("respawnpacket")) {
            return KIND_BARRIER;
        }
        if (name.contains("levelchunkwithlight")
            || name.contains("chunkdata")
            || name.contains("mapchunk")
            || name.contains("lightupdate")
            || name.contains("chunksbiomes")
            || name.contains("mapitemdata")
            || name.contains("updaterecipes")
            || name.contains("recipebookadd")
            || name.contains("updateadvancements")
            || name.equals("clientboundcommandspacket")
            || name.contains("registrydata")
            || name.contains("updatetags")
            || name.contains("selectknownpacks")
            || name.contains("setchunkcachecenter")
            || name.contains("setchunkcacheradius")
            || name.contains("setsimulationdistance")
            || name.contains("resourcepackpush")) {
            return KIND_BULK;
        }
        if (name.contains("statusresponse") || name.contains("pongresponse")) {
            return KIND_STATUS;
        }
        if (name.contains("keepalive")
            || name.contains("pingpacket")
            || name.contains("clientboundping")
            || name.contains("serverboundping")
            || name.equals("ping")
            || name.contains("pongpacket")
            || name.contains("entitymotion")
            || name.contains("entityvelocity")
            || name.contains("damageevent")
            || name.contains("hurtanimation")
            || name.contains("teleportentity")
            || name.contains("acceptteleportation")
            || name.contains("teleportconfirm")
            || name.contains("entityposition")
            || name.contains("moveentity")
            || name.contains("rotatehead")
            || name.contains("playerposition")
            || name.contains("blockupdate")
            || name.contains("blockchangedack")
            || name.contains("sectionblocksupdate")
            || name.contains("blockdestruction")
            || name.contains("blockevent")
            || name.contains("soundpacket")
            || name.contains("sethealth")
            || name.contains("setexperience")
            || name.contains("systemchat")
            || name.contains("playerchat")
            || name.contains("disguisedchat")
            || name.contains("actionbar")
            || name.contains("disconnect")
            || name.contains("transfer")) {
            return KIND_EXPRESS;
        }
        return KIND_AUTO;
    }

    private static int classifyByResourceId(String id) {
        if (id.endsWith(":bundle_delimiter")) {
            return KIND_BUNDLE_DELIMITER;
        }
        if (id.endsWith(":bundle")) {
            return KIND_BUNDLE_CONTAINER;
        }
        if (id.endsWith(":chunk_batch_start")
            || id.endsWith(":chunk_batch_finished")
            || id.endsWith(":forget_level_chunk")
            || id.endsWith(":finish_configuration")
            || id.endsWith(":start_configuration")
            || id.endsWith(":login")
            || id.endsWith(":respawn")) {
            return KIND_BARRIER;
        }
        if (id.endsWith(":level_chunk_with_light")
            || id.endsWith(":light_update")
            || id.endsWith(":chunks_biomes")
            || id.endsWith(":map_item_data")
            || id.endsWith(":update_recipes")
            || id.endsWith(":recipe_book_add")
            || id.endsWith(":update_advancements")
            || id.endsWith(":commands")
            || id.endsWith(":registry_data")
            || id.endsWith(":update_tags")
            || id.endsWith(":select_known_packs")
            || id.endsWith(":set_chunk_cache_center")
            || id.endsWith(":set_chunk_cache_radius")
            || id.endsWith(":set_simulation_distance")
            || id.endsWith(":resource_pack_push")) {
            return KIND_BULK;
        }
        if (id.endsWith(":status_response") || id.endsWith(":pong_response")) {
            return KIND_STATUS;
        }
        if (id.endsWith(":keep_alive")
            || id.endsWith(":ping")
            || id.endsWith(":pong")
            || id.endsWith(":player_position")
            || id.endsWith(":teleport_entity")
            || id.endsWith(":accept_teleportation")
            || id.endsWith(":entity_position_sync")
            || id.endsWith(":move_entity_pos")
            || id.endsWith(":move_entity_pos_rot")
            || id.endsWith(":move_entity_rot")
            || id.endsWith(":rotate_head")
            || id.endsWith(":set_entity_motion")
            || id.endsWith(":damage_event")
            || id.endsWith(":hurt_animation")
            || id.endsWith(":entity_event")
            || id.endsWith(":block_update")
            || id.endsWith(":block_changed_ack")
            || id.endsWith(":section_blocks_update")
            || id.endsWith(":block_destruction")
            || id.endsWith(":block_event")
            || id.endsWith(":sound")
            || id.endsWith(":sound_entity")
            || id.endsWith(":set_health")
            || id.endsWith(":set_experience")
            || id.endsWith(":system_chat")
            || id.endsWith(":player_chat")
            || id.endsWith(":disguised_chat")
            || id.endsWith(":disconnect")
            || id.endsWith(":transfer")) {
            return KIND_EXPRESS;
        }
        return KIND_AUTO;
    }

    private static Method findPacketTypeMethod(Class<?> type) {
        Method fallback = null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                if (rt.isPrimitive() || rt == String.class || rt.getName().startsWith("java.")) continue;
                String rn = rt.getSimpleName().toLowerCase(Locale.ROOT);
                String mn = m.getName().toLowerCase(Locale.ROOT);
                if (rn.contains("packettype") || mn.equals("type") || mn.equals("method_55846")) {
                    try {
                        m.setAccessible(true);
                        return m;
                    } catch (Throwable ignored) {
                    }
                }
                if (fallback == null && rn.contains("class_9145")) {
                    try {
                        m.setAccessible(true);
                        fallback = m;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return fallback;
    }
}
