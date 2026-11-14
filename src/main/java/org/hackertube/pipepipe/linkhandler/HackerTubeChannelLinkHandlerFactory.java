package org.hackertube.pipepipe.linkhandler;

import org.hackertube.pipepipe.config.HackerTubeAreaCatalog;
import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;

/**
 * Handles both Topic Area URLs ({@code /a/<areaId>}) with prefix {@code area:} and YouTube
 * creator Channel URLs ({@code /channel/<encodedName>}) with prefix {@code channel:}.
 */
public final class HackerTubeChannelLinkHandlerFactory extends ListLinkHandlerFactory {

    public static final String PREFIX_AREA = "area:";
    public static final String PREFIX_CHANNEL = "channel:";

    private final HackerTubePrefs prefs;

    public HackerTubeChannelLinkHandlerFactory(final HackerTubePrefs prefs) {
        this.prefs = prefs;
    }

    public static String areaIdFor(final String rawAreaId) {
        return PREFIX_AREA + rawAreaId;
    }

    public static String channelIdFor(final String rawChannelName) {
        return PREFIX_CHANNEL + rawChannelName;
    }

    public static boolean isAreaId(final String id) {
        return id != null && id.startsWith(PREFIX_AREA);
    }

    public static String unwrapTarget(final String id) {
        if (id == null) {
            return "";
        }
        if (id.startsWith(PREFIX_AREA)) {
            return id.substring(PREFIX_AREA.length());
        }
        if (id.startsWith(PREFIX_CHANNEL)) {
            return id.substring(PREFIX_CHANNEL.length());
        }
        return id;
    }

    @Override
    public String getId(final String url) throws ParsingException {
        final String rawPath = HackerTubeUrlUtil.parseRawPath(url);
        if (rawPath != null) {
            if (rawPath.startsWith("/a/")) {
                final String area = stripTrailingAndSubpath(rawPath.substring("/a/".length()));
                if (!area.isEmpty()) {
                    return PREFIX_AREA + HackerTubeUrlUtil.decode(area);
                }
            } else if (rawPath.startsWith("/channel/")) {
                final String ch = stripTrailingAndSubpath(rawPath.substring("/channel/".length()));
                if (!ch.isEmpty()) {
                    return PREFIX_CHANNEL + HackerTubeUrlUtil.decode(ch);
                }
            }
        }
        throw new ParsingException("Not a HackerTube channel/area URL: " + url);
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
            throw new ParsingException("Empty channel/area id");
        }
        final String effectiveBase = (baseUrl == null || baseUrl.isEmpty())
                ? prefs.getBaseUrl() : HackerTubeUrlUtil.stripTrailingSlash(baseUrl);
        if (id.startsWith(PREFIX_AREA)) {
            return effectiveBase + "/a/" + HackerTubeUrlUtil.encode(id.substring(PREFIX_AREA.length()));
        }
        if (id.startsWith(PREFIX_CHANNEL)) {
            return effectiveBase + "/channel/" + HackerTubeUrlUtil.encode(id.substring(PREFIX_CHANNEL.length()));
        }
        if (HackerTubeAreaCatalog.isKnownAreaId(id)) {
            return effectiveBase + "/a/" + HackerTubeUrlUtil.encode(id);
        }
        return effectiveBase + "/channel/" + HackerTubeUrlUtil.encode(id);
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        if (!HackerTubeUrlUtil.isHackerTubeHost(url, prefs.getBaseUrl())) {
            return false;
        }
        final String path = HackerTubeUrlUtil.parseRawPath(url);
        return path != null && (path.startsWith("/a/") || path.startsWith("/channel/"));
    }

    private static String stripTrailingAndSubpath(final String s) {
        final int slash = s.indexOf('/');
        return slash >= 0 ? s.substring(0, slash) : s;
    }
}
