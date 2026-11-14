package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.api.HackerTubeApiClient.ListParams;
import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.linkhandler.HackerTubeKioskLinkHandlerFactory;
import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stream-returning kiosks: {@code Home}, {@code Longs}, {@code Hidden gems},
 * {@code Recently recommended}, and {@code Top ranked}.
 */
public final class HackerTubeKioskExtractor extends KioskExtractor<StreamInfoItem> {

    private final HackerTubeRepository repository;

    public HackerTubeKioskExtractor(final StreamingService service,
                                    final ListLinkHandler linkHandler,
                                    final String kioskId,
                                    final HackerTubeRepository repository) {
        super(service, linkHandler, kioskId);
        this.repository = repository;
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) {
        // Loaded dynamically in getInitialPage() / getPage()
    }

    @Nonnull
    @Override
    public String getName() {
        switch (getId()) {
            case HackerTubeKioskLinkHandlerFactory.KIOSK_HOME:
                return "Home";
            case HackerTubeKioskLinkHandlerFactory.KIOSK_LONGS:
                return "Longs";
            case HackerTubeKioskLinkHandlerFactory.KIOSK_HIDDEN_GEMS:
                return "Hidden gems";
            case HackerTubeKioskLinkHandlerFactory.KIOSK_RECENTLY_RECOMMENDED:
                return "Recently recommended";
            case HackerTubeKioskLinkHandlerFactory.KIOSK_TOP_RANKED:
                return "Top ranked";
            default:
                return getId();
        }
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        final String seed = String.valueOf(ThreadLocalRandom.current().nextInt(1, 1_000_000_000));
        return fetchOffset(0, seed);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        final int offset = parseOffset(page);
        final String seed = extractSeed(page);
        return fetchOffset(offset, seed);
    }

    private InfoItemsPage<StreamInfoItem> fetchOffset(final int offset, final String seed)
            throws IOException {
        final int pageSize = HackerTubeRepository.DEFAULT_PAGE_SIZE;
        final List<VideoRecord> items;
        if (HackerTubeKioskLinkHandlerFactory.KIOSK_HOME.equals(getId())) {
            items = repository.getFeed(seed, offset, pageSize);
        } else {
            items = repository.getList(paramsForKiosk(), true, offset, pageSize);
        }

        final StreamInfoItemsCollector collector = HackerTubeItemConverter.collectStreams(
                getService(), repository.getPrefs(), items);
        final Page nextPage = items.size() < pageSize && !HackerTubeKioskLinkHandlerFactory.KIOSK_HOME.equals(getId())
                ? null
                : (items.isEmpty() ? null : buildNextPage(offset + pageSize, seed));
        return new InfoItemsPage<>(collector, nextPage);
    }

    private ListParams paramsForKiosk() {
        final ListParams p = new ListParams();
        switch (getId()) {
            case HackerTubeKioskLinkHandlerFactory.KIOSK_LONGS:
                p.dur = "xlong";
                p.sort = "score";
                break;
            case HackerTubeKioskLinkHandlerFactory.KIOSK_HIDDEN_GEMS:
                p.gems = true;
                p.sort = "score";
                break;
            case HackerTubeKioskLinkHandlerFactory.KIOSK_RECENTLY_RECOMMENDED:
                p.sort = "recent";
                break;
            case HackerTubeKioskLinkHandlerFactory.KIOSK_TOP_RANKED:
            default:
                p.sort = "score";
                break;
        }
        return p;
    }

    private Page buildNextPage(final int nextOffset, final String seed) {
        final String base = repository.getPrefs().getBaseUrl();
        return new Page(base + "/kiosk/" + getId() + "?seed=" + HackerTubeUrlUtil.encode(seed)
                + "&offset=" + nextOffset, String.valueOf(nextOffset));
    }

    private static int parseOffset(@Nullable final Page page) {
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

    private static String extractSeed(@Nullable final Page page) {
        if (page == null) {
            return "42";
        }
        final String seed = HackerTubeUrlUtil.extractQueryParam(
                HackerTubeUrlUtil.parseQuery(page.getUrl()), "seed");
        if (seed == null || seed.isEmpty()) {
            return "42";
        }
        return seed.matches("\\d+") ? seed : String.valueOf(seed.hashCode() & 0x7fffffff);
    }
}
