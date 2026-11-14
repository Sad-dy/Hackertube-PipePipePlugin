package org.hackertube.pipepipe.model;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable data models parsed from both HackerTube live HTTP APIs and {@code hackertube-data.zip}
 * offline snapshots.
 */
public final class HackerTubeModels {

    private HackerTubeModels() {
    }

    public static final class CategoryRef {
        public final String id;
        public final String name;
        public final String area;
        public final String areaName;
        public final String description;
        public final int count;

        public CategoryRef(final String id, final String name, final String area,
                           final String areaName, final String description, final int count) {
            this.id = nonNull(id);
            this.name = nonNull(name);
            this.area = nonNull(area);
            this.areaName = nonNull(areaName);
            this.description = nonNull(description);
            this.count = count;
        }
    }

    public static final class AreaEntry {
        public final String id;
        public final String name;
        public final int count;
        public final List<CategoryRef> categories;

        public AreaEntry(final String id, final String name, final int count,
                         final List<CategoryRef> categories) {
            this.id = nonNull(id);
            this.name = nonNull(name);
            this.count = count;
            this.categories = categories == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(categories));
        }

        public static AreaEntry fromJson(final JsonObject obj) {
            final String areaId = obj.getString("id", "");
            final String areaName = obj.getString("name", areaId);
            final int count = obj.getInt("count", 0);
            final JsonArray catsArr = obj.getArray("categories");
            final List<CategoryRef> cats = new ArrayList<>();
            if (catsArr != null) {
                for (int i = 0; i < catsArr.size(); i++) {
                    final JsonObject c = catsArr.getObject(i);
                    if (c == null) {
                        continue;
                    }
                    cats.add(new CategoryRef(
                            c.getString("id", ""),
                            c.getString("name", ""),
                            areaId,
                            areaName,
                            c.getString("description", ""),
                            c.getInt("count", 0)
                    ));
                }
            }
            return new AreaEntry(areaId, areaName, count, cats);
        }
    }

    public static final class QuoteInfo {
        public final String text;
        public final long hnCommentId;
        public final String year;

        public QuoteInfo(final String text, final long hnCommentId, final String year) {
            this.text = nonNull(text);
            this.hnCommentId = hnCommentId;
            this.year = nonNull(year);
        }

        public static QuoteInfo fromJson(final JsonObject obj) {
            if (obj == null) {
                return null;
            }
            final String text = obj.getString("text", "");
            if (text.isEmpty()) {
                return null;
            }
            final long cid = firstPositiveLong(obj, "hn_comment_id", "id");
            final Object yearObj = obj.get("year");
            final String year = yearObj == null ? "" : String.valueOf(yearObj);
            return new QuoteInfo(text, cid, year);
        }
    }

    public static final class HnStats {
        public final int mentions;
        public final int recommenders;
        public final int recMonths;
        public final int strongRecs;
        public final int firstYear;
        public final int lastYear;
        public final Map<String, Integer> stances;

        public HnStats(final int mentions, final int recommenders, final int recMonths,
                       final int strongRecs, final int firstYear, final int lastYear,
                       final Map<String, Integer> stances) {
            this.mentions = mentions;
            this.recommenders = recommenders;
            this.recMonths = recMonths;
            this.strongRecs = strongRecs;
            this.firstYear = firstYear;
            this.lastYear = lastYear;
            this.stances = stances == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new HashMap<>(stances));
        }

        public static HnStats fromJson(final JsonObject obj) {
            if (obj == null) {
                return new HnStats(0, 0, 0, 0, 0, 0, Collections.emptyMap());
            }
            final Map<String, Integer> stances = new HashMap<>();
            final JsonObject st = obj.getObject("stances");
            if (st != null) {
                for (final String k : st.keySet()) {
                    stances.put(k, st.getInt(k, 0));
                }
            }
            final int strong = obj.has("strong_recs")
                    ? obj.getInt("strong_recs", 0)
                    : obj.getInt("strong", stances.getOrDefault("strong_rec", 0));
            return new HnStats(
                    obj.getInt("mentions", 0),
                    obj.getInt("recommenders", 0),
                    obj.getInt("rec_months", 0),
                    strong,
                    obj.has("first_year") ? obj.getInt("first_year", 0) : obj.getInt("first", 0),
                    obj.has("last_year") ? obj.getInt("last_year", 0) : obj.getInt("last", 0),
                    stances
            );
        }
    }

    public static final class RelatedRef {
        public final String vid;
        public final int sharedRecommenders;

        public RelatedRef(final String vid, final int sharedRecommenders) {
            this.vid = nonNull(vid);
            this.sharedRecommenders = sharedRecommenders;
        }
    }

    public static final class MentionRecord {
        public final long hnCommentId;
        public final String hnUrl;
        public final String vid;
        public final String author;
        public final String month;
        public final String storyTitle;
        public final String stance;
        public final String why;
        public final String quote;
        public final String text;

        public MentionRecord(final long hnCommentId, final String hnUrl, final String vid,
                             final String author, final String month, final String storyTitle,
                             final String stance, final String why, final String quote,
                             final String text) {
            this.hnCommentId = hnCommentId;
            this.hnUrl = nonNull(hnUrl);
            this.vid = nonNull(vid);
            this.author = nonNull(author);
            this.month = nonNull(month);
            this.storyTitle = nonNull(storyTitle);
            this.stance = nonNull(stance);
            this.why = nonNull(why);
            this.quote = nonNull(quote);
            this.text = nonNull(text);
        }

        /**
         * Handles both {@code mentions.jsonl} schema ({@code hn_comment_id}, {@code month},
         * {@code story_title}, {@code hn_url}) and live {@code /api/video} schema ({@code id},
         * {@code ym}, {@code story}).
         */
        public static MentionRecord fromJson(final JsonObject obj, final String fallbackVid) {
            final long cid = firstPositiveLong(obj, "hn_comment_id", "id");
            String hnUrl = obj.getString("hn_url", "");
            if (hnUrl.isEmpty() && cid > 0) {
                hnUrl = "https://news.ycombinator.com/item?id=" + cid;
            }
            final String vid = firstNonEmpty(obj.getString("vid", ""), fallbackVid);
            final String author = obj.getString("author", "hn_user");
            final String month = firstNonEmpty(obj.getString("month", ""), obj.getString("ym", ""));
            final String story = firstNonEmpty(obj.getString("story_title", ""), obj.getString("story", ""));
            final String stance = obj.getString("stance", "reference");
            final String why = obj.getString("why", "");
            final String quote = obj.getString("quote", "");
            final String text = obj.getString("text", "");
            return new MentionRecord(cid, hnUrl, vid, author, month, story, stance, why, quote, text);
        }
    }

    public static final class VideoRecord {
        public final String vid;
        public final String title;
        public final String channel;
        public final String published;
        public final long durationSeconds;
        public final long views;
        public final long likes;
        public final String thumbnail;
        public final String summary;
        public final String whyHn;
        public final String contentType;
        public final String level;
        public final String shelfLife;
        public final String primaryTopic;
        public final List<String> tags;
        public final List<CategoryRef> categories;
        public final QuoteInfo quote;
        public final HnStats hn;
        public final double score;
        public final List<String> whys;
        public final List<RelatedRef> hnRelated;
        public final String reason;
        public final String source;
        public final String note;

        public VideoRecord(final String vid, final String title, final String channel,
                           final String published, final long durationSeconds, final long views,
                           final long likes, final String thumbnail, final String summary,
                           final String whyHn, final String contentType, final String level,
                           final String shelfLife, final String primaryTopic,
                           final List<String> tags, final List<CategoryRef> categories,
                           final QuoteInfo quote, final HnStats hn, final double score,
                           final List<String> whys, final List<RelatedRef> hnRelated,
                           final String reason, final String source, final String note) {
            this.vid = nonNull(vid);
            this.title = nonNull(title);
            this.channel = nonNull(channel);
            this.published = nonNull(published);
            this.durationSeconds = durationSeconds;
            this.views = views;
            this.likes = likes;
            this.thumbnail = nonNull(thumbnail);
            this.summary = nonNull(summary);
            this.whyHn = nonNull(whyHn);
            this.contentType = nonNull(contentType);
            this.level = nonNull(level);
            this.shelfLife = nonNull(shelfLife);
            this.primaryTopic = nonNull(primaryTopic);
            this.tags = tags == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(tags));
            this.categories = categories == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(categories));
            this.quote = quote;
            this.hn = hn == null ? new HnStats(0, 0, 0, 0, 0, 0, Collections.emptyMap()) : hn;
            this.score = score;
            this.whys = whys == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(whys));
            this.hnRelated = hnRelated == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(hnRelated));
            this.reason = nonNull(reason);
            this.source = nonNull(source);
            this.note = nonNull(note);
        }

        public VideoRecord withReason(final String newReason, final String newSource) {
            return new VideoRecord(
                    vid, title, channel, published, durationSeconds, views, likes, thumbnail,
                    summary, whyHn, contentType, level, shelfLife, primaryTopic, tags, categories,
                    quote, hn, score, whys, hnRelated, newReason, newSource, note
            );
        }

        public static VideoRecord fromJson(final JsonObject obj) {
            final List<String> tags = readStringArray(obj.getArray("tags"));
            final List<String> whys = readStringArray(obj.getArray("whys"));

            final List<CategoryRef> cats = new ArrayList<>();
            final JsonArray catArr = obj.getArray("categories");
            if (catArr != null) {
                for (int i = 0; i < catArr.size(); i++) {
                    final JsonObject c = catArr.getObject(i);
                    if (c == null) {
                        continue;
                    }
                    cats.add(new CategoryRef(
                            c.getString("id", ""),
                            c.getString("name", ""),
                            c.getString("area", ""),
                            c.getString("area_name", ""),
                            c.getString("description", ""),
                            c.getInt("count", 0)
                    ));
                }
            }

            final List<RelatedRef> related = new ArrayList<>();
            final JsonArray relArr = obj.getArray("hn_related");
            if (relArr != null) {
                for (int i = 0; i < relArr.size(); i++) {
                    final JsonObject r = relArr.getObject(i);
                    if (r == null) {
                        continue;
                    }
                    final String rVid = r.getString("vid", "");
                    if (!rVid.isEmpty()) {
                        related.add(new RelatedRef(rVid, r.getInt("shared_recommenders",
                                r.getInt("shared", 1))));
                    }
                }
            }

            return new VideoRecord(
                    obj.getString("vid", ""),
                    obj.getString("title", ""),
                    obj.getString("channel", ""),
                    obj.getString("published", ""),
                    obj.has("duration_s") && !obj.isNull("duration_s") ? obj.getLong("duration_s", 0L) : -1L,
                    obj.has("views") && !obj.isNull("views") ? obj.getLong("views", -1L) : -1L,
                    obj.has("likes") && !obj.isNull("likes") ? obj.getLong("likes", -1L) : -1L,
                    obj.getString("thumbnail", ""),
                    obj.getString("summary", ""),
                    obj.getString("why_hn", ""),
                    obj.getString("content_type", ""),
                    obj.getString("level", "general"),
                    obj.getString("shelf_life", "evergreen"),
                    obj.getString("primary_topic", ""),
                    tags,
                    cats,
                    QuoteInfo.fromJson(obj.getObject("quote")),
                    HnStats.fromJson(obj.getObject("hn")),
                    obj.getDouble("score", 0.0),
                    whys,
                    related,
                    obj.getString("reason", ""),
                    obj.getString("source", ""),
                    obj.getString("note", "")
            );
        }
    }

    public static final class VideoDetail {
        public final VideoRecord video;
        public final List<MentionRecord> comments;

        public VideoDetail(final VideoRecord video, final List<MentionRecord> comments) {
            this.video = video;
            this.comments = comments == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(comments));
        }
    }

    public static final class PlaylistSummary {
        public final String slug;
        public final String title;
        public final String pitch;
        public final String kind;
        public final String tier;
        public final int minutes;
        public final int count;
        public final List<String> cover;

        public PlaylistSummary(final String slug, final String title, final String pitch,
                               final String kind, final String tier, final int minutes,
                               final int count, final List<String> cover) {
            this.slug = nonNull(slug);
            this.title = nonNull(title);
            this.pitch = nonNull(pitch);
            this.kind = nonNull(kind);
            this.tier = nonNull(tier);
            this.minutes = minutes;
            this.count = count;
            this.cover = cover == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(cover));
        }

        public static PlaylistSummary fromJson(final JsonObject obj) {
            return new PlaylistSummary(
                    obj.getString("slug", ""),
                    obj.getString("title", ""),
                    obj.getString("pitch", ""),
                    obj.getString("kind", ""),
                    obj.getString("tier", ""),
                    obj.getInt("minutes", 0),
                    obj.getInt("count", 0),
                    readStringArray(obj.getArray("cover"))
            );
        }
    }

    public static final class PlaylistDetail {
        public final String slug;
        public final String title;
        public final String tier;
        public final String kind;
        public final String pitch;
        public final String intro;
        public final int minutes;
        public final List<VideoRecord> videos;

        public PlaylistDetail(final String slug, final String title, final String tier,
                              final String kind, final String pitch, final String intro,
                              final int minutes, final List<VideoRecord> videos) {
            this.slug = nonNull(slug);
            this.title = nonNull(title);
            this.tier = nonNull(tier);
            this.kind = nonNull(kind);
            this.pitch = nonNull(pitch);
            this.intro = nonNull(intro);
            this.minutes = minutes;
            this.videos = videos == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(videos));
        }

        public static PlaylistDetail fromJson(final JsonObject obj) {
            final JsonArray arr = obj.getArray("videos");
            final List<VideoRecord> videos = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    final JsonObject v = arr.getObject(i);
                    if (v != null) {
                        videos.add(VideoRecord.fromJson(v));
                    }
                }
            }
            return new PlaylistDetail(
                    obj.getString("slug", ""),
                    obj.getString("title", ""),
                    obj.getString("tier", ""),
                    obj.getString("kind", ""),
                    obj.getString("pitch", ""),
                    obj.getString("intro", ""),
                    obj.getInt("minutes", 0),
                    videos
            );
        }
    }

    public static final class SearchResponse {
        public final boolean weak;
        public final List<VideoRecord> results;

        public SearchResponse(final boolean weak, final List<VideoRecord> results) {
            this.weak = weak;
            this.results = results == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(results));
        }
    }

    public static List<String> readStringArray(final JsonArray arr) {
        if (arr == null || arr.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            final String s = arr.getString(i);
            if (s != null && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static long firstPositiveLong(final JsonObject obj, final String k1, final String k2) {
        final long v1 = obj.getLong(k1, 0L);
        return v1 > 0L ? v1 : obj.getLong(k2, 0L);
    }

    private static String firstNonEmpty(final String a, final String b) {
        return (a != null && !a.isEmpty()) ? a : nonNull(b);
    }

    private static String nonNull(final String s) {
        return s == null ? "" : s;
    }
}
