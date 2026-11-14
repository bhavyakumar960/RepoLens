package org.example.springaimcp.rag.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class HybridBatchStatsTest {

    @Test
    void addTracksFailedFilePathsForTargetedReingestion() {
        HybridIngestionService.HybridBatchStats stats = new HybridIngestionService.HybridBatchStats();

        stats.add(new HybridIngestionService.HybridIngestionResult(
                "/tmp/Ok.java",
                null,
                null,
                true,
                null
        ));
        stats.add(new HybridIngestionService.HybridIngestionResult(
                "/tmp/FailedOne.java",
                null,
                null,
                false,
                "first"
        ));
        stats.add(new HybridIngestionService.HybridIngestionResult(
                "/tmp/FailedTwo.java",
                null,
                null,
                false,
                "second"
        ));

        assertEquals(3, stats.getTotalFiles());
        assertEquals(1, stats.getSuccessfulFiles());
        assertEquals(2, stats.getFailedFiles());
        assertIterableEquals(
                List.of("/tmp/FailedOne.java", "/tmp/FailedTwo.java"),
                stats.getFailedFilePaths()
        );
    }
}
