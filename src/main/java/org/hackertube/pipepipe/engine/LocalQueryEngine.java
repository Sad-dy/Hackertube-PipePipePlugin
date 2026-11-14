package org.hackertube.pipepipe.engine;

import org.hackertube.pipepipe.api.HackerTubeApiClient.ListParams;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.SearchResponse;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;

import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Pure-Java filtering, sorting, and hybrid lexical + taxonomy + embedding-neighbor search over
 * {@link LocalSnapshotData}, also providing shared post-filtering for live API results when
 * client-side toggles (like {@code level}, legacy date brackets, {@code hideNews}, or
 * {@code hideZeroRec}) apply.
 */
public final class LocalQueryEngine {

    private LocalQueryEngine() {
    }

    public static List<VideoRecord> queryList(final LocalSnapshotData data,
                                              final ListParams params,
                                              final boolean hideNews,
                                              final boolean hideZeroRec,
                                              final int offset,
                                              final int limit) {
        if (!data.isReady() || limit <= 0) {
            return Collections.emptyList();
        }
        final List<VideoRecord> filtered = applyFilters(data.videos, params, hideNews, hideZeroRec);
        sortVideos(filtered, params == null ? null : params.sort);
        if (offset >= filtered.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(filtered.subList(offset, Math.min(filtered.size(), offset + limit)));
    }

    public static SearchResponse search(final LocalSnapshotData data,
                                        final String query,
                                        @Nullable final ListParams params,
                                        final int offset,
                                        final int limit) {
        if (!data.isReady() || limit <= 0) {
            return new SearchResponse(true, Collections.emptyList());
        }
        final String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            final List<VideoRecord> browse = queryList(data, params == null ? new ListParams() : params,
                    false, false, offset, limit);
            return new SearchResponse(false, browse);
        }

        final List<String> terms = tokenize(trimmed);
        if (terms.isEmpty()) {
            return new SearchResponse(true, Collections.emptyList());
        }

        final List<VideoRecord> pool = applyFilters(data.videos, params, false, false);
        final String fullLower = trimmed.toLowerCase(Locale.ROOT);

        // Pre-index taxonomy category descriptions that match query terms for synonym expansion
        final Map<String, Double> catBoost = new HashMap<>();
        for (final CategoryRef cat : data.categoriesById.values()) {
            final String catText = (cat.name + " " + cat.description).toLowerCase(Locale.ROOT);
            final double hit = scoreText(catText, fullLower, terms);
            if (hit > 0.0) {
                catBoost.put(cat.id, hit);
            }
        }

        final List<ScoredMatch> lexicalHits = new ArrayList<>();
        for (final VideoRecord v : pool) {
            final double lex = scoreVideoLexical(v, fullLower, terms, catBoost);
            if (lex > 0.0) {
                final double blended = lex + 0.08 * Math.log1p(Math.max(0.0, v.score));
                lexicalHits.add(new ScoredMatch(v, blended));
            }
        }
        lexicalHits.sort((a, b) -> Double.compare(b.score, a.score));

        // Expand top lexical hits into semantic embedding neighbors so concept queries remain rich
        final List<VideoRecord> merged = new ArrayList<>(lexicalHits.size() + 32);
        final Set<String> seen = new HashSet<>(lexicalHits.size() * 2 + 64);
        for (final ScoredMatch m : lexicalHits) {
            if (seen.add(m.video.vid)) {
                merged.add(m.video);
            }
        }

        if (data.embeddings != null && !lexicalHits.isEmpty() && lexicalHits.size() < 40) {
            final List<Integer> seedRows = new ArrayList<>(5);
            for (int i = 0; i < Math.min(5, lexicalHits.size()); i++) {
                final Integer r = data.embeddingRowByVid.get(lexicalHits.get(i).video.vid);
                if (r != null) {
                    seedRows.add(r);
                }
            }
            if (!seedRows.isEmpty()) {
                final float[] queryVec = data.embeddings.buildTasteVector(seedRows, null);
                final List<ScoredMatch> neighborHits = new ArrayList<>();
                for (final VideoRecord v : pool) {
                    if (seen.contains(v.vid)) {
                        continue;
                    }
                    final Integer row = data.embeddingRowByVid.get(v.vid);
                    if (row == null) {
                        continue;
                    }
                    final float sim = data.embeddings.dotRow(row, queryVec);
                    if (sim >= 0.48f) {
                        neighborHits.add(new ScoredMatch(v, sim));
                    }
                }
                neighborHits.sort((a, b) -> Double.compare(b.score, a.score));
                for (int i = 0; i < Math.min(25, neighborHits.size()); i++) {
                    final VideoRecord nv = neighborHits.get(i).video;
                    if (seen.add(nv.vid)) {
                        merged.add(nv.withReason("Related semantic match", "semantic"));
                    }
                }
            }
        }

        final String sort = params == null ? null : params.sort;
        if (sort != null && !sort.isEmpty() && !"relevance".equals(sort)) {
            sortVideos(merged, sort);
        }

        final boolean weak = lexicalHits.isEmpty() && merged.size() < 3;
        if (offset >= merged.size()) {
            return new SearchResponse(weak, Collections.emptyList());
        }
        return new SearchResponse(weak, new ArrayList<>(
                merged.subList(offset, Math.min(merged.size(), offset + limit))));
    }

    public static List<VideoRecord> applyFilters(final List<VideoRecord> source,
                                                 @Nullable final ListParams params,
                                                 final boolean hideNews,
                                                 final boolean hideZeroRec) {
        final List<VideoRecord> out = new ArrayList<>(source.size());
        final int currentYear = Year.now().getValue();
        for (final VideoRecord v : source) {
            if (hideNews && "news".equalsIgnoreCase(v.shelfLife)) {
                continue;
            }
            if (hideZeroRec && v.hn.recommenders <= 0) {
                continue;
            }
            if (params != null && !matchesParams(v, params, currentYear)) {
                continue;
            }
            out.add(v);
        }
        return out;
    }

    public static boolean needsClientSidePostFilter(@Nullable final ListParams params,
                                                    final boolean hideNews,
                                                    final boolean hideZeroRec) {
        if (hideNews || hideZeroRec) {
            return true;
        }
        if (params == null) {
            return false;
        }
        if (params.level != null && !params.level.isEmpty()) {
            return true;
        }
        return "2010s".equals(params.date) || "2000s".equals(params.date);
    }

    private static boolean matchesParams(final VideoRecord v, final ListParams p, final int currentYear) {
        if (!isBlank(p.area) && !hasArea(v, p.area)) {
            return false;
        }
        if (!isBlank(p.cat) && !hasCategory(v, p.cat)) {
            return false;
        }
        if (!isBlank(p.channel) && !v.channel.equalsIgnoreCase(p.channel.trim())) {
            return false;
        }
        if (!isBlank(p.type) && !p.type.equalsIgnoreCase(v.contentType)) {
            return false;
        }
        if (!isBlank(p.level) && !p.level.equalsIgnoreCase(v.level)) {
            return false;
        }
        if (p.evergreen && !"evergreen".equalsIgnoreCase(v.shelfLife)) {
            return false;
        }
        if (p.gems && !LocalRecommender.isHiddenGem(v)) {
            return false;
        }
        if (!isBlank(p.dur) && !matchesDuration(v.durationSeconds, p.dur)) {
            return false;
        }
        if (!isBlank(p.date) && !matchesDate(v, p.date, currentYear)) {
            return false;
        }
        return true;
    }

    private static boolean matchesDuration(final long durSec, final String durFilter) {
        if (durSec <= 0) {
            // If export lacks duration_s (not synced from YouTube/list), don't hide unmeasured items harshly?
            //Wait: when a duration filter IS active, match strictly on known duration only if present.
            return false;
        }
        switch (durFilter) {
            case "short":
                return durSec < 300;        // < 5m
            case "medium":
                return durSec >= 300 && durSec < 1200; // 5m - 20m
            case "long":
                return durSec >= 1200 && durSec < 2700; // 20m - 45m
            case "xlong":
                return durSec >= 2700;      // 45m+
            default:
                return true;
        }
    }

    private static boolean matchesDate(final VideoRecord v, final String dateFilter, final int currentYear) {
        final int year = extractPublishYear(v);
        if (year <= 0) {
            return false;
        }
        switch (dateFilter) {
            case "year":
                return year >= currentYear - 1;
            case "2020s":
                return year >= 2020;
            case "2010s":
                return year >= 2010 && year <= 2019;
            case "2000s":
                return year < 2010;
            default:
                return true;
        }
    }

    private static int extractPublishYear(final VideoRecord v) {
        if (v.published != null && v.published.length() >= 4) {
            try {
                return Integer.parseInt(v.published.substring(0, 4));
            } catch (final NumberFormatException ignored) {
            }
        }
        if (v.hn.firstYear > 0) {
            return v.hn.firstYear;
        }
        return v.hn.lastYear;
    }

    private static boolean hasArea(final VideoRecord v, final String areaId) {
        for (final CategoryRef c : v.categories) {
            if (areaId.equalsIgnoreCase(c.area)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCategory(final VideoRecord v, final String catId) {
        for (final CategoryRef c : v.categories) {
            if (catId.equalsIgnoreCase(c.id)) {
                return true;
            }
        }
        return false;
    }

    public static void sortVideos(final List<VideoRecord> list, @Nullable final String sort) {
        if (sort == null || sort.isEmpty() || "score".equals(sort)) {
            list.sort((a, b) -> Double.compare(b.score, a.score));
            return;
        }
        switch (sort) {
            case "recent":
                list.sort((a, b) -> {
                    final int y = Integer.compare(b.hn.lastYear, a.hn.lastYear);
                    if (y != 0) {
                        return y;
                    }
                    final int pc = b.published.compareTo(a.published);
                    return pc != 0 ? pc : Double.compare(b.score, a.score);
                });
                break;
            case "views":
                list.sort(Comparator.comparingLong((VideoRecord v) -> v.views).reversed());
                break;
            case "liked":
                list.sort(Comparator.comparingLong((VideoRecord v) -> v.likes).reversed());
                break;
            case "old":
                list.sort((a, b) -> {
                    final int ya = extractPublishYear(a);
                    final int yb = extractPublishYear(b);
                    if (ya == 0 && yb == 0) {
                        return 0;
                    }
                    if (ya == 0) {
                        return 1;
                    }
                    if (yb == 0) {
                        return -1;
                    }
                    final int c = Integer.compare(ya, yb);
                    return c != 0 ? c : a.published.compareTo(b.published);
                });
                break;
            default:
                list.sort((a, b) -> Double.compare(b.score, a.score));
                break;
        }
    }

    private static double scoreVideoLexical(final VideoRecord v,
                                            final String fullLower,
                                            final List<String> terms,
                                            final Map<String, Double> catBoost) {
        double total = 0.0;
        total += 4.0 * scoreText(v.title.toLowerCase(Locale.ROOT), fullLower, terms);
        total += 3.0 * scoreText(v.channel.toLowerCase(Locale.ROOT), fullLower, terms);
        total += 2.8 * scoreText(v.primaryTopic.toLowerCase(Locale.ROOT), fullLower, terms);

        if (!v.tags.isEmpty()) {
            final StringBuilder tagSb = new StringBuilder();
            for (final String t : v.tags) {
                tagSb.append(t.toLowerCase(Locale.ROOT)).append(' ');
            }
            total += 2.6 * scoreText(tagSb.toString(), fullLower, terms);
        }

        double catScore = 0.0;
        for (final CategoryRef c : v.categories) {
            catScore += catBoost.getOrDefault(c.id, 0.0);
            catScore += scoreText((c.name + " " + c.areaName).toLowerCase(Locale.ROOT), fullLower, terms);
        }
        total += 1.8 * catScore;

        total += 1.4 * scoreText(v.summary.toLowerCase(Locale.ROOT), fullLower, terms);
        total += 1.2 * scoreText(v.whyHn.toLowerCase(Locale.ROOT), fullLower, terms);
        if (v.quote != null) {
            total += 0.9 * scoreText(v.quote.text.toLowerCase(Locale.ROOT), fullLower, terms);
        }
        return total;
    }

    private static double scoreText(final String haystack,
                                    final String fullLower,
                                    final List<String> terms) {
        if (haystack.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        if (fullLower.length() >= 3 && haystack.contains(fullLower)) {
            score += 2.5;
        }
        int matched = 0;
        for (final String term : terms) {
            if (haystack.contains(term)) {
                matched++;
                score += 1.0;
            }
        }
        if (terms.size() > 1 && matched == terms.size()) {
            score += 1.2;
        }
        return score;
    }

    private static List<String> tokenize(final String q) {
        final String lower = q.toLowerCase(Locale.ROOT);
        final String[] parts = lower.split("[^a-z0-9+.#_-]+");
        final List<String> out = new ArrayList<>(parts.length);
        for (final String p : parts) {
            if (!p.isEmpty() && p.length() >= 2) {
                out.add(p);
            }
        }
        if (out.isEmpty() && !lower.trim().isEmpty()) {
            out.add(lower.trim());
        }
        return out;
    }

    private static boolean isBlank(@Nullable final String s) {
        return s == null || s.trim().isEmpty();
    }

    private static final class ScoredMatch {
        final VideoRecord video;
        final double score;

        ScoredMatch(final VideoRecord video, final double score) {
            this.video = video;
            this.score = score;
        }
    }
}
