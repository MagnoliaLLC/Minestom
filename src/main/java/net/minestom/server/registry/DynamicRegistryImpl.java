package net.minestom.server.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Result;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.gamedata.DataPack;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.common.TagsPacket;
import net.minestom.server.network.packet.server.configuration.RegistryDataPacket;
import net.minestom.server.utils.json.JsonUtil;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApiStatus.Internal
final class DynamicRegistryImpl<T> implements DynamicRegistry<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicRegistryImpl.class);
    private static final String UNSAFE_REMOVE_MESSAGE = "Unsafe remove is disabled. Enable by setting the system property 'minestom.registry.unsafe-ops' to 'true'";
    // Could also just use `this`, but this is a good candidate for identityless classes.
    // Also, what use case requires you to mutate registries faster than one monitor?
    private static final Object REGISTRY_LOCK = new Object();

    private volatile @Nullable Registries registries = null;
    private final CachedPacket vanillaRegistryDataPacket = new CachedPacket(() -> createRegistryDataPacket(registries, true));

    private final List<T> idToValue;
    private final List<RegistryKey<T>> idToKey;
    private final Map<RegistryKey<T>, Integer> keyToId;
    private final Map<Key, T> keyToValue;
    private final Map<T, RegistryKey<T>> valueToKey;
    private final List<DataPack> packById;

    // Verbatim NBT to emit for entries grafted by RegistryDataOverride, keyed by entry key. The source
    // backend's NBT is kept as-is because this registry's codec does not necessarily re-encode to the
    // same bytes (field order / optional fields differ), which would break the proxy's byte-for-byte
    // fingerprint. Empty in normal operation. Only populated by reorderToMatch.
    private final Map<Key, BinaryTag> overrideEntryNbt = new HashMap<>();

    private final Map<TagKey<T>, RegistryTagImpl.Backed<T>> tags;

    private final Key key;
    private final Codec<T> codec;

    DynamicRegistryImpl(Key key, @Nullable Codec<T> codec) {
        this.key = key;
        this.codec = codec;
        // Expect stale data possibilities with unsafe ops.
        this.idToValue = new ArrayList<>();
        this.idToKey = new ArrayList<>();
        this.keyToId = new HashMap<>();
        this.keyToValue = new HashMap<>();
        this.valueToKey = new HashMap<>();
        this.packById = new ArrayList<>();
        // Tags are always mutable across the lock.
        this.tags = new ConcurrentHashMap<>();
    }

    // Used to create compressed registries
    DynamicRegistryImpl(Key key, @Nullable Codec<T> codec, List<T> idToValue,
                        Map<RegistryKey<T>, Integer> keyToId, List<RegistryKey<T>> idToKey,
                        Map<Key, T> keyToValue, Map<T, RegistryKey<T>> valueToKey,
                        List<DataPack> packById, Map<TagKey<T>, RegistryTagImpl.Backed<T>> tags) {
        this.key = key;
        this.codec = codec;
        this.idToValue = idToValue;
        this.idToKey = idToKey;
        this.keyToId = keyToId;
        this.keyToValue = keyToValue;
        this.valueToKey = valueToKey;
        this.packById = packById;
        this.tags = tags;
    }

    @Override
    public Key key() {
        return this.key;
    }

    public @UnknownNullability Codec<T> codec() {
        return codec;
    }

    @Override
    public @Nullable T get(int id) {
        if (id < 0 || id >= idToValue.size())
            return null;
        return idToValue.get(id);
    }

    @Override
    public @Nullable T get(Key key) {
        return keyToValue.get(key);
    }

    @Override
    public @Nullable RegistryKey<T> getKey(int id) {
        if (id < 0 || id >= idToKey.size())
            return null;
        return idToKey.get(id);
    }

    @Override
    public @Nullable RegistryKey<T> getKey(T value) {
        return valueToKey.get(value);
    }

    @Override
    public @Nullable RegistryKey<T> getKey(Key key) {
        if (!keyToValue.containsKey(key))
            return null;
        return new RegistryKeyImpl<>(key);
    }

    @Override
    public int getId(RegistryKey<T> key) {
        return keyToId.getOrDefault(key, -1);
    }

    @Override
    public RegistryKey<T> register(Key key, T object, DataPack pack) {
        if (isFrozen()) throw new UnsupportedOperationException(UNSAFE_REMOVE_MESSAGE);
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(object, "Object cannot be null");
        Objects.requireNonNull(pack, "Pack cannot be null");

        final RegistryKey<T> registryKey = new RegistryKeyImpl<>(key);
        synchronized (REGISTRY_LOCK) {
            Integer id = keyToId.get(registryKey); // Array set at home
            keyToValue.put(key, object);
            valueToKey.put(object, registryKey);
            if (id == null) {
                idToValue.add(object);
                idToKey.add(registryKey);
                keyToId.put(registryKey, idToValue.size() - 1);
                packById.add(pack);
            } else {
                idToValue.set(id, object);
                idToKey.set(id, registryKey);
                keyToId.put(registryKey, id);
                packById.set(id, pack);
            }

            vanillaRegistryDataPacket.invalidate();
            return registryKey;
        }
    }

    @Override
    public boolean remove(Key key) throws UnsupportedOperationException {
        if (isFrozen()) throw new UnsupportedOperationException(UNSAFE_REMOVE_MESSAGE);
        Objects.requireNonNull(key, "Key cannot be null");

        final RegistryKey<T> registryKey = new RegistryKeyImpl<>(key);
        synchronized (REGISTRY_LOCK) {
            Integer idObject = keyToId.get(registryKey);
            if (idObject == null) return false;
            int id = idObject;

            // Remove value from all mappings (shifting down indices)
            idToValue.remove(id);
            idToKey.remove(registryKey);
            keyToId.remove(registryKey);
            var value = keyToValue.remove(key);
            valueToKey.remove(value);
            packById.remove(id);

            // Remove all references from tags
            for (final var tag : tags.values()) {
                tag.remove(registryKey);
            }

            vanillaRegistryDataPacket.invalidate();
            return true;
        }
    }

    @Override
    public @Nullable DataPack getPack(int id) {
        if (id < 0 || id >= packById.size())
            return null;
        return packById.get(id);
    }

    @Override
    public int size() {
        return idToValue.size();
    }

    @Override
    public Collection<RegistryKey<T>> keys() {
        return Collections.unmodifiableCollection(idToKey);
    }

    @Override
    public Collection<T> values() {
        return Collections.unmodifiableCollection(idToValue);
    }

    // Tags

    @Override
    public @Nullable RegistryTag<T> getTag(TagKey<T> key) {
        return this.tags.get(key);
    }

    @Override
    public RegistryTag<T> getOrCreateTag(TagKey<T> key) {
        return this.tags.computeIfAbsent(key, RegistryTagImpl.Backed::new);
    }

    @Override
    public boolean removeTag(TagKey<T> key) {
        return this.tags.remove(key) != null;
    }

    @Override
    public Collection<RegistryTag<T>> tags() {
        return Collections.unmodifiableCollection(this.tags.values());
    }

    @Override // This method is called by a virtual thread in the configuration phase
    public SendablePacket registryDataPacket(Registries registries, boolean excludeVanilla) {
        // We cache the vanilla packet because that is by far the most common case. If some client claims not to have
        // the vanilla datapack we can compute the entire thing.
        if (excludeVanilla) {
            if (this.registries != registries) {
                synchronized (REGISTRY_LOCK) { // Bootleg off the static lock for this mutation
                    if (this.registries != registries) {
                        this.registries = registries;
                        vanillaRegistryDataPacket.invalidate();
                    }
                }
            }
            return vanillaRegistryDataPacket;
        }

        return createRegistryDataPacket(registries, false);
    }

    @Override
    public TagsPacket.Registry tagRegistry() {
        final List<TagsPacket.Tag> tagList = new ArrayList<>(tags.size());
        for (final RegistryTagImpl.Backed<T> tag : tags.values()) {
            final int[] entries = new int[tag.size()];
            int i = 0;
            for (var registryKey : tag)
                entries[i++] = keyToId.get(registryKey);
            tagList.add(new TagsPacket.Tag(tag.key().key().asString(), entries));
        }
        return new TagsPacket.Registry(key().asString(), tagList);
    }

    /**
     * Rebuilds this registry so its id order and wire output exactly match {@code orderedEntries} (the
     * entries of a registry-data packet another backend sent). Used to make a Minestom hub advertise a
     * backend's precise registry set so the proxy can skip client reconfiguration (see
     * {@link RegistryDataOverride}).
     *
     * <p>Everything that goes on the wire is taken verbatim from the entries — the id order, which
     * entries are omitted, and the exact NBT bytes — so this is agnostic to how the backend orders or
     * serializes its registries (no dependency on vanilla's alphabetical sort or this registry's codec
     * field order, either of which could change). For each entry, in order:</p>
     * <ul>
     *   <li>with NBT — the NBT is stored verbatim ({@link #overrideEntryNbt}) and emitted as-is under a
     *       non-{@link DataPack#MINECRAFT_CORE} pack, so it is always sent in full;</li>
     *   <li>without NBT — reused under {@link DataPack#MINECRAFT_CORE}, so it is omitted for clients that
     *       know the vanilla pack, exactly as the backend omitted it.</li>
     * </ul>
     *
     * <p>The typed value stored per id is only used server-side (never re-encoded for the wire): this
     * registry's existing value is reused when the key is one it already had, otherwise a placeholder is
     * used (custom entries are advertised to the client but never placed by a hub). Entries this
     * registry has that the override does not mention are appended afterwards (the backend is expected to
     * be a superset). Tags are left untouched — they reference keys, not ids.</p>
     *
     * @throws IllegalStateException if an omitted entry has no existing value to reuse, or a new entry
     *                               must be grafted into an empty registry (no placeholder available)
     */
    @ApiStatus.Internal
    void reorderToMatch(List<RegistryDataPacket.Entry> orderedEntries) {
        final List<T> newIdToValue = new ArrayList<>(orderedEntries.size());
        final List<RegistryKey<T>> newIdToKey = new ArrayList<>(orderedEntries.size());
        final Map<RegistryKey<T>, Integer> newKeyToId = new HashMap<>();
        final Map<Key, T> newKeyToValue = new HashMap<>();
        final Map<T, RegistryKey<T>> newValueToKey = new HashMap<>();
        final List<DataPack> newPackById = new ArrayList<>(orderedEntries.size());
        final Map<Key, BinaryTag> newOverrideNbt = new HashMap<>();
        final Set<Key> seen = new HashSet<>();
        // Any real value works as a filler for custom entries: they are never placed server-side, only
        // advertised to the client (whose bytes come from the verbatim NBT, not from this value).
        final T placeholder = idToValue.isEmpty() ? null : idToValue.get(0);

        for (RegistryDataPacket.Entry entry : orderedEntries) {
            final Key entryKey = Key.key(entry.id());
            if (!seen.add(entryKey)) {
                throw new IllegalStateException("Duplicate entry " + entryKey + " in override for registry " + key);
            }
            final T existing = keyToValue.get(entryKey);
            final T value;
            final DataPack pack;
            final boolean mapValueToKey;
            if (entry.data() != null) {
                newOverrideNbt.put(entryKey, entry.data());
                pack = DataPack.MINESTOM_UNNAMED;
                if (existing != null) {
                    value = existing;
                    mapValueToKey = true;
                } else if (placeholder != null) {
                    value = placeholder; // filler; do not pollute valueToKey with it
                    mapValueToKey = false;
                } else {
                    throw new IllegalStateException("Cannot graft " + entryKey + " into empty registry " + key);
                }
            } else {
                if (existing == null) {
                    throw new IllegalStateException("Override omits data for " + entryKey + " in registry "
                            + key + " but Minestom has no such entry to reuse");
                }
                value = existing;
                pack = DataPack.MINECRAFT_CORE;
                mapValueToKey = true;
            }
            appendTo(newIdToValue, newIdToKey, newKeyToId, newKeyToValue, newValueToKey, newPackById,
                    entryKey, value, pack, mapValueToKey);
        }

        for (int i = 0; i < idToKey.size(); i++) {
            final RegistryKey<T> existingKey = idToKey.get(i);
            if (seen.contains(existingKey.key())) {
                continue;
            }
            LOGGER.warn("Registry {}: entry {} is not in the override; appending it (fast switch will not match)",
                    key, existingKey.key());
            appendTo(newIdToValue, newIdToKey, newKeyToId, newKeyToValue, newValueToKey, newPackById,
                    existingKey.key(), idToValue.get(i), packById.get(i), true);
        }

        synchronized (REGISTRY_LOCK) {
            idToValue.clear();
            idToValue.addAll(newIdToValue);
            idToKey.clear();
            idToKey.addAll(newIdToKey);
            keyToId.clear();
            keyToId.putAll(newKeyToId);
            keyToValue.clear();
            keyToValue.putAll(newKeyToValue);
            valueToKey.clear();
            valueToKey.putAll(newValueToKey);
            packById.clear();
            packById.addAll(newPackById);
            overrideEntryNbt.clear();
            overrideEntryNbt.putAll(newOverrideNbt);
            vanillaRegistryDataPacket.invalidate();
        }
    }

    private void appendTo(List<T> idToValue, List<RegistryKey<T>> idToKey, Map<RegistryKey<T>, Integer> keyToId,
                          Map<Key, T> keyToValue, Map<T, RegistryKey<T>> valueToKey, List<DataPack> packById,
                          Key entryKey, T value, DataPack pack, boolean mapValueToKey) {
        final int id = idToValue.size();
        final RegistryKey<T> registryKey = new RegistryKeyImpl<>(entryKey);
        idToValue.add(value);
        idToKey.add(registryKey);
        keyToId.put(registryKey, id);
        keyToValue.put(entryKey, value);
        if (mapValueToKey) {
            valueToKey.put(value, registryKey);
        }
        packById.add(pack);
    }

    /**
     * Replaces this registry's tags with {@code orderedTags} (the tags a backend sent for this
     * registry), so a Minestom hub advertises the backend's exact tag set. Companion to
     * {@link #reorderToMatch}: because that method has already made this registry's id order match the
     * backend's, the entry ids carried in each tag resolve to the same keys the backend meant. Used by
     * {@link RegistryDataOverride} for tag registries the proxy folds into its fast-switch fingerprint
     * (currently {@code minecraft:dialog}, whose {@code pause_screen_additions} decides whether a
     * backend's custom pause-menu buttons show).
     *
     * @throws IllegalStateException if a tag references an id this registry does not have (which would
     *                               mean {@link #reorderToMatch} was not applied, or the dumps are
     *                               inconsistent)
     */
    @ApiStatus.Internal
    void overrideTags(List<TagsPacket.Tag> orderedTags) {
        final Map<TagKey<T>, RegistryTagImpl.Backed<T>> newTags = new HashMap<>();
        for (TagsPacket.Tag tag : orderedTags) {
            final TagKey<T> tagKey = TagKey.unsafeOf(Key.key(tag.identifier()));
            final RegistryTagImpl.Backed<T> backed = new RegistryTagImpl.Backed<>(tagKey);
            for (int id : tag.entries()) {
                if (id < 0 || id >= idToKey.size()) {
                    throw new IllegalStateException("Tag " + tag.identifier() + " references out-of-range id "
                            + id + " in registry " + key);
                }
                backed.add(idToKey.get(id));
            }
            newTags.put(tagKey, backed);
        }
        synchronized (REGISTRY_LOCK) {
            tags.clear();
            tags.putAll(newTags);
        }
    }

    RegistryDataPacket createRegistryDataPacket(Registries registries, boolean excludeVanilla) {
        Objects.requireNonNull(codec, "Cannot create registry data packet for server-only registry");
        Transcoder<BinaryTag> transcoder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        // Copy to avoid concurrent modification issues while iterating, as we are not synchronized on the registry
        final List<T> idToValue;
        final List<DataPack> packById;
        if (!canFreeze()) {
            synchronized (REGISTRY_LOCK) {
                idToValue = List.copyOf(this.idToValue);
                packById = List.copyOf(this.packById);
            }
        } else {
            idToValue = this.idToValue;
            packById = this.packById;
        }
        List<RegistryDataPacket.Entry> entries = new ArrayList<>(idToValue.size());
        for (int i = 0; i < idToValue.size(); i++) {
            CompoundBinaryTag data = null;
            // sorta todo, sorta just a note:
            // Right now we very much only support the minecraft:core (vanilla) 'pack'. Any entry which was not loaded
            // from static data will be treated as non vanilla and always sent completely. However, we really should
            // support arbitrary packs and associate all registry data with a datapack. Additionally, we should generate
            // all data for the experimental datapacks built in to vanilla such as the next update experimental (1.21 at
            // the time of writing). Datagen currently behaves kind of badly in that the registry inspecting generators
            // like material, block, etc generate entries which are behind feature flags, whereas the ones which inspect
            // static assets (the traditionally dynamic registries), do not generate those assets.
            T entry = idToValue.get(i);
            DataPack pack = packById.get(i);
            if (!excludeVanilla || pack != DataPack.MINECRAFT_CORE) {
                final BinaryTag override = overrideEntryNbt.get(getKey(i).key());
                if (override != null) {
                    // Verbatim NBT grafted from another backend; emit as-is rather than re-encoding, so
                    // the bytes match the backend exactly (see reorderToMatch). Empty in normal operation.
                    data = (CompoundBinaryTag) override;
                } else {
                    final Result<BinaryTag> entryResult = codec.encode(transcoder, entry);
                    if (entryResult instanceof Result.Ok(BinaryTag tag)) {
                        data = (CompoundBinaryTag) tag;
                    } else {
                        throw new IllegalStateException("Failed to encode registry entry " + i + " (" + getKey(i) + ") for registry " + key);
                    }
                }
            }
            //noinspection DataFlowIssue
            entries.add(new RegistryDataPacket.Entry(getKey(i).key().asString(), data));
        }
        return new RegistryDataPacket(key.asString(), entries);
    }

    /**
     * Attempts to create a copy with compressed data structures.
     *
     * @return A safe copy of this registry
     */
    @Contract(pure = true)
    DynamicRegistryImpl<T> compact() {
        // Create new instances so they are trimmed to size without downcasting.
        return new DynamicRegistryImpl<>(key, codec,
                new ArrayList<>(idToValue),
                new HashMap<>(keyToId),
                new ArrayList<>(idToKey),
                new HashMap<>(keyToValue),
                new HashMap<>(valueToKey),
                new ArrayList<>(packById),
                new ConcurrentHashMap<>(tags)
        );
    }

    static boolean isFrozen() {
        return canFreeze() && MinecraftServer.process() != null && MinecraftServer.isStarted();
    }

    static boolean canFreeze() {
        return !ServerFlag.REGISTRY_UNSAFE_OPS && !ServerFlag.INSIDE_TEST;
    }

    @SuppressWarnings("removal")
    void loadStaticJsonRegistry(@Nullable Registries registries, @Nullable Comparator<String> idComparator, Codec<T> codec) {
        // Tags must exist before entries are decoded because registry codecs can resolve tags while loading values.
        tags.putAll(RegistryData.loadTags(key()));
        try (InputStream resourceStream = RegistryData.loadRegistryFile(String.format("%s.json", key().value()))) {
            Check.notNull(resourceStream, "Registry resource {0}.json does not exist!", key().value());
            final JsonElement json = JsonUtil.fromJson(new InputStreamReader(resourceStream, StandardCharsets.UTF_8));
            if (!(json instanceof JsonObject root))
                throw new IllegalStateException("Failed to load registry " + key() + ": expected a JSON object, got " + json);

            final Transcoder<JsonElement> transcoder = registries != null ? new RegistryTranscoder<>(Transcoder.JSON, registries) : Transcoder.JSON;
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(root.entrySet());
            if (idComparator != null) entries.sort(Map.Entry.comparingByKey(idComparator));
            for (Map.Entry<String, JsonElement> entry : entries) {
                final String namespace = entry.getKey();
                final Result<T> valueResult = codec.decode(transcoder, entry.getValue());
                if (valueResult instanceof Result.Ok(T value)) {
                    register(namespace, value, DataPack.MINECRAFT_CORE);
                } else {
                    throw new IllegalStateException("Failed to decode registry entry " + namespace + " for registry " + key() + ": " + valueResult);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
