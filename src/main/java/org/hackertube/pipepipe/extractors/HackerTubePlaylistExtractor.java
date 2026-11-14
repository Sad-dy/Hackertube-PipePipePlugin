package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.engine.HackerTubeRepository.CategoryPlaylistResult;
import org.hackertube.pipepipe.linkhandler.HackerTubePlaylistLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistDetail;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Extracts either a curated "Playlists by Claude" playlist ({@code p:<slug>}) or a Topic Category
 * collection ({@code c:<catId>}).
 */
public final class HackerTubePlaylistExtractor extends PlaylistExtractor {

    private final HackerTubeRepository repository;
    @Nullable private PlaylistDetail curated;
    @Nullable private CategoryPlaylistResult initialCategory;

    public HackerTubePlaylistExtractor(final StreamingService service,
                                       final ListLinkHandler linkHandler,
                                       final HackerTubeRepository repository) {
        super(service, linkHandler);
        this.repository = repository;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        final String id = getId();
        final String target = HackerTubePlaylistLinkHandlerFactory.unwrapTarget(id);
        if (HackerTubePlaylistLinkHandlerFactory.isCategoryId(id)) {
            initialCategory = repository.getCategoryPlaylist(
                    target, 0, HackerTubeRepository.DEFAULT_PAGE_SIZE);
        } else {
            curated = repository.getCuratedPlaylist(target);
        }
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        if (curated != null) {
            return curated.title.isEmpty() ? getId() : curated.title;
        }
        if (initialCategory != null && initialCategory.category != null
                && !initialCategory.category.name.isEmpty()) {
            return initialCategory.category.name;
        }
        return HackerTubePlaylistLinkHandlerFactory.unwrapTarget(getId());
    }

    @Nonnull
    @Override
    public String getThumbnailUrl() throws ParsingException {
        final List<VideoRecord> videos = curated != null
                ? curated.videos
                : (initialCategory != null ? initialCategory.videos : null);
        if (videos != null && !videos.isEmpty()) {
            return HackerTubeItemConverter.resolveThumbnail(repository.getPrefs(), videos.get(0));
        }
        return "";
    }

    @Override
    public String getUploaderUrl() {
        final String baseUrl = repository.getPrefs().getBaseUrl();
        if (initialCategory != null && initialCategory.category != null
                && !initialCategory.category.area.isEmpty()) {
            return baseUrl + "/a/" + HackerTubeUrlUtil.encode(initialCategory.category.area);
        }
        if (curated != null) {
            return baseUrl + "/playlists";
        }
        return baseUrl + "/browse";
    }

    @Override
    public String getUploaderName() {
        if (curated != null) {
            final String tier = "deep".equalsIgnoreCase(curated.tier) ? "Deep Dive" : "Start Here";
            final String runtime = curated.minutes > 0 ? " · " + curated.minutes + " min" : "";
            return "HackerTube · " + tier + runtime;
        }
        if (initialCategory != null && initialCategory.category != null
                && !initialCategory.category.areaName.isEmpty()) {
            return initialCategory.category.areaName;
        }
        return "HackerTube";
    }

    @Override
    public String getUploaderAvatarUrl() {
        return "";
    }

    @Override
    public boolean isUploaderVerified() {
        return false;
    }

    @Override
    public long getStreamCount() {
        if (curated != null) {
            return curated.videos.size();
        }
        if (initialCategory != null && initialCategory.category != null
                && initialCategory.category.count > 0) {
            return initialCategory.category.count;
        }
        return ITEM_COUNT_UNKNOWN;
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        final HackerTubePrefs prefs = repository.getPrefs();
        if (curated != null) {
            final StreamInfoItemsCollector collector = HackerTubeItemConverter.collectStreams(
                    getService(), prefs, curated.videos);
            return new InfoItemsPage<>(collector, null);
        }
        final String id = getId();
        final String catId = HackerTubePlaylistLinkHandlerFactory.unwrapTarget(id);
        final CategoryPlaylistResult res = initialCategory != null
                ? initialCategory
                : repository.getCategoryPlaylist(catId, 0, HackerTubeRepository.DEFAULT_PAGE_SIZE);
        return buildCategoryPage(res.videos, 0);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        final String id = getId();
        if (!HackerTubePlaylistLinkHandlerFactory.isCategoryId(id)) {
            return InfoItemsPage.emptyPage();
        }
        final int offset = HackerTubeItemConverter.parseOffset(page);
        final String catId = HackerTubePlaylistLinkHandlerFactory.unwrapTarget(id);
        final CategoryPlaylistResult res = repository.getCategoryPlaylist(
                catId, offset, HackerTubeRepository.DEFAULT_PAGE_SIZE);
        return buildCategoryPage(res.videos, offset);
    }

    private InfoItemsPage<StreamInfoItem> buildCategoryPage(final List<VideoRecord> videos,
                                                            final int offset)
            throws ParsingException {
        final StreamInfoItemsCollector collector = HackerTubeItemConverter.collectStreams(
                getService(), repository.getPrefs(), videos);
        final int pageSize = HackerTubeRepository.DEFAULT_PAGE_SIZE;
        final Page nextPage = videos.size() >= pageSize
                ? HackerTubeItemConverter.buildOffsetPage(getUrl(), offset + pageSize)
                : null;
        return new InfoItemsPage<>(collector, nextPage);
    }
}
