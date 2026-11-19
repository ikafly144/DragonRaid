package net.sabafly.dragonraid.api.pack;

import java.nio.file.Path;
import java.util.List;

/**
 * Loader SPI reading {@code plugins/DragonRaid/contents/<pack-id>/} packs and atomically
 * updating registered Content Finder entries, achievements, rewards, and progression policies.
 */
public interface ContentPackLoader {

    ContentPackLoadReport reload();

    List<LoadedContentPack> loadedPacks();

    ContentPackLoadReport lastReport();

    Path contentsRoot();

    default ContentPackCatalogSnapshot catalogSnapshot() {
        return ContentPackCatalogSnapshot.empty();
    }
}
