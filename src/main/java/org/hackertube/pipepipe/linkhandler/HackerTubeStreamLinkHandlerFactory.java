package org.hackertube.pipepipe.linkhandler;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;

/**
 * Handles HackerTube watch URLs ({@code https://hackertube.org/watch/<vid>}) while leaving bare
 * YouTube URLs assigned to the native YouTube service.
 */
public final class HackerTubeStreamLinkHandlerFactory extends LinkHandlerFactory {

    private final HackerTubePrefs prefs;

    public HackerTubeStreamLinkHandlerFactory(final HackerTubePrefs prefs) {
        this.prefs = prefs;
    }

    @Override
    public String getId(final String url) throws ParsingException {
        final String path = HackerTubeUrlUtil.parsePath(url);
        if (path != null && path.startsWith("/watch/")) {
            final String rest = path.substring("/watch/".length());
            final int slash = rest.indexOf('/');
            final String candidate = slash >= 0 ? rest.substring(0, slash) : rest;
            final String vid = HackerTubeUrlUtil.extractYouTubeId(candidate);
            if (vid != null) {
                return vid;
            }
        }
        final String direct = HackerTubeUrlUtil.extractYouTubeId(url);
        if (direct != null && HackerTubeUrlUtil.isHackerTubeHost(url, prefs.getBaseUrl())) {
            return direct;
        }
        throw new ParsingException("Not a HackerTube watch URL: " + url);
    }

    @Override
    public String getUrl(final String id) throws ParsingException {
        return getUrl(id, prefs.getBaseUrl());
    }

    @Override
    public String getUrl(final String id, final String baseUrl) throws ParsingException {
        final String vid = HackerTubeUrlUtil.extractYouTubeId(id);
        if (vid == null) {
            throw new ParsingException("Invalid video id: " + id);
        }
        final String effectiveBase = (baseUrl == null || baseUrl.isEmpty())
                ? prefs.getBaseUrl() : baseUrl;
        return HackerTubeUrlUtil.canonicalWatchUrl(effectiveBase, vid);
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        if (!HackerTubeUrlUtil.isHackerTubeHost(url, prefs.getBaseUrl())) {
            return false;
        }
        final String path = HackerTubeUrlUtil.parsePath(url);
        if (path == null || !path.startsWith("/watch/")) {
            return false;
        }
        return HackerTubeUrlUtil.extractYouTubeId(path.substring("/watch/".length())) != null;
    }
}
