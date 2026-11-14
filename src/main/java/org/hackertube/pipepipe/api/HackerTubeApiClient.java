package org.hackertube.pipepipe.api;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.MentionRecord;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistDetail;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistSummary;
import org.hackertube.pipepipe.model.HackerTubeModels.SearchResponse;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoDetail;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.StreamingResponse;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Lightweight HTTP client against HackerTube's JSON API and zip export endpoints, routed through
 * PipePipe's {@link Downloader#getStreaming} with bounded timeouts so server outages fail fast to
 * local snapshot mode.
 */
public final class HackerTubeApiClient {

    private static final long API_TIMEOUT_MS = 8000L;
    private static final long EXPORT_TIMEOUT_MS = 90000L;

    public static final class ListParams {
        @Nullable public String area;
        @Nullable public String cat;
        @Nullable public String channel;
        @Nullable public String date;
        @Nullable public String type;
        @Nullable public String dur;
        public boolean gems;
        public boolean evergreen;
        @Nullable public String sort;
        @Nullable public String level;

        public boolean hasOnlyDefaultSort() {
            return (sort == null || sort.isEmpty() || "score".equals(sort))
                    && isBlank(area) && isBlank(cat) && isBlank(channel)
                    && isBlank(date) && isBlank(type) && isBlank(dur)
                    && !gems && !evergreen && isBlank(level);
        }
    }

    public List<VideoRecord> fetchFeed(final String baseUrl,
                                       final List<String> likes,
                                       final List<String> dislikes,
                                       final List<String> seen,
                                       final String seed,
                                       final int offset,
                                       final int limit) throws IOException, ReCaptchaException, JsonParserException {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("like", String.join(",", likes));
        params.put("dislike", String.join(",", dislikes));
        params.put("seen", String.join(",", seen));
        params.put("seed", normalizeNumericSeed(seed));
        params.put("offset", String.valueOf(offset));
        params.put("n", String.valueOf(limit));
        final String url = buildUrl(baseUrl, "/api/feed", params);
        return parseVideoArray(JsonParser.array().from(getString(url, API_TIMEOUT_MS)));
    }

    public List<VideoRecord> fetchList(final String baseUrl,
                                       final ListParams listParams,
                                       final int offset,
                                       final int limit) throws IOException, ReCaptchaException, JsonParserException {
        final Map<String, String> params = toQueryParams(listParams);
        params.put("offset", String.valueOf(offset));
        params.put("n", String.valueOf(limit));
        final String url = buildUrl(baseUrl, "/api/list", params);
        return parseVideoArray(JsonParser.array().from(getString(url, API_TIMEOUT_MS)));
    }

    public SearchResponse fetchSearch(final String baseUrl,
                                      final String query,
                                      @Nullable final ListParams listParams,
                                      final int offset,
                                      final int limit) throws IOException, ReCaptchaException, JsonParserException {
        final Map<String, String> params = listParams == null ? new LinkedHashMap<>() : toQueryParams(listParams);
        params.put("q", query);
        params.put("offset", String.valueOf(offset));
        params.put("n", String.valueOf(limit));
        final String url = buildUrl(baseUrl, "/api/search", params);
        final JsonObject obj = JsonParser.object().from(getString(url, API_TIMEOUT_MS));
        return new SearchResponse(
                obj.getBoolean("weak", false),
                parseVideoArray(obj.getArray("results"))
        );
    }

    public List<AreaEntry> fetchBrowseAreas(final String baseUrl)
            throws IOException, ReCaptchaException, JsonParserException {
        final String url = buildUrl(baseUrl, "/api/browse", null);
        final JsonArray arr = JsonParser.array().from(getString(url, API_TIMEOUT_MS));
        final List<AreaEntry> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            final JsonObject o = arr.getObject(i);
            if (o != null) {
                out.add(AreaEntry.fromJson(o));
            }
        }
        return out;
    }

    public List<PlaylistSummary> fetchPlaylists(final String baseUrl)
            throws IOException, ReCaptchaException, JsonParserException {
        final String url = buildUrl(baseUrl, "/api/playlists", null);
        final JsonArray arr = JsonParser.array().from(getString(url, API_TIMEOUT_MS));
        final List<PlaylistSummary> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            final JsonObject o = arr.getObject(i);
            if (o != null) {
                out.add(PlaylistSummary.fromJson(o));
            }
        }
        return out;
    }

    public PlaylistDetail fetchPlaylistDetail(final String baseUrl, final String slug)
            throws IOException, ReCaptchaException, JsonParserException {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("slug", slug);
        final String url = buildUrl(baseUrl, "/api/playlist", params);
        return PlaylistDetail.fromJson(JsonParser.object().from(getString(url, API_TIMEOUT_MS)));
    }

    public VideoDetail fetchVideoDetail(final String baseUrl, final String vid)
            throws IOException, ReCaptchaException, JsonParserException {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("vid", vid);
        final String url = buildUrl(baseUrl, "/api/video", params);
        final JsonObject obj = JsonParser.object().from(getString(url, API_TIMEOUT_MS));
        final JsonObject vObj = obj.getObject("video");
        final VideoRecord video = vObj == null ? null : VideoRecord.fromJson(vObj);
        final JsonArray cArr = obj.getArray("comments");
        final List<MentionRecord> comments = new ArrayList<>();
        if (cArr != null) {
            for (int i = 0; i < cArr.size(); i++) {
                final JsonObject c = cArr.getObject(i);
                if (c != null) {
                    comments.add(MentionRecord.fromJson(c, vid));
                }
            }
        }
        return new VideoDetail(video, comments);
    }

    public List<VideoRecord> fetchRelated(final String baseUrl, final String vid, final int limit)
            throws IOException, ReCaptchaException, JsonParserException {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("vid", vid);
        params.put("n", String.valueOf(limit));
        final String url = buildUrl(baseUrl, "/api/related", params);
        return parseVideoArray(JsonParser.array().from(getString(url, API_TIMEOUT_MS)));
    }

    public JsonObject fetchExportMeta(final String baseUrl)
            throws IOException, ReCaptchaException, JsonParserException {
        final String url = buildUrl(baseUrl, "/api/export", null);
        return JsonParser.object().from(getString(url, API_TIMEOUT_MS));
    }

    public void downloadExportZip(final String baseUrl, final String zipPath, final File destFile)
            throws IOException, ReCaptchaException {
        final String path = zipPath.startsWith("/") ? zipPath : "/" + zipPath;
        final String url = HackerTubeUrlUtil.stripTrailingSlash(baseUrl) + path;
        final File tmp = new File(destFile.getParentFile(), destFile.getName() + ".tmp");
        try (StreamingResponse resp = requireDownloader().getStreaming(url, null, null, EXPORT_TIMEOUT_MS)) {
            if (resp.responseCode() >= 400) {
                throw new IOException("HTTP " + resp.responseCode() + " downloading " + url);
            }
            try (InputStream in = resp.body();
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 65536)) {
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
            }
        }
        if (destFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            destFile.delete();
        }
        if (!tmp.renameTo(destFile)) {
            throw new IOException("Failed to move downloaded zip to " + destFile);
        }
    }

    private String getString(final String url, final long timeoutMs)
            throws IOException, ReCaptchaException {
        try (StreamingResponse resp = requireDownloader().getStreaming(url, null, null, timeoutMs)) {
            final String body = readUtf8(resp.body());
            if (resp.responseCode() >= 400) {
                throw new IOException("HTTP " + resp.responseCode() + " for " + url);
            }
            return body;
        }
    }

    private static Downloader requireDownloader() throws IOException {
        final Downloader d = NewPipe.getDownloader();
        if (d == null) {
            throw new IOException("NewPipe Downloader not initialized");
        }
        return d;
    }

    private static String readUtf8(final InputStream in) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> toQueryParams(@Nullable final ListParams p) {
        final Map<String, String> map = new LinkedHashMap<>();
        if (p == null) {
            return map;
        }
        putIfPresent(map, "area", p.area);
        putIfPresent(map, "cat", p.cat);
        putIfPresent(map, "channel", p.channel);
        putIfPresent(map, "date", p.date);
        putIfPresent(map, "type", p.type);
        putIfPresent(map, "dur", p.dur);
        if (p.gems) {
            map.put("gems", "1");
        }
        if (p.evergreen) {
            map.put("evergreen", "1");
        }
        putIfPresent(map, "sort", p.sort);
        // Server ignores unknown/old date options cleanly; local layer post-filters level & legacy ranges.
        return map;
    }

    private static void putIfPresent(final Map<String, String> map, final String k, @Nullable final String v) {
        if (!isBlank(v)) {
            map.put(k, v);
        }
    }

    private static String buildUrl(final String baseUrl, final String path,
                                   @Nullable final Map<String, String> params) {
        final StringBuilder sb = new StringBuilder(HackerTubeUrlUtil.stripTrailingSlash(baseUrl));
        sb.append(path);
        if (params != null && !params.isEmpty()) {
            char sep = '?';
            for (final Map.Entry<String, String> entry : params.entrySet()) {
                sb.append(sep)
                        .append(HackerTubeUrlUtil.encode(entry.getKey()))
                        .append('=')
                        .append(HackerTubeUrlUtil.encode(entry.getValue()));
                sep = '&';
            }
        }
        return sb.toString();
    }

    private static List<VideoRecord> parseVideoArray(@Nullable final JsonArray arr) {
        if (arr == null || arr.isEmpty()) {
            return new ArrayList<>(0);
        }
        final List<VideoRecord> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            final JsonObject o = arr.getObject(i);
            if (o != null) {
                out.add(VideoRecord.fromJson(o));
            }
        }
        return out;
    }

    private static String normalizeNumericSeed(@Nullable final String seed) {
        if (seed == null || seed.isEmpty()) {
            return "42";
        }
        if (seed.matches("\\d{1,12}")) {
            return seed;
        }
        return String.valueOf(Math.max(1, seed.hashCode() & 0x7fffffff));
    }

    private static boolean isBlank(@Nullable final String s) {
        return s == null || s.trim().isEmpty();
    }
}
