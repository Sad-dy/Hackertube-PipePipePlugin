package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.api.HackerTubeApiClient.ListParams;
import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.linkhandler.HackerTubeChannelLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Extracts either a HackerTube Topic Area ({@code area:<id>}, tabs: Videos / Playlists / Search)
 * or a HackerTube-filtered Creator Channel ({@code channel:<name>}, tabs: Videos / Search).
 */
public final class HackerTubeChannelExtractor extends ChannelExtractor {

    private final HackerTubeRepository repository;
    @Nullable private AreaEntry area;
    @Nullable private List<VideoRecord> initialVideos;

    public HackerTubeChannelExtractor(final StreamingService service,
                                      final ListLinkHandler linkHandler,
                                      final HackerTubeRepository repository) {
        super(service, linkHandler);
        this.repository = repository;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        final String id = getId();
        final String target = HackerTubeChannelLinkHandlerFactory.unwrapTarget(id);
        if (HackerTubeChannelLinkHandlerFactory.isAreaId(id)) {
            area = repository.getArea(target);
            if (area == null) {
                area = new AreaEntry(target, target, 0, Collections.emptyList());
            }
        }
        initialVideos = repository.getList(
                paramsForTarget(id, target), false, 0, HackerTubeRepository.DEFAULT_PAGE_SIZE);
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        if (area != null && !area.name.isEmpty()) {
            return area.name;
        }
        return HackerTubeChannelLinkHandlerFactory.unwrapTarget(getId());
    }

    @Override
    public String getAvatarUrl() {
        return "";
    }

    @Override
    public String getBannerUrl() {
        return "";
    }

    @Override
    public long getSubscriberCount() {
        return UNKNOWN_SUBSCRIBER_COUNT;
    }

    @Override
    public String getDescription() throws ParsingException {
        final HackerTubePrefs prefs = repository.getPrefs();
        if (area != null) {
            final StringBuilder sb = new StringBuilder();
            if (area.count > 0) {
                sb.append(area.count).append(" HackerTube videos in ").append(area.name).append('.');
            } else {
                sb.append("HackerTube Topic Area: ").append(area.name).append('.');
            }
            if (!area.categories.isEmpty()) {
                sb.append("\n\nCategories (see Playlists tab):");
                for (final CategoryRef c : area.categories) {
                    sb.append("\n- ").append(c.name);
                    if (c.count > 0) {
                        sb.append(" (").append(c.count).append(')');
                    }
                }
            }
            return sb.toString();
        }
        final String creator = HackerTubeChannelLinkHandlerFactory.unwrapTarget(getId());
        return "YouTube creator \"" + creator + "\" curated on HackerTube ("
                + prefs.getBaseUrl() + "/channel/" + HackerTubeUrlUtil.encode(creator)
                + "). Use the Search tab inside this channel to search their HackerTube appearances.";
    }

    @Nonnull
    @Override
    public List<ListLinkHandler> getTabs() throws ParsingException {
        final String id = getId();
        if (HackerTubeChannelLinkHandlerFactory.isAreaId(id)) {
            return Arrays.asList(
                    tabHandler(id, ChannelTabs.VIDEOS),
                    tabHandler(id, ChannelTabs.PLAYLISTS),
                    tabHandler(id, ChannelTabs.SEARCH)
            );
        }
        return Arrays.asList(
                tabHandler(id, ChannelTabs.VIDEOS),
                tabHandler(id, ChannelTabs.SEARCH)
        );
    }

    @Nonnull
    @Override
    public List<String> getTags() {
        final Set<String> out = new LinkedHashSet<>();
        if (area != null) {
            for (final CategoryRef c : area.categories) {
                if (!c.name.isEmpty()) {
                    out.add(c.name);
                    if (out.size() >= 12) {
                        break;
                    }
                }
            }
            return new ArrayList<>(out);
        }
        if (initialVideos != null) {
            for (final VideoRecord v : initialVideos) {
                if (!v.primaryTopic.isEmpty()) {
                    out.add(v.primaryTopic);
                }
                for (final String tag : v.tags) {
                    if (!tag.isEmpty()) {
                        out.add(tag);
                    }
                    if (out.size() >= 12) {
                        return new ArrayList<>(out);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        final List<VideoRecord> videos = initialVideos != null
                ? initialVideos
                : repository.getList(
                        paramsForTarget(getId(), HackerTubeChannelLinkHandlerFactory.unwrapTarget(getId())),
                        false, 0, HackerTubeRepository.DEFAULT_PAGE_SIZE);
        return buildPage(videos, 0);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        final int offset = HackerTubeItemConverter.parseOffset(page);
        final String id = getId();
        final String target = HackerTubeChannelLinkHandlerFactory.unwrapTarget(id);
        final List<VideoRecord> videos = repository.getList(
                paramsForTarget(id, target), false, offset, HackerTubeRepository.DEFAULT_PAGE_SIZE);
        return buildPage(videos, offset);
    }

    private InfoItemsPage<StreamInfoItem> buildPage(final List<VideoRecord> videos, final int offset)
            throws ParsingException {
        final StreamInfoItemsCollector collector = HackerTubeItemConverter.collectStreams(
                getService(), repository.getPrefs(), videos);
        final int pageSize = HackerTubeRepository.DEFAULT_PAGE_SIZE;
        final Page nextPage = videos.size() >= pageSize
                ? HackerTubeItemConverter.buildOffsetPage(getUrl(), offset + pageSize)
                : null;
        return new InfoItemsPage<>(collector, nextPage);
    }

    private ListLinkHandler tabHandler(final String id, final String tab) throws ParsingException {
        return getService().getChannelTabLHFactory().fromQuery(
                id,
                Collections.singletonList(new FilterItem(Filter.ITEM_IDENTIFIER_UNKNOWN, tab)),
                null);
    }

    private static ListParams paramsForTarget(final String id, final String target) {
        final ListParams p = new ListParams();
        if (HackerTubeChannelLinkHandlerFactory.isAreaId(id)) {
            p.area = target;
        } else {
            p.channel = target;
        }
        p.sort = "score";
        return p;
    }
}
