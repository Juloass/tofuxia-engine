package fr.tofuxia.app;

import fr.tofuxia.entity.attribute.EntityAttributeContainer;
import fr.tofuxia.entity.attribute.EntityAttributeDefaults;

import java.util.List;
import java.util.Map;

public record NetworkWorldState(
        boolean connected,
        String dimensionId,
        int serverTick,
        long worldTimeMillis,
        long serverTimeMillis,
        long receivedClientTimeMillis,
        long dayLengthMillis,
        List<Entity> entities,
        ClientChunkCache chunkCache,
        ClientResourceNodeCache resourceNodeCache,
        ClientAdminSpatialCache adminSpatialCache
) {
    private static final int CLOCK_MINUTES_PER_DAY = 24 * 60;
    private static final int SUNRISE_CLOCK_OFFSET_MINUTES = 6 * 60;

    // public static final long DEFAULT_DAY_LENGTH_MILLIS = 8L * 60L * 60L * 1000L;
    // public static final long DEFAULT_DAY_LENGTH_MILLIS = 20L * 60L * 1000L;
    public static final long DEFAULT_DAY_LENGTH_MILLIS = 2L * 60L * 1000L;
    public static final NetworkWorldState EMPTY = new NetworkWorldState(false, "", 0, 0, 0, 0,
            DEFAULT_DAY_LENGTH_MILLIS, List.of(), ClientChunkCache.empty(), ClientResourceNodeCache.empty(),
            ClientAdminSpatialCache.empty());

    public NetworkWorldState(boolean connected, String dimensionId, int serverTick, List<Entity> entities, ClientChunkCache chunkCache) {
        this(connected, dimensionId, serverTick, 0, 0, 0, DEFAULT_DAY_LENGTH_MILLIS, entities, chunkCache,
                ClientResourceNodeCache.empty(), ClientAdminSpatialCache.empty());
    }

    public NetworkWorldState {
        dayLengthMillis = dayLengthMillis <= 0 ? DEFAULT_DAY_LENGTH_MILLIS : dayLengthMillis;
        entities = List.copyOf(entities);
        chunkCache = chunkCache == null ? ClientChunkCache.empty() : chunkCache;
        resourceNodeCache = resourceNodeCache == null ? ClientResourceNodeCache.empty() : resourceNodeCache;
        adminSpatialCache = adminSpatialCache == null ? ClientAdminSpatialCache.empty() : adminSpatialCache;
    }

    public float worldDayPhase(long clientNowMillis) {
        long elapsed = receivedClientTimeMillis <= 0 ? 0 : Math.max(0, clientNowMillis - receivedClientTimeMillis);
        long length = Math.max(1, dayLengthMillis);
        long t = Math.floorMod(worldTimeMillis + elapsed, length);
        return t / (float) length;
    }

    public String worldClock(long clientNowMillis) {
        return clockForPhase(worldDayPhase(clientNowMillis));
    }

    public static String clockForPhase(float phase) {
        int minutes = Math.floorMod(Math.round(phase * CLOCK_MINUTES_PER_DAY) + SUNRISE_CLOCK_OFFSET_MINUTES,
                CLOCK_MINUTES_PER_DAY);
        return "%02d:%02d".formatted(minutes / 60, minutes % 60);
    }

    public boolean sunVisible(long clientNowMillis) {
        return Math.sin(worldDayPhase(clientNowMillis) * Math.PI * 2.0) > 0.03;
    }

    public String celestialLabel(long clientNowMillis) {
        float altitude = (float) Math.sin(worldDayPhase(clientNowMillis) * Math.PI * 2.0);
        if (Math.abs(altitude) < 0.35f) return "SUN+MOON";
        return altitude > 0.0f ? "SUN" : "MOON";
    }

    public NetworkWorldState withTimeSample(int serverTick, long worldTimeMillis, long serverTimeMillis,
                                            long receivedClientTimeMillis, long dayLengthMillis) {
        return new NetworkWorldState(connected, dimensionId, serverTick, worldTimeMillis, serverTimeMillis,
                receivedClientTimeMillis, dayLengthMillis, entities, chunkCache, resourceNodeCache, adminSpatialCache);
    }

    public NetworkWorldState withChunks(int serverTick, ClientChunkCache chunkCache) {
        return new NetworkWorldState(connected, dimensionId, serverTick, worldTimeMillis, serverTimeMillis,
                receivedClientTimeMillis, dayLengthMillis, entities, chunkCache, resourceNodeCache, adminSpatialCache);
    }

    public NetworkWorldState withResourceNodes(int serverTick, ClientResourceNodeCache resourceNodeCache) {
        return new NetworkWorldState(connected, dimensionId, serverTick, worldTimeMillis, serverTimeMillis,
                receivedClientTimeMillis, dayLengthMillis, entities, chunkCache, resourceNodeCache, adminSpatialCache);
    }

    public NetworkWorldState withEntities(int serverTick, List<Entity> entities) {
        return new NetworkWorldState(connected, dimensionId, serverTick, worldTimeMillis, serverTimeMillis,
                receivedClientTimeMillis, dayLengthMillis, entities, chunkCache, resourceNodeCache, adminSpatialCache);
    }

    public NetworkWorldState withAdminSpatial(int serverTick, ClientAdminSpatialCache adminSpatialCache) {
        return new NetworkWorldState(connected, dimensionId, serverTick, worldTimeMillis, serverTimeMillis,
                receivedClientTimeMillis, dayLengthMillis, entities, chunkCache, resourceNodeCache, adminSpatialCache);
    }

    public List<Chunk> chunks() {
        return chunkCache.chunks();
    }

    public record Entity(long id, String archetype, float x, float y, float z,
                         float velocityX, float velocityY, float velocityZ,
                         float yaw, float pitch, boolean grounded,
                         String animation, boolean collidable, boolean interactable,
                         EntityAttributeContainer attributes, String displayName, String groupId) {
        public Entity(long id, String archetype, float x, float y, float z,
                      float velocityX, float velocityY, float velocityZ,
                      float yaw, float pitch, boolean grounded,
                      String animation, boolean collidable, boolean interactable,
                      EntityAttributeContainer attributes) {
            this(id, archetype, x, y, z, velocityX, velocityY, velocityZ, yaw, pitch, grounded,
                    animation, collidable, interactable, attributes, "", "");
        }

        public Entity(long id, String archetype, float x, float y, float z,
                      float velocityX, float velocityY, float velocityZ,
                      float yaw, float pitch, boolean grounded,
                      String animation, boolean collidable, boolean interactable,
                      Map<String, Float> legacySyncedAttributes) {
            this(id, archetype, x, y, z, velocityX, velocityY, velocityZ, yaw, pitch, grounded,
                    animation, collidable, interactable, new EntityAttributeContainer(EntityAttributeDefaults.empty()), "", "");
        }

        public Entity(long id, String archetype, float x, float y, float z, String animation,
                      boolean collidable, boolean interactable, Map<String, Float> syncedAttributes) {
            this(id, archetype, x, y, z, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, true,
                    animation, collidable, interactable,
                    new EntityAttributeContainer(EntityAttributeDefaults.empty()), "", "");
        }

        public Entity(long id, String archetype, float x, float y, float z, String animation,
                      boolean collidable, boolean interactable, EntityAttributeContainer attributes) {
            this(id, archetype, x, y, z, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, true,
                    animation, collidable, interactable, attributes, "", "");
        }

        public Entity(long id, String archetype, float x, float y, float z, String animation,
                      boolean collidable, boolean interactable, EntityAttributeContainer attributes,
                      String displayName) {
            this(id, archetype, x, y, z, animation, collidable, interactable, attributes, displayName, "");
        }

        public Entity(long id, String archetype, float x, float y, float z, String animation,
                      boolean collidable, boolean interactable, EntityAttributeContainer attributes,
                      String displayName, String groupId) {
            this(id, archetype, x, y, z, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, true,
                    animation, collidable, interactable, attributes, displayName, groupId);
        }

        public Entity {
            attributes = attributes == null
                    ? new EntityAttributeContainer(EntityAttributeDefaults.empty()) : attributes;
            displayName = displayName == null ? "" : displayName;
            groupId = groupId == null ? "" : groupId;
        }
    }

    public record Chunk(String dimensionId, int x, int y, int z, List<Block> blocks, List<Fluid> fluids) {
        public Chunk(String dimensionId, int x, int y, int z, List<Block> blocks) {
            this(dimensionId, x, y, z, blocks, List.of());
        }

        public Chunk {
            blocks = List.copyOf(blocks);
            fluids = fluids == null ? List.of() : List.copyOf(fluids);
        }

        public ChunkKey key() {
            return new ChunkKey(dimensionId, x, y, z);
        }
    }

    public record Block(int localX, int localZ, int y, String blockId, Map<String, String> properties) {
        public Block {
            if (blockId == null || blockId.isBlank()) throw new IllegalArgumentException("blockId is required");
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    public record Fluid(int localX, int localZ, int y, String fluidId, int level, boolean falling, boolean source) {
        public Fluid {
            if (fluidId == null || fluidId.isBlank()) throw new IllegalArgumentException("fluidId is required");
            if (level < 1 || level > 8) throw new IllegalArgumentException("fluid level must be 1..8");
        }
    }

    public record ChunkKey(String dimensionId, int x, int y, int z) {
        public ChunkKey {
            if (dimensionId == null || dimensionId.isBlank()) throw new IllegalArgumentException("dimensionId is required");
        }
    }

    public record ResourceNode(String dimensionId, int x, int y, int z, String typeId, long depletedUntilTick,
                               boolean available) {
        public ResourceNode {
            if (dimensionId == null || dimensionId.isBlank()) throw new IllegalArgumentException("dimensionId is required");
            if (typeId == null || typeId.isBlank()) throw new IllegalArgumentException("typeId is required");
        }

        public ResourceNodeKey key() {
            return new ResourceNodeKey(dimensionId, x, y, z);
        }
    }

    public record ResourceNodeKey(String dimensionId, int x, int y, int z) {
        public ResourceNodeKey {
            if (dimensionId == null || dimensionId.isBlank()) throw new IllegalArgumentException("dimensionId is required");
        }
    }

    public record ClientResourceNodeCache(Map<ResourceNodeKey, ResourceNode> nodesByKey) {
        public ClientResourceNodeCache {
            nodesByKey = Map.copyOf(nodesByKey);
        }

        public static ClientResourceNodeCache empty() {
            return new ClientResourceNodeCache(Map.of());
        }

        public List<ResourceNode> nodes() {
            return nodesByKey.values().stream()
                    .sorted(java.util.Comparator.comparing(ResourceNode::dimensionId)
                            .thenComparingInt(ResourceNode::x)
                            .thenComparingInt(ResourceNode::y)
                            .thenComparingInt(ResourceNode::z))
                    .toList();
        }

        public ResourceNode get(ResourceNodeKey key) {
            return nodesByKey.get(key);
        }

        public ClientResourceNodeCache withNode(ResourceNode node) {
            return mutate().put(node).build();
        }

        public ClientResourceNodeCache withoutNode(ResourceNodeKey key) {
            return mutate().remove(key).build();
        }

        public ClientResourceNodeCache withoutChunk(String dimensionId, int chunkX, int chunkY, int chunkZ, int chunkSize) {
            return mutate().removeChunk(dimensionId, chunkX, chunkY, chunkZ, chunkSize).build();
        }

        public Builder mutate() {
            return new Builder(this);
        }

        public static final class Builder {
            private final ClientResourceNodeCache original;
            private final java.util.LinkedHashMap<ResourceNodeKey, ResourceNode> nodes;
            private boolean changed;

            private Builder(ClientResourceNodeCache original) {
                this.original = original;
                this.nodes = new java.util.LinkedHashMap<>(original.nodesByKey);
            }

            public Builder put(ResourceNode node) {
                changed |= nodes.put(node.key(), node) != node;
                return this;
            }

            public Builder remove(ResourceNodeKey key) {
                changed |= nodes.remove(key) != null;
                return this;
            }

            public Builder removeChunk(String dimensionId, int chunkX, int chunkY, int chunkZ, int chunkSize) {
                changed |= nodes.entrySet().removeIf(entry -> entry.getKey().dimensionId().equals(dimensionId)
                        && Math.floorDiv(entry.getKey().x(), chunkSize) == chunkX
                        && Math.floorDiv(entry.getKey().y(), chunkSize) == chunkY
                        && Math.floorDiv(entry.getKey().z(), chunkSize) == chunkZ);
                return this;
            }

            public ClientResourceNodeCache build() {
                return changed ? new ClientResourceNodeCache(nodes) : original;
            }
        }
    }

    public record BoundsInfo(String dimensionId, float minX, float minY, float minZ,
                             float maxX, float maxY, float maxZ) {
        public BoundsInfo {
            if (dimensionId == null || dimensionId.isBlank()) throw new IllegalArgumentException("dimensionId is required");
        }
    }

    public record AdminTrigger(String triggerId, BoundsInfo bounds, String signalId, Map<String, String> payload,
                               boolean enabled, boolean adminVisible, int revision) {
        public AdminTrigger {
            if (triggerId == null || triggerId.isBlank()) throw new IllegalArgumentException("triggerId is required");
            if (signalId == null || signalId.isBlank()) throw new IllegalArgumentException("signalId is required");
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record AdminSpawn(String spawnId, BoundsInfo bounds, String typeId, Map<String, String> payload,
                             boolean enabled, int revision) {
        public AdminSpawn {
            if (spawnId == null || spawnId.isBlank()) throw new IllegalArgumentException("spawnId is required");
            if (typeId == null || typeId.isBlank()) throw new IllegalArgumentException("typeId is required");
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record ClientAdminSpatialCache(Map<String, AdminTrigger> triggersById, Map<String, AdminSpawn> spawnsById) {
        public ClientAdminSpatialCache {
            triggersById = Map.copyOf(triggersById);
            spawnsById = Map.copyOf(spawnsById);
        }

        public static ClientAdminSpatialCache empty() {
            return new ClientAdminSpatialCache(Map.of(), Map.of());
        }

        public ClientAdminSpatialCache withTrigger(AdminTrigger trigger) {
            return mutate().put(trigger).build();
        }

        public ClientAdminSpatialCache withSpawn(AdminSpawn spawn) {
            return mutate().put(spawn).build();
        }

        public Builder mutate() {
            return new Builder(this);
        }

        public static final class Builder {
            private final ClientAdminSpatialCache original;
            private final java.util.LinkedHashMap<String, AdminTrigger> triggers;
            private final java.util.LinkedHashMap<String, AdminSpawn> spawns;
            private boolean changed;

            private Builder(ClientAdminSpatialCache original) {
                this.original = original;
                this.triggers = new java.util.LinkedHashMap<>(original.triggersById);
                this.spawns = new java.util.LinkedHashMap<>(original.spawnsById);
            }

            public Builder put(AdminTrigger trigger) {
                changed |= triggers.put(trigger.triggerId(), trigger) != trigger;
                return this;
            }

            public Builder put(AdminSpawn spawn) {
                changed |= spawns.put(spawn.spawnId(), spawn) != spawn;
                return this;
            }

            public ClientAdminSpatialCache build() {
                return changed ? new ClientAdminSpatialCache(triggers, spawns) : original;
            }
        }
    }

    public record ClientChunkCache(Map<ChunkKey, Chunk> chunksByKey) {
        public ClientChunkCache {
            chunksByKey = Map.copyOf(chunksByKey);
        }

        public static ClientChunkCache empty() {
            return new ClientChunkCache(Map.of());
        }

        public static ClientChunkCache of(List<Chunk> chunks) {
            java.util.LinkedHashMap<ChunkKey, Chunk> byKey = new java.util.LinkedHashMap<>();
            for (Chunk chunk : chunks) byKey.put(chunk.key(), chunk);
            return new ClientChunkCache(byKey);
        }

        public List<Chunk> chunks() {
            return chunksByKey.values().stream()
                    .sorted(java.util.Comparator.comparing(Chunk::dimensionId)
                            .thenComparingInt(Chunk::x)
                            .thenComparingInt(Chunk::y)
                            .thenComparingInt(Chunk::z))
                    .toList();
        }

        public Chunk get(ChunkKey key) {
            return chunksByKey.get(key);
        }

        public boolean contains(ChunkKey key) {
            return chunksByKey.containsKey(key);
        }

        public int size() {
            return chunksByKey.size();
        }

        public boolean isEmpty() {
            return chunksByKey.isEmpty();
        }

        public ClientChunkCache withChunk(Chunk chunk) {
            return mutate().put(chunk).build();
        }

        public ClientChunkCache withoutChunk(ChunkKey key) {
            return mutate().remove(key).build();
        }

        public ClientChunkCache withBlock(String dimensionId, int worldX, int y, int worldZ, int chunkSize, Block block) {
            return mutate().block(dimensionId, worldX, y, worldZ, chunkSize, block).build();
        }

        public ClientChunkCache withFluid(String dimensionId, int worldX, int y, int worldZ, int chunkSize, Fluid fluid) {
            return mutate().fluid(dimensionId, worldX, y, worldZ, chunkSize, fluid).build();
        }

        public Builder mutate() {
            return new Builder(this);
        }

        public static final class Builder {
            private final ClientChunkCache original;
            private final java.util.LinkedHashMap<ChunkKey, Chunk> chunks;
            private boolean changed;

            private Builder(ClientChunkCache original) {
                this.original = original;
                this.chunks = new java.util.LinkedHashMap<>(original.chunksByKey);
            }

            public Builder put(Chunk chunk) {
                changed |= chunks.put(chunk.key(), chunk) != chunk;
                return this;
            }

            public Builder remove(ChunkKey key) {
                changed |= chunks.remove(key) != null;
                return this;
            }

            public Builder block(String dimensionId, int worldX, int y, int worldZ, int chunkSize, Block block) {
                ChunkKey key = key(dimensionId, worldX, y, worldZ, chunkSize);
                Chunk chunk = chunks.get(key);
                if (chunk == null) return this;
                int localX = Math.floorMod(worldX, chunkSize);
                int localZ = Math.floorMod(worldZ, chunkSize);
                java.util.ArrayList<Block> blocks = new java.util.ArrayList<>(chunk.blocks().size() + 1);
                boolean replaced = false;
                for (Block existing : chunk.blocks()) {
                    if (existing.localX() == localX && existing.localZ() == localZ && existing.y() == y) {
                        if (block != null) blocks.add(new Block(localX, localZ, y, block.blockId(), block.properties()));
                        replaced = true;
                    } else {
                        blocks.add(existing);
                    }
                }
                if (!replaced && block != null) {
                    blocks.add(new Block(localX, localZ, y, block.blockId(), block.properties()));
                }
                chunks.put(key, new Chunk(chunk.dimensionId(), chunk.x(), chunk.y(), chunk.z(), blocks, chunk.fluids()));
                changed = true;
                return this;
            }

            public Builder fluid(String dimensionId, int worldX, int y, int worldZ, int chunkSize, Fluid fluid) {
                ChunkKey key = key(dimensionId, worldX, y, worldZ, chunkSize);
                Chunk chunk = chunks.get(key);
                if (chunk == null) return this;
                int localX = Math.floorMod(worldX, chunkSize);
                int localZ = Math.floorMod(worldZ, chunkSize);
                java.util.ArrayList<Fluid> fluids = new java.util.ArrayList<>(chunk.fluids().size() + 1);
                boolean replaced = false;
                for (Fluid existing : chunk.fluids()) {
                    if (existing.localX() == localX && existing.localZ() == localZ && existing.y() == y) {
                        if (fluid != null) {
                            fluids.add(new Fluid(localX, localZ, y, fluid.fluidId(), fluid.level(), fluid.falling(), fluid.source()));
                        }
                        replaced = true;
                    } else {
                        fluids.add(existing);
                    }
                }
                if (!replaced && fluid != null) {
                    fluids.add(new Fluid(localX, localZ, y, fluid.fluidId(), fluid.level(), fluid.falling(), fluid.source()));
                }
                chunks.put(key, new Chunk(chunk.dimensionId(), chunk.x(), chunk.y(), chunk.z(), chunk.blocks(), fluids));
                changed = true;
                return this;
            }

            public ClientChunkCache build() {
                return changed ? new ClientChunkCache(chunks) : original;
            }

            private static ChunkKey key(String dimensionId, int worldX, int y, int worldZ, int chunkSize) {
                return new ChunkKey(dimensionId, Math.floorDiv(worldX, chunkSize),
                        Math.floorDiv(y, chunkSize), Math.floorDiv(worldZ, chunkSize));
            }
        }
    }
}
