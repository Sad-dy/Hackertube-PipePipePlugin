package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemsCollector;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * Kiosk displaying HackerTube's 16 Topic Areas as navigable channels; opening an Area reveals its
 * Videos, Category playlists, and In-Area Search tabs.
 */
public final class HackerTubeCategoriesKioskExtractor extends KioskExtractor<ChannelInfoItem> {

    private final HackerTubeRepository repository;

    public HackerTubeCategoriesKioskExtractor(final StreamingService service,
                                              final ListLinkHandler linkHandler,
                                              final String kioskId,
                                              final HackerTubeRepository repository) {
        super(service, linkHandler, kioskId);
        this.repository = repository;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) {
        // Loaded dynamically in getInitialPage()
    }

    @Nonnull
    @Override
    public String getName() {
        return "Categories";
    }

    @Nonnull
    @Override
    public InfoItemsPage<ChannelInfoItem> getInitialPage() {
        final List<AreaEntry> areas = repository.getAreas();
        final String baseUrl = repository.getPrefs().getBaseUrl();
        final ChannelInfoItemsCollector collector = new ChannelInfoItemsCollector(getServiceId());
        for (final AreaEntry area : areas) {
            collector.commit(toAreaChannelItem(baseUrl, area));
        }
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    public InfoItemsPage<ChannelInfoItem> getPage(final Page page) {
        return InfoItemsPage.emptyPage();
    }

    public static ChannelInfoItemExtractor toAreaChannelItem(final String baseUrl, final AreaEntry area) {
        final String areaUrl = baseUrl + "/a/" + HackerTubeUrlUtil.encode(area.id);
        final StringBuilder desc = new StringBuilder();
        for (int i = 0; i < Math.min(5, area.categories.size()); i++) {
            final CategoryRef c = area.categories.get(i);
            if (i > 0) {
                desc.append(" · ");
            }
            desc.append(c.name);
        }
        final String summaryDesc = desc.toString();

        return new ChannelInfoItemExtractor() {
            @Override
            public String getDescription() {
                return summaryDesc;
            }

            @Override
            public long getSubscriberCount() {
                return -1L;
            }

            @Override
            public long getStreamCount() {
                return area.count > 0 ? area.count : -1L;
            }

            @Override
            public boolean isVerified() {
                return false;
            }

            @Override
            public String getName() {
                return area.name;
            }

            @Override
            public String getUrl() {
                return areaUrl;
            }

            @Override
            public String getThumbnailUrl() {
                return "";
            }
        };
    }
}
