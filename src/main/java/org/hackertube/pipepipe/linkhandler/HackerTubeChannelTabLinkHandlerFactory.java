package org.hackertube.pipepipe.linkhandler;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Link handler for HackerTube Area tabs ({@link ChannelTabs#VIDEOS},
 * {@link ChannelTabs#PLAYLISTS}, {@link ChannelTabs#SEARCH}) and Creator channel tabs.
 *
 * <p>Uses path suffixes ({@code /videos}, {@code /playlists}, {@code /search}) with no query
 * string so PipePipe's {@code SearchFragment} can append {@code ?query=...} cleanly.</p>
 */
public final class HackerTubeChannelTabLinkHandlerFactory extends ListLinkHandlerFactory {

    private final HackerTubeChannelLinkHandlerFactory channelLH;

    public HackerTubeChannelTabLinkHandlerFactory(final HackerTubePrefs prefs) {
        this.channelLH = new HackerTubeChannelLinkHandlerFactory(prefs);
    }

    @Override
    public String getId(final String url) throws ParsingException {
        return channelLH.getId(url);
    }

    @Override
    public String getUrl(final String id,
                         final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) throws ParsingException {
        return channelLH.getUrl(id, contentFilter, sortFilter) + getSuffix(contentFilter);
    }

    @Override
    public String getUrl(final String id,
                         final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter,
                         final String baseUrl) throws ParsingException {
        return channelLH.getUrl(id, contentFilter, sortFilter, baseUrl) + getSuffix(contentFilter);
    }

    @Override
    public ListLinkHandler fromUrl(final String url, final String baseUrl) throws ParsingException {
        if (url == null) {
            throw new IllegalArgumentException("url may not be null");
        }
        final String path = HackerTubeUrlUtil.parsePath(url);
        final String query = getSearchQueryFromUrl(url);
        final String tab;
        if (!query.isEmpty() || (path != null && path.endsWith("/search"))) {
            tab = ChannelTabs.SEARCH;
        } else if (path != null && path.endsWith("/playlists")) {
            tab = ChannelTabs.PLAYLISTS;
        } else {
            tab = ChannelTabs.VIDEOS;
        }
        final String id = getId(url);
        final List<FilterItem> contentFilters = Collections.singletonList(
                new FilterItem(Filter.ITEM_IDENTIFIER_UNKNOWN, tab));
        String cleanUrl = getUrl(id, contentFilters, null, baseUrl);
        if (ChannelTabs.SEARCH.equals(tab) && !query.isEmpty()) {
            cleanUrl = cleanUrl + "?query=" + HackerTubeUrlUtil.encode(query);
        }
        return new ListLinkHandler(url, cleanUrl, id, contentFilters, null);
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        return channelLH.onAcceptUrl(url);
    }

    public static String getSearchQueryFromUrl(@Nullable final String url) {
        final String rawQuery = HackerTubeUrlUtil.parseQuery(url);
        final String q = HackerTubeUrlUtil.extractQueryParam(rawQuery, "query");
        return q == null ? "" : q;
    }

    private static String getSuffix(@Nullable final List<FilterItem> contentFilter) {
        if (contentFilter == null || contentFilter.isEmpty() || contentFilter.get(0) == null) {
            return "/videos";
        }
        final String tab = contentFilter.get(0).getName();
        if (ChannelTabs.PLAYLISTS.equals(tab)) {
            return "/playlists";
        }
        if (ChannelTabs.SEARCH.equals(tab)) {
            return "/search";
        }
        return "/videos";
    }
}
