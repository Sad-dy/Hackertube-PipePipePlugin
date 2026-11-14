package org.hackertube.pipepipe.api;

import com.grack.nanojson.JsonObject;

import org.hackertube.pipepipe.bridge.PipePipeBridge;
import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.engine.LocalSnapshotData;
import org.hackertube.pipepipe.engine.LocalSnapshotStore;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistDetail;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistSummary;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates periodic non-blocking download and indexing of {@code hackertube-data.zip}, browse
 * areas, and curated playlists so PipePipe keeps working offline even if {@code hackertube.org}
 * becomes unavailable.
 */
public final class SnapshotSyncManager {

    private static final AtomicBoolean SYNC_IN_PROGRESS = new AtomicBoolean(false);

    private final HackerTubeApiClient apiClient;
    private final LocalSnapshotStore store;

    public SnapshotSyncManager(final HackerTubeApiClient apiClient, final LocalSnapshotStore store) {
        this.apiClient = apiClient;
        this.store = store;
    }

    public void maybeTriggerBackgroundSync(final HackerTubePrefs prefs) {
        if (prefs.isOfflineFallbackMode()) {
            return;
        }
        final int hours = prefs.getAutoSyncHours();
        if (hours <= 0) {
            return;
        }
        // Only run background zip download automatically inside Android PipePipe host
        if (PipePipeBridge.getAppContext() == null) {
            return;
        }
        final long lastSync = store.getLastSyncMillis();
        final long intervalMs = hours * 3600_000L;
        if (lastSync > 0 && (System.currentTimeMillis() - lastSync) < intervalMs) {
            return;
        }
        if (!SYNC_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }

        final String baseUrl = prefs.getBaseUrl();
        final Thread t = new Thread(() -> {
            try {
                syncNow(baseUrl);
            } catch (final Throwable ignored) {
                // Background cache warmup should never break interactive UI calls
            } finally {
                SYNC_IN_PROGRESS.set(false);
            }
        }, "HackerTube-SnapshotSync");
        t.setDaemon(true);
        t.start();
    }

    public void syncNow(final String baseUrl) throws Exception {
        final JsonObject meta = apiClient.fetchExportMeta(baseUrl);
        final String generated = meta.getString("generated", "");
        final JsonObject zipObj = meta.getObject("zip");
        final String zipName = zipObj != null ? zipObj.getString("name", "hackertube-data.zip")
                : "hackertube-data.zip";

        final String existingGenerated = store.getExportGeneratedDate();
        final boolean needZip = store.getSnapshot() == null
                || generated.isEmpty()
                || !generated.equals(existingGenerated);

        if (needZip) {
            final File zipFile = new File(PipePipeBridge.getStorageDir(), "hackertube-data.zip");
            try {
                apiClient.downloadExportZip(baseUrl, "/download/" + zipName, zipFile);
                final LocalSnapshotData data = LocalSnapshotData.fromExportZip(zipFile);
                store.saveExportSnapshot(data, generated);
            } finally {
                if (zipFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    zipFile.delete();
                }
            }
        }

        // Refresh live browse areas and the curated playlists (excluded from hackertube-data.zip)
        final List<AreaEntry> areas = apiClient.fetchBrowseAreas(baseUrl);
        store.saveBrowseAreas(areas);

        final List<PlaylistSummary> summaries = apiClient.fetchPlaylists(baseUrl);
        final List<PlaylistDetail> details = new ArrayList<>(summaries.size());
        for (final PlaylistSummary s : summaries) {
            if (!s.slug.isEmpty()) {
                try {
                    details.add(apiClient.fetchPlaylistDetail(baseUrl, s.slug));
                } catch (final Exception ignored) {
                }
            }
        }
        store.savePlaylists(summaries, details);
    }
}
