package net.minestom.server.registry;

import net.minestom.server.ServerFlag;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.common.TagsPacket;
import net.minestom.server.network.packet.server.configuration.RegistryDataPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Grafts extra registry entries dumped by Velocity-CTD into Minestom's own registries at startup, so
 * a Minestom hub advertises the exact registry set of a "more complex" backend (for example a Paper
 * server with a custom world generator that adds non-vanilla biomes / dimension types).
 *
 * <p>Rather than swapping the outgoing registry packets at the wire edge, this mutates Minestom's
 * internal {@link DynamicRegistry} state — the same layer the vanilla-ordering work operates on. Each
 * registry is rebuilt so its id order and contents exactly match the backend's (see
 * {@link DynamicRegistryImpl#reorderToMatch}). This matters because the backend does not simply append
 * its extra entries: a datapack like Terralith interleaves its custom biomes among the vanilla ones
 * (sorted by path) and even replaces some vanilla definitions. Once the registries match, every encoder
 * that derives wire shape from a registry (notably the chunk biome palette's direct-bit width, and
 * dimension heights) lines up with the backend automatically, and Minestom re-emits the backend's exact
 * registry packets itself. When the client already holds that set, Velocity-CTD's fast server-switch can
 * move the player to the backend with no client-visible reconfiguration.</p>
 *
 * <p>The dumps are the raw bodies of the clientbound registry-data packets Velocity-CTD captured from
 * the backend ({@code <dumpDir>/<label>/NN-R-<name>.bin}); their wire layout is identical to
 * {@link RegistryDataPacket}. Point {@code -Dminestom.registry-data-override-dir} at one label
 * directory (e.g. {@code .../client-paper}).</p>
 *
 * <p>Most tag dumps ({@code NN-T-*.bin}, wire layout of {@link TagsPacket}) are ignored — tags are not
 * part of Velocity-CTD's fingerprint, so Minestom keeps sending its own. The exception is
 * {@link #FINGERPRINTED_TAG_REGISTRIES}: the proxy does fold those into the fingerprint, so for any such
 * registry we also graft the backend's tags (see {@link DynamicRegistryImpl#overrideTags}). In
 * particular {@code minecraft:dialog}'s {@code pause_screen_additions} tag is what makes a backend's
 * custom pause-menu button appear; without replaying it the hub's dialog tags differ from the backend's
 * and the fast switch falls back to a full reconfiguration.</p>
 *
 * <p>After each registry is rebuilt, {@link #verifyRoundTrip} re-encodes it and compares against the
 * dump byte-for-byte, warning if they differ. Because the NBT is emitted verbatim this should always
 * match; a warning would indicate the NBT (de)serialization itself is not byte-preserving, and means
 * the fast switch will fall back for that registry rather than fail silently.</p>
 */
public final class RegistryDataOverride {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryDataOverride.class);

    // Dump files are named "NN-R-<name>.bin" for registries and "NN-T-<name>.bin" for tags.
    private static final Pattern REGISTRY_FILE = Pattern.compile("\\d+-R-.*\\.bin");
    private static final Pattern TAG_FILE = Pattern.compile("\\d+-T-.*\\.bin");

    // Registries Velocity-CTD excludes from its fast-switch fingerprint; a byte mismatch on these does
    // not affect the switch, so we still graft them (for client consistency) but do not warn about it.
    private static final List<String> FINGERPRINT_IGNORED = List.of(
            "minecraft:chat_type", "minecraft:test_environment", "minecraft:test_instance");

    // Tag registries Velocity-CTD DOES fold into its fast-switch fingerprint (its
    // SIGNIFICANT_TAG_REGISTRIES). For these we replay the backend's tags verbatim; all other tag
    // registries are cosmetic to the fingerprint and Minestom keeps sending its own. Keep in sync with
    // the proxy.
    private static final Set<String> FINGERPRINTED_TAG_REGISTRIES = Set.of(
            "minecraft:dialog");

    private RegistryDataOverride() {
    }

    /**
     * Grafts the configured dump directory's extra entries into {@code registries}. No-op when
     * {@code -Dminestom.registry-data-override-dir} is unset. Must run once, after the registries are
     * built and before they are used (players connect / instances are created).
     */
    public static void augment(Registries registries) {
        final String dir = ServerFlag.REGISTRY_DATA_OVERRIDE_DIR;
        if (dir == null || dir.isBlank()) {
            return;
        }

        final List<Dump> dumps = load(Path.of(dir), registries);
        if (dumps == null || dumps.isEmpty()) {
            return;
        }

        final Map<String, DynamicRegistry<?>> byId = new HashMap<>();
        for (DynamicRegistry<?> registry : RegistriesImpl.configurationRegistries(registries)) {
            byId.put(registry.key().asString(), registry);
        }

        final Set<String> grafted = new HashSet<>();
        for (Dump dump : dumps) {
            final String registryId = dump.packet.registryId();
            final DynamicRegistry<?> registry = byId.get(registryId);
            if (registry == null) {
                LOGGER.warn("Registry-data override for {} has no matching Minestom registry; ignoring", registryId);
                continue;
            }
            try {
                ((DynamicRegistryImpl<?>) registry).reorderToMatch(dump.packet.entries());
            } catch (Exception e) {
                LOGGER.warn("Registry-data override {}: failed to apply; leaving Minestom's registry unchanged",
                        registryId, e);
                continue;
            }
            verifyRoundTrip((DynamicRegistryImpl<?>) registry, dump, registries);
            grafted.add(registryId);
        }
        LOGGER.info("Applied {} registry override(s) from {} to Minestom's registries", grafted.size(), dir);

        augmentTags(Path.of(dir), registries, byId, grafted);
    }

    /**
     * Replays the backend's tags for the {@link #FINGERPRINTED_TAG_REGISTRIES}. Only registries whose
     * entries were successfully grafted above are touched: their id order now matches the backend, so the
     * tag entry ids resolve correctly. No-op when the directory has no tag dump.
     */
    private static void augmentTags(Path dir, Registries registries, Map<String, DynamicRegistry<?>> byId,
                                    Set<String> grafted) {
        final TagsPacket tags = loadTags(dir, registries);
        if (tags == null) {
            return;
        }
        int applied = 0;
        for (TagsPacket.Registry tagRegistry : tags.registries()) {
            final String registryId = tagRegistry.registry();
            if (!FINGERPRINTED_TAG_REGISTRIES.contains(registryId)) {
                continue;
            }
            if (!grafted.contains(registryId)) {
                LOGGER.warn("Tag override for {} skipped: its registry entries were not grafted, so tag ids "
                        + "would not resolve; the fast switch will not match for this registry", registryId);
                continue;
            }
            try {
                ((DynamicRegistryImpl<?>) byId.get(registryId)).overrideTags(tagRegistry.tags());
                applied++;
            } catch (Exception e) {
                LOGGER.warn("Tag override {}: failed to apply; leaving Minestom's tags unchanged", registryId, e);
            }
        }
        if (applied > 0) {
            LOGGER.info("Applied {} tag override(s) from {} to Minestom's registries", applied, dir);
        }
    }

    /**
     * Confirms the rebuilt registry reproduces the backend's entries (same ids in the same order, same
     * omitted/full split, same NBT values). The comparison is by {@link RegistryDataPacket.Entry#equals}
     * — NBT compounds compare as unordered maps, so this ignores key ordering (which is exactly what
     * Velocity-CTD normalizes away in its fingerprint) and flags only genuine structural differences.
     */
    private static void verifyRoundTrip(DynamicRegistryImpl<?> registry, Dump dump, Registries registries) {
        final String registryId = dump.packet.registryId();
        final RegistryDataPacket generated;
        try {
            generated = registry.createRegistryDataPacket(registries, true);
        } catch (Exception e) {
            LOGGER.warn("Registry-data override {}: could not re-encode for verification", registryId, e);
            return;
        }
        if (!generated.entries().equals(dump.packet.entries()) && !FINGERPRINT_IGNORED.contains(registryId)) {
            LOGGER.warn("Registry-data override {}: rebuilt registry does not match the backend's entries "
                            + "({} vs {} entries); the fast switch will not match for this registry",
                    registryId, generated.entries().size(), dump.packet.entries().size());
        }
    }

    private static List<Dump> load(Path dir, Registries registries) {
        final List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> REGISTRY_FILE.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            LOGGER.error("Failed to list registry-data override directory {}; not augmenting registries", dir, e);
            return null;
        }
        if (files.isEmpty()) {
            LOGGER.warn("Registry-data override directory {} contains no NN-R-*.bin files; not augmenting registries", dir);
            return null;
        }

        final List<Dump> dumps = new ArrayList<>(files.size());
        for (Path file : files) {
            final byte[] raw;
            try {
                raw = Files.readAllBytes(file);
            } catch (IOException e) {
                LOGGER.error("Failed to read registry-data override {}; not augmenting registries", file, e);
                return null;
            }
            try {
                final NetworkBuffer buffer = NetworkBuffer.wrap(raw, 0, raw.length, registries);
                final RegistryDataPacket packet = buffer.read(RegistryDataPacket.SERIALIZER);
                if (buffer.readableBytes() != 0) {
                    LOGGER.error("Registry-data override {} has {} trailing byte(s); not augmenting registries",
                            file, buffer.readableBytes());
                    return null;
                }
                dumps.add(new Dump(packet));
            } catch (Exception e) {
                LOGGER.error("Failed to decode registry-data override {}; not augmenting registries", file, e);
                return null;
            }
        }
        return dumps;
    }

    /**
     * Reads the single {@code NN-T-*.bin} tag dump (the wire body of a {@link TagsPacket}) from the
     * override directory. Returns {@code null} when there is no tag dump or it cannot be read/decoded —
     * tag replay is best-effort and never blocks the registry override.
     */
    private static TagsPacket loadTags(Path dir, Registries registries) {
        final List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> TAG_FILE.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            LOGGER.error("Failed to list tag dumps in {}; not augmenting tags", dir, e);
            return null;
        }
        if (files.isEmpty()) {
            return null;
        }
        if (files.size() > 1) {
            LOGGER.warn("Override directory {} has {} tag dumps; using {}", dir, files.size(),
                    files.get(0).getFileName());
        }
        final Path file = files.get(0);
        final byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            LOGGER.error("Failed to read tag dump {}; not augmenting tags", file, e);
            return null;
        }
        try {
            final NetworkBuffer buffer = NetworkBuffer.wrap(raw, 0, raw.length, registries);
            final TagsPacket packet = buffer.read(TagsPacket.SERIALIZER);
            if (buffer.readableBytes() != 0) {
                LOGGER.error("Tag dump {} has {} trailing byte(s); not augmenting tags", file, buffer.readableBytes());
                return null;
            }
            return packet;
        } catch (Exception e) {
            LOGGER.error("Failed to decode tag dump {}; not augmenting tags", file, e);
            return null;
        }
    }

    private record Dump(RegistryDataPacket packet) {
    }
}
