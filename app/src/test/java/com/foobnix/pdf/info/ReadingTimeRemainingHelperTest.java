package com.foobnix.pdf.info;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.foobnix.pdf.info.ReadingTimeRemainingHelper.Estimate;

import org.junit.Test;

public class ReadingTimeRemainingHelperTest {

    @Test
    public void countsExtractedWordsInsteadOfPages() {
        assertEquals(6,
                     ReadingTimeRemainingHelper.countWords(
                             "Hello, reader! This has 123 words."));
        assertEquals(2, ReadingTimeRemainingHelper.countWords("Привет мир"));
        assertEquals(0, ReadingTimeRemainingHelper.countWords(" \n\t—… "));
        assertEquals(0, ReadingTimeRemainingHelper.countWords(null));
    }

    @Test
    public void calculatesRequestedExamplesFromWordsAndWpm() {
        Estimate estimate = ReadingTimeRemainingHelper.calculate(
                3_600,
                111_000,
                200
        );

        assertEquals(3_600, estimate.chapterWords);
        assertEquals(111_000, estimate.bookWords);
        assertEquals(18, estimate.chapterMinutes);
        assertEquals(555, estimate.bookMinutes);
    }

    @Test
    public void roundsPartialMinutesUp() {
        assertEquals(0, ReadingTimeRemainingHelper.minutesForWords(0, 200));
        assertEquals(1, ReadingTimeRemainingHelper.minutesForWords(1, 200));
        assertEquals(2, ReadingTimeRemainingHelper.minutesForWords(201, 200));
    }

    @Test
    public void zeroWordsIsAValidBoundaryEstimate() {
        assertFalse(ReadingTimeRemainingHelper.isUnavailableWordCount(0));
        assertTrue(ReadingTimeRemainingHelper.isUnavailableWordCount(-1));
    }

    @Test
    public void clampsReadingSpeedToSupportedRange() {
        assertEquals(ReadingTimeRemainingHelper.MIN_WORDS_PER_MINUTE,
                     ReadingTimeRemainingHelper.sanitizeWordsPerMinute(1));
        assertEquals(240,
                     ReadingTimeRemainingHelper.sanitizeWordsPerMinute(240));
        assertEquals(ReadingTimeRemainingHelper.MAX_WORDS_PER_MINUTE,
                     ReadingTimeRemainingHelper.sanitizeWordsPerMinute(10_000));
    }

    @Test
    public void bookEstimateNeverDropsBelowChapterEstimate() {
        Estimate estimate = ReadingTimeRemainingHelper.calculate(2_000, 500, 200);

        assertEquals(2_000, estimate.bookWords);
        assertEquals(10, estimate.bookMinutes);
    }

    @Test
    public void everyReadingTimeVisibilityCombinationLoadsIndependently() {
        for (int mask = 0; mask < 16; mask++) {
            boolean chapterExpanded = (mask & 1) != 0;
            boolean bookExpanded = (mask & 2) != 0;
            boolean chapterStatus = (mask & 4) != 0;
            boolean bookStatus = (mask & 8) != 0;
            assertEquals(
                    "chapter visibility mask " + mask,
                    chapterExpanded || chapterStatus,
                    ReadingTimeRemainingHelper.shouldLoadChapter(
                            chapterExpanded, chapterStatus));
            assertEquals(
                    "book visibility mask " + mask,
                    bookExpanded || bookStatus,
                    ReadingTimeRemainingHelper.shouldLoadBook(
                            bookExpanded, bookStatus));
            assertEquals(
                    "visibility mask " + mask,
                    mask != 0,
                    ReadingTimeRemainingHelper.shouldLoad(
                            chapterExpanded,
                            bookExpanded,
                            chapterStatus,
                            bookStatus));
        }
    }
}
