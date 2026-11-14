package org.hackertube.pipepipe.engine;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import org.hackertube.pipepipe.model.HackerTubeJsonCodec;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.MentionRecord;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistDetail;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistSummary;
import org.hackertube.pipepipe.model.HackerTubeModels.RelatedRef;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Persistent local snapshot storage backed by Android's {@link SQLiteDatabase} and binary float16
 * embeddings inside PipePipe's files directory.
 */
public final class LocalSnapshotStore {

    private static final String DB_FILE = "hackertube_snapshot.db";
    private static final String EMBEDDINGS_BIN = "embeddings.bin";
    private static final String EMBEDDING_IDS_JSON = "embedding_ids.json";

    private static final String KEY_EXPORT_GENERATED = "export_generated";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_AREAS_JSON = "areas_json";
    private static final String KEY_PLAYLISTS_JSON = "playlists_json";

    private final File storageDir;
    private volatile LocalSnapshotData cachedSnapshot;

    public LocalSnapshotStore(final File storageDir) {
        this.storageDir = storageDir;
        if (!storageDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            storageDir.mkdirs();
        }
    }

    @Nullable
    public synchronized LocalSnapshotData getSnapshot() {
        if (cachedSnapshot != null && cachedSnapshot.isReady()) {
            return cachedSnapshot;
        }
        try {
            final LocalSnapshotData loaded = loadFromDisk();
            if (loaded != null && loaded.isReady()) {
                cachedSnapshot = loaded;
                return loaded;
            }
        } catch (final Throwable ignored) {
            // Outside Android or empty/corrupt local cache
        }
        return null;
    }

    public long getLastSyncMillis() {
        try (SQLiteDatabase db = openDb()) {
            final String val = readMeta(db, KEY_LAST_SYNC_MS);
            return val == null ? 0L : Long.parseLong(val);
        } catch (final Throwable ignored) {
            return 0L;
        }
    }

    @Nullable
    public String getExportGeneratedDate() {
        try (SQLiteDatabase db = openDb()) {
            return readMeta(db, KEY_EXPORT_GENERATED);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    public void saveExportSnapshot(final LocalSnapshotData snapshot,
                                   @Nullable final String generatedDate) throws IOException {
        if (snapshot.embeddings != null && !snapshot.embeddingRowByVid.isEmpty()) {
            snapshot.embeddings.saveBinaryCache(new File(storageDir, EMBEDDINGS_BIN));
            saveEmbeddingIds(snapshot.embeddingRowByVid);
        }

        try (SQLiteDatabase db = openDb()) {
            final Map<String, YtMeta> ytMeta = readAllYtMeta(db);
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM videos");
                db.execSQL("DELETE FROM mentions");

                final SQLiteStatement vStmt = db.compileStatement(
                        "INSERT OR REPLACE INTO videos(vid, rank_order, json) VALUES(?, ?, ?)");
                int rank = 0;
                for (final VideoRecord v : snapshot.videos) {
                    final VideoRecord enriched = applyYtMeta(v, ytMeta.get(v.vid));
                    vStmt.clearBindings();
                    vStmt.bindString(1, enriched.vid);
                    vStmt.bindLong(2, rank++);
                    vStmt.bindString(3, HackerTubeJsonCodec.encodeVideo(enriched));
                    vStmt.executeInsert();
                }

                final SQLiteStatement mStmt = db.compileStatement(
                        "INSERT INTO mentions(vid, seq, json) VALUES(?, ?, ?)");
                for (final Map.Entry<String, List<MentionRecord>> e : snapshot.mentionsByVid.entrySet()) {
                    int seq = 0;
                    for (final MentionRecord m : e.getValue()) {
                        mStmt.clearBindings();
                        mStmt.bindString(1, e.getKey());
                        mStmt.bindLong(2, seq++);
                        mStmt.bindString(3, HackerTubeJsonCodec.encodeMention(m));
                        mStmt.executeInsert();
                    }
                }

                if (!snapshot.areas.isEmpty()) {
                    writeMeta(db, KEY_AREAS_JSON, HackerTubeJsonCodec.encodeBrowseAreas(snapshot.areas));
                }
                if (generatedDate != null && !generatedDate.isEmpty()) {
                    writeMeta(db, KEY_EXPORT_GENERATED, generatedDate);
                }
                writeMeta(db, KEY_LAST_SYNC_MS, String.valueOf(System.currentTimeMillis()));
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        synchronized (this) {
            cachedSnapshot = null;
        }
    }

    public void saveBrowseAreas(final List<AreaEntry> areas) {
        if (areas == null || areas.isEmpty()) {
            return;
        }
        try (SQLiteDatabase db = openDb()) {
            writeMeta(db, KEY_AREAS_JSON, HackerTubeJsonCodec.encodeBrowseAreas(areas));
        } catch (final Throwable ignored) {
        }
    }

    public void savePlaylists(final List<PlaylistSummary> summaries,
                              final List<PlaylistDetail> details) {
        try (SQLiteDatabase db = openDb()) {
            db.beginTransaction();
            try {
                if (summaries != null && !summaries.isEmpty()) {
                    writeMeta(db, KEY_PLAYLISTS_JSON,
                            HackerTubeJsonCodec.encodePlaylistSummaries(summaries));
                }
                if (details != null && !details.isEmpty()) {
                    final SQLiteStatement stmt = db.compileStatement(
                            "INSERT OR REPLACE INTO playlists(slug, json) VALUES(?, ?)");
                    for (final PlaylistDetail d : details) {
                        stmt.clearBindings();
                        stmt.bindString(1, d.slug);
                        stmt.bindString(2, HackerTubeJsonCodec.encodePlaylistDetail(d));
                        stmt.executeInsert();
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (final Throwable ignored) {
        }
        synchronized (this) {
            cachedSnapshot = null;
        }
    }

    public void savePlaylistDetail(final PlaylistDetail detail) {
        if (detail == null || detail.slug.isEmpty()) {
            return;
        }
        try (SQLiteDatabase db = openDb()) {
            final SQLiteStatement stmt = db.compileStatement(
                    "INSERT OR REPLACE INTO playlists(slug, json) VALUES(?, ?)");
            stmt.bindString(1, detail.slug);
            stmt.bindString(2, HackerTubeJsonCodec.encodePlaylistDetail(detail));
            stmt.executeInsert();
        } catch (final Throwable ignored) {
        }
    }

    public void recordObservedYtMetadata(final Collection<VideoRecord> videos) {
        if (videos == null || videos.isEmpty()) {
            return;
        }
        try (SQLiteDatabase db = openDb()) {
            db.beginTransaction();
            try {
                final SQLiteStatement stmt = db.compileStatement(
                        "INSERT OR REPLACE INTO yt_meta(vid, published, duration_s, views, likes) VALUES(?, ?, ?, ?, ?)");
                for (final VideoRecord v : videos) {
                    if (v.vid.isEmpty() || (v.durationSeconds <= 0 && v.views < 0 && v.published.isEmpty())) {
                        continue;
                    }
                    stmt.clearBindings();
                    stmt.bindString(1, v.vid);
                    stmt.bindString(2, v.published);
                    stmt.bindLong(3, v.durationSeconds);
                    stmt.bindLong(4, v.views);
                    stmt.bindLong(5, v.likes);
                    stmt.executeInsert();
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (final Throwable ignored) {
        }
    }

    @Nullable
    private LocalSnapshotData loadFromDisk() throws Exception {
        final File dbFile = new File(storageDir, DB_FILE);
        if (!dbFile.exists()) {
            return null;
        }
        try (SQLiteDatabase db = openDb()) {
            final Map<String, YtMeta> ytMeta = readAllYtMeta(db);
            final List<VideoRecord> videos = new ArrayList<>(6500);
            final Map<String, List<RelatedRef>> related = new HashMap<>(4096);

            try (Cursor c = db.rawQuery("SELECT json FROM videos ORDER BY rank_order ASC", null)) {
                while (c.moveToNext()) {
                    final VideoRecord raw = VideoRecord.fromJson(JsonParser.object().from(c.getString(0)));
                    final VideoRecord v = applyYtMeta(raw, ytMeta.get(raw.vid));
                    videos.add(v);
                    if (!v.hnRelated.isEmpty()) {
                        related.put(v.vid, v.hnRelated);
                    }
                }
            }
            if (videos.isEmpty()) {
                return null;
            }

            final Map<String, List<MentionRecord>> mentions = new HashMap<>(8192);
            try (Cursor c = db.rawQuery("SELECT vid, json FROM mentions ORDER BY vid, seq ASC", null)) {
                while (c.moveToNext()) {
                    final String vid = c.getString(0);
                    final MentionRecord m = MentionRecord.fromJson(
                            JsonParser.object().from(c.getString(1)), vid);
                    mentions.computeIfAbsent(vid, k -> new ArrayList<>(4)).add(m);
                }
            }

            final List<AreaEntry> areas = new ArrayList<>();
            final String areasJson = readMeta(db, KEY_AREAS_JSON);
            if (areasJson != null && !areasJson.isEmpty()) {
                final JsonArray arr = JsonParser.array().from(areasJson);
                for (int i = 0; i < arr.size(); i++) {
                    final JsonObject ao = arr.getObject(i);
                    if (ao != null) {
                        areas.add(AreaEntry.fromJson(ao));
                    }
                }
            }

            final List<PlaylistSummary> summaries = new ArrayList<>();
            final String plJson = readMeta(db, KEY_PLAYLISTS_JSON);
            if (plJson != null && !plJson.isEmpty()) {
                final JsonArray arr = JsonParser.array().from(plJson);
                for (int i = 0; i < arr.size(); i++) {
                    final JsonObject po = arr.getObject(i);
                    if (po != null) {
                        summaries.add(PlaylistSummary.fromJson(po));
                    }
                }
            }

            final Map<String, PlaylistDetail> playlistsBySlug = new LinkedHashMap<>();
            try (Cursor c = db.rawQuery("SELECT slug, json FROM playlists", null)) {
                while (c.moveToNext()) {
                    final String slug = c.getString(0);
                    final PlaylistDetail detail = PlaylistDetail.fromJson(
                            JsonParser.object().from(c.getString(1)));
                    playlistsBySlug.put(slug, detail);
                }
            }

            NpyFloat16Reader embeddings = null;
            final Map<String, Integer> embRows = new HashMap<>(8192);
            final File binFile = new File(storageDir, EMBEDDINGS_BIN);
            final File idsFile = new File(storageDir, EMBEDDING_IDS_JSON);
            if (binFile.exists() && idsFile.exists()) {
                embeddings = NpyFloat16Reader.loadBinaryCache(binFile);
                final String idsStr = new String(Files.readAllBytes(idsFile.toPath()), StandardCharsets.UTF_8);
                LocalSnapshotData.parseEmbeddingIds(idsStr, embRows);
            }

            return new LocalSnapshotData(
                    videos, related, mentions, areas, summaries, playlistsBySlug, embRows, embeddings);
        }
    }

    private void saveEmbeddingIds(final Map<String, Integer> rowByVid) throws IOException {
        final String[] ordered = new String[rowByVid.size()];
        for (final Map.Entry<String, Integer> e : rowByVid.entrySet()) {
            final int idx = e.getValue();
            if (idx >= 0 && idx < ordered.length) {
                ordered[idx] = e.getKey();
            }
        }
        final String json = JsonWriter.string(java.util.Arrays.asList(ordered));
        final File idsFile = new File(storageDir, EMBEDDING_IDS_JSON);
        Files.write(idsFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private SQLiteDatabase openDb() {
        final SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(
                new File(storageDir, DB_FILE), null);
        db.execSQL("CREATE TABLE IF NOT EXISTS videos (vid TEXT PRIMARY KEY, rank_order INTEGER, json TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS mentions (vid TEXT, seq INTEGER, json TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mentions_vid ON mentions(vid)");
        db.execSQL("CREATE TABLE IF NOT EXISTS playlists (slug TEXT PRIMARY KEY, json TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS yt_meta (vid TEXT PRIMARY KEY, published TEXT, duration_s INTEGER, views INTEGER, likes INTEGER)");
        return db;
    }

    @Nullable
    private static String readMeta(final SQLiteDatabase db, final String key) {
        try (Cursor c = db.rawQuery("SELECT value FROM meta WHERE key = ?", new String[]{key})) {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        }
        return null;
    }

    private static void writeMeta(final SQLiteDatabase db, final String key, final String value) {
        final SQLiteStatement stmt = db.compileStatement(
                "INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)");
        stmt.bindString(1, key);
        stmt.bindString(2, value);
        stmt.executeInsert();
    }

    private static Map<String, YtMeta> readAllYtMeta(final SQLiteDatabase db) {
        final Map<String, YtMeta> map = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT vid, published, duration_s, views, likes FROM yt_meta", null)) {
            while (c.moveToNext()) {
                map.put(c.getString(0), new YtMeta(
                        c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4)));
            }
        }
        return map;
    }

    private static VideoRecord applyYtMeta(final VideoRecord v, @Nullable final YtMeta yt) {
        if (yt == null) {
            return v;
        }
        final String pub = !v.published.isEmpty() ? v.published : yt.published;
        final long dur = v.durationSeconds > 0 ? v.durationSeconds : yt.durationS;
        final long views = v.views >= 0 ? v.views : yt.views;
        final long likes = v.likes >= 0 ? v.likes : yt.likes;
        return new VideoRecord(
                v.vid, v.title, v.channel, pub, dur, views, likes, v.thumbnail,
                v.summary, v.whyHn, v.contentType, v.level, v.shelfLife, v.primaryTopic,
                v.tags, v.categories, v.quote, v.hn, v.score, v.whys, v.hnRelated,
                v.reason, v.source, v.note
        );
    }

    private static final class YtMeta {
        final String published;
        final long durationS;
        final long views;
        final long likes;

        YtMeta(final String published, final long durationS, final long views, final long likes) {
            this.published = published == null ? "" : published;
            this.durationS = durationS;
            this.views = views;
            this.likes = likes;
        }
    }
}
