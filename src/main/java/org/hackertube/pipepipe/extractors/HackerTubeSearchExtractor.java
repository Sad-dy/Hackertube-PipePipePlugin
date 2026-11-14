package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.api.HackerTubeApiClient.ListParams;
import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistSummary;
import org.hackertube.pipepipe.model.HackerTubeModels.SearchResponse;
import org.hackertube.pipepipe.search.HackerTubeSearchFilters;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.search.SearchExtractor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Search extractor supporting hybrid HackerTube search, wildcard {@code *} full-catalog filtered
 * browse, Topic Areas/Creators ({@code channels}), and Curated/Category collections
 * ({@code playlists}).
 */
public final class HackerTubeSearchExtractor extends SearchExtractor {

    private final HackerTubeRepository repository;
    @Nullable private InfoItemsPage<InfoItem> initialPageCache;
    @Nullable private String weakSuggestion;

    public HackerTubeSearchExtractor(final StreamingService service,
                                     final SearchQueryHandler linkHandler,
                                     final HackerTubeRepository repository) {
        super(service, linkHandler);
        this.repository = repository;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        initialPageCache = fetchAtOffset(0);
    }

    @Nonnull
    @Override
    public String getSearchSuggestion() {
        return weakSuggestion == null ? "" : weakSuggestion;
    }

    @Override
    public boolean isCorrectedSearch() {
        return false;
    }

    @Nonnull
    @Override
    public InfoItemsPage<InfoItem> getInitialPageInternal() throws IOException, ExtractionException {
        if (initialPageCache != null) {
            return initialPageCache;
        }
        return fetchAtOffset(0);
    }

    @Override
    public InfoItemsPage<InfoItem> getPageInternal(final Page page)
            throws IOException, ExtractionException {
        return fetchAtOffset(HackerTubeItemConverter.parseOffset(page));
    }

    private InfoItemsPage<InfoItem> fetchAtOffset(final int offset)
            throws IOException, ParsingException {
        final String query = getSearchString();
        final String rawUrlQuery = HackerTubeUrlUtil.parseQuery(getUrl());
        final String cf = HackerTubeSearchFilters.resolveContentFilter(
                getLinkHandler().getContentFilters(), rawUrlQuery);
        final ListParams params = HackerTubeSearchFilters.resolveListParams(
                getLinkHandler().getSortFilter(), rawUrlQuery);
        final HackerTubePrefs prefs = repository.getPrefs();
        final String baseUrl = prefs.getBaseUrl();
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());

        if (HackerTubeSearchFilters.CONTENT_CHANNELS.equals(cf)) {
            if (offset == 0) {
                collectMatchingChannels(collector, query, baseUrl, 50);
            }
            return new InfoItemsPage<>(collector, null);
        }

        if (HackerTubeSearchFilters.CONTENT_PLAYLISTS.equals(cf)) {
            if (offset == 0) {
                collectMatchingPlaylists(collector, query, prefs, 60);
            }
            return new InfoItemsPage<>(collector, null);
        }

        final boolean unfilteredAll = HackerTubeSearchFilters.CONTENT_ALL.equals(cf)
                && params.hasOnlyDefaultSort()
                && offset == 0
                && !isWildcardOrBlank(query);
        if (unfilteredAll) {
            collectMatchingPlaylists(collector, query, prefs, 2);
            collectMatchingAreasOnly(collector, query, baseUrl, 1);
        }

        final int pageSize = HackerTubeRepository.DEFAULT_PAGE_SIZE;
        final SearchResponse resp = repository.search(query, params, offset, pageSize);
        if (offset == 0 && resp.weak && !isWildcardOrBlank(query)) {
            weakSuggestion = findWeakSuggestion(query);
        }
        HackerTubeItemConverter.addStreamsToMultiCollector(collector, prefs, resp.results);
        final Page nextPage = resp.results.size() >= pageSize
                ? HackerTubeItemConverter.buildOffsetPage(getUrl(), offset + pageSize)
                : null;
        return new InfoItemsPage<>(collector, nextPage);
    }

    private void collectMatchingChannels(final MultiInfoItemsCollector collector,
                                         final String query,
                                         final String baseUrl,
                                         final int maxItems) {
        int added = collectMatchingAreasOnly(collector, query, baseUrl, maxItems);
        if (added < maxItems) {
            final List<String> creators = repository.getCachedChannelsMatching(
                    query, maxItems - added);
            for (final String creator : creators) {
                collector.commit(HackerTubeItemConverter.toCreatorChannelItemExtractor(
                        baseUrl, creator, -1L));
            }
        }
    }

    private int collectMatchingAreasOnly(final MultiInfoItemsCollector collector,
                                         final String query,
                                         final String baseUrl,
                                         final int maxItems) {
        final String qLower = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        final boolean all = qLower.isEmpty() || "*".equals(qLower);
        int count = 0;
        for (final AreaEntry area : repository.getAreas()) {
            if (count >= maxItems) {
                break;
            }
            if (all || matchesQuery(qLower, area.name + " " + area.id)) {
                collector.commit(HackerTubeCategoriesKioskExtractor.toAreaChannelItem(baseUrl, area));
                count++;
            }
        }
        return count;
    }

    private void collectMatchingPlaylists(final MultiInfoItemsCollector collector,
                                          final String query,
                                          final HackerTubePrefs prefs,
                                          final int maxItems) {
        final String qLower = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        final boolean all = qLower.isEmpty() || "*".equals(qLower);
        int count = 0;
        List<PlaylistSummary> curated = Collections.emptyList();
        try {
            curated = repository.getPlaylists();
        } catch (final IOException ignored) {
        }
        for (final PlaylistSummary p : curated) {
            if (count >= maxItems) {
                return;
            }
            if (all || matchesQuery(qLower, p.title + " " + p.pitch)) {
                collector.commit(HackerTubePlaylistsKioskExtractor.toPlaylistItemExtractor(prefs, p));
                count++;
            }
        }
        for (final AreaEntry area : repository.getAreas()) {
            for (final CategoryRef c : area.categories) {
                if (count >= maxItems) {
                    return;
                }
                if (all || matchesQuery(qLower, c.name + " " + c.description + " " + c.areaName)) {
                    collector.commit(HackerTubeItemConverter.toCategoryPlaylistItemExtractor(
                            prefs.getBaseUrl(), c));
                    count++;
                }
            }
        }
    }

    @Nullable
    private String findWeakSuggestion(final String query) {
        final String[] tokens = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        for (final AreaEntry area : repository.getCachedAreasOnly()) {
            for (final CategoryRef c : area.categories) {
                final String low = c.name.toLowerCase(Locale.ROOT);
                for (final String t : tokens) {
                    if (t.length() >= 3 && low.contains(t)) {
                        return c.name;
                    }
                }
            }
        }
        return "*";
    }

    private static boolean matchesQuery(final String qLower, final String text) {
        final String hay = text.toLowerCase(Locale.ROOT);
        if (hay.contains(qLower)) {
            return true;
        }
        for (final String tok : qLower.split("\\s+")) {
            if (tok.length() >= 2 && !hay.contains(tok)) {
                return false;
            }
        }
        return !qLower.isEmpty();
    }

    private static boolean isWildcardOrBlank(@Nullable final String query) {
        if (query == null) {
            return true;
        }
        final String t = query.trim();
        return t.isEmpty() || "*".equals(t);
    }
}
