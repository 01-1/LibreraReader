package com.foobnix.pdf.info;

import com.foobnix.model.AppState;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.pdf.info.wrapper.DocumentController;

import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

public final class OutlineProgressHelper {

    private OutlineProgressHelper() {
    }

    public static PageRange getProgressRange(DocumentController controller) {
        int pageCount = controller.getPageCount();
        if (!AppState.get().isScrollProgressByChapter) {
            return PageRange.wholeBook(pageCount);
        }
        return calculateRange(controller.getCurrentOutline(),
                              controller.getCurentPageFirst1(),
                              pageCount,
                              AppState.get().isScrollProgressByPart);
    }

    public static PageRange calculateRange(List<OutlineLinkWrapper> outline,
                                           int currentPage,
                                           int pageCount,
                                           boolean modulePartScope) {
        PageRange wholeBook = PageRange.wholeBook(pageCount);
        if (pageCount <= 0 || outline == null || outline.isEmpty()) {
            return wholeBook;
        }

        NavigableSet<Integer> starts = getStarts(outline, pageCount, modulePartScope);
        if (starts.isEmpty()) {
            return wholeBook;
        }

        int safeCurrentPage = clamp(currentPage, 1, pageCount);
        int startPage = 1;
        int endPage = pageCount;
        for (int candidate : starts) {
            if (candidate <= safeCurrentPage) {
                startPage = candidate;
            } else {
                endPage = candidate - 1;
                break;
            }
        }
        return new PageRange(startPage, Math.max(startPage, endPage));
    }

    public static int findPreviousChapterPage(List<OutlineLinkWrapper> outline,
                                              int currentPage,
                                              int pageCount) {
        NavigableSet<Integer> starts = getStarts(outline, pageCount, false);
        if (starts.isEmpty()) {
            return -1;
        }
        int safeCurrentPage = clamp(currentPage, 1, Math.max(1, pageCount));
        Integer currentStart = starts.floor(safeCurrentPage);
        return currentStart == null ? -1 : valueOrMinusOne(starts.lower(currentStart));
    }

    public static int findNextChapterPage(List<OutlineLinkWrapper> outline,
                                          int currentPage,
                                          int pageCount) {
        NavigableSet<Integer> starts = getStarts(outline, pageCount, false);
        if (starts.isEmpty()) {
            return -1;
        }
        int safeCurrentPage = clamp(currentPage, 1, Math.max(1, pageCount));
        Integer currentStart = starts.floor(safeCurrentPage);
        if (currentStart == null) {
            return starts.first();
        }
        return valueOrMinusOne(starts.higher(currentStart));
    }

    private static NavigableSet<Integer> getStarts(List<OutlineLinkWrapper> outline,
                                                   int pageCount,
                                                   boolean modulePartScope) {
        TreeSet<Integer> starts = new TreeSet<>();
        if (outline == null || outline.isEmpty() || pageCount <= 0) {
            return starts;
        }

        int firstLevel = Integer.MAX_VALUE;
        for (OutlineLinkWrapper item : outline) {
            if (isValidTarget(item, pageCount)) {
                firstLevel = Math.min(firstLevel, item.level);
            }
        }
        if (firstLevel == Integer.MAX_VALUE) {
            return starts;
        }

        int lastIncludedLevel = modulePartScope ? firstLevel : firstLevel + 2;
        for (OutlineLinkWrapper item : outline) {
            if (isValidTarget(item, pageCount) && item.level <= lastIncludedLevel) {
                starts.add(item.targetPage);
            }
        }
        return starts;
    }

    private static int valueOrMinusOne(Integer value) {
        return value == null ? -1 : value;
    }

    private static boolean isValidTarget(OutlineLinkWrapper item, int pageCount) {
        return item != null && item.targetPage >= 1 && item.targetPage <= pageCount;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class PageRange {
        public final int startPage;
        public final int endPage;

        public PageRange(int startPage, int endPage) {
            this.startPage = Math.max(1, startPage);
            this.endPage = Math.max(this.startPage, endPage);
        }

        public static PageRange wholeBook(int pageCount) {
            return new PageRange(1, Math.max(1, pageCount));
        }

        public int getPageCount() {
            return endPage - startPage + 1;
        }

        public int toRelativeProgress(int absolutePage) {
            return clamp(absolutePage, startPage, endPage) - startPage;
        }

        public int toAbsolutePage(int relativeProgress) {
            return startPage + clamp(relativeProgress, 0, getPageCount() - 1);
        }
    }
}
