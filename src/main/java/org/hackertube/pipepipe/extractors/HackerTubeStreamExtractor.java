package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoDetail;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;
import org.jsoup.nodes.Entities;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.InfoItemExtractor;
import org.schabi.newpipe.extractor.InfoItemsCollector;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.Frameset;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Delegates playback media, subtitles, SponsorBlock ID, chapters, and framesets to
 * {@link ServiceList#YouTube} while enriching Description, Tags, Thumbnails, Creator links,
 * Comments tab, and Related items with HackerTube metadata.
 */
public final class HackerTubeStreamExtractor extends StreamExtractor {

    private final HackerTubeRepository repository;
    @Nullable private VideoDetail htDetail;
    @Nullable private StreamExtractor ytExtractor;
    @Nullable private Exception ytError;

    public HackerTubeStreamExtractor(final StreamingService service,
                                     final LinkHandler linkHandler,
                                     final HackerTubeRepository repository) {
        super(service, linkHandler);
        this.repository = repository;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        final String vid = getId();
        try {
            htDetail = repository.getVideoDetail(vid);
        } catch (final IOException ignored) {
            htDetail = null;
        }

        try {
            ytExtractor = ServiceList.YouTube.getStreamExtractor(
                    HackerTubeUrlUtil.canonicalYouTubeWatchUrl(vid));
            ytExtractor.forceLocalization(getExtractorLocalization());
            ytExtractor.forceContentCountry(getExtractorContentCountry());
            ytExtractor.fetchPage();
        } catch (final Exception e) {
            ytError = e;
            if (htDetail == null || htDetail.video == null) {
                if (e instanceof IOException) {
                    throw (IOException) e;
                }
                if (e instanceof ExtractionException) {
                    throw (ExtractionException) e;
                }
                throw new ExtractionException("Failed to fetch YouTube stream for " + vid, e);
            }
        }
    }

    @Nullable
    private VideoRecord htVideo() {
        return htDetail == null ? null : htDetail.video;
    }

    private boolean isYtReady() {
        return ytExtractor != null && ytError == null;
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        final VideoRecord v = htVideo();
        if (v != null && !v.title.isEmpty()) {
            return v.title;
        }
        if (isYtReady()) {
            return ytExtractor.getName();
        }
        return getId();
    }

    @Override
    public StreamType getStreamType() throws ParsingException {
        if (isYtReady()) {
            return ytExtractor.getStreamType();
        }
        return StreamType.VIDEO_STREAM;
    }

    @Override
    public int getAgeLimit() throws ParsingException {
        return isYtReady() ? ytExtractor.getAgeLimit() : NO_AGE_LIMIT;
    }

    @Nonnull
    @Override
    public String getThumbnailUrl() throws ParsingException {
        final HackerTubePrefs prefs = repository.getPrefs();
        if (prefs.isUseVideoFrames()) {
            return HackerTubeUrlUtil.buildThumbnailUrl(getId(), true);
        }
        final VideoRecord v = htVideo();
        if (v != null && !v.thumbnail.isEmpty()) {
            return v.thumbnail;
        }
        if (isYtReady()) {
            return ytExtractor.getThumbnailUrl();
        }
        return HackerTubeUrlUtil.buildThumbnailUrl(getId(), false);
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() throws ParsingException {
        final List<Image> images = new ArrayList<>();
        final String vid = getId();
        if (repository.getPrefs().isUseVideoFrames()) {
            images.add(new Image(HackerTubeUrlUtil.buildThumbnailUrl(vid, true),
                    360, 480, Image.ResolutionLevel.HIGH));
        }
        images.add(new Image(HackerTubeUrlUtil.buildThumbnailUrl(vid, false),
                360, 480, Image.ResolutionLevel.MEDIUM));
        if (isYtReady()) {
            images.addAll(ytExtractor.getThumbnails());
        }
        return images;
    }

    @Nonnull
    @Override
    public Description getDescription() throws ParsingException {
        final VideoRecord v = htVideo();
        if (v == null) {
            return isYtReady() ? ytExtractor.getDescription() : Description.EMPTY_DESCRIPTION;
        }
        final StringBuilder html = new StringBuilder();
        final String baseUrl = repository.getPrefs().getBaseUrl();

        if (v.quote != null && !v.quote.text.isEmpty()) {
            html.append("<blockquote>&ldquo;").append(esc(v.quote.text)).append("&rdquo;");
            if (v.quote.hnCommentId > 0) {
                final String qUrl = "https://news.ycombinator.com/item?id=" + v.quote.hnCommentId;
                html.append(" &mdash; <a href=\"").append(esc(qUrl)).append("\">HN")
                        .append(v.quote.year.isEmpty() ? "" : " (" + esc(v.quote.year) + ")")
                        .append("</a>");
            }
            html.append("</blockquote>");
        }

        if (!v.whyHn.isEmpty()) {
            html.append("<p><b>Why HN likes it:</b> ").append(esc(v.whyHn)).append("</p>");
        }
        if (!v.summary.isEmpty()) {
            html.append("<p><b>Summary:</b> ").append(esc(v.summary)).append("</p>");
        }

        html.append("<p><b>HN Stats:</b> ")
                .append("Score ").append(String.format(Locale.US, "%.1f", v.score))
                .append(" &bull; ").append(v.hn.recommenders).append(" recommenders")
                .append(" &bull; ").append(v.hn.recMonths).append(" rec months")
                .append(" &bull; ").append(v.hn.strongRecs).append(" strong recs")
                .append(" &bull; ").append(v.hn.mentions).append(" mentions");
        if (v.hn.firstYear > 0) {
            html.append(" (").append(v.hn.firstYear);
            if (v.hn.lastYear > v.hn.firstYear) {
                html.append("&ndash;").append(v.hn.lastYear);
            }
            html.append(')');
        }
        html.append("</p>");

        if (!v.contentType.isEmpty() || !v.level.isEmpty() || !v.shelfLife.isEmpty()) {
            html.append("<p><b>Classification:</b> ")
                    .append(esc(v.contentType))
                    .append(" &bull; level: ").append(esc(v.level))
                    .append(" &bull; shelf life: ").append(esc(v.shelfLife))
                    .append("</p>");
        }

        if (!v.categories.isEmpty()) {
            html.append("<p><b>Categories:</b> ");
            for (int i = 0; i < v.categories.size(); i++) {
                final CategoryRef c = v.categories.get(i);
                if (i > 0) {
                    html.append(" &bull; ");
                }
                if (!c.area.isEmpty()) {
                    html.append("<a href=\"").append(esc(baseUrl + "/a/" + HackerTubeUrlUtil.encode(c.area)))
                            .append("\">").append(esc(c.areaName.isEmpty() ? c.area : c.areaName))
                            .append("</a> / ");
                }
                html.append("<a href=\"").append(esc(baseUrl + "/c/" + HackerTubeUrlUtil.encode(c.id)))
                        .append("\">").append(esc(c.name)).append("</a>");
            }
            html.append("</p>");
        }

        if (!v.tags.isEmpty()) {
            html.append("<p><b>Topics:</b> ");
            for (int i = 0; i < v.tags.size(); i++) {
                if (i > 0) {
                    html.append(' ');
                }
                html.append('#').append(esc(v.tags.get(i).replace(' ', '_')));
            }
            html.append("</p>");
        }

        final String ytUrl = HackerTubeUrlUtil.canonicalYouTubeWatchUrl(v.vid);
        final String htUrl = HackerTubeUrlUtil.canonicalWatchUrl(baseUrl, v.vid);
        html.append("<p><a href=\"").append(esc(ytUrl)).append("\">Open on YouTube</a> &bull; ")
                .append("<a href=\"").append(esc(htUrl)).append("\">Open on HackerTube</a></p>");

        if (isYtReady()) {
            final Description ytDesc = ytExtractor.getDescription();
            if (ytDesc != null && !ytDesc.getContent().isEmpty()) {
                html.append("<hr><p><b>YouTube Description</b></p>");
                if (ytDesc.getType() == Description.HTML) {
                    html.append(ytDesc.getContent());
                } else {
                    html.append("<p>").append(esc(ytDesc.getContent()).replace("\n", "<br>")).append("</p>");
                }
            }
        }
        return new Description(html.toString(), Description.HTML);
    }

    @Nonnull
    @Override
    public List<String> getTags() throws ParsingException {
        final Set<String> merged = new LinkedHashSet<>();
        final VideoRecord v = htVideo();
        if (v != null) {
            if (!v.primaryTopic.isEmpty()) {
                merged.add(v.primaryTopic);
            }
            merged.addAll(v.tags);
        }
        if (isYtReady()) {
            merged.addAll(ytExtractor.getTags());
        }
        return new ArrayList<>(merged);
    }

    @Nonnull
    @Override
    public String getCategory() throws ParsingException {
        final VideoRecord v = htVideo();
        if (v != null && !v.categories.isEmpty()) {
            return v.categories.get(0).name;
        }
        return isYtReady() ? ytExtractor.getCategory() : "";
    }

    @Nonnull
    @Override
    public String getUploaderName() throws ParsingException {
        if (isYtReady()) {
            final String ytName = ytExtractor.getUploaderName();
            if (!ytName.isEmpty()) {
                return ytName;
            }
        }
        final VideoRecord v = htVideo();
        return v == null ? "" : v.channel;
    }

    @Nonnull
    @Override
    public String getUploaderUrl() throws ParsingException {
        final String name = getUploaderName();
        if (name.isEmpty()) {
            return "";
        }
        return repository.getPrefs().getBaseUrl() + "/channel/" + HackerTubeUrlUtil.encode(name);
    }

    @Nonnull
    @Override
    public String getUploaderAvatarUrl() throws ParsingException {
        return isYtReady() ? ytExtractor.getUploaderAvatarUrl() : "";
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() throws ParsingException {
        return isYtReady() ? ytExtractor.getUploaderAvatars() : Collections.emptyList();
    }

    @Override
    public boolean isUploaderVerified() throws ParsingException {
        return isYtReady() && ytExtractor.isUploaderVerified();
    }

    @Override
    public long getUploaderSubscriberCount() throws ParsingException {
        return isYtReady() ? ytExtractor.getUploaderSubscriberCount() : UNKNOWN_SUBSCRIBER_COUNT;
    }

    @Override
    public long getLength() throws ParsingException {
        if (isYtReady()) {
            return ytExtractor.getLength();
        }
        final VideoRecord v = htVideo();
        return v != null && v.durationSeconds > 0 ? v.durationSeconds : 0L;
    }

    @Override
    public long getTimeStamp() throws ParsingException {
        return isYtReady() ? ytExtractor.getTimeStamp() : 0L;
    }

    @Override
    public long getViewCount() throws ParsingException {
        if (isYtReady()) {
            return ytExtractor.getViewCount();
        }
        final VideoRecord v = htVideo();
        return v == null ? -1L : v.views;
    }

    @Override
    public long getLikeCount() throws ParsingException {
        if (isYtReady()) {
            return ytExtractor.getLikeCount();
        }
        final VideoRecord v = htVideo();
        return v == null ? -1L : v.likes;
    }

    @Override
    public long getDislikeCount() throws ParsingException {
        return isYtReady() ? ytExtractor.getDislikeCount() : -1L;
    }

    @Nullable
    @Override
    public String getTextualUploadDate() throws ParsingException {
        if (isYtReady()) {
            return ytExtractor.getTextualUploadDate();
        }
        final VideoRecord v = htVideo();
        return v == null ? null : HackerTubeItemConverter.formatTextualDate(v);
    }

    @Nullable
    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        if (isYtReady()) {
            return ytExtractor.getUploadDate();
        }
        final VideoRecord v = htVideo();
        return v == null ? null : HackerTubeItemConverter.parseUploadDate(v.published);
    }

    @Nonnull
    @Override
    public String getDashMpdUrl() throws ParsingException {
        return isYtReady() ? ytExtractor.getDashMpdUrl() : "";
    }

    @Nonnull
    @Override
    public String getHlsUrl() throws ParsingException {
        return isYtReady() ? ytExtractor.getHlsUrl() : "";
    }

    @Nullable
    @Override
    public Map<String, String> getManifestRequestHeaders() throws ParsingException {
        return isYtReady() ? ytExtractor.getManifestRequestHeaders() : null;
    }

    @Override
    public List<AudioStream> getAudioStreams() throws IOException, ExtractionException {
        return isYtReady() ? ytExtractor.getAudioStreams() : Collections.emptyList();
    }

    @Override
    public List<VideoStream> getVideoStreams() throws IOException, ExtractionException {
        return isYtReady() ? ytExtractor.getVideoStreams() : Collections.emptyList();
    }

    @Override
    public List<VideoStream> getVideoOnlyStreams() throws IOException, ExtractionException {
        return isYtReady() ? ytExtractor.getVideoOnlyStreams() : Collections.emptyList();
    }

    @Nonnull
    @Override
    public List<SubtitlesStream> getSubtitlesDefault() throws IOException, ExtractionException {
        return isYtReady() ? ytExtractor.getSubtitlesDefault() : Collections.emptyList();
    }

    @Nonnull
    @Override
    public List<SubtitlesStream> getSubtitles(final MediaFormat format)
            throws IOException, ExtractionException {
        return isYtReady() ? ytExtractor.getSubtitles(format) : Collections.emptyList();
    }

    @Nonnull
    @Override
    public List<Frameset> getFrames() throws ExtractionException {
        return isYtReady() ? ytExtractor.getFrames() : Collections.emptyList();
    }

    @Nonnull
    @Override
    public List<StreamSegment> getStreamSegments() throws ParsingException {
        return isYtReady() ? ytExtractor.getStreamSegments() : Collections.emptyList();
    }

    @Nonnull
    @Override
    public String getLicence() throws ParsingException {
        return isYtReady() ? ytExtractor.getLicence() : "";
    }

    @Nullable
    @Override
    public Locale getLanguageInfo() throws ParsingException {
        return isYtReady() ? ytExtractor.getLanguageInfo() : null;
    }

    @Override
    public Privacy getPrivacy() throws ParsingException {
        return isYtReady() ? ytExtractor.getPrivacy() : Privacy.PUBLIC;
    }

    @Override
    public boolean isShortFormContent() throws ParsingException {
        return isYtReady() && ytExtractor.isShortFormContent();
    }

    @Override
    public boolean requiresMembership() throws ParsingException {
        return isYtReady() && ytExtractor.requiresMembership();
    }

    @Override
    public boolean isSupportComments() {
        return true;
    }

    @Override
    public boolean isSupportBulletComments() {
        return false;
    }

    @Override
    public boolean isSupportRelatedItems() {
        return true;
    }

    @Override
    public InfoItemsCollector<? extends InfoItem, ? extends InfoItemExtractor> getRelatedItems() {
        final List<VideoRecord> rel = repository.getRelated(getLinkHandler().getId(), 20);
        return HackerTubeItemConverter.collectStreams(getService(), repository.getPrefs(), rel);
    }

    @Override
    public String getErrorMessage() {
        if (ytExtractor != null) {
            final String msg = ytExtractor.getErrorMessage();
            if (msg != null && !msg.isEmpty()) {
                return msg;
            }
        }
        return ytError == null ? null : ytError.getMessage();
    }

    private static String esc(final String s) {
        return Entities.escape(s == null ? "" : s);
    }
}
