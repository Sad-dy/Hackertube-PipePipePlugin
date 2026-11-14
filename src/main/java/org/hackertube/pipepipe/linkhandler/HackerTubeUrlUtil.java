package org.hackertube.pipepipe.linkhandler;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Shared helpers for parsing/building HackerTube and YouTube URLs.
 */
public final class HackerTubeUrlUtil {

    private static final Pattern YOUTUBE_ID_EXACT = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern YOUTUBE_URL_PATTERN = Pattern.compile(
            "(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/|/v/|/watch/)([A-Za-z0-9_-]{11})");

    private HackerTubeUrlUtil() {
    }

    @Nullable
    public static String extractYouTubeId(@Nullable final String input) {
        if (input == null) {
            return null;
        }
        final String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (YOUTUBE_ID_EXACT.matcher(trimmed).matches()) {
            return trimmed;
        }
        final Matcher m = YOUTUBE_URL_PATTERN.matcher(trimmed);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    public static String canonicalWatchUrl(final String baseUrl, final String vid) {
        return stripTrailingSlash(baseUrl) + "/watch/" + encode(vid);
    }

    public static String canonicalYouTubeWatchUrl(final String vid) {
        return "https://www.youtube.com/watch?v=" + encode(vid);
    }

    public static String buildThumbnailUrl(final String vid, final boolean useVideoFrames) {
        final String file = useVideoFrames ? "hq2.jpg" : "hqdefault.jpg";
        return "https://i.ytimg.com/vi/" + encode(vid) + "/" + file;
    }

    public static boolean isHackerTubeHost(@Nullable final String url,
                                           @Nullable final String configuredBaseUrl) {
        final String host = parseHost(url);
        if (host == null) {
            return false;
        }
        if (host.equals("hackertube.org") || host.equals("www.hackertube.org")) {
            return true;
        }
        final String cfgHost = parseHost(configuredBaseUrl);
        return cfgHost != null && host.equals(cfgHost);
    }

    @Nullable
    public static String parsePath(@Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            return URI.create(url.trim()).getPath();
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    public static String parseRawPath(@Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            return URI.create(url.trim()).getRawPath();
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    public static String parseQuery(@Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            return URI.create(url.trim()).getRawQuery();
        } catch (final IllegalArgumentException e) {
            final int q = url.indexOf('?');
            return q >= 0 ? url.substring(q + 1) : null;
        }
    }

    @Nullable
    public static String extractQueryParam(@Nullable final String rawQuery, final String key) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (final String part : rawQuery.split("&")) {
            final int eq = part.indexOf('=');
            final String k = eq >= 0 ? decode(part.substring(0, eq)) : decode(part);
            if (key.equals(k)) {
                return eq >= 0 ? decode(part.substring(eq + 1)) : "";
            }
        }
        return null;
    }

    @Nullable
    private static String parseHost(@Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            final String host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public static String stripTrailingSlash(final String url) {
        if (url == null || url.isEmpty()) {
            return "https://hackertube.org";
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public static String encode(final String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (final UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String decode(final String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (final UnsupportedEncodingException | IllegalArgumentException e) {
            return value == null ? "" : value;
        }
    }
}
