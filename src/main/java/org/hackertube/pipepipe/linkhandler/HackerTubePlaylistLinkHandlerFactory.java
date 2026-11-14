package org.hackertube.pipepipe.linkhandler;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;

/**
 * Handles Curated Playlists ({@code /p/<slug>}, id {@code p:<slug>}) and Topic Categories
 * ({@code /c/<catId>}, id {@code c:<catId>}).
 */
public final class HackerTubePlaylistLinkHandlerFactory extends ListLinkHandlerFactory {

    public static final String PREFIX_PLAYLIST = "p:";
    public static final String PREFIX_CATEGORY = "c:";

    private final HackerTubePrefs prefs;

    public HackerTubePlaylistLinkHandlerFactory(final HackerTubePrefs prefs) {
        this.prefs = prefs;
    }

    public static String playlistIdFor(final String slug) {
        return PREFIX_PLAYLIST + slug;
    }

    public static String categoryIdFor(final String catId) {
        return PREFIX_CATEGORY + catId;
    }

    public static boolean isCategoryId(final String id) {
        return id != null && id.startsWith(PREFIX_CATEGORY);
    }

    public static String unwrapTarget(final String id) {
        if (id == null) {
            return "";
        }
        if (id.startsWith(PREFIX_PLAYLIST)) {
            return id.substring(PREFIX_PLAYLIST.length());
        }
        if (id.startsWith(PREFIX_CATEGORY)) {
            return id.substring(PREFIX_CATEGORY.length());
        }
        return id;
    }

    @Override
    public String getId(final String url) throws ParsingException {
        final String path = HackerTubeUrlUtil.parseRawPath(url);
        if (path != null) {
            if (path.startsWith("/p/")) {
                final String slug = stripSubpath(path.substring("/p/".length()));
                if (!slug.isEmpty()) {
                    return PREFIX_PLAYLIST + HackerTubeUrlUtil.decode(slug);
                }
            } else if (path.startsWith("/c/")) {
                final String cat = stripSubpath(path.substring("/c/".length()));
                if (!cat.isEmpty()) {
                    return PREFIX_CATEGORY + HackerTubeUrlUtil.decode(cat);
                }
            }
        }
        throw new ParsingException("Not a HackerTube playlist or category URL: " + url);
    }

    @Override
    public String getUrl(final String id,
                         final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) throws ParsingException {
        return getUrl(id, contentFilter, sortFilter, prefs.getBaseUrl());
    }

    @Override
    public String getUrl(final String id,
                         final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter,
                         final String baseUrl) throws ParsingException {
        if (id == null || id.isEmpty()) {
            throw new ParsingException("Empty playlist id");
        }
        final String effectiveBase = (baseUrl == null || baseUrl.isEmpty())
                ? prefs.getBaseUrl() : HackerTubeUrlUtil.stripTrailingSlash(baseUrl);
        if (id.startsWith(PREFIX_CATEGORY)) {
            return effectiveBase + "/c/" + HackerTubeUrlUtil.encode(id.substring(PREFIX_CATEGORY.length()));
        }
        final String slug = id.startsWith(PREFIX_PLAYLIST)
                ? id.substring(PREFIX_PLAYLIST.length()) : id;
        return effectiveBase + "/p/" + HackerTubeUrlUtil.encode(slug);
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        if (!HackerTubeUrlUtil.isHackerTubeHost(url, prefs.getBaseUrl())) {
            return false;
        }
        final String path = HackerTubeUrlUtil.parseRawPath(url);
        return path != null && (path.startsWith("/p/") || path.startsWith("/c/"));
    }

    private static String stripSubpath(final String s) {
        final int slash = s.indexOf('/');
        return slash >= 0 ? s.substring(0, slash) : s;
    }
}
