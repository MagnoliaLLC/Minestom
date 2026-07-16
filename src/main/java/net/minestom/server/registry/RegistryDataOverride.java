package net.minestom.server.registry;

import net.minestom.server.ServerFlag;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.configuration.RegistryDataPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * directory (e.g. {@code .../client-paper}). Tag dumps ({@code NN-T-*.bin}) are ignored — tags are not
 * part of Velocity-CTD's fingerprint, and Minestom keeps sending its own.</p>
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

    // Registries Velocity-CTD excludes from its fast-switch fingerprint; a byte mismatch on these does
    // not affect the switch, so we still graft them (for client consistency) but do not warn about it.
    private static final List<String> FINGERPRINT_IGNORED = List.of(
            "minecraft:chat_type", "minecraft:test_environment", "minecraft:test_instance");

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

        int applied = 0;
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
            applied++;
        }
        LOGGER.info("Applied {} registry override(s) from {} to Minestom's registries", applied, dir);
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

    private record Dump(RegistryDataPacket packet) {
    }
}
