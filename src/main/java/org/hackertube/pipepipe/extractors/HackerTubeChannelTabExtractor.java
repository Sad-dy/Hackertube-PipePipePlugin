package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.api.HackerTubeApiClient.ListParams;
import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.linkhandler.HackerTubeChannelLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeChannelTabLinkHandlerFactory;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

/**
 * Handles {@link ChannelTabs#VIDEOS}, {@link ChannelTabs#PLAYLISTS} (Topic Categories under an Area),
 * and {@link ChannelTabs#SEARCH} (scoped to an Area or Creator channel).
 */
public final class HackerTubeChannelTabExtractor extends ChannelTabExtractor {

    private final HackerTubeRepository repository;

    public HackerTubeChannelTabExtractor(final StreamingService service,
                                         final ListLinkHandler linkHandler,
                                         final HackerTubeRepository repository) {
        super(service, linkHandler);
        this.repository = repository;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) {
        // Loaded lazily in getInitialPage() / getPage()
    }

    @Nonnull
    @Override
    public InfoItemsPage<InfoItem> getInitialPage() throws IOException, ExtractionException {
        return fetchOffset(0);
    }

    @Override
    public InfoItemsPage<InfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        return fetchOffset(HackerTubeItemConverter.parseOffset(page));
    }

    private InfoItemsPage<InfoItem> fetchOffset(final int offset)
            throws IOException, ParsingException {
        final String id = getId();
        final String target = HackerTubeChannelLinkHandlerFactory.unwrapTarget(id);
        final boolean isArea = HackerTubeChannelLinkHandlerFactory.isAreaId(id);
        final String tab = resolveTab();
        final String baseUrl = repository.getPrefs().getBaseUrl();

        if (ChannelTabs.PLAYLISTS.equals(tab)) {
            final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
            if (isArea) {
                final AreaEntry area = repository.getArea(target);
                if (area != null) {
                    for (final CategoryRef c : area.categories) {
                        collector.commit(
                                HackerTubeItemConverter.toCategoryPlaylistItemExtractor(baseUrl, c));
                    }
                }
            }
            return new InfoItemsPage<>(collector, null);
        }

        final ListParams params = new ListParams();
        if (isArea) {
            params.area = target;
        } else {
            params.channel = target;
        }
        params.sort = "score";

        final int pageSize = HackerTubeRepository.DEFAULT_PAGE_SIZE;
        final List<VideoRecord> videos;
        if (ChannelTabs.SEARCH.equals(tab)) {
            final String query = HackerTubeChannelTabLinkHandlerFactory.getSearchQueryFromUrl(
                    getOriginalUrl());
            if (query.isEmpty() || "*".equals(query.trim())) {
                videos = repository.getList(params, false, offset, pageSize);
            } else {
                videos = repository.search(query, params, offset, pageSize).results;
            }
        } else {
            videos = repository.getList(params, false, offset, pageSize);
        }

        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
        HackerTubeItemConverter.addStreamsToMultiCollector(collector, repository.getPrefs(), videos);
        final Page nextPage = videos.size() >= pageSize
                ? HackerTubeItemConverter.buildOffsetPage(getUrl(), offset + pageSize)
                : null;
        return new InfoItemsPage<>(collector, nextPage);
    }

    private String resolveTab() throws ParsingException {
        final List<FilterItem> filters = getLinkHandler().getContentFilters();
        if (filters != null && !filters.isEmpty() && filters.get(0) != null) {
            return filters.get(0).getName();
        }
        final String orig = getOriginalUrl();
        if (orig != null) {
            if (orig.contains("/playlists")) {
                return ChannelTabs.PLAYLISTS;
            }
            if (orig.contains("/search") || orig.contains("?query=")) {
                return ChannelTabs.SEARCH;
            }
        }
        return ChannelTabs.VIDEOS;
    }
}
