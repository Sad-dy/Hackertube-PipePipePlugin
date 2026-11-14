package org.hackertube.pipepipe.engine;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.hackertube.pipepipe.config.HackerTubeAreaCatalog;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.MentionRecord;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistDetail;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistSummary;
import org.hackertube.pipepipe.model.HackerTubeModels.RelatedRef;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.annotation.Nullable;

/**
 * In-memory snapshot of HackerTube's dataset ({@code hackertube-data.zip} + cached playlists and
 * YouTube metadata). Usable identically inside Android SQLite storage and pure-JVM unit tests.
 */
public final class LocalSnapshotData {

    public final List<VideoRecord> videos;
    public final Map<String, VideoRecord> videosByVid;
    public final Map<String, List<RelatedRef>> relatedByVid;
    public final Map<String, List<MentionRecord>> mentionsByVid;
    public final List<AreaEntry> areas;
    public final Map<String, CategoryRef> categoriesById;
    public final List<PlaylistSummary> playlistSummaries;
    public final Map<String, PlaylistDetail> playlistsBySlug;
    public final Map<String, Integer> embeddingRowByVid;
    @Nullable public final NpyFloat16Reader embeddings;

    public LocalSnapshotData(final List<VideoRecord> videos,
                             final Map<String, List<RelatedRef>> relatedByVid,
                             final Map<String, List<MentionRecord>> mentionsByVid,
                             final List<AreaEntry> areas,
                             final List<PlaylistSummary> playlistSummaries,
                             final Map<String, PlaylistDetail> playlistsBySlug,
                             final Map<String, Integer> embeddingRowByVid,
                             @Nullable final NpyFloat16Reader embeddings) {
        this.videos = Collections.unmodifiableList(new ArrayList<>(videos));
        final Map<String, VideoRecord> vMap = new LinkedHashMap<>(videos.size() * 2);
        for (final VideoRecord v : videos) {
            vMap.put(v.vid, v);
        }
        this.videosByVid = Collections.unmodifiableMap(vMap);
        this.relatedByVid = Collections.unmodifiableMap(new HashMap<>(relatedByVid));
        this.mentionsByVid = Collections.unmodifiableMap(new HashMap<>(mentionsByVid));
        this.areas = areas == null || areas.isEmpty()
                ? HackerTubeAreaCatalog.DEFAULT_AREAS
                : Collections.unmodifiableList(new ArrayList<>(areas));
        final Map<String, CategoryRef> catMap = new LinkedHashMap<>();
        for (final AreaEntry area : this.areas) {
            for (final CategoryRef c : area.categories) {
                catMap.put(c.id, c);
            }
        }
        this.categoriesById = Collections.unmodifiableMap(catMap);
        this.playlistSummaries = playlistSummaries == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(playlistSummaries));
        this.playlistsBySlug = playlistsBySlug == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(playlistsBySlug));
        this.embeddingRowByVid = embeddingRowByVid == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(embeddingRowByVid));
        this.embeddings = embeddings;
    }

    public boolean isReady() {
        return !videos.isEmpty();
    }

    /**
     * Parses a {@code hackertube-data.zip} directly into a {@link LocalSnapshotData} instance.
     */
    public static LocalSnapshotData fromExportZip(final File zipFile) throws IOException, JsonParserException {
        final List<VideoRecord> rawVideos = new ArrayList<>(6500);
        final Map<String, List<RelatedRef>> related = new HashMap<>(4096);
        final Map<String, List<MentionRecord>> mentions = new HashMap<>(8192);
        final List<AreaEntry> areas = new ArrayList<>();
        final Map<String, Integer> embRows = new HashMap<>(8192);
        NpyFloat16Reader embeddings = null;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                final String name = simpleName(entry.getName());
                switch (name) {
                    case "videos.jsonl":
                        parseVideosJsonl(zis, rawVideos);
                        break;
                    case "mentions.jsonl":
                        parseMentionsJsonl(zis, mentions);
                        break;
                    case "related.jsonl":
                        parseRelatedJsonl(zis, related);
                        break;
                    case "taxonomy.json":
                        parseTaxonomyJson(readAllUtf8(zis), areas);
                        break;
                    case "embedding_ids.json":
                        parseEmbeddingIds(readAllUtf8(zis), embRows);
                        break;
                    case "embeddings.npy":
                        embeddings = NpyFloat16Reader.readNpy(zis);
                        break;
                    default:
                        break;
                }
                zis.closeEntry();
            }
        }

        final List<VideoRecord> mergedVideos = attachRelatedAndComputeAreaCounts(rawVideos, related, areas);
        return new LocalSnapshotData(
                mergedVideos,
                related,
                mentions,
                areas,
                Collections.emptyList(),
                Collections.emptyMap(),
                embRows,
                embeddings
        );
    }

    private static List<VideoRecord> attachRelatedAndComputeAreaCounts(
            final List<VideoRecord> rawVideos,
            final Map<String, List<RelatedRef>> related,
            final List<AreaEntry> areas) {
        final Map<String, Integer> areaCounts = new HashMap<>();
        final Map<String, Integer> catCounts = new HashMap<>();
        final List<VideoRecord> out = new ArrayList<>(rawVideos.size());
        for (final VideoRecord v : rawVideos) {
            for (final CategoryRef c : v.categories) {
                if (!c.area.isEmpty()) {
                    areaCounts.put(c.area, areaCounts.getOrDefault(c.area, 0) + 1);
                }
                if (!c.id.isEmpty()) {
                    catCounts.put(c.id, catCounts.getOrDefault(c.id, 0) + 1);
                }
            }
            final List<RelatedRef> rel = related.get(v.vid);
            if (rel != null && v.hnRelated.isEmpty()) {
                out.add(new VideoRecord(
                        v.vid, v.title, v.channel, v.published, v.durationSeconds, v.views,
                        v.likes, v.thumbnail, v.summary, v.whyHn, v.contentType, v.level,
                        v.shelfLife, v.primaryTopic, v.tags, v.categories, v.quote, v.hn,
                        v.score, v.whys, rel, v.reason, v.source, v.note
                ));
            } else {
                out.add(v);
            }
        }
        // Populate taxonomy counts when loaded from taxonomy.json (which omits counts)
        if (!areas.isEmpty()) {
            for (int i = 0; i < areas.size(); i++) {
                final AreaEntry a = areas.get(i);
                final List<CategoryRef> updatedCats = new ArrayList<>(a.categories.size());
                for (final CategoryRef c : a.categories) {
                    final int cc = catCounts.getOrDefault(c.id, c.count);
                    updatedCats.add(new CategoryRef(c.id, c.name, c.area, c.areaName, c.description, cc));
                }
                final int ac = areaCounts.getOrDefault(a.id, a.count);
                areas.set(i, new AreaEntry(a.id, a.name, ac, updatedCats));
            }
        }
        return out;
    }

    private static void parseVideosJsonl(final ZipInputStream zis, final List<VideoRecord> out)
            throws IOException, JsonParserException {
        final BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8), 32768);
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            out.add(VideoRecord.fromJson(JsonParser.object().from(line)));
        }
    }

    private static void parseMentionsJsonl(final ZipInputStream zis,
                                           final Map<String, List<MentionRecord>> out)
            throws IOException, JsonParserException {
        final BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8), 32768);
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            final MentionRecord m = MentionRecord.fromJson(JsonParser.object().from(line), "");
            if (!m.vid.isEmpty()) {
                out.computeIfAbsent(m.vid, k -> new ArrayList<>(4)).add(m);
            }
        }
    }

    private static void parseRelatedJsonl(final ZipInputStream zis,
                                          final Map<String, List<RelatedRef>> out)
            throws IOException, JsonParserException {
        final BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8), 32768);
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            final JsonObject o = JsonParser.object().from(line);
            final String vid = o.getString("vid", "");
            final JsonArray arr = o.getArray("related");
            if (vid.isEmpty() || arr == null || arr.isEmpty()) {
                continue;
            }
            final List<RelatedRef> refs = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                final JsonObject r = arr.getObject(i);
                if (r != null) {
                    final String rv = r.getString("vid", "");
                    if (!rv.isEmpty()) {
                        refs.add(new RelatedRef(rv, r.getInt("shared_recommenders", 1)));
                    }
                }
            }
            if (!refs.isEmpty()) {
                out.put(vid, refs);
            }
        }
    }

    public static void parseTaxonomyJson(final String json, final List<AreaEntry> out)
            throws JsonParserException {
        final JsonObject root = JsonParser.object().from(json);
        final JsonArray areasArr = root.getArray("areas");
        if (areasArr == null) {
            return;
        }
        for (int i = 0; i < areasArr.size(); i++) {
            final JsonObject a = areasArr.getObject(i);
            if (a != null) {
                out.add(AreaEntry.fromJson(a));
            }
        }
    }

    public static void parseEmbeddingIds(final String json, final Map<String, Integer> out)
            throws JsonParserException {
        final JsonArray arr = JsonParser.array().from(json);
        for (int i = 0; i < arr.size(); i++) {
            final String vid = arr.getString(i);
            if (vid != null && !vid.isEmpty()) {
                out.put(vid, i);
            }
        }
    }

    private static String simpleName(final String entryName) {
        final int slash = entryName.lastIndexOf('/');
        return slash >= 0 ? entryName.substring(slash + 1) : entryName;
    }

    private static String readAllUtf8(final ZipInputStream zis) throws IOException {
        final StringBuilder sb = new StringBuilder(8192);
        final char[] buf = new char[4096];
        final InputStreamReader r = new InputStreamReader(zis, StandardCharsets.UTF_8);
        int n;
        while ((n = r.read(buf)) >= 0) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
