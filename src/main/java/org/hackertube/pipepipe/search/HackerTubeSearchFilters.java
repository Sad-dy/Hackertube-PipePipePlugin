package org.hackertube.pipepipe.search;

import org.hackertube.pipepipe.api.HackerTubeApiClient.ListParams;
import org.hackertube.pipepipe.config.HackerTubeAreaCatalog;
import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.SearchFiltersBase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Search and browse filters mirroring HackerTube's facets plus Topic Area & Level selectors so
 * users can filter via Search (or wildcard {@code *}) without Kiosk filter chips in PipePipe.
 */
public final class HackerTubeSearchFilters extends SearchFiltersBase {

    public static final String CONTENT_ALL = "all";
    public static final String CONTENT_VIDEOS = "videos";
    public static final String CONTENT_PLAYLISTS = "playlists";
    public static final String CONTENT_CHANNELS = "channels";

    private int contentFilterAllId;
    private int contentFilterVideosId;
    private int contentFilterPlaylistsId;
    private int contentFilterChannelsId;

    public HackerTubeSearchFilters() {
        init();
        build();
    }

    @Override
    protected void init() {
        contentFilterAllId = builder.addSortItem(new HackerTubeFilterItem(CONTENT_ALL, "cf", CONTENT_ALL));
        contentFilterVideosId = builder.addSortItem(new HackerTubeFilterItem(CONTENT_VIDEOS, "cf", CONTENT_VIDEOS));
        contentFilterPlaylistsId = builder.addSortItem(new HackerTubeFilterItem(CONTENT_PLAYLISTS, "cf", CONTENT_PLAYLISTS));
        contentFilterChannelsId = builder.addSortItem(new HackerTubeFilterItem(CONTENT_CHANNELS, "cf", CONTENT_CHANNELS));
        this.defaultContentFilterId = contentFilterAllId;

        addContentFilter(builder.createSortGroup(null, true, new FilterItem[]{
                builder.getFilterForId(contentFilterAllId),
                builder.getFilterForId(contentFilterVideosId),
                builder.getFilterForId(contentFilterPlaylistsId),
                builder.getFilterForId(contentFilterChannelsId),
        }));

        // Upload date
        final FilterGroup dateGroup = singleGroup("upload_date", new String[][]{
                {"all", null, null},
                {"past_year", "date", "year"},
                {"2020s", "date", "2020s"},
                {"2010s", "date", "2010s"},
                {"Before 2010", "date", "2000s"},
        });

        // Content type
        final FilterGroup typeGroup = singleGroup("Type", new String[][]{
                {"all", null, null},
                {"Talk", "type", "talk"},
                {"Lecture", "type", "lecture"},
                {"Course", "type", "course"},
                {"Documentary", "type", "documentary"},
                {"Explainer", "type", "explainer"},
                {"Interview", "type", "interview"},
                {"Tutorial", "type", "tutorial"},
                {"Demo", "type", "demo"},
                {"Commentary", "type", "commentary"},
                {"Comedy", "type", "comedy"},
                {"Music", "type", "music"},
        });

        // Duration
        final FilterGroup durationGroup = singleGroup("duration", new String[][]{
                {"all", null, null},
                {"short_video", "dur", "short"},
                {"medium_length", "dur", "medium"},
                {"long_video", "dur", "long"},
                {"extra_long", "dur", "xlong"},
        });

        // Multi-select Features
        final int featGems = builder.addSortItem(new HackerTubeFilterItem("Hidden gems", "gems", "1"));
        final int featEvergreen = builder.addSortItem(new HackerTubeFilterItem("Evergreen only", "evergreen", "1"));
        final FilterGroup featuresGroup = builder.createSortGroup("features", false, new FilterItem[]{
                builder.getFilterForId(featGems),
                builder.getFilterForId(featEvergreen),
        });

        // Sort by
        final FilterGroup sortGroup = singleGroup("sortby", new String[][]{
                {"sort_relevance", null, null},
                {"Score (HN rank)", "sort", "score"},
                {"Recently recommended", "sort", "recent"},
                {"sort_view", "sort", "views"},
                {"sort_likes", "sort", "liked"},
                {"oldest", "sort", "old"},
        });

        // Topic Area
        final List<String[]> areaEntries = new ArrayList<>();
        areaEntries.add(new String[]{"all", null, null});
        for (final AreaEntry area : HackerTubeAreaCatalog.DEFAULT_AREAS) {
            areaEntries.add(new String[]{area.name, "area", area.id});
        }
        final FilterGroup areaGroup = singleGroup("Area", areaEntries.toArray(new String[0][]));

        // Technical Level
        final FilterGroup levelGroup = singleGroup("Level", new String[][]{
                {"all", null, null},
                {"General", "level", "general"},
                {"Intro", "level", "intro"},
                {"Intermediate", "level", "intermediate"},
                {"Advanced", "level", "advanced"},
        });

        final Filter videoSortFilters = new Filter.Builder(new FilterGroup[]{
                sortGroup,
                dateGroup,
                typeGroup,
                durationGroup,
                featuresGroup,
                areaGroup,
                levelGroup,
        }).build();

        final Filter emptySortFilters = new Filter.Builder(new FilterGroup[0]).build();

        // First call sets allSortFiltersVariant on SearchFiltersBase (skipped from map)
        addContentFilterSortVariant(-1, videoSortFilters);
        addContentFilterSortVariant(contentFilterAllId, videoSortFilters);
        addContentFilterSortVariant(contentFilterVideosId, videoSortFilters);
        addContentFilterSortVariant(contentFilterPlaylistsId, emptySortFilters);
        addContentFilterSortVariant(contentFilterChannelsId, emptySortFilters);
    }

    private FilterGroup singleGroup(final String groupName, final String[][] defs) {
        final FilterItem[] items = new FilterItem[defs.length];
        for (int i = 0; i < defs.length; i++) {
            final String label = defs[i][0];
            final String key = defs[i][1];
            final String val = defs[i][2];
            final int id = builder.addSortItem(new HackerTubeFilterItem(label, key, val));
            items[i] = builder.getFilterForId(id);
        }
        return builder.createSortGroup(groupName, true, items);
    }

    @Override
    public String evaluateSelectedFilters(final String searchString) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("cf", evaluateSelectedContentFilters());

        if (selectedSortFilter != null) {
            for (final FilterItem item : selectedSortFilter) {
                if (item instanceof HackerTubeFilterItem) {
                    final HackerTubeFilterItem ht = (HackerTubeFilterItem) item;
                    if (ht.paramKey != null && ht.paramVal != null) {
                        params.put(ht.paramKey, ht.paramVal);
                    }
                }
            }
        }

        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(HackerTubeUrlUtil.encode(e.getKey()))
                    .append('=')
                    .append(HackerTubeUrlUtil.encode(e.getValue()));
        }
        return sb.toString();
    }

    @Override
    public String evaluateSelectedContentFilters() {
        if (selectedContentFilter != null && !selectedContentFilter.isEmpty()) {
            final FilterItem first = selectedContentFilter.get(0);
            if (first instanceof HackerTubeFilterItem) {
                final String v = ((HackerTubeFilterItem) first).paramVal;
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
            if (first != null && first.getName() != null) {
                return first.getName();
            }
        }
        return CONTENT_ALL;
    }

    public static String resolveContentFilter(@Nullable final List<FilterItem> contentFilters,
                                              @Nullable final String rawUrlQuery) {
        if (contentFilters != null && !contentFilters.isEmpty()) {
            final FilterItem first = contentFilters.get(0);
            if (first instanceof HackerTubeFilterItem && ((HackerTubeFilterItem) first).paramVal != null) {
                return ((HackerTubeFilterItem) first).paramVal;
            }
            if (first != null && first.getName() != null && !first.getName().isEmpty()) {
                return first.getName();
            }
        }
        final String fromQuery = HackerTubeUrlUtil.extractQueryParam(rawUrlQuery, "cf");
        return (fromQuery == null || fromQuery.isEmpty()) ? CONTENT_ALL : fromQuery;
    }

    public static ListParams resolveListParams(@Nullable final List<FilterItem> sortFilters,
                                               @Nullable final String rawUrlQuery) {
        final ListParams params = new ListParams();
        if (rawUrlQuery != null && !rawUrlQuery.isEmpty()) {
            params.date = HackerTubeUrlUtil.extractQueryParam(rawUrlQuery, "date");
            params.type = HackerTubeUrlUtil.extractQueryParam(rawUrlQuery, "type");
            params.dur = HackerTubeUrlUtil.extractQueryParam(rawUrlQuery, "dur");
            params.gems = "1".equals(HackerTubeUrlUtil.extractQueryParam(rawUrlQuery, "gems"));
            params.evergreen = "1".equals(HackerTubeUrlUtil.extractQueryParam(rawUrlQuery, "evergreen"));
            params.sort = HackerTubeUrlUtil.extractQueryParam(rawUrlQuery, "sort");
            params.area = HackerTubeUrlUtil.extractQueryParam(rawUrlQuery, "area");
            params.level = HackerTubeUrlUtil.extractQueryParam(rawUrlQuery, "level");
        }
        if (sortFilters != null) {
            for (final FilterItem item : sortFilters) {
                if (!(item instanceof HackerTubeFilterItem)) {
                    continue;
                }
                final HackerTubeFilterItem ht = (HackerTubeFilterItem) item;
                if (ht.paramKey == null || ht.paramVal == null) {
                    continue;
                }
                switch (ht.paramKey) {
                    case "date": params.date = ht.paramVal; break;
                    case "type": params.type = ht.paramVal; break;
                    case "dur": params.dur = ht.paramVal; break;
                    case "gems": params.gems = "1".equals(ht.paramVal); break;
                    case "evergreen": params.evergreen = "1".equals(ht.paramVal); break;
                    case "sort": params.sort = ht.paramVal; break;
                    case "area": params.area = ht.paramVal; break;
                    case "level": params.level = ht.paramVal; break;
                    default: break;
                }
            }
        }
        return params;
    }

    public static final class HackerTubeFilterItem extends FilterItem {
        @Nullable public final String paramKey;
        @Nullable public final String paramVal;

        public HackerTubeFilterItem(final String name,
                                    @Nullable final String paramKey,
                                    @Nullable final String paramVal) {
            super(Filter.ITEM_IDENTIFIER_UNKNOWN, name);
            this.paramKey = paramKey;
            this.paramVal = paramVal;
        }
    }
}
