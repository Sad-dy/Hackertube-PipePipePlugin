package org.hackertube.pipepipe;

import org.hackertube.pipepipe.engine.LocalRecommender;
import org.hackertube.pipepipe.engine.LocalSnapshotData;
import org.hackertube.pipepipe.engine.NpyFloat16Reader;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.HnStats;
import org.hackertube.pipepipe.model.HackerTubeModels.MentionRecord;
import org.hackertube.pipepipe.model.HackerTubeModels.RelatedRef;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpyAndRecommenderTest {

    @Test
    void npyFloat16ReaderAndBinaryCacheRoundtrip(@TempDir final Path tempDir) throws Exception {
        // 3 rows x 4 dims float16:
        // row 0 = [1.0, 0.0, 0.0, 0.0] -> 0x3C00
        // row 1 = [0.5, 0.5, 0.0, 0.0] -> 0x3800
        // row 2 = [-1.0, 0.0, 1.0, 0.0] -> 0xBC00, 0x3C00
        final short[] halfs = new short[]{
                (short) 0x3C00, 0, 0, 0,
                (short) 0x3800, (short) 0x3800, 0, 0,
                (short) 0xBC00, 0, (short) 0x3C00, 0
        };
        final byte[] npyBytes = buildSyntheticNpy(3, 4, halfs);
        final NpyFloat16Reader reader = NpyFloat16Reader.readNpy(new ByteArrayInputStream(npyBytes));
        assertEquals(3, reader.rows);
        assertEquals(4, reader.dims);

        final float[] r0 = reader.getRowAsFloat(0);
        assertEquals(1.0f, r0[0], 1e-4f);
        assertEquals(0.0f, r0[1], 1e-4f);

        final float[] r2 = reader.getRowAsFloat(2);
        assertEquals(-1.0f, r2[0], 1e-4f);
        assertEquals(1.0f, r2[2], 1e-4f);

        final File binCache = tempDir.resolve("embeddings.bin").toFile();
        reader.saveBinaryCache(binCache);
        final NpyFloat16Reader reloaded = NpyFloat16Reader.loadBinaryCache(binCache);
        assertEquals(3, reloaded.rows);
        assertEquals(4, reloaded.dims);
        assertEquals(0.5f, reloaded.getRowAsFloat(1)[0], 1e-4f);

        // Positive centroid toward row 0, negative toward row 2
        final float[] taste = reloaded.buildTasteVector(
                Collections.singletonList(0),
                Collections.singletonList(2));
        assertTrue(taste[0] > taste[2]);
    }

    @Test
    void exportZipImportBackfillsTaxonomyCountsAndRelated(@TempDir final Path tempDir) throws Exception {
        final File zipFile = tempDir.resolve("hackertube-data.zip").toFile();
        final short[] embHalfs = new short[]{
                (short) 0x3C00, 0, 0, 0,
                (short) 0x3800, (short) 0x3800, 0, 0,
                0, 0, (short) 0x3C00, 0
        };
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("taxonomy.json"));
            zos.write(("{\"areas\":[{\"id\":\"software\",\"name\":\"Software Engineering\",\"categories\":["
                    + "{\"id\":\"compilers\",\"name\":\"Compilers\",\"description\":\"LLVM & parsers\"}]}]}")
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("videos.jsonl"));
            final String v1 = "{\"vid\":\"vidAAAAAAAA\",\"title\":\"Compiler Design Live\",\"channel\":\"Ch1\","
                    + "\"published\":\"2024-03-01T00:00:00Z\",\"duration_s\":1800,\"views\":50000,\"likes\":2000,"
                    + "\"summary\":\"LLVM parser deep dive\",\"why_hn\":\"Loved on HN\",\"content_type\":\"talk\","
                    + "\"level\":\"advanced\",\"shelf_life\":\"evergreen\",\"primary_topic\":\"Compilers\","
                    + "\"tags\":[\"llvm\",\"compilers\"],\"categories\":[{\"id\":\"compilers\",\"name\":\"Compilers\","
                    + "\"area\":\"software\",\"area_name\":\"Software Engineering\"}],\"hn\":{\"mentions\":4,"
                    + "\"recommenders\":3,\"rec_months\":2,\"strong_recs\":2,\"first_year\":2021,\"last_year\":2025},\"score\":92.5}\n";
            final String v2 = "{\"vid\":\"vidBBBBBBBB\",\"title\":\"Parsing Tricks\",\"channel\":\"Ch2\","
                    + "\"published\":\"2023-02-01T00:00:00Z\",\"duration_s\":1200,\"views\":20000,\"likes\":900,"
                    + "\"summary\":\"Pratt parsers\",\"why_hn\":\"Clear walkthrough\",\"content_type\":\"explainer\","
                    + "\"level\":\"intermediate\",\"shelf_life\":\"evergreen\",\"primary_topic\":\"Compilers\","
                    + "\"tags\":[\"parsing\"],\"categories\":[{\"id\":\"compilers\",\"name\":\"Compilers\","
                    + "\"area\":\"software\",\"area_name\":\"Software Engineering\"}],\"hn\":{\"mentions\":2,"
                    + "\"recommenders\":2,\"rec_months\":1,\"strong_recs\":1,\"first_year\":2023,\"last_year\":2024},\"score\":64.0}\n";
            final String v3 = "{\"vid\":\"vidCCCCCCCC\",\"title\":\"Breaking Tech News\",\"channel\":\"Ch3\","
                    + "\"published\":\"2026-01-01T00:00:00Z\",\"duration_s\":300,\"views\":1000,\"likes\":50,"
                    + "\"shelf_life\":\"news\",\"categories\":[],\"hn\":{\"mentions\":1,\"recommenders\":0,\"rec_months\":0,"
                    + "\"strong_recs\":0,\"first_year\":2026,\"last_year\":2026},\"score\":10.0}\n";
            zos.write((v1 + v2 + v3).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("mentions.jsonl"));
            zos.write(("{\"hn_comment_id\":42001,\"vid\":\"vidAAAAAAAA\",\"author\":\"pg\",\"month\":\"2024-04\","
                    + "\"story_title\":\"Show HN: Compiler\",\"stance\":\"strong_rec\",\"text\":\"Must watch\"}\n")
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("related.jsonl"));
            zos.write("{\"vid\":\"vidAAAAAAAA\",\"related\":[{\"vid\":\"vidBBBBBBBB\",\"shared_recommenders\":2}]}\n"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("embedding_ids.json"));
            zos.write("[\"vidAAAAAAAA\",\"vidBBBBBBBB\",\"vidCCCCCCCC\"]".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("embeddings.npy"));
            zos.write(buildSyntheticNpy(3, 4, embHalfs));
            zos.closeEntry();
        }

        final LocalSnapshotData snap = LocalSnapshotData.fromExportZip(zipFile);
        assertTrue(snap.isReady());
        assertEquals(3, snap.videos.size());
        assertEquals(1, snap.areas.size());
        assertEquals(2, snap.areas.get(0).count);
        assertEquals(2, snap.areas.get(0).categories.get(0).count);
        assertEquals(1, snap.mentionsByVid.get("vidAAAAAAAA").size());
        assertNotNull(snap.embeddings);

        final List<VideoRecord> related = LocalRecommender.findRelatedLocal(snap, "vidAAAAAAAA", 5);
        assertFalse(related.isEmpty());
        assertEquals("vidBBBBBBBB", related.get(0).vid);
    }

    @Test
    void recommenderHonorsWatchedNewsZeroRecDislikesAndTitleDedupe() {
        final CategoryRef swCat = new CategoryRef("compilers", "Compilers", "software", "Software", "", 4);
        final AreaEntry swArea = new AreaEntry("software", "Software", 4, Collections.singletonList(swCat));

        final VideoRecord liked = video("v1_like_0001", "Liked Architecture Talk", "chA", "evergreen", 5, 3, 95.0, swCat);
        final VideoRecord disliked = video("v2_dis_00002", "Disliked Fluff", "chB", "evergreen", 2, 0, 50.0, swCat);
        final VideoRecord dupeOne = video("v3_dup_00003", "Build Your Own Lisp!", "chC", "evergreen", 4, 2, 88.0, swCat);
        final VideoRecord dupeTwo = video("v4_dup_00004", "build your own lisp", "chC", "evergreen", 2, 0, 40.0, swCat);
        final VideoRecord watched = video("v5_seen_0005", "Watched Classic", "chD", "evergreen", 6, 3, 90.0, swCat);
        final VideoRecord news = video("v6_news_0006", "Weekly Release Notes", "chE", "news", 3, 1, 80.0, swCat);
        final VideoRecord zeroRec = video("v7_zero_0007", "Unendorsed Citation", "chF", "evergreen", 0, 0, 75.0, swCat);
        final VideoRecord freshGem = video("v8_gem_00008", "Type Systems From Scratch", "chG", "evergreen", 3, 2, 84.0, swCat);

        final Map<String, List<RelatedRef>> rel = new HashMap<>();
        rel.put("v1_like_0001", Collections.singletonList(new RelatedRef("v8_gem_00008", 3)));

        final Map<String, Integer> embRows = new HashMap<>();
        embRows.put("v1_like_0001", 0);
        embRows.put("v2_dis_00002", 1);
        embRows.put("v8_gem_00008", 2);
        // Row 0 ~= [1,0,0,0], Row 1 (disliked) = [0,1,0,0], Row 2 (gem) = [0.7, 0, 0.7, 0]
        final NpyFloat16Reader emb = new NpyFloat16Reader(3, 4, new short[]{
                (short) 0x3C00, 0, 0, 0,
                0, (short) 0x3C00, 0, 0,
                (short) 0x399A, 0, (short) 0x399A, 0
        });

        final LocalSnapshotData snap = new LocalSnapshotData(
                Arrays.asList(liked, dupeOne, watched, news, zeroRec, freshGem, disliked, dupeTwo),
                rel,
                Collections.<String, List<MentionRecord>>emptyMap(),
                Collections.singletonList(swArea),
                Collections.emptyList(),
                Collections.emptyMap(),
                embRows,
                emb);

        final List<VideoRecord> feed = LocalRecommender.recommendFeed(
                snap,
                Collections.singletonList("v1_like_0001"),
                Collections.singletonList("v2_dis_00002"),
                Collections.singletonList("v5_seen_0005"),
                "seed-abc",
                true, true, true,
                0, 20);

        assertEquals(2, feed.size());
        final List<String> vids = Arrays.asList(feed.get(0).vid, feed.get(1).vid);
        assertTrue(vids.contains("v3_dup_00003"));
        assertTrue(vids.contains("v8_gem_00008"));
        assertFalse(vids.contains("v1_like_0001"));
        assertFalse(vids.contains("v2_dis_00002"));
        assertFalse(vids.contains("v4_dup_00004"));
        assertFalse(vids.contains("v5_seen_0005"));
        assertFalse(vids.contains("v6_news_0006"));
        assertFalse(vids.contains("v7_zero_0007"));
    }

    private static VideoRecord video(final String vid, final String title, final String channel,
                                     final String shelfLife, final int recs, final int strongRecs,
                                     final double score, final CategoryRef cat) {
        return new VideoRecord(
                vid, title, channel, "2022-05-01T00:00:00Z", 1500L, 10000L, 500L,
                "", "Summary for " + title, "Why HN likes " + title,
                "talk", "intermediate", shelfLife, "CS",
                Collections.singletonList("systems"),
                Collections.singletonList(cat),
                null,
                new HnStats(recs, recs, 1, strongRecs, 2022, 2024, Collections.emptyMap()),
                score,
                Collections.emptyList(),
                Collections.emptyList(),
                "", "", ""
        );
    }

    private static byte[] buildSyntheticNpy(final int rows, final int dims, final short[] halfs)
            throws Exception {
        final String dict = "{'descr': '<f2', 'fortran_order': False, 'shape': (" + rows + ", " + dims + "), }";
        final int preamble = 10 + dict.length() + 1;
        final int pad = (16 - (preamble % 16)) % 16;
        final StringBuilder hdr = new StringBuilder(dict);
        for (int i = 0; i < pad; i++) {
            hdr.append(' ');
        }
        hdr.append('\n');
        final byte[] hdrBytes = hdr.toString().getBytes(StandardCharsets.US_ASCII);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0});
        final ByteBuffer lenBb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) hdrBytes.length);
        out.write(lenBb.array());
        out.write(hdrBytes);

        final ByteBuffer dataBb = ByteBuffer.allocate(halfs.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (final short h : halfs) {
            dataBb.putShort(h);
        }
        out.write(dataBb.array());
        return out.toByteArray();
    }
}
