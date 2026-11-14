package org.hackertube.pipepipe.linkhandler;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.search.HackerTubeSearchFilters;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;

/**
 * Builds and parses HackerTube search URLs alongside {@link HackerTubeSearchFilters}.
 */
public final class HackerTubeSearchQueryHandlerFactory extends SearchQueryHandlerFactory {

    private final HackerTubePrefs prefs;
    private final HackerTubeSearchFilters searchFilters = new HackerTubeSearchFilters();

    public HackerTubeSearchQueryHandlerFactory(final HackerTubePrefs prefs) {
        this.prefs = prefs;
    }

    @Override
    public String getUrl(final String query,
                         final List<FilterItem> selectedContentFilter,
                         final List<FilterItem> selectedSortFilter) {
        return getUrl(query, selectedContentFilter, selectedSortFilter, prefs.getBaseUrl());
    }

    @Override
    public String getUrl(final String query,
                         final List<FilterItem> selectedContentFilter,
                         final List<FilterItem> selectedSortFilter,
                         final String baseUrl) {
        searchFilters.setSelectedContentFilter(selectedContentFilter);
        searchFilters.setSelectedSortFilter(selectedSortFilter);
        final String filterQuery = searchFilters.evaluateSelectedFilters(query);
        final String effectiveBase = (baseUrl == null || baseUrl.isEmpty())
                ? prefs.getBaseUrl() : HackerTubeUrlUtil.stripTrailingSlash(baseUrl);
        final StringBuilder sb = new StringBuilder(effectiveBase)
                .append("/search?q=")
                .append(HackerTubeUrlUtil.encode(query == null ? "" : query));
        if (!filterQuery.isEmpty()) {
            sb.append('&').append(filterQuery);
        }
        return sb.toString();
    }

    @Override
    public String getSearchString(final String url) {
        final String q = HackerTubeUrlUtil.extractQueryParam(HackerTubeUrlUtil.parseQuery(url), "q");
        return q == null ? "" : q;
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        if (!HackerTubeUrlUtil.isHackerTubeHost(url, prefs.getBaseUrl())) {
            return false;
        }
        final String path = HackerTubeUrlUtil.parsePath(url);
        return "/search".equals(path);
    }

    @Override
    public Filter getAvailableContentFilter() {
        return searchFilters.getContentFilters();
    }

    @Override
    public FilterItem getFilterItem(final int filterId) {
        return searchFilters.getFilterItem(filterId);
    }

    @Override
    public Filter getAvailableSortFilter() {
        return searchFilters.getSortFilters();
    }

    @Override
    public Filter getContentFilterSortFilterVariant(final int contentFilterId) {
        return searchFilters.getContentFilterSortFilterVariant(contentFilterId);
    }
}
