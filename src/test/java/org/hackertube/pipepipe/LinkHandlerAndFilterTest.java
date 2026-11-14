package org.hackertube.pipepipe;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.hackertube.pipepipe.api.HackerTubeApiClient.ListParams;
import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.linkhandler.HackerTubeChannelLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeChannelTabLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeCommentsLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeKioskLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubePlaylistLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeSearchQueryHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeStreamLinkHandlerFactory;
import org.hackertube.pipepipe.model.HackerTubeJsonCodec;
import org.hackertube.pipepipe.model.HackerTubeModels.MentionRecord;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;
import org.hackertube.pipepipe.search.HackerTubeSearchFilters;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.plugin.PluginPreferences;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkHandlerAndFilterTest {

    @Test
    void streamHandlerAcceptsHackerTubeAndRejectsBareYouTube() throws Exception {
        final HackerTubePrefs prefs = new HackerTubePrefs(defaultPrefs());
        final HackerTubeStreamLinkHandlerFactory streamLH = new HackerTubeStreamLinkHandlerFactory(prefs);

        assertTrue(streamLH.onAcceptUrl("https://hackertube.org/watch/dQw4w9WgXcQ"));
        assertFalse(streamLH.onAcceptUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertFalse(streamLH.onAcceptUrl("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", streamLH.getId("https://hackertube.org/watch/dQw4w9WgXcQ"));
        assertEquals("https://hackertube.org/watch/dQw4w9WgXcQ", streamLH.getUrl("dQw4w9WgXcQ"));

        final HackerTubeCommentsLinkHandlerFactory commentsLH = new HackerTubeCommentsLinkHandlerFactory(prefs);
        assertEquals("dQw4w9WgXcQ", commentsLH.getId("https://hackertube.org/watch/dQw4w9WgXcQ"));
    }

    @Test
    void channelChannelTabPlaylistAndKioskHandlersRoundtrip() throws Exception {
        final HackerTubePrefs prefs = new HackerTubePrefs(defaultPrefs());
        final HackerTubeChannelLinkHandlerFactory chLH = new HackerTubeChannelLinkHandlerFactory(prefs);
        final HackerTubeChannelTabLinkHandlerFactory tabLH = new HackerTubeChannelTabLinkHandlerFactory(prefs);
        final HackerTubePlaylistLinkHandlerFactory plLH = new HackerTubePlaylistLinkHandlerFactory(prefs);
        final HackerTubeKioskLinkHandlerFactory kioskLH = new HackerTubeKioskLinkHandlerFactory(prefs);

        assertEquals("area:systems-security", chLH.getId("https://hackertube.org/a/systems-security"));
        assertEquals("channel:Two Minute Papers",
                chLH.getId("https://hackertube.org/channel/Two%20Minute%20Papers"));

        final ListLinkHandler searchTabBase = tabLH.fromQuery(
                "area:software",
                Collections.singletonList(new FilterItem(Filter.ITEM_IDENTIFIER_UNKNOWN, ChannelTabs.SEARCH)),
                null);
        assertFalse(searchTabBase.getUrl().contains("?"));
        final ListLinkHandler searchWithQuery = tabLH.fromUrl(searchTabBase.getUrl() + "?query=rust%20async");
        assertEquals("rust async", HackerTubeChannelTabLinkHandlerFactory.getSearchQueryFromUrl(
                searchWithQuery.getOriginalUrl()));
        assertEquals(ChannelTabs.SEARCH, searchWithQuery.getContentFilters().get(0).getName());

        assertEquals("p:start-here-cs", plLH.getId("https://hackertube.org/p/start-here-cs"));
        assertEquals("c:compilers", plLH.getId("https://hackertube.org/c/compilers"));

        assertEquals("home", kioskLH.getId("https://hackertube.org/"));
        assertEquals("hidden_gems", kioskLH.getId("https://hackertube.org/gems"));
        assertEquals("top_ranked", kioskLH.getId("https://hackertube.org/top"));
    }

    @Test
    void searchQueryHandlerAndFacetFiltersRoundtrip() throws Exception {
        final HackerTubePrefs prefs = new HackerTubePrefs(defaultPrefs());
        final HackerTubeSearchQueryHandlerFactory qh = new HackerTubeSearchQueryHandlerFactory(prefs);

        final Filter cfFilter = qh.getAvailableContentFilter();
        final FilterItem videosCf = findFilterItem(cfFilter, "videos");
        final Filter sortVariant = qh.getContentFilterSortFilterVariant(videosCf.getIdentifier());

        final List<FilterItem> sortSelected = new ArrayList<>();
        sortSelected.add(findFilterItem(sortVariant, "Talk"));
        sortSelected.add(findFilterItem(sortVariant, "Hidden gems"));
        sortSelected.add(findFilterItem(sortVariant, "Advanced"));
        sortSelected.add(findFilterItem(sortVariant, "2010s"));

        final SearchQueryHandler built = qh.fromQuery(
                "*", Collections.singletonList(videosCf), sortSelected);
        final ListLinkHandler reparsed = qh.fromUrl(built.getUrl());
        assertEquals("*", built.getSearchString());
        assertEquals("*", reparsed.getId());

        final ListParams params = HackerTubeSearchFilters.resolveListParams(
                built.getSortFilter(), reparsed.getUrl().substring(reparsed.getUrl().indexOf('?') + 1));
        assertEquals("talk", params.type);
        assertTrue(params.gems);
        assertEquals("advanced", params.level);
        assertEquals("2010s", params.date);
    }

    @Test
    void dualSchemaModelsAndJsonCodecRoundtrip() throws Exception {
        final JsonObject apiMention = JsonParser.object().from(
                "{\"id\":999,\"author\":\"patio11\",\"ym\":\"2023-07\","
                        + "\"story\":\"Ask HN\",\"stance\":\"rec\",\"text\":\"Worth watching\"}");
        final MentionRecord m = MentionRecord.fromJson(apiMention, "vidTest12345");
        assertEquals(999L, m.hnCommentId);
        assertEquals("https://news.ycombinator.com/item?id=999", m.hnUrl);
        assertEquals("2023-07", m.month);
        assertEquals("Ask HN", m.storyTitle);

        final String encodedMention = HackerTubeJsonCodec.encodeMention(m);
        final MentionRecord decodedMention = MentionRecord.fromJson(
                JsonParser.object().from(encodedMention), "");
        assertEquals(m.hnCommentId, decodedMention.hnCommentId);
        assertEquals(m.vid, decodedMention.vid);

        final JsonObject rawVideo = JsonParser.object().from(
                "{\"vid\":\"vidTest12345\",\"title\":\"Codec Test\",\"channel\":\"Ch\","
                        + "\"duration_s\":600,\"score\":42.5,\"hn\":{\"recommenders\":2,\"strong\":1,"
                        + "\"first\":2020,\"last\":2024,\"stances\":{\"strong_rec\":1,\"rec\":1}}}");
        final VideoRecord v = VideoRecord.fromJson(rawVideo);
        assertEquals(1, v.hn.strongRecs);
        assertEquals(2020, v.hn.firstYear);

        final VideoRecord vRoundtrip = VideoRecord.fromJson(
                JsonParser.object().from(HackerTubeJsonCodec.encodeVideo(v)));
        assertEquals(v.vid, vRoundtrip.vid);
        assertEquals(v.score, vRoundtrip.score, 1e-6);
        assertEquals(v.hn.strongRecs, vRoundtrip.hn.strongRecs);
    }

    @Test
    void prefsParseCsvAndBaseUrlCleanly() {
        final InMemoryPluginPreferences raw = defaultPrefs();
        raw.putString("base_url", "https://mirror.example.com/");
        raw.putString("likes_playlist", "HackerTube Likes, Favorites , ");
        final HackerTubePrefs prefs = new HackerTubePrefs(raw);
        assertEquals("https://mirror.example.com", prefs.getBaseUrl());
        assertEquals(Arrays.asList("HackerTube Likes", "Favorites"), prefs.getLikesPlaylistNames());
        assertEquals(168, prefs.getAutoSyncHours());
    }

    private static FilterItem findFilterItem(final Filter filter, final String name) {
        for (final FilterGroup g : filter.getFilterGroups()) {
            for (final FilterItem item : g.filterItems) {
                if (name.equals(item.getName())) {
                    return item;
                }
            }
        }
        throw new AssertionError("FilterItem not found: " + name);
    }

    private static InMemoryPluginPreferences defaultPrefs() {
        final InMemoryPluginPreferences p = new InMemoryPluginPreferences();
        p.putString("base_url", HackerTubePrefs.DEFAULT_BASE_URL);
        p.putString("likes_playlist", HackerTubePrefs.DEFAULT_LIKES_PLAYLIST);
        p.putString("dislikes_playlist", HackerTubePrefs.DEFAULT_DISLIKES_PLAYLIST);
        p.putString("auto_sync_hours", "168");
        p.putBoolean("use_video_frames", true);
        p.putBoolean("personalize_from_pipepipe", true);
        p.putBoolean("hide_watched_in_home", true);
        p.putBoolean("hide_news_videos", true);
        p.putBoolean("hide_zero_rec_home", true);
        return p;
    }

    private static final class InMemoryPluginPreferences implements PluginPreferences {
        private final Map<String, String> strings = new HashMap<>();
        private final Map<String, Boolean> booleans = new HashMap<>();

        @Nullable
        @Override
        public String getString(@Nonnull final String key) {
            return strings.get(key);
        }

        @Override
        public boolean getBoolean(@Nonnull final String key) {
            return Boolean.TRUE.equals(booleans.get(key));
        }

        @Override
        public void putString(@Nonnull final String key, @Nullable final String value) {
            strings.put(key, value);
        }

        @Override
        public void putBoolean(@Nonnull final String key, final boolean value) {
            booleans.put(key, value);
        }

        @Override
        public void addChangeListener(@Nonnull final ChangeListener listener) {
        }
    }
}
