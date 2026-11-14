package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistSummary;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemsCollector;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

/**
 * Kiosk displaying HackerTube's curated "Playlists by Claude" collection ({@code Start Here} and
 * {@code Deep Dive}).
 */
public final class HackerTubePlaylistsKioskExtractor extends KioskExtractor<PlaylistInfoItem> {

    private final HackerTubeRepository repository;

    public HackerTubePlaylistsKioskExtractor(final StreamingService service,
                                             final ListLinkHandler linkHandler,
                                             final String kioskId,
                                             final HackerTubeRepository repository) {
        super(service, linkHandler, kioskId);
        this.repository = repository;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) {
        // Loaded in getInitialPage()
    }

    @Nonnull
    @Override
    public String getName() {
        return "Playlists";
    }

    @Nonnull
    @Override
    public InfoItemsPage<PlaylistInfoItem> getInitialPage() throws IOException {
        final List<PlaylistSummary> playlists = repository.getPlaylists();
        final PlaylistInfoItemsCollector collector = new PlaylistInfoItemsCollector(getServiceId());
        for (final PlaylistSummary p : playlists) {
            collector.commit(toPlaylistItemExtractor(repository.getPrefs(), p));
        }
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    public InfoItemsPage<PlaylistInfoItem> getPage(final Page page) {
        return InfoItemsPage.emptyPage();
    }

    public static PlaylistInfoItemExtractor toPlaylistItemExtractor(final HackerTubePrefs prefs,
                                                                    final PlaylistSummary p) {
        final String baseUrl = prefs.getBaseUrl();
        final String url = baseUrl + "/p/" + HackerTubeUrlUtil.encode(p.slug);
        final String tierLabel = "deep".equalsIgnoreCase(p.tier) ? "Deep Dive" : "Start Here";
        final String subtitle = tierLabel + (p.minutes > 0 ? " · " + p.minutes + " min" : "");
        final String thumb = p.cover.isEmpty()
                ? ""
                : HackerTubeUrlUtil.buildThumbnailUrl(p.cover.get(0), prefs.isUseVideoFrames());

        return new PlaylistInfoItemExtractor() {
            @Override
            public String getUploaderName() {
                return subtitle;
            }

            @Override
            public long getStreamCount() {
                return p.count;
            }

            @Override
            public String getName() {
                return p.title;
            }

            @Override
            public String getUrl() {
                return url;
            }

            @Override
            public String getThumbnailUrl() {
                return thumb;
            }
        };
    }
}
