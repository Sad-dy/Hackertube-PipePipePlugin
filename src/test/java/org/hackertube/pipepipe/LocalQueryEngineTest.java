package org.hackertube.pipepipe;

import org.hackertube.pipepipe.api.HackerTubeApiClient.ListParams;
import org.hackertube.pipepipe.engine.LocalQueryEngine;
import org.hackertube.pipepipe.engine.LocalSnapshotData;
import org.hackertube.pipepipe.engine.NpyFloat16Reader;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.HnStats;
import org.hackertube.pipepipe.model.HackerTubeModels.SearchResponse;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalQueryEngineTest {

    @Test
    void filtersAndSortsMatchHackerTubeFacets() {
        final LocalSnapshotData snap = sampleSnapshot();

        final ListParams gemLong = new ListParams();
        gemLong.gems = true;
        gemLong.dur = "xlong";
        final List<VideoRecord> resGems = LocalQueryEngine.queryList(snap, gemLong, false, false, 0, 20);
        assertEquals(1, resGems.size());
        assertEquals("vidLegacy01", resGems.get(0).vid);

        final ListParams levelAdv = new ListParams();
        levelAdv.level = "advanced";
        levelAdv.date = "2010s";
        assertTrue(LocalQueryEngine.needsClientSidePostFilter(levelAdv, false, false));
        final List<VideoRecord> resAdv = LocalQueryEngine.queryList(snap, levelAdv, false, false, 0, 20);
        assertEquals(1, resAdv.size());
        assertEquals("vidRust1501", resAdv.get(0).vid);

        final ListParams viewsSort = new ListParams();
        viewsSort.sort = "views";
        final List<VideoRecord> byViews = LocalQueryEngine.queryList(snap, viewsSort, true, false, 0, 20);
        assertEquals(3, byViews.size()); // news video filtered out by hideNews=true
        assertEquals("vidRust1501", byViews.get(0).vid);
        assertEquals("vidEmb00001", byViews.get(2).vid);

        final ListParams oldSort = new ListParams();
        oldSort.sort = "old";
        final List<VideoRecord> byOld = LocalQueryEngine.queryList(snap, oldSort, true, false, 0, 20);
        assertEquals("vidLegacy01", byOld.get(0).vid);
    }

    @Test
    void searchSupportsWildcardTaxonomySynonymsAndEmbeddingNeighbors() {
        final LocalSnapshotData snap = sampleSnapshot();

        // Wildcard '*' with a filter acts like browse-all with facets
        final ListParams areaOnly = new ListParams();
        areaOnly.area = "systems-security";
        final SearchResponse wild = LocalQueryEngine.search(snap, "*", areaOnly, 0, 20);
        assertFalse(wild.weak);
        assertEquals(2, wild.results.size());

        // Taxonomy description synonym match ("wasm" only appears in category description for vidLegacy01? No, in systems cat description!)
        final SearchResponse tax = LocalQueryEngine.search(snap, "sandboxing", null, 0, 20);
        assertFalse(tax.weak);
        assertFalse(tax.results.isEmpty());

        // Lexical match on vidRust1501 pulls in embedding neighbor vidEmb00001 (similarity > 0.32)
        final SearchResponse hybrid = LocalQueryEngine.search(snap, "borrow checker", null, 0, 20);
        assertFalse(hybrid.results.isEmpty());
        assertEquals("vidRust1501", hybrid.results.get(0).vid);
        assertTrue(hybrid.results.stream().anyMatch(v -> "vidEmb00001".equals(v.vid)));
    }

    private static LocalSnapshotData sampleSnapshot() {
        final CategoryRef sysCat = new CategoryRef(
                "kernel-wasm", "Kernels & Sandboxing", "systems-security",
                "Systems, Networks & Security", "Operating systems, hypervisors, sandboxing", 2);
        final CategoryRef langCat = new CategoryRef(
                "compilers", "Compilers", "software",
                "Software Engineering & Languages", "LLVM, parsers, type systems", 2);
        final AreaEntry sysArea = new AreaEntry("systems-security", "Systems", 2, Collections.singletonList(sysCat));
        final AreaEntry swArea = new AreaEntry("software", "Software", 2, Collections.singletonList(langCat));

        final VideoRecord legacyLongGem = new VideoRecord(
                "vidLegacy01", "Original UNIX Documentary", "AT&T Archives", "1982-01-01T00:00:00Z",
                3600L, 80_000L, 12_000L, "", "Early UNIX history", "Fondly cited",
                "documentary", "intro", "evergreen", "UNIX", Collections.singletonList("unix"),
                Collections.singletonList(sysCat), null,
                new HnStats(5, 4, 3, 2, 2012, 2024, Collections.emptyMap()), 95.0,
                Collections.emptyList(), Collections.emptyList(), "", "", "");

        final VideoRecord rust2010s = new VideoRecord(
                "vidRust1501", "Rust Borrow Checker Deep Dive", "ConfChannel", "2018-06-15T00:00:00Z",
                1800L, 900_000L, 25_000L, "", "Ownership internals", "Classic talk",
                "talk", "advanced", "evergreen", "Rust", Collections.singletonList("rust"),
                Collections.singletonList(sysCat), null,
                new HnStats(4, 3, 2, 1, 2019, 2023, Collections.emptyMap()), 88.0,
                Collections.emptyList(), Collections.emptyList(), "", "", "");

        // Semantically close to rust2010s without matching keywords lexically
        final VideoRecord embNeighbor = new VideoRecord(
                "vidEmb00001", "Linear Types Illustrated", "UniversityLectures", "2024-02-10T00:00:00Z",
                900L, 15_000L, 700L, "", "Substructural logic in programming", "Clear visuals",
                "lecture", "intermediate", "evergreen", "Types", Collections.singletonList("types"),
                Collections.singletonList(langCat), null,
                new HnStats(1, 1, 1, 0, 2024, 2024, Collections.emptyMap()), 60.0,
                Collections.emptyList(), Collections.emptyList(), "", "", "");

        final VideoRecord newsItem = new VideoRecord(
                "vidNews0001", "Daily Tech Headline", "NewsDaily", "2026-01-02T00:00:00Z",
                240L, 50_000L, 800L, "", "Breaking announcement", "Top story today",
                "commentary", "general", "news", "Industry", Collections.singletonList("news"),
                Collections.singletonList(langCat), null,
                new HnStats(1, 1, 1, 0, 2026, 2026, Collections.emptyMap()), 40.0,
                Collections.emptyList(), Collections.emptyList(), "", "", "");

        final Map<String, Integer> embRows = new HashMap<>();
        embRows.put("vidLegacy01", 0);
        embRows.put("vidRust1501", 1);
        embRows.put("vidEmb00001", 2);
        embRows.put("vidNews0001", 3);
        // rust2010s=[1,0,0,0], embNeighbor=[1,0,0,0] (cos=1.0), legacy=[0,1,0,0], news=[0,0,1,0]
        final NpyFloat16Reader emb = new NpyFloat16Reader(4, 4, new short[]{
                0, (short) 0x3C00, 0, 0,
                (short) 0x3C00, 0, 0, 0,
                (short) 0x3C00, 0, 0, 0,
                0, 0, (short) 0x3C00, 0
        });

        return new LocalSnapshotData(
                Arrays.asList(legacyLongGem, rust2010s, embNeighbor, newsItem),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Arrays.asList(sysArea, swArea),
                Collections.emptyList(),
                Collections.emptyMap(),
                embRows,
                emb);
    }
}
