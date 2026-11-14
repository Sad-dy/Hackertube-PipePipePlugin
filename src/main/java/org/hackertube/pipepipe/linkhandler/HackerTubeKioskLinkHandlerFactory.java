package org.hackertube.pipepipe.linkhandler;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles HackerTube's 7 Kiosk routes.
 */
public final class HackerTubeKioskLinkHandlerFactory extends ListLinkHandlerFactory {

    public static final String KIOSK_HOME = "home";
    public static final String KIOSK_LONGS = "longs";
    public static final String KIOSK_CATEGORIES = "categories";
    public static final String KIOSK_PLAYLISTS = "playlists";
    public static final String KIOSK_HIDDEN_GEMS = "hidden_gems";
    public static final String KIOSK_RECENTLY_RECOMMENDED = "recently_recommended";
    public static final String KIOSK_TOP_RANKED = "top_ranked";

    private static final Map<String, String> PATH_TO_ID;
    private static final Map<String, String> ID_TO_PATH;

    static {
        final Map<String, String> p2i = new LinkedHashMap<>();
        p2i.put("/feed", KIOSK_HOME);
        p2i.put("/", KIOSK_HOME);
        p2i.put("/longs", KIOSK_LONGS);
        p2i.put("/browse", KIOSK_CATEGORIES);
        p2i.put("/playlists", KIOSK_PLAYLISTS);
        p2i.put("/gems", KIOSK_HIDDEN_GEMS);
        p2i.put("/recent", KIOSK_RECENTLY_RECOMMENDED);
        p2i.put("/top", KIOSK_TOP_RANKED);
        PATH_TO_ID = Collections.unmodifiableMap(p2i);

        final Map<String, String> i2p = new LinkedHashMap<>();
        i2p.put(KIOSK_HOME, "/feed");
        i2p.put(KIOSK_LONGS, "/longs");
        i2p.put(KIOSK_CATEGORIES, "/browse");
        i2p.put(KIOSK_PLAYLISTS, "/playlists");
        i2p.put(KIOSK_HIDDEN_GEMS, "/gems");
        i2p.put(KIOSK_RECENTLY_RECOMMENDED, "/recent");
        i2p.put(KIOSK_TOP_RANKED, "/top");
        ID_TO_PATH = Collections.unmodifiableMap(i2p);
    }

    private final HackerTubePrefs prefs;

    public HackerTubeKioskLinkHandlerFactory(final HackerTubePrefs prefs) {
        this.prefs = prefs;
    }

    @Override
    public String getId(final String url) throws ParsingException {
        final String path = normalizePath(HackerTubeUrlUtil.parsePath(url));
        final String id = PATH_TO_ID.get(path);
        if (id != null) {
            return id;
        }
        throw new ParsingException("Unknown HackerTube kiosk URL: " + url);
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
        final String path = ID_TO_PATH.get(id);
        if (path == null) {
            throw new ParsingException("Unknown kiosk id: " + id);
        }
        final String effectiveBase = (baseUrl == null || baseUrl.isEmpty())
                ? prefs.getBaseUrl() : HackerTubeUrlUtil.stripTrailingSlash(baseUrl);
        return effectiveBase + path;
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        if (!HackerTubeUrlUtil.isHackerTubeHost(url, prefs.getBaseUrl())) {
            return false;
        }
        final String path = normalizePath(HackerTubeUrlUtil.parsePath(url));
        return PATH_TO_ID.containsKey(path);
    }

    private static String normalizePath(final String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
