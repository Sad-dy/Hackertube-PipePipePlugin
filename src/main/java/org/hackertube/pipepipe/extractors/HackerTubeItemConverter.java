package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.linkhandler.HackerTubeChannelLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.InfoItemsCollector;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Shared converter turning {@link VideoRecord} items into {@link StreamInfoItem}s for kiosks,
 * playlists, channels, search results, and related videos without duplicating field mapping.
 */
public final class HackerTubeItemConverter {

    private HackerTubeItemConverter() {
    }

    public static StreamInfoItemsCollector collectStreams(final StreamingService htService,
                                                          final HackerTubePrefs prefs,
                                                          final List<VideoRecord> videos) {
        final boolean directYt = prefs.isDirectYouTubeMode();
        final int itemServiceId = directYt
                ? ServiceList.YouTube.getServiceId()
                : htService.getServiceId();
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(itemServiceId);
        for (final VideoRecord v : videos) {
            if (v == null || v.vid.isEmpty()) {
                continue;
            }
            collector.commit(toItemExtractor(prefs, v, directYt));
        }
        return collector;
    }

    public static void addStreamsToMultiCollector(final MultiInfoItemsCollector collector,
                                                  final HackerTubePrefs prefs,
                                                  final List<VideoRecord> videos) {
        final boolean directYt = prefs.isDirectYouTubeMode();
        final List<InfoItem> directItems = directYt ? new ArrayList<>(videos.size()) : null;
        for (final VideoRecord v : videos) {
            if (v == null || v.vid.isEmpty()) {
                continue;
            }
            if (directYt) {
                // MultiInfoItemsCollector stamps its own serviceId when extracting; construct YT item directly.
                directItems.add(buildDirectStreamItem(prefs, v));
            } else {
                collector.commit(toItemExtractor(prefs, v, false));
            }
        }
        if (directItems != null && !directItems.isEmpty()) {
            collector.addAll(directItems);
        }
    }

    private static StreamInfoItem buildDirectStreamItem(final HackerTubePrefs prefs, final VideoRecord v) {
        final StreamInfoItem item = new StreamInfoItem(
                ServiceList.YouTube.getServiceId(),
                HackerTubeUrlUtil.canonicalYouTubeWatchUrl(v.vid),
                safeTitle(v),
                StreamType.VIDEO_STREAM);
        item.setDuration(v.durationSeconds > 0 ? v.durationSeconds : -1L);
        item.setUploaderName(v.channel);
        item.setViewCount(v.views);
        item.setThumbnailUrl(resolveThumbnail(prefs, v));
        item.setTextualUploadDate(formatTextualDate(v));
        item.setUploadDate(parseUploadDate(v.published));
        item.setShortDescription(buildCardShortDescription(v));
        return item;
    }

    public static StreamInfoItemExtractor toItemExtractor(final HackerTubePrefs prefs,
                                                          final VideoRecord v,
                                                          final boolean directYt) {
        final String baseUrl = prefs.getBaseUrl();
        final String itemUrl = directYt
                ? HackerTubeUrlUtil.canonicalYouTubeWatchUrl(v.vid)
                : HackerTubeUrlUtil.canonicalWatchUrl(baseUrl, v.vid);
        final String uploaderUrl = (!directYt && !v.channel.isEmpty())
                ? baseUrl + "/channel/" + HackerTubeUrlUtil.encode(v.channel)
                : "";

        return new StreamInfoItemExtractor() {
            @Override
            public StreamType getStreamType() {
                return StreamType.VIDEO_STREAM;
            }

            @Override
            public long getDuration() {
                return v.durationSeconds > 0 ? v.durationSeconds : -1L;
            }

            @Override
            public long getViewCount() {
                return v.views;
            }

            @Override
            public String getUploaderName() {
                return v.channel;
            }

            @Override
            public String getUploaderId() {
                return v.channel.isEmpty() ? null
                        : HackerTubeChannelLinkHandlerFactory.channelIdFor(v.channel);
            }

            @Override
            public String getUploaderUrl() {
                return uploaderUrl;
            }

            @Nullable
            @Override
            public String getTextualUploadDate() {
                return formatTextualDate(v);
            }

            @Nullable
            @Override
            public DateWrapper getUploadDate() {
                return parseUploadDate(v.published);
            }

            @Nullable
            @Override
            public String getShortDescription() {
                return buildCardShortDescription(v);
            }

            @Override
            public String getName() {
                return safeTitle(v);
            }

            @Override
            public String getUrl() {
                return itemUrl;
            }

            @Override
            public String getThumbnailUrl() {
                return resolveThumbnail(prefs, v);
            }
        };
    }

    public static String resolveThumbnail(final HackerTubePrefs prefs, final VideoRecord v) {
        if (prefs.isUseVideoFrames()) {
            return HackerTubeUrlUtil.buildThumbnailUrl(v.vid, true);
        }
        if (!v.thumbnail.isEmpty()) {
            return v.thumbnail;
        }
        return HackerTubeUrlUtil.buildThumbnailUrl(v.vid, false);
    }

    @Nullable
    public static DateWrapper parseUploadDate(@Nullable final String published) {
        if (published == null || published.isEmpty()) {
            return null;
        }
        try {
            return new DateWrapper(OffsetDateTime.parse(published));
        } catch (final DateTimeParseException ignored) {
            return null;
        }
    }

    @Nullable
    public static String formatTextualDate(final VideoRecord v) {
        if (!v.published.isEmpty() && v.published.length() >= 10) {
            return v.published.substring(0, 10);
        }
        if (v.hn.firstYear > 0 && v.hn.lastYear > v.hn.firstYear) {
            return "HN " + v.hn.firstYear + "–" + v.hn.lastYear;
        }
        if (v.hn.lastYear > 0) {
            return "HN " + v.hn.lastYear;
        }
        return null;
    }

    @Nullable
    public static String buildCardShortDescription(final VideoRecord v) {
        final StringBuilder sb = new StringBuilder();
        if (!v.reason.isEmpty()) {
            sb.append(v.reason);
        } else if (!v.note.isEmpty()) {
            sb.append(v.note);
        } else if (!v.whyHn.isEmpty()) {
            sb.append(v.whyHn);
        } else if (!v.summary.isEmpty()) {
            sb.append(v.summary);
        }
        if (v.hn.strongRecs > 0 || v.hn.recommenders > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(v.hn.recommenders).append(" HN recs");
            if (v.hn.strongRecs > 0) {
                sb.append(" (").append(v.hn.strongRecs).append(" strong)");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    public static PlaylistInfoItemExtractor toCategoryPlaylistItemExtractor(final String baseUrl,
                                                                            final CategoryRef cat) {
        final String url = HackerTubeUrlUtil.stripTrailingSlash(baseUrl)
                + "/c/" + HackerTubeUrlUtil.encode(cat.id);
        final String subtitle = cat.areaName.isEmpty() ? "HackerTube Topic" : cat.areaName;
        return new PlaylistInfoItemExtractor() {
            @Override
            public String getUploaderName() {
                return subtitle;
            }

            @Override
            public long getStreamCount() {
                return cat.count > 0 ? cat.count : -1L;
            }

            @Override
            public String getName() {
                return cat.name.isEmpty() ? cat.id : cat.name;
            }

            @Override
            public String getUrl() {
                return url;
            }

            @Override
            public String getThumbnailUrl() {
                return "";
            }
        };
    }

    public static ChannelInfoItemExtractor toCreatorChannelItemExtractor(final String baseUrl,
                                                                         final String channelName,
                                                                         final long approxCount) {
        final String url = HackerTubeUrlUtil.stripTrailingSlash(baseUrl)
                + "/channel/" + HackerTubeUrlUtil.encode(channelName);
        return new ChannelInfoItemExtractor() {
            @Override
            public String getDescription() {
                return "YouTube creator featured on HackerTube";
            }

            @Override
            public long getSubscriberCount() {
                return -1L;
            }

            @Override
            public long getStreamCount() {
                return approxCount > 0 ? approxCount : -1L;
            }

            @Override
            public boolean isVerified() {
                return false;
            }

            @Override
            public String getName() {
                return channelName;
            }

            @Override
            public String getUrl() {
                return url;
            }

            @Override
            public String getThumbnailUrl() {
                return "";
            }
        };
    }

    public static Page buildOffsetPage(final String pageUrlBase, final int nextOffset) {
        final String sep = pageUrlBase.contains("?") ? "&" : "?";
        return new Page(pageUrlBase + sep + "offset=" + nextOffset, String.valueOf(nextOffset));
    }

    public static int parseOffset(@Nullable final Page page) {
        if (page == null) {
            return 0;
        }
        final String fromQuery = HackerTubeUrlUtil.extractQueryParam(
                HackerTubeUrlUtil.parseQuery(page.getUrl()), "offset");
        if (fromQuery != null) {
            try {
                return Math.max(0, Integer.parseInt(fromQuery));
            } catch (final NumberFormatException ignored) {
            }
        }
        if (page.getId() != null) {
            try {
                return Math.max(0, Integer.parseInt(page.getId()));
            } catch (final NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static String safeTitle(final VideoRecord v) {
        return v.title.isEmpty() ? v.vid : v.title;
    }
}
