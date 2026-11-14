package org.hackertube.pipepipe;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.hackertube.pipepipe.bridge.PipePipeBridge;
import org.hackertube.pipepipe.bridge.PipePipeBridge.PersonalizationSignals;
import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.extractors.HackerTubeCommentsExtractor;
import org.hackertube.pipepipe.model.HackerTubeModels.MentionRecord;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.plugin.PluginPreferences;
import org.schabi.newpipe.extractor.stream.Description;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommentsAndSignalFilterTest {

    @Test
    void commentDescriptionPlacesBareHnUrlFirstForLinkifyCompatibility() throws Exception {
        final JsonObject raw = JsonParser.object().from(
                "{\"id\":42001,\"author\":\"patio11\",\"ym\":\"2024-05\","
                        + "\"story\":\"Ask HN: Best talks\",\"stance\":\"strong_rec\","
                        + "\"why\":\"Essential talk\",\"text\":\"Must watch all the way through.\"}");
        final MentionRecord mention = MentionRecord.fromJson(raw, "vidTest12345");
        final Description desc = HackerTubeCommentsExtractor.buildCommentDescription(mention);

        assertEquals(Description.HTML, desc.getType());
        assertTrue(desc.getContent().startsWith("<p>HN: https://news.ycombinator.com/item?id=42001</p>"));
        assertFalse(desc.getContent().contains("<a href="));
        assertTrue(desc.getContent().contains("<b>Why:</b> Essential talk"));
        assertTrue(desc.getContent().contains("Must watch all the way through."));
    }

    @Test
    void commentDescriptionHandlesMissingHnUrlCleanly() throws Exception {
        final JsonObject raw = JsonParser.object().from(
                "{\"author\":\"user1\",\"ym\":\"2024-05\",\"quote\":\"Short quote\"}");
        final MentionRecord mention = MentionRecord.fromJson(raw, "vidTest12345");
        final Description desc = HackerTubeCommentsExtractor.buildCommentDescription(mention);

        assertFalse(desc.getContent().contains("HN:"));
        assertTrue(desc.getContent().startsWith("<blockquote>"));
    }

    @Test
    void buildSignalsIgnoresStarterPlaceholdersAndEnforcesDislikeWins() {
        final Set<String> seen = new LinkedHashSet<>(Arrays.asList("vidSeen0001", "dQw4w9WgXcQ"));
        final Set<String> rawLikes = new LinkedHashSet<>(Arrays.asList(
                PipePipeBridge.SEED_LIKES_VIDEO_ID, "vidRealLike", "vidShared01"));
        final Set<String> rawDislikes = new LinkedHashSet<>(Arrays.asList(
                PipePipeBridge.SEED_DISLIKES_VIDEO_ID, "vidRealHate", "vidShared01"));

        final PersonalizationSignals signals = PipePipeBridge.buildSignals(seen, rawLikes, rawDislikes);
        assertEquals(Collections.singletonList("vidRealLike"), signals.likes);
        assertEquals(Arrays.asList("vidRealHate", "vidShared01"), signals.dislikes);
        assertEquals(Arrays.asList("vidSeen0001", "dQw4w9WgXcQ"), signals.seen);
    }

    @Test
    void starterPlaylistSeedFlagPersistsOnce() {
        final StubPluginPreferences stub = new StubPluginPreferences();
        final HackerTubePrefs prefs = new HackerTubePrefs(stub);
        assertFalse(prefs.isStarterPlaylistsSeeded());
        prefs.markStarterPlaylistsSeeded();
        assertTrue(prefs.isStarterPlaylistsSeeded());
    }

    @Test
    void seedsMissingStarterPlaylistsWithoutOverwritingExistingOnes() {
        final FakeRoomDatabase roomDb = new FakeRoomDatabase();
        assertTrue(PipePipeBridge.seedStarterPlaylistsInRoomDb(
                roomDb, 42, "https://hackertube.org", true));
        assertEquals(1, roomDb.writableDb.beginTxCalls);
        assertEquals(1, roomDb.writableDb.commitTxCalls);
        assertEquals(1, roomDb.writableDb.endTxCalls);
        assertEquals(1, roomDb.tracker.refreshCalls);

        assertEquals(2, roomDb.writableDb.playlistsByName.size());
        assertTrue(roomDb.writableDb.playlistsByName.containsKey("hackertube likes"));
        assertTrue(roomDb.writableDb.playlistsByName.containsKey("hackertube dislikes"));
        assertEquals(
                "https://hackertube.org/watch/dQw4w9WgXcQ",
                roomDb.writableDb.streamsByKey.get("42|https://hackertube.org/watch/dQw4w9WgXcQ").url);
        assertEquals(
                "https://i.ytimg.com/vi/XqZsoesa55w/hq2.jpg",
                roomDb.writableDb.streamsByKey.get("42|https://hackertube.org/watch/XqZsoesa55w").thumbUrl);
        assertEquals(2, roomDb.writableDb.joins.size());

        // Second pass skips both existing playlists cleanly
        assertTrue(PipePipeBridge.seedStarterPlaylistsInRoomDb(
                roomDb, 42, "https://hackertube.org", true));
        assertEquals(2, roomDb.writableDb.playlistsByName.size());
        assertEquals(1, roomDb.tracker.refreshCalls);
    }

    @Test
    void preservesAlreadyExistingPlaylistAndReusesExistingStream() {
        final FakeRoomDatabase roomDb = new FakeRoomDatabase();
        roomDb.writableDb.playlistsByName.put("hackertube likes", 7L);
        roomDb.writableDb.streamsByKey.put(
                "42|https://hackertube.org/watch/XqZsoesa55w",
                new StreamRow(99L, "https://hackertube.org/watch/XqZsoesa55w", "existing"));

        assertTrue(PipePipeBridge.seedStarterPlaylistsInRoomDb(
                roomDb, 42, "https://hackertube.org", true));
        assertEquals(7L, roomDb.writableDb.playlistsByName.get("hackertube likes"));
        assertTrue(roomDb.writableDb.playlistsByName.containsKey("hackertube dislikes"));
        assertEquals(Collections.singletonList("100:99:0"), roomDb.writableDb.joins);
    }

    public static final class FakeRoomDatabase {
        final FakeOpenHelper openHelper = new FakeOpenHelper();
        final FakeSupportDb writableDb = openHelper.db;
        final FakeInvalidationTracker tracker = new FakeInvalidationTracker();

        public FakeOpenHelper getOpenHelper() {
            return openHelper;
        }

        public FakeInvalidationTracker getInvalidationTracker() {
            return tracker;
        }
    }

    public static final class FakeOpenHelper {
        final FakeSupportDb db = new FakeSupportDb();

        public FakeSupportDb getWritableDatabase() {
            return db;
        }
    }

    public static final class FakeInvalidationTracker {
        int refreshCalls;

        public void refreshVersionsAsync() {
            refreshCalls++;
        }
    }

    private static final class StreamRow {
        final long uid;
        final String url;
        final String thumbUrl;

        StreamRow(final long uid, final String url, final String thumbUrl) {
            this.uid = uid;
            this.url = url;
            this.thumbUrl = thumbUrl;
        }
    }

    public static final class FakeCursor {
        private final Long value;
        private boolean moved;

        FakeCursor(@Nullable final Long value) {
            this.value = value;
        }

        public boolean moveToFirst() {
            if (value != null && !moved) {
                moved = true;
                return true;
            }
            return false;
        }

        public long getLong(final int columnIndex) {
            return value != null && columnIndex == 0 ? value : 0L;
        }

        public void close() {
        }
    }

    @SuppressWarnings("unused")
    public static final class FakeSupportDb {
        long nextPlaylistUid = 100L;
        long nextStreamUid = 200L;
        int beginTxCalls;
        int commitTxCalls;
        int endTxCalls;
        final Map<String, Long> playlistsByName = new LinkedHashMap<>();
        final Map<String, StreamRow> streamsByKey = new LinkedHashMap<>();
        final List<String> joins = new ArrayList<>();

        public void beginTransaction() {
            beginTxCalls++;
        }

        public void setTransactionSuccessful() {
            commitTxCalls++;
        }

        public void endTransaction() {
            endTxCalls++;
        }

        public FakeCursor query(final String sql, final Object[] bindArgs) {
            if (sql.contains("FROM playlists")) {
                final String norm = String.valueOf(bindArgs[0]);
                return new FakeCursor(playlistsByName.get(norm));
            }
            if (sql.contains("FROM streams")) {
                final String key = bindArgs[0] + "|" + bindArgs[1];
                final StreamRow row = streamsByKey.get(key);
                return new FakeCursor(row == null ? null : row.uid);
            }
            return new FakeCursor(null);
        }

        public void execSQL(final String sql, final Object[] bindArgs) {
            if (sql.startsWith("INSERT OR IGNORE INTO streams")) {
                final String key = bindArgs[0] + "|" + bindArgs[1];
                streamsByKey.putIfAbsent(
                        key,
                        new StreamRow(nextStreamUid++, String.valueOf(bindArgs[1]), String.valueOf(bindArgs[6])));
            } else if (sql.startsWith("INSERT INTO playlists")) {
                final String name = String.valueOf(bindArgs[0]).trim().toLowerCase();
                playlistsByName.put(name, nextPlaylistUid++);
            } else if (sql.startsWith("INSERT INTO playlist_stream_join")) {
                joins.add(bindArgs[0] + ":" + bindArgs[1] + ":0");
            }
        }
    }

    private static final class StubPluginPreferences implements PluginPreferences {
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
