package org.hackertube.pipepipe.config;

import org.schabi.newpipe.extractor.plugin.PluginPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Typed accessors over {@link PluginPreferences} with matching defaults from {@code plugin.json}.
 */
public final class HackerTubePrefs {

    public static final String DEFAULT_BASE_URL = "https://hackertube.org";
    public static final String DEFAULT_LIKES_PLAYLIST = "HackerTube Likes";
    public static final String DEFAULT_DISLIKES_PLAYLIST = "HackerTube Dislikes";
    public static final int DEFAULT_AUTO_SYNC_HOURS = 168;

    @Nullable
    private final PluginPreferences prefs;

    public HackerTubePrefs(@Nullable final PluginPreferences prefs) {
        this.prefs = prefs;
    }

    public String getBaseUrl() {
        final String raw = prefs == null
                ? DEFAULT_BASE_URL
                : prefs.getString("base_url");
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }
        return trimmed;
    }

    public boolean isDirectYouTubeMode() {
        return getBoolean("direct_youtube_mode", false);
    }

    public boolean isUseVideoFrames() {
        return getBoolean("use_video_frames", true);
    }

    public boolean isPersonalizeFromPipePipe() {
        return getBoolean("personalize_from_pipepipe", true);
    }

    public List<String> getLikesPlaylistNames() {
        return parseCsv(getString("likes_playlist", DEFAULT_LIKES_PLAYLIST));
    }

    public List<String> getDislikesPlaylistNames() {
        return parseCsv(getString("dislikes_playlist", DEFAULT_DISLIKES_PLAYLIST));
    }

    public boolean isHideWatchedInHome() {
        return getBoolean("hide_watched_in_home", true);
    }

    public boolean isOfflineFallbackMode() {
        return getBoolean("offline_fallback_mode", false);
    }

    public boolean isHideNewsVideos() {
        return getBoolean("hide_news_videos", true);
    }

    public boolean isHideZeroRecHome() {
        return getBoolean("hide_zero_rec_home", true);
    }

    public int getAutoSyncHours() {
        final String raw = getString("auto_sync_hours", String.valueOf(DEFAULT_AUTO_SYNC_HOURS));
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (final NumberFormatException ignored) {
            return DEFAULT_AUTO_SYNC_HOURS;
        }
    }

    private boolean getBoolean(final String key, final boolean def) {
        return prefs == null ? def : prefs.getBoolean(key);
    }

    private String getString(final String key, final String def) {
        if (prefs == null) {
            return def;
        }
        final String v = prefs.getString(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static List<String> parseCsv(final String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final String[] parts = csv.split(",");
        final List<String> out = new ArrayList<>(parts.length);
        for (final String part : parts) {
            final String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }
}
