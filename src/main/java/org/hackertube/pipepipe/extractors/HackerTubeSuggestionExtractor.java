package org.hackertube.pipepipe.extractors;

import org.hackertube.pipepipe.engine.HackerTubeRepository;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistSummary;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local zero-latency search suggestions using cached Areas, Categories, Curated Playlists, and
 * the {@code *} browse-all wildcard hint.
 */
public final class HackerTubeSuggestionExtractor extends SuggestionExtractor {

    private static final int MAX_SUGGESTIONS = 10;

    private final HackerTubeRepository repository;

    public HackerTubeSuggestionExtractor(final StreamingService service,
                                         final HackerTubeRepository repository) {
        super(service);
        this.repository = repository;
    }

    @Override
    public List<String> suggestionList(final String query) {
        final String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        final Map<String, String> out = new LinkedHashMap<>();

        if (q.isEmpty() || "*".equals(q) || "all".startsWith(q)) {
            out.put("*", "*");
        }

        final List<AreaEntry> areas = repository.getCachedAreasOnly();
        final List<PlaylistSummary> playlists = repository.getCachedPlaylistsOnly();

        // Prefix matches first
        for (final AreaEntry area : areas) {
            addIfMatch(out, area.name, q, true);
            for (final CategoryRef c : area.categories) {
                addIfMatch(out, c.name, q, true);
            }
        }
        for (final PlaylistSummary p : playlists) {
            addIfMatch(out, p.title, q, true);
        }

        // Substring matches second
        if (!q.isEmpty() && out.size() < MAX_SUGGESTIONS) {
            for (final AreaEntry area : areas) {
                addIfMatch(out, area.name, q, false);
                for (final CategoryRef c : area.categories) {
                    addIfMatch(out, c.name, q, false);
                }
            }
            for (final PlaylistSummary p : playlists) {
                addIfMatch(out, p.title, q, false);
            }
        }

        return new ArrayList<>(out.values());
    }

    private static void addIfMatch(final Map<String, String> out,
                                   final String label,
                                   final String qLower,
                                   final boolean prefixOnly) {
        if (out.size() >= MAX_SUGGESTIONS || label == null || label.isEmpty()) {
            return;
        }
        final String low = label.toLowerCase(Locale.ROOT);
        final boolean matched;
        if (qLower.isEmpty() || "*".equals(qLower)) {
            matched = prefixOnly;
        } else if (prefixOnly) {
            matched = low.startsWith(qLower);
        } else {
            matched = low.contains(qLower);
        }
        if (matched) {
            out.putIfAbsent(low, label);
        }
    }
}
