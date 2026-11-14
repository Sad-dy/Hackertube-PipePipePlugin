package org.hackertube.pipepipe.engine;

import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.RelatedRef;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;

import javax.annotation.Nullable;

/**
 * Pure-Java on-device 9-bucket recommender matching HackerTube's personalization model
 * ({@code similar}, {@code corec}, {@code taste}, {@code classics}, {@code gems},
 * {@code recent}, {@code explore}, {@code deep}, {@code wildcard}).
 */
public final class LocalRecommender {

    private LocalRecommender() {
    }

    public static List<VideoRecord> recommendFeed(final LocalSnapshotData data,
                                                  final List<String> likes,
                                                  final List<String> dislikes,
                                                  final List<String> seen,
                                                  final String seed,
                                                  final boolean hideWatched,
                                                  final boolean hideNews,
                                                  final boolean hideZeroRec,
                                                  final int offset,
                                                  final int limit) {
        if (!data.isReady() || limit <= 0) {
            return Collections.emptyList();
        }
        final Random rng = new Random(seedToLong(seed));

        final Set<String> excluded = new HashSet<>(dislikes);
        excluded.addAll(likes);
        if (hideWatched) {
            excluded.addAll(seen);
        }

        // Pre-compute disliked vector average for downranking close dislike neighbors
        final float[] dislikeVec = computeDislikeCentroid(data, dislikes);

        // Dedupe by normalized title (keep highest score first since videos list is score-ordered)
        final List<VideoRecord> eligible = new ArrayList<>(data.videos.size());
        final Set<String> seenTitles = new HashSet<>(data.videos.size());
        for (final VideoRecord v : data.videos) {
            if (excluded.contains(v.vid)) {
                continue;
            }
            if (hideNews && "news".equalsIgnoreCase(v.shelfLife)) {
                continue;
            }
            if (hideZeroRec && v.hn.recommenders <= 0) {
                continue;
            }
            if (isTooCloseToDislikes(data, v.vid, dislikeVec)) {
                continue;
            }
            final String normTitle = normalizeTitle(v.title);
            if (!normTitle.isEmpty() && !seenTitles.add(normTitle)) {
                continue;
            }
            eligible.add(v);
        }

        if (eligible.isEmpty()) {
            return Collections.emptyList();
        }

        final Map<String, VideoRecord> eligibleByVid = new LinkedHashMap<>(eligible.size() * 2);
        for (final VideoRecord v : eligible) {
            eligibleByVid.put(v.vid, v);
        }

        final List<VideoRecord> similarBucket = buildSimilarBucket(data, likes, eligibleByVid, rng);
        final List<VideoRecord> corecBucket = buildCorecBucket(data, likes, eligibleByVid);
        final List<VideoRecord> tasteBucket = buildTasteBucket(data, likes, dislikes, eligible, rng);
        final List<VideoRecord> classicsBucket = buildClassicsBucket(eligible, rng);
        final List<VideoRecord> gemsBucket = buildGemsBucket(eligible, rng);
        final List<VideoRecord> recentBucket = buildRecentBucket(eligible, rng);
        final List<VideoRecord> exploreBucket = buildExploreBucket(data, likes, eligible, rng);
        final List<VideoRecord> deepBucket = buildDeepBucket(data, likes, eligible, rng);
        final List<VideoRecord> wildcardBucket = buildWildcardBucket(eligible, rng);

        final List<List<VideoRecord>> buckets = buildInterleaveSchedule(
                !likes.isEmpty(),
                similarBucket, corecBucket, tasteBucket,
                classicsBucket, gemsBucket, recentBucket,
                exploreBucket, deepBucket, wildcardBucket);

        final int needed = Math.min(eligible.size(), offset + limit);
        final List<VideoRecord> merged = new ArrayList<>(needed);
        final Set<String> picked = new HashSet<>(needed * 2);
        final int[] cursors = new int[buckets.size()];

        boolean progressed = true;
        while (merged.size() < needed && progressed) {
            progressed = false;
            for (int b = 0; b < buckets.size() && merged.size() < needed; b++) {
                final List<VideoRecord> bucket = buckets.get(b);
                while (cursors[b] < bucket.size()) {
                    final VideoRecord cand = bucket.get(cursors[b]++);
                    if (picked.add(cand.vid)) {
                        merged.add(cand);
                        progressed = true;
                        break;
                    }
                }
            }
        }

        // Fill remaining capacity cleanly from score-ordered tail if needed
        if (merged.size() < needed) {
            for (final VideoRecord v : eligible) {
                if (merged.size() >= needed) {
                    break;
                }
                if (picked.add(v.vid)) {
                    merged.add(v.withReason(buildFallbackReason(v), "classics"));
                }
            }
        }

        if (offset >= merged.size()) {
            return Collections.emptyList();
        }
        return merged.subList(offset, Math.min(merged.size(), offset + limit));
    }

    public static List<VideoRecord> findRelatedLocal(final LocalSnapshotData data,
                                                     final String vid,
                                                     final int limit) {
        final List<VideoRecord> out = new ArrayList<>(limit);
        final Set<String> seen = new HashSet<>();
        seen.add(vid);

        // 1. HN co-recommendations first
        final List<RelatedRef> refs = data.relatedByVid.get(vid);
        if (refs != null) {
            for (final RelatedRef ref : refs) {
                if (out.size() >= limit) {
                    break;
                }
                final VideoRecord target = data.videosByVid.get(ref.vid);
                if (target != null && seen.add(target.vid)) {
                    out.add(target.withReason(
                            "Co-recommended by " + ref.sharedRecommenders + " HN commenter(s)",
                            "corec"));
                }
            }
        }

        // 2. Semantic embedding neighbors to fill remaining slots
        if (out.size() < limit && data.embeddings != null) {
            final Integer row = data.embeddingRowByVid.get(vid);
            if (row != null) {
                final float[] qVec = data.embeddings.getRowAsFloat(row);
                final List<ScoredVideo> sims = new ArrayList<>(128);
                for (final VideoRecord cand : data.videos) {
                    if (seen.contains(cand.vid)) {
                        continue;
                    }
                    final Integer cRow = data.embeddingRowByVid.get(cand.vid);
                    if (cRow == null) {
                        continue;
                    }
                    final float sim = data.embeddings.dotRow(cRow, qVec);
                    sims.add(new ScoredVideo(cand, sim));
                }
                sims.sort((a, b) -> Float.compare(b.score, a.score));
                for (final ScoredVideo sv : sims) {
                    if (out.size() >= limit) {
                        break;
                    }
                    if (seen.add(sv.video.vid)) {
                        out.add(sv.video.withReason("Semantically similar on HackerTube", "similar"));
                    }
                }
            }
        }
        return out;
    }

    private static List<List<VideoRecord>> buildInterleaveSchedule(
            final boolean hasLikes,
            final List<VideoRecord> similar,
            final List<VideoRecord> corec,
            final List<VideoRecord> taste,
            final List<VideoRecord> classics,
            final List<VideoRecord> gems,
            final List<VideoRecord> recent,
            final List<VideoRecord> explore,
            final List<VideoRecord> deep,
            final List<VideoRecord> wildcard) {
        final List<List<VideoRecord>> sched = new ArrayList<>(12);
        if (hasLikes) {
            sched.add(similar);
            sched.add(corec);
            sched.add(taste);
            sched.add(classics);
            sched.add(gems);
            sched.add(recent);
            sched.add(explore);
            sched.add(deep);
            sched.add(wildcard);
        } else {
            sched.add(classics);
            sched.add(gems);
            sched.add(recent);
            sched.add(explore);
            sched.add(deep);
            sched.add(wildcard);
            sched.add(classics);
        }
        return sched;
    }

    private static List<VideoRecord> buildSimilarBucket(final LocalSnapshotData data,
                                                        final List<String> likes,
                                                        final Map<String, VideoRecord> eligibleByVid,
                                                        final Random rng) {
        if (likes.isEmpty() || data.embeddings == null) {
            return Collections.emptyList();
        }
        final List<String> shuffledLikes = new ArrayList<>(likes);
        Collections.shuffle(shuffledLikes, new Random(rng.nextLong()));
        final int maxSeeds = Math.min(8, shuffledLikes.size());

        final Map<String, ScoredVideoWithReason> bestPerVid = new HashMap<>();
        for (int s = 0; s < maxSeeds; s++) {
            final String likeVid = shuffledLikes.get(s);
            final Integer seedRow = data.embeddingRowByVid.get(likeVid);
            if (seedRow == null) {
                continue;
            }
            final VideoRecord seedVideo = data.videosByVid.get(likeVid);
            final String seedTitle = seedVideo != null && !seedVideo.title.isEmpty()
                    ? truncate(seedVideo.title, 48)
                    : likeVid;
            final float[] seedVec = data.embeddings.getRowAsFloat(seedRow);

            for (final VideoRecord cand : eligibleByVid.values()) {
                final Integer cRow = data.embeddingRowByVid.get(cand.vid);
                if (cRow == null) {
                    continue;
                }
                final float sim = data.embeddings.dotRow(cRow, seedVec);
                final float rankScore = sim + 0.04f * (float) Math.log1p(Math.max(0.0, cand.score));
                final ScoredVideoWithReason prev = bestPerVid.get(cand.vid);
                if (prev == null || rankScore > prev.score) {
                    bestPerVid.put(cand.vid, new ScoredVideoWithReason(
                            cand.withReason("Similar to \"" + seedTitle + "\"", "similar"),
                            rankScore));
                }
            }
        }
        final List<ScoredVideoWithReason> list = new ArrayList<>(bestPerVid.values());
        list.sort((a, b) -> Float.compare(b.score, a.score));
        return unwrapLimit(list, 60);
    }

    private static List<VideoRecord> buildCorecBucket(final LocalSnapshotData data,
                                                      final List<String> likes,
                                                      final Map<String, VideoRecord> eligibleByVid) {
        if (likes.isEmpty()) {
            return Collections.emptyList();
        }
        final Map<String, ScoredVideoWithReason> scored = new HashMap<>();
        for (final String likeVid : likes) {
            final List<RelatedRef> rels = data.relatedByVid.get(likeVid);
            if (rels == null) {
                continue;
            }
            final VideoRecord seedVideo = data.videosByVid.get(likeVid);
            final String seedTitle = seedVideo != null && !seedVideo.title.isEmpty()
                    ? truncate(seedVideo.title, 48)
                    : likeVid;
            for (final RelatedRef ref : rels) {
                final VideoRecord cand = eligibleByVid.get(ref.vid);
                if (cand == null) {
                    continue;
                }
                final float w = ref.sharedRecommenders * 10.0f + (float) cand.score;
                final ScoredVideoWithReason prev = scored.get(cand.vid);
                if (prev == null) {
                    scored.put(cand.vid, new ScoredVideoWithReason(
                            cand.withReason("Often recommended with \"" + seedTitle + "\"", "corec"),
                            w));
                } else {
                    scored.put(cand.vid, new ScoredVideoWithReason(prev.video, prev.score + w));
                }
            }
        }
        final List<ScoredVideoWithReason> list = new ArrayList<>(scored.values());
        list.sort((a, b) -> Float.compare(b.score, a.score));
        return unwrapLimit(list, 60);
    }

    private static List<VideoRecord> buildTasteBucket(final LocalSnapshotData data,
                                                      final List<String> likes,
                                                      final List<String> dislikes,
                                                      final List<VideoRecord> eligible,
                                                      final Random rng) {
        if (likes.isEmpty() || data.embeddings == null) {
            return Collections.emptyList();
        }
        final List<Integer> likeRows = resolveRows(data, likes);
        if (likeRows.isEmpty()) {
            return Collections.emptyList();
        }
        final List<Integer> dislikeRows = resolveRows(data, dislikes);
        final float[] tasteVec = data.embeddings.buildTasteVector(likeRows, dislikeRows);

        final List<ScoredVideoWithReason> scored = new ArrayList<>(eligible.size());
        for (final VideoRecord v : eligible) {
            final Integer row = data.embeddingRowByVid.get(v.vid);
            if (row == null) {
                continue;
            }
            final float sim = data.embeddings.dotRow(row, tasteVec);
            final float rank = sim + 0.03f * (float) Math.log1p(Math.max(0.0, v.score))
                    + rng.nextFloat() * 0.02f;
            scored.add(new ScoredVideoWithReason(
                    v.withReason("Matches your taste profile", "taste"),
                    rank));
        }
        scored.sort((a, b) -> Float.compare(b.score, a.score));
        return unwrapLimit(scored, 80);
    }

    private static List<VideoRecord> buildClassicsBucket(final List<VideoRecord> eligible,
                                                         final Random rng) {
        final List<VideoRecord> pool = new ArrayList<>();
        for (final VideoRecord v : eligible) {
            if ("evergreen".equalsIgnoreCase(v.shelfLife) && v.hn.recMonths >= 4) {
                pool.add(v.withReason(buildFallbackReason(v), "classics"));
                if (pool.size() >= 180) {
                    break;
                }
            }
        }
        if (pool.isEmpty()) {
            for (int i = 0; i < Math.min(120, eligible.size()); i++) {
                final VideoRecord v = eligible.get(i);
                pool.add(v.withReason(buildFallbackReason(v), "classics"));
            }
        }
        weightedShuffleTop(pool, rng, 0.35);
        return pool;
    }

    private static List<VideoRecord> buildGemsBucket(final List<VideoRecord> eligible,
                                                     final Random rng) {
        final List<VideoRecord> pool = new ArrayList<>();
        for (final VideoRecord v : eligible) {
            if (isHiddenGem(v)) {
                pool.add(v.withReason("Hidden gem strongly praised on HN", "gems"));
                if (pool.size() >= 180) {
                    break;
                }
            }
        }
        weightedShuffleTop(pool, rng, 0.40);
        return pool;
    }

    private static List<VideoRecord> buildRecentBucket(final List<VideoRecord> eligible,
                                                       final Random rng) {
        final List<VideoRecord> pool = new ArrayList<>();
        for (final VideoRecord v : eligible) {
            if (v.hn.lastYear >= 2024 && v.hn.recommenders >= 2) {
                pool.add(v.withReason("Recommended on HN through " + v.hn.lastYear, "recent"));
            }
        }
        pool.sort((a, b) -> {
            final int yc = Integer.compare(b.hn.lastYear, a.hn.lastYear);
            return yc != 0 ? yc : Double.compare(b.score, a.score);
        });
        final List<VideoRecord> top = pool.size() > 150 ? new ArrayList<>(pool.subList(0, 150)) : pool;
        weightedShuffleTop(top, rng, 0.45);
        return top;
    }

    private static List<VideoRecord> buildExploreBucket(final LocalSnapshotData data,
                                                        final List<String> likes,
                                                        final List<VideoRecord> eligible,
                                                        final Random rng) {
        final Set<String> likedAreas = new HashSet<>();
        for (final String vid : likes) {
            final VideoRecord lv = data.videosByVid.get(vid);
            if (lv != null) {
                for (final CategoryRef c : lv.categories) {
                    if (!c.area.isEmpty()) {
                        likedAreas.add(c.area);
                    }
                }
            }
        }
        final List<VideoRecord> pool = new ArrayList<>();
        for (final VideoRecord v : eligible) {
            if (v.categories.isEmpty()) {
                continue;
            }
            final CategoryRef primary = v.categories.get(0);
            if (likedAreas.isEmpty() || !likedAreas.contains(primary.area)) {
                final String areaLabel = primary.areaName.isEmpty() ? primary.area : primary.areaName;
                pool.add(v.withReason("Explore " + areaLabel, "explore"));
                if (pool.size() >= 200) {
                    break;
                }
            }
        }
        Collections.shuffle(pool, new Random(rng.nextLong()));
        return pool;
    }

    private static List<VideoRecord> buildDeepBucket(final LocalSnapshotData data,
                                                     final List<String> likes,
                                                     final List<VideoRecord> eligible,
                                                     final Random rng) {
        final Set<String> likedAreas = new HashSet<>();
        for (final String vid : likes) {
            final VideoRecord lv = data.videosByVid.get(vid);
            if (lv != null && !lv.categories.isEmpty()) {
                likedAreas.add(lv.categories.get(0).area);
            }
        }
        final List<VideoRecord> pool = new ArrayList<>();
        for (final VideoRecord v : eligible) {
            final boolean isDeepLevel = "advanced".equalsIgnoreCase(v.level)
                    || "intermediate".equalsIgnoreCase(v.level)
                    || "lecture".equalsIgnoreCase(v.contentType)
                    || "course".equalsIgnoreCase(v.contentType)
                    || v.durationSeconds >= 1800;
            if (!isDeepLevel) {
                continue;
            }
            final boolean sameArea = !likedAreas.isEmpty()
                    && !v.categories.isEmpty()
                    && likedAreas.contains(v.categories.get(0).area);
            if (likedAreas.isEmpty() || sameArea) {
                final String topic = v.primaryTopic.isEmpty() ? "in-depth talk" : v.primaryTopic;
                pool.add(v.withReason("Deep dive: " + topic, "deep"));
                if (pool.size() >= 150) {
                    break;
                }
            }
        }
        weightedShuffleTop(pool, rng, 0.5);
        return pool;
    }

    private static List<VideoRecord> buildWildcardBucket(final List<VideoRecord> eligible,
                                                         final Random rng) {
        final int start = Math.min(eligible.size() / 4, 250);
        final int end = Math.min(eligible.size(), 3000);
        final List<VideoRecord> pool = new ArrayList<>();
        for (int i = start; i < end; i++) {
            final VideoRecord v = eligible.get(i);
            if (v.score >= 3.0) {
                pool.add(v.withReason("Serendipitous HN pick", "wildcard"));
            }
        }
        Collections.shuffle(pool, new Random(rng.nextLong()));
        return pool.size() > 100 ? new ArrayList<>(pool.subList(0, 100)) : pool;
    }

    public static boolean isHiddenGem(final VideoRecord v) {
        if (v.hn.strongRecs < 2 && v.score < 4.0) {
            return false;
        }
        if (v.views > 0 && v.views < 150_000L) {
            return true;
        }
        return v.hn.mentions > 0
                && (double) v.hn.strongRecs / Math.max(1, v.hn.mentions) >= 0.45
                && v.score >= 4.0;
    }

    public static String normalizeTitle(final String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        final String lower = title.toLowerCase(Locale.ROOT);
        final StringBuilder sb = new StringBuilder(lower.length());
        boolean lastSpace = true;
        for (int i = 0; i < lower.length(); i++) {
            final char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                lastSpace = false;
            } else if (!lastSpace) {
                sb.append(' ');
                lastSpace = true;
            }
        }
        return sb.toString().trim();
    }

    private static String buildFallbackReason(final VideoRecord v) {
        if (!v.whyHn.isEmpty()) {
            return v.whyHn;
        }
        if (v.hn.recMonths > 0) {
            return "Recommended in " + v.hn.recMonths + " distinct months on HN";
        }
        return "Featured on HackerTube";
    }

    @Nullable
    private static float[] computeDislikeCentroid(final LocalSnapshotData data, final List<String> dislikes) {
        if (dislikes.isEmpty() || data.embeddings == null) {
            return null;
        }
        final List<Integer> rows = resolveRows(data, dislikes);
        if (rows.isEmpty()) {
            return null;
        }
        final float[] centroid = new float[data.embeddings.dims];
        final float inv = 1.0f / rows.size();
        for (final int r : rows) {
            data.embeddings.addScaledRow(r, inv, centroid);
        }
        NpyFloat16Reader.normalizeInPlace(centroid);
        return centroid;
    }

    private static boolean isTooCloseToDislikes(final LocalSnapshotData data,
                                                final String vid,
                                                @Nullable final float[] dislikeVec) {
        if (dislikeVec == null || data.embeddings == null) {
            return false;
        }
        final Integer row = data.embeddingRowByVid.get(vid);
        if (row == null) {
            return false;
        }
        return data.embeddings.dotRow(row, dislikeVec) > 0.78f;
    }

    private static List<Integer> resolveRows(final LocalSnapshotData data, final Collection<String> vids) {
        final List<Integer> rows = new ArrayList<>(vids.size());
        for (final String v : vids) {
            final Integer row = data.embeddingRowByVid.get(v);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static void weightedShuffleTop(final List<VideoRecord> pool,
                                           final Random rng,
                                           final double jitterWeight) {
        if (pool.size() <= 1) {
            return;
        }
        final Map<String, Double> keys = new HashMap<>(pool.size() * 2);
        for (int i = 0; i < pool.size(); i++) {
            final VideoRecord v = pool.get(i);
            final double rankVal = -i * (1.0 - jitterWeight) + rng.nextDouble() * pool.size() * jitterWeight;
            keys.put(v.vid, rankVal);
        }
        pool.sort(Comparator.comparingDouble((VideoRecord v) -> keys.get(v.vid)).reversed());
    }

    private static List<VideoRecord> unwrapLimit(final List<ScoredVideoWithReason> list, final int max) {
        final int n = Math.min(max, list.size());
        final List<VideoRecord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(list.get(i).video);
        }
        return out;
    }

    private static long seedToLong(@Nullable final String seed) {
        if (seed == null || seed.isEmpty()) {
            return 1337L;
        }
        final CRC32 crc = new CRC32();
        crc.update(seed.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static String truncate(final String s, final int maxLen) {
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen - 1) + "…";
    }

    private static final class ScoredVideo {
        final VideoRecord video;
        final float score;

        ScoredVideo(final VideoRecord video, final float score) {
            this.video = video;
            this.score = score;
        }
    }

    private static final class ScoredVideoWithReason {
        final VideoRecord video;
        final float score;

        ScoredVideoWithReason(final VideoRecord video, final float score) {
            this.video = video;
            this.score = score;
        }
    }
}
