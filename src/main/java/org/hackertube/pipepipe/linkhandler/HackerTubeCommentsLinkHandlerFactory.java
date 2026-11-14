package org.hackertube.pipepipe.linkhandler;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;

/**
 * Comments link handler resolving HackerTube HN discussion mentions for a video.
 */
public final class HackerTubeCommentsLinkHandlerFactory extends ListLinkHandlerFactory {

    private final HackerTubeStreamLinkHandlerFactory streamLH;

    public HackerTubeCommentsLinkHandlerFactory(final HackerTubePrefs prefs) {
        this.streamLH = new HackerTubeStreamLinkHandlerFactory(prefs);
    }

    @Override
    public String getId(final String url) throws ParsingException {
        return streamLH.getId(url);
    }

    @Override
    public String getUrl(final String id,
                         final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) throws ParsingException {
        return streamLH.getUrl(id) + "#comments";
    }

    @Override
    public String getUrl(final String id,
                         final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter,
                         final String baseUrl) throws ParsingException {
        return streamLH.getUrl(id, baseUrl) + "#comments";
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        return streamLH.onAcceptUrl(url);
    }
}
