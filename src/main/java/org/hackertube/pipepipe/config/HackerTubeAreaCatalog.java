package org.hackertube.pipepipe.config;

import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical 16 HackerTube topic areas (matches {@code /api/browse} and {@code taxonomy.json}).
 */
public final class HackerTubeAreaCatalog {

    public static final List<AreaEntry> DEFAULT_AREAS = Collections.unmodifiableList(Arrays.asList(
            new AreaEntry("arts-entertainment", "Arts, Comedy, Music & Film", 1731, null),
            new AreaEntry("software", "Software Engineering & Languages", 1604, null),
            new AreaEntry("politics-history-society", "Politics, History & Society", 1425, null),
            new AreaEntry("computing-history-culture", "Computing History & Culture", 1295, null),
            new AreaEntry("engineering-making", "Engineering, Transport & Making", 994, null),
            new AreaEntry("systems-security", "Systems, Networks & Security", 956, null),
            new AreaEntry("life-sciences-health", "Life Sciences, Mind & Health", 827, null),
            new AreaEntry("business-economics", "Business, Startups & Economics", 787, null),
            new AreaEntry("ideas-education", "Ideas, Learning & Language", 782, null),
            new AreaEntry("hardware", "Hardware & Electronics", 769, null),
            new AreaEntry("physics-space", "Physics, Space & Chemistry", 768, null),
            new AreaEntry("ai-data", "AI, Machine Learning & Data", 718, null),
            new AreaEntry("games", "Games & Game Development", 503, null),
            new AreaEntry("design-graphics-media", "Design, Graphics & Visual Media", 501, null),
            new AreaEntry("math-theory", "Mathematics & Theoretical CS", 479, null),
            new AreaEntry("earth-energy-environment", "Earth, Energy & Environment", 448, null)
    ));

    private static final Map<String, AreaEntry> BY_ID;

    static {
        final Map<String, AreaEntry> map = new LinkedHashMap<>();
        for (final AreaEntry area : DEFAULT_AREAS) {
            map.put(area.id, area);
        }
        BY_ID = Collections.unmodifiableMap(map);
    }

    private HackerTubeAreaCatalog() {
    }

    public static AreaEntry getDefault(final String areaId) {
        return BY_ID.get(areaId);
    }

    public static boolean isKnownAreaId(final String areaId) {
        return areaId != null && BY_ID.containsKey(areaId);
    }
}
