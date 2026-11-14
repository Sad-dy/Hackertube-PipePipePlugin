package org.hackertube.pipepipe.model;

import com.grack.nanojson.JsonStringWriter;
import com.grack.nanojson.JsonWriter;

import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.MentionRecord;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistDetail;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistSummary;
import org.hackertube.pipepipe.model.HackerTubeModels.RelatedRef;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;

import java.util.List;
import java.util.Map;

/**
 * Lossless {@code nanojson} serializer for persisting HackerTube models cleanly into local SQLite.
 */
public final class HackerTubeJsonCodec {

    private HackerTubeJsonCodec() {
    }

    public static String encodeVideo(final VideoRecord v) {
        final JsonStringWriter w = JsonWriter.string();
        writeVideoObject(w, v);
        return w.done();
    }

    public static String encodeMention(final MentionRecord m) {
        final JsonStringWriter w = JsonWriter.string();
        w.object();
        w.value("hn_comment_id", m.hnCommentId);
        w.value("hn_url", m.hnUrl);
        w.value("vid", m.vid);
        w.value("author", m.author);
        w.value("month", m.month);
        w.value("story_title", m.storyTitle);
        w.value("stance", m.stance);
        w.value("why", m.why);
        w.value("quote", m.quote);
        w.value("text", m.text);
        w.end();
        return w.done();
    }

    public static String encodeBrowseAreas(final List<AreaEntry> areas) {
        final JsonStringWriter w = JsonWriter.string();
        w.array();
        for (final AreaEntry a : areas) {
            w.object();
            w.value("id", a.id);
            w.value("name", a.name);
            w.value("count", a.count);
            w.array("categories");
            for (final CategoryRef c : a.categories) {
                w.object();
                w.value("id", c.id);
                w.value("name", c.name);
                w.value("description", c.description);
                w.value("count", c.count);
                w.end();
            }
            w.end();
            w.end();
        }
        w.end();
        return w.done();
    }

    public static String encodePlaylistSummaries(final List<PlaylistSummary> summaries) {
        final JsonStringWriter w = JsonWriter.string();
        w.array();
        for (final PlaylistSummary s : summaries) {
            w.object();
            w.value("slug", s.slug);
            w.value("title", s.title);
            w.value("pitch", s.pitch);
            w.value("kind", s.kind);
            w.value("tier", s.tier);
            w.value("minutes", s.minutes);
            w.value("count", s.count);
            w.array("cover");
            for (final String c : s.cover) {
                w.value(c);
            }
            w.end();
            w.end();
        }
        w.end();
        return w.done();
    }

    public static String encodePlaylistDetail(final PlaylistDetail d) {
        final JsonStringWriter w = JsonWriter.string();
        w.object();
        w.value("slug", d.slug);
        w.value("title", d.title);
        w.value("tier", d.tier);
        w.value("kind", d.kind);
        w.value("pitch", d.pitch);
        w.value("intro", d.intro);
        w.value("minutes", d.minutes);
        w.array("videos");
        for (final VideoRecord v : d.videos) {
            writeVideoObject(w, v);
        }
        w.end();
        w.end();
        return w.done();
    }

    private static void writeVideoObject(final JsonStringWriter w, final VideoRecord v) {
        w.object();
        w.value("vid", v.vid);
        w.value("title", v.title);
        w.value("channel", v.channel);
        if (!v.published.isEmpty()) {
            w.value("published", v.published);
        }
        if (v.durationSeconds >= 0) {
            w.value("duration_s", v.durationSeconds);
        }
        if (v.views >= 0) {
            w.value("views", v.views);
        }
        if (v.likes >= 0) {
            w.value("likes", v.likes);
        }
        if (!v.thumbnail.isEmpty()) {
            w.value("thumbnail", v.thumbnail);
        }
        w.value("summary", v.summary);
        w.value("why_hn", v.whyHn);
        w.value("content_type", v.contentType);
        w.value("level", v.level);
        w.value("shelf_life", v.shelfLife);
        w.value("primary_topic", v.primaryTopic);
        if (!v.note.isEmpty()) {
            w.value("note", v.note);
        }

        w.array("tags");
        for (final String t : v.tags) {
            w.value(t);
        }
        w.end();

        w.array("whys");
        for (final String why : v.whys) {
            w.value(why);
        }
        w.end();

        w.array("categories");
        for (final CategoryRef c : v.categories) {
            w.object();
            w.value("id", c.id);
            w.value("name", c.name);
            w.value("area", c.area);
            w.value("area_name", c.areaName);
            w.end();
        }
        w.end();

        if (v.quote != null) {
            w.object("quote");
            w.value("text", v.quote.text);
            w.value("hn_comment_id", v.quote.hnCommentId);
            if (!v.quote.year.isEmpty()) {
                w.value("year", v.quote.year);
            }
            w.end();
        }

        w.object("hn");
        w.value("mentions", v.hn.mentions);
        w.value("recommenders", v.hn.recommenders);
        w.value("rec_months", v.hn.recMonths);
        w.value("strong_recs", v.hn.strongRecs);
        w.value("first_year", v.hn.firstYear);
        w.value("last_year", v.hn.lastYear);
        w.object("stances");
        for (final Map.Entry<String, Integer> e : v.hn.stances.entrySet()) {
            w.value(e.getKey(), e.getValue());
        }
        w.end();
        w.end();

        w.value("score", v.score);

        if (!v.hnRelated.isEmpty()) {
            w.array("hn_related");
            for (final RelatedRef r : v.hnRelated) {
                w.object();
                w.value("vid", r.vid);
                w.value("shared_recommenders", r.sharedRecommenders);
                w.end();
            }
            w.end();
        }
        w.end();
    }
}
