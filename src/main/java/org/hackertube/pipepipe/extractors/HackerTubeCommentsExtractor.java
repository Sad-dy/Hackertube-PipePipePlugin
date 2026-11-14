package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.model.HackerTubeModels.MentionRecord;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoDetail;
import org.jsoup.nodes.Entities;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemsCollector;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.stream.Description;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Extracts labeled Hacker News comment mentions for a video, sorted best-first
 * ({@code strong_rec -> rec -> reference -> critical -> joke}), with strong recommendations
 * hearted and the pull-quote comment pinned.
 */
public final class HackerTubeCommentsExtractor extends CommentsExtractor {

    private final HackerTubeRepository repository;
    @Nullable private VideoDetail detail;

    public HackerTubeCommentsExtractor(final StreamingService service,
                                       final ListLinkHandler linkHandler,
                                       final HackerTubeRepository repository) {
        super(service, linkHandler);
        this.repository = repository;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        detail = repository.getVideoDetail(getId());
    }

    @Nonnull
    @Override
    public InfoItemsPage<CommentsInfoItem> getInitialPage() throws ExtractionException {
        if (detail == null || detail.comments.isEmpty()) {
            return InfoItemsPage.emptyPage();
        }
        final long pinnedId = (detail.video != null && detail.video.quote != null)
                ? detail.video.quote.hnCommentId : 0L;

        final List<MentionRecord> sorted = new ArrayList<>(detail.comments);
        sorted.sort((a, b) -> {
            final boolean aPin = pinnedId > 0 && a.hnCommentId == pinnedId;
            final boolean bPin = pinnedId > 0 && b.hnCommentId == pinnedId;
            if (aPin != bPin) {
                return aPin ? -1 : 1;
            }
            final int wCmp = Integer.compare(stanceWeight(b.stance), stanceWeight(a.stance));
            if (wCmp != 0) {
                return wCmp;
            }
            return b.month.compareTo(a.month);
        });

        final CommentsInfoItemsCollector collector = new CommentsInfoItemsCollector(getServiceId());
        for (final MentionRecord m : sorted) {
            collector.commit(toCommentExtractor(m, pinnedId));
        }
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    public InfoItemsPage<CommentsInfoItem> getPage(final Page page) {
        return InfoItemsPage.emptyPage();
    }

    private CommentsInfoItemExtractor toCommentExtractor(final MentionRecord m, final long pinnedId)
            throws ParsingException {
        final String displayName = m.author + " [" + stanceLabel(m.stance) + "]";
        final String itemUrl = m.hnUrl.isEmpty()
                ? getUrl()
                : m.hnUrl;
        final String dateAndStory = m.storyTitle.isEmpty()
                ? m.month
                : (m.month.isEmpty() ? m.storyTitle : m.month + " · " + truncate(m.storyTitle, 52));
        final Description text = buildCommentDescription(m);

        return new CommentsInfoItemExtractor() {
            @Override
            public String getCommentId() {
                return m.hnCommentId > 0 ? String.valueOf(m.hnCommentId) : "";
            }

            @Override
            public String getUploaderName() {
                return displayName;
            }

            @Override
            public String getTextualUploadDate() {
                return dateAndStory;
            }

            @Override
            public Description getCommentText() {
                return text;
            }

            @Override
            public boolean isHeartedByUploader() {
                return "strong_rec".equals(m.stance);
            }

            @Override
            public boolean isPinned() {
                return pinnedId > 0 && m.hnCommentId == pinnedId;
            }

            @Override
            public String getName() {
                return displayName;
            }

            @Override
            public String getUrl() {
                return itemUrl;
            }

            @Override
            public String getThumbnailUrl() {
                return "";
            }
        };
    }

    private static Description buildCommentDescription(final MentionRecord m) {
        final StringBuilder html = new StringBuilder();
        if (!m.why.isEmpty()) {
            html.append("<p><b>Why:</b> ").append(esc(m.why)).append("</p>");
        }
        if (!m.storyTitle.isEmpty()) {
            html.append("<p><i>HN thread:</i> ").append(esc(m.storyTitle)).append("</p>");
        }
        if (!m.text.isEmpty()) {
            String formatted = esc(m.text)
                    .replace("[VIDEO]", "<b>[VIDEO]</b>")
                    .replace("[LINK]", "[LINK]")
                    .replace("\n\n", "</p><p>")
                    .replace("\n", "<br>");
            html.append("<p>").append(formatted).append("</p>");
        } else if (!m.quote.isEmpty()) {
            html.append("<blockquote>&ldquo;").append(esc(m.quote)).append("&rdquo;</blockquote>");
        }
        if (!m.hnUrl.isEmpty()) {
            html.append("<p><a href=\"").append(esc(m.hnUrl)).append("\">Open comment on Hacker News</a></p>");
        }
        return new Description(html.toString(), Description.HTML);
    }

    public static int stanceWeight(@Nullable final String stance) {
        if (stance == null) {
            return 1;
        }
        switch (stance) {
            case "strong_rec":
                return 4;
            case "rec":
                return 3;
            case "reference":
                return 2;
            case "critical":
                return 1;
            case "joke":
                return 0;
            default:
                return 1;
        }
    }

    private static String stanceLabel(@Nullable final String stance) {
        if (stance == null) {
            return "HN";
        }
        switch (stance) {
            case "strong_rec":
                return "Strong Rec";
            case "rec":
                return "Rec";
            case "reference":
                return "Reference";
            case "critical":
                return "Critical";
            case "joke":
                return "Joke";
            default:
                return stance;
        }
    }

    private static String truncate(final String s, final int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    private static String esc(final String s) {
        return Entities.escape(s == null ? "" : s);
    }
}
