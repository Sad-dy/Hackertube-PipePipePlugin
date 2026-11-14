package org.hackertube.pipepipe;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.extractors.HackerTubeCategoriesKioskExtractor;
import org.hackertube.pipepipe.extractors.HackerTubeChannelExtractor;
import org.hackertube.pipepipe.extractors.HackerTubeChannelTabExtractor;
import org.hackertube.pipepipe.extractors.HackerTubeCommentsExtractor;
import org.hackertube.pipepipe.extractors.HackerTubeKioskExtractor;
import org.hackertube.pipepipe.extractors.HackerTubePlaylistExtractor;
import org.hackertube.pipepipe.extractors.HackerTubePlaylistsKioskExtractor;
import org.hackertube.pipepipe.extractors.HackerTubeSearchExtractor;
import org.hackertube.pipepipe.extractors.HackerTubeStreamExtractor;
import org.hackertube.pipepipe.extractors.HackerTubeSuggestionExtractor;
import org.hackertube.pipepipe.linkhandler.HackerTubeChannelLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeChannelTabLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeCommentsLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeKioskLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubePlaylistLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeSearchQueryHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.util.Arrays;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.AUDIO;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.COMMENTS;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.SPONSORBLOCK;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

/**
 * PipePipe {@link StreamingService} implementation for HackerTube ({@code hackertube.org}).
 */
public final class HackerTubeService extends StreamingService {

    private static final long INFO_CACHE_TTL_MS = 10L * 60L * 1000L;

    private final HackerTubePrefs prefs;
    private final HackerTubeRepository repository;

    private final HackerTubeStreamLinkHandlerFactory streamLH;
    private final HackerTubeChannelLinkHandlerFactory channelLH;
    private final HackerTubeChannelTabLinkHandlerFactory channelTabLH;
    private final HackerTubePlaylistLinkHandlerFactory playlistLH;
    private final HackerTubeSearchQueryHandlerFactory searchQH;
    private final HackerTubeCommentsLinkHandlerFactory commentsLH;
    private final HackerTubeKioskLinkHandlerFactory kioskLH;

    public HackerTubeService(final int id,
                             final HackerTubePrefs prefs,
                             final HackerTubeRepository repository) {
        super(id, "HackerTube", Arrays.asList(AUDIO, VIDEO, COMMENTS, SPONSORBLOCK));
        this.prefs = prefs;
        this.repository = repository;
        this.streamLH = new HackerTubeStreamLinkHandlerFactory(prefs);
        this.channelLH = new HackerTubeChannelLinkHandlerFactory(prefs);
        this.channelTabLH = new HackerTubeChannelTabLinkHandlerFactory(prefs);
        this.playlistLH = new HackerTubePlaylistLinkHandlerFactory(prefs);
        this.searchQH = new HackerTubeSearchQueryHandlerFactory(prefs);
        this.commentsLH = new HackerTubeCommentsLinkHandlerFactory(prefs);
        this.kioskLH = new HackerTubeKioskLinkHandlerFactory(prefs);
    }

    @Override
    public String getBaseUrl() {
        return prefs.getBaseUrl();
    }

    @Override
    public long getInfoCacheExpirationMillis() {
        return INFO_CACHE_TTL_MS;
    }

    @Override
    public LinkHandlerFactory getStreamLHFactory() {
        return streamLH;
    }

    @Override
    public ListLinkHandlerFactory getChannelLHFactory() {
        return channelLH;
    }

    @Override
    public ListLinkHandlerFactory getChannelTabLHFactory() {
        return channelTabLH;
    }

    @Override
    public ListLinkHandlerFactory getPlaylistLHFactory() {
        return playlistLH;
    }

    @Override
    public SearchQueryHandlerFactory getSearchQHFactory() {
        return searchQH;
    }

    @Override
    public ListLinkHandlerFactory getCommentsLHFactory() {
        return commentsLH;
    }

    @Override
    public SearchExtractor getSearchExtractor(final SearchQueryHandler queryHandler) {
        return new HackerTubeSearchExtractor(this, queryHandler, repository);
    }

    @Override
    public SuggestionExtractor getSuggestionExtractor() {
        return new HackerTubeSuggestionExtractor(this, repository);
    }

    @Override
    public SubscriptionExtractor getSubscriptionExtractor() {
        return null;
    }

    @Override
    public KioskList getKioskList() throws ExtractionException {
        final KioskList list = new KioskList(this);
        final KioskList.KioskExtractorFactory factory = (streamingService, url, kioskId) -> {
            final ListLinkHandler handler = kioskLH.fromId(kioskId);
            if (HackerTubeKioskLinkHandlerFactory.KIOSK_CATEGORIES.equals(kioskId)) {
                return new HackerTubeCategoriesKioskExtractor(
                        HackerTubeService.this, handler, kioskId, repository);
            }
            if (HackerTubeKioskLinkHandlerFactory.KIOSK_PLAYLISTS.equals(kioskId)) {
                return new HackerTubePlaylistsKioskExtractor(
                        HackerTubeService.this, handler, kioskId, repository);
            }
            return new HackerTubeKioskExtractor(
                    HackerTubeService.this, handler, kioskId, repository);
        };

        try {
            list.addKioskEntry(factory, kioskLH, HackerTubeKioskLinkHandlerFactory.KIOSK_HOME);
            list.addKioskEntry(factory, kioskLH, HackerTubeKioskLinkHandlerFactory.KIOSK_LONGS);
            list.addKioskEntry(factory, kioskLH, HackerTubeKioskLinkHandlerFactory.KIOSK_CATEGORIES);
            list.addKioskEntry(factory, kioskLH, HackerTubeKioskLinkHandlerFactory.KIOSK_PLAYLISTS);
            list.addKioskEntry(factory, kioskLH, HackerTubeKioskLinkHandlerFactory.KIOSK_HIDDEN_GEMS);
            list.addKioskEntry(factory, kioskLH, HackerTubeKioskLinkHandlerFactory.KIOSK_RECENTLY_RECOMMENDED);
            list.addKioskEntry(factory, kioskLH, HackerTubeKioskLinkHandlerFactory.KIOSK_TOP_RANKED);
            list.setDefaultKiosk(HackerTubeKioskLinkHandlerFactory.KIOSK_HOME);
        } catch (final Exception e) {
            throw new ExtractionException("Failed to initialize HackerTube kiosk list", e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler linkHandler) {
        return new HackerTubeChannelExtractor(this, linkHandler, repository);
    }

    @Override
    public ChannelTabExtractor getChannelTabExtractor(final ListLinkHandler linkHandler) {
        return new HackerTubeChannelTabExtractor(this, linkHandler, repository);
    }

    @Override
    public PlaylistExtractor getPlaylistExtractor(final ListLinkHandler linkHandler) {
        return new HackerTubePlaylistExtractor(this, linkHandler, repository);
    }

    @Override
    public StreamExtractor getStreamExtractor(final LinkHandler linkHandler) {
        return new HackerTubeStreamExtractor(this, linkHandler, repository);
    }

    @Override
    public CommentsExtractor getCommentsExtractor(final ListLinkHandler linkHandler) {
        return new HackerTubeCommentsExtractor(this, linkHandler, repository);
    }
}
