package com.foobnix.pdf.info;

import static org.junit.Assert.assertEquals;

import com.foobnix.pdf.info.OutlineProgressHelper.PageRange;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class OutlineProgressHelperTest {

    @Test
    public void chapterScopeUsesChapterAndSectionLevels() {
        List<OutlineLinkWrapper> outline = outline(
                item(1, 0),
                item(3, 1),
                item(6, 2),
                item(8, 3),
                item(10, 1),
                item(20, 0)
        );

        PageRange range = OutlineProgressHelper.calculateRange(outline, 7, 30, false);

        assertEquals(6, range.startPage);
        assertEquals(9, range.endPage);
        assertEquals(4, range.getPageCount());
    }

    @Test
    public void moduleScopeUsesOnlyTopLevelEntries() {
        List<OutlineLinkWrapper> outline = outline(
                item(1, 0),
                item(3, 1),
                item(10, 1),
                item(20, 0)
        );

        PageRange range = OutlineProgressHelper.calculateRange(outline, 7, 30, true);

        assertEquals(1, range.startPage);
        assertEquals(19, range.endPage);
    }

    @Test
    public void missingOutlineFallsBackToWholeBook() {
        PageRange empty = OutlineProgressHelper.calculateRange(Collections.emptyList(), 7, 30, false);
        PageRange missing = OutlineProgressHelper.calculateRange(null, 7, 30, true);

        assertEquals(1, empty.startPage);
        assertEquals(30, empty.endPage);
        assertEquals(1, missing.startPage);
        assertEquals(30, missing.endPage);
    }

    @Test
    public void rangeMappingUsesOneBasedPagesAndClamps() {
        PageRange range = new PageRange(6, 9);

        assertEquals(0, range.toRelativeProgress(5));
        assertEquals(2, range.toRelativeProgress(8));
        assertEquals(3, range.toRelativeProgress(12));
        assertEquals(6, range.toAbsolutePage(-1));
        assertEquals(8, range.toAbsolutePage(2));
        assertEquals(9, range.toAbsolutePage(99));
    }

    @Test
    public void chapterArrowsSkipCurrentChapterAndDeepOutlineEntries() {
        List<OutlineLinkWrapper> outline = outline(
                item(1, 0),
                item(3, 1),
                item(6, 2),
                item(8, 3),
                item(10, 1),
                item(20, 0)
        );

        assertEquals(3, OutlineProgressHelper.findPreviousChapterPage(outline, 7, 30));
        assertEquals(10, OutlineProgressHelper.findNextChapterPage(outline, 7, 30));
        assertEquals(6, OutlineProgressHelper.findPreviousChapterPage(outline, 10, 30));
        assertEquals(20, OutlineProgressHelper.findNextChapterPage(outline, 10, 30));
    }

    @Test
    public void chapterArrowsHandleFrontMatterAndBookEnds() {
        List<OutlineLinkWrapper> outline = outline(item(3, 0), item(10, 0));

        assertEquals(-1, OutlineProgressHelper.findPreviousChapterPage(outline, 1, 20));
        assertEquals(3, OutlineProgressHelper.findNextChapterPage(outline, 1, 20));
        assertEquals(-1, OutlineProgressHelper.findPreviousChapterPage(outline, 3, 20));
        assertEquals(10, OutlineProgressHelper.findNextChapterPage(outline, 3, 20));
        assertEquals(3, OutlineProgressHelper.findPreviousChapterPage(outline, 20, 20));
        assertEquals(-1, OutlineProgressHelper.findNextChapterPage(outline, 20, 20));
    }

    @Test
    public void duplicateAndInvalidTargetsDoNotCreateBrokenRanges() {
        List<OutlineLinkWrapper> outline = outline(
                item(-1, 0),
                item(5, 0),
                item(5, 1),
                item(15, 0),
                item(40, 0)
        );

        PageRange range = OutlineProgressHelper.calculateRange(outline, 7, 20, false);

        assertEquals(5, range.startPage);
        assertEquals(14, range.endPage);
        assertEquals(15, OutlineProgressHelper.findNextChapterPage(outline, 7, 20));
    }

    private static OutlineLinkWrapper item(int targetPage, int level) {
        OutlineLinkWrapper item = new OutlineLinkWrapper("Item " + targetPage, null, level, null);
        item.targetPage = targetPage;
        return item;
    }

    private static List<OutlineLinkWrapper> outline(OutlineLinkWrapper... items) {
        return Arrays.asList(items);
    }
}
