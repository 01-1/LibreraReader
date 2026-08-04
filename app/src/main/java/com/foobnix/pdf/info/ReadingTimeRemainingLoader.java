package com.foobnix.pdf.info;

import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.LOG;
import com.foobnix.pdf.info.wrapper.DocumentController;

import org.ebookdroid.BookType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReadingTimeRemainingLoader {

    private static final int EPUB_PAGE_TEXT_ATTEMPTS = 3;
    private static final int EPUB_NEARBY_PAGE_LIMIT = 2;
    private static final long EPUB_PAGE_TEXT_RETRY_DELAY_MS = 75L;

    public interface Callback {
        void onChapterResult(int wordsRemaining, int minutesRemaining);
        void onBookResult(int wordsRemaining, int minutesRemaining);
    }

    private final DocumentController controller;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "librera-reading-time");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final Map<Integer, Integer> wordsByPage = new ConcurrentHashMap<>();
    private final Map<Integer, List<String>> pageWordsByPage = new ConcurrentHashMap<>();
    private final AtomicInteger generation = new AtomicInteger();

    private Future<?> activeRequest;
    private int cachedPageCount = -1;
    private EpubReadingTimeIndex epubIndex;
    private int lastEpubSourceWord = -1;
    private int epubVisibleBookEndWord = -1;
    private boolean isShutdown;

    public ReadingTimeRemainingLoader(DocumentController controller) {
        this.controller = controller;
    }

    public synchronized void load(int currentPage,
                                  int pageCount,
                                  int chapterEndPage,
                                  int wordsPerMinute,
                                  boolean calculateChapter,
                                  boolean calculateBook,
                                  Callback callback) {
        if (isShutdown) {
            return;
        }

        int requestGeneration = generation.incrementAndGet();
        if (activeRequest != null) {
            activeRequest.cancel(true);
        }
        if (cachedPageCount != pageCount) {
            wordsByPage.clear();
            pageWordsByPage.clear();
            cachedPageCount = pageCount;
            epubVisibleBookEndWord = -1;
        }

        final int safePageCount = Math.max(0, pageCount);
        final int safeCurrentPage = Math.max(1, Math.min(Math.max(1, safePageCount), currentPage));
        final int safeChapterEnd = Math.max(safeCurrentPage,
                                            Math.min(Math.max(1, safePageCount), chapterEndPage));
        final int safeWordsPerMinute =
                ReadingTimeRemainingHelper.sanitizeWordsPerMinute(wordsPerMinute);
        final boolean isEpub = BookType.EPUB.is(controller.getCurrentBook().getPath());

        activeRequest = executor.submit(new Runnable() {
            @Override public void run() {
                if (isEpub) {
                    loadEpub(requestGeneration,
                             safeCurrentPage,
                             safePageCount,
                             safeChapterEnd,
                             safeWordsPerMinute,
                             calculateChapter,
                             calculateBook,
                             callback);
                    return;
                }

                int chapterWords = 0;
                int bookWords = 0;
                for (int page = safeCurrentPage; page <= safeChapterEnd; page++) {
                    if (Thread.currentThread().isInterrupted() ||
                            generation.get() != requestGeneration) {
                        return;
                    }
                    int pageWords = getWordsForPage(page - 1);
                    bookWords += pageWords;
                    chapterWords += pageWords;
                }

                if (calculateChapter) {
                    postChapter(requestGeneration,
                                chapterWords,
                                safeWordsPerMinute,
                                callback);
                }
                if (!calculateBook) {
                    return;
                }

                for (int page = safeChapterEnd + 1; page <= safePageCount; page++) {
                    if (Thread.currentThread().isInterrupted() ||
                            generation.get() != requestGeneration) {
                        return;
                    }
                    bookWords += getWordsForPage(page - 1);
                }
                postBook(requestGeneration, bookWords, safeWordsPerMinute, callback);
            }
        });
    }

    private void loadEpub(int requestGeneration,
                          int currentPage,
                          int pageCount,
                          int chapterEndPage,
                          int wordsPerMinute,
                          boolean calculateChapter,
                          boolean calculateBook,
                          Callback callback) {
        try {
            if (epubIndex == null) {
                epubIndex = EpubReadingTimeIndex.load(controller.getCurrentBook());
            }
            EpubAnchor anchor = epubIndex == null ? null :
                    locateEpubAnchor(currentPage - 1, pageCount);
            if (anchor == null && epubIndex != null && lastEpubSourceWord >= 0) {
                EpubReadingTimeIndex.Position lastPosition =
                        epubIndex.positionAt(lastEpubSourceWord);
                if (lastPosition != null) {
                    anchor = new EpubAnchor(lastPosition, Collections.emptyList());
                }
            }
            if (anchor == null) {
                postUnavailable(requestGeneration,
                                calculateChapter,
                                calculateBook,
                                callback);
                return;
            }
            EpubReadingTimeIndex.Position position = anchor.position;
            lastEpubSourceWord = position.sourceWord;
            if (calculateChapter) {
                int chapterWords = position.chapterWordsRemaining;
                if (chapterEndPage < pageCount) {
                    List<String> nextChapterWords = getPageWords(chapterEndPage);
                    EpubReadingTimeIndex.Position nextChapter =
                            epubIndex.locate(nextChapterWords,
                                             position.sourceWord + anchor.pageWords.size());
                    if (nextChapter != null &&
                            nextChapter.sourceWord > position.sourceWord) {
                        chapterWords = nextChapter.sourceWord - position.sourceWord;
                    }
                }
                postChapter(requestGeneration,
                            chapterWords,
                            wordsPerMinute,
                            callback);
            }
            if (calculateBook) {
                int visibleBookEndWord = resolveEpubVisibleBookEnd(position, pageCount);
                EpubReadingTimeIndex.Position visiblePosition =
                        epubIndex.limitBookEnd(position, visibleBookEndWord);
                if (visiblePosition == null) {
                    postBook(requestGeneration, -1, 0, callback);
                    return;
                }
                postBook(requestGeneration,
                         visiblePosition.bookWordsRemaining,
                         wordsPerMinute,
                         callback);
            }
        } catch (IOException error) {
            LOG.e(error);
            postUnavailable(requestGeneration, calculateChapter, calculateBook, callback);
        } catch (RuntimeException error) {
            LOG.e(error);
            postUnavailable(requestGeneration, calculateChapter, calculateBook, callback);
        }
    }

    private int resolveEpubVisibleBookEnd(EpubReadingTimeIndex.Position currentPosition,
                                          int pageCount) {
        if (epubVisibleBookEndWord >= currentPosition.sourceWord) {
            return epubVisibleBookEndWord;
        }
        if (pageCount <= 0) {
            return -1;
        }

        int finalPage = pageCount - 1;
        for (int attempt = 0; attempt < EPUB_PAGE_TEXT_ATTEMPTS; attempt++) {
            List<String> finalPageWords = getPageWords(finalPage);
            if (!finalPageWords.isEmpty()) {
                EpubReadingTimeIndex.Position finalPagePosition =
                        epubIndex.locateAtOrAfter(finalPageWords,
                                                 currentPosition.sourceWord,
                                                 currentPosition.sourceWord);
                if (finalPagePosition != null) {
                    int candidateEnd =
                            epubIndex.sourceWordAfterPage(finalPagePosition, finalPageWords);
                    if (candidateEnd >= currentPosition.sourceWord) {
                        epubVisibleBookEndWord = candidateEnd;
                        return candidateEnd;
                    }
                }
            }
            pageWordsByPage.remove(finalPage);
            if (attempt + 1 < EPUB_PAGE_TEXT_ATTEMPTS && !waitForPageTextRetry()) {
                return -1;
            }
        }
        return -1;
    }

    private EpubAnchor locateEpubAnchor(int currentPage, int pageCount) {
        for (int attempt = 0; attempt < EPUB_PAGE_TEXT_ATTEMPTS; attempt++) {
            EpubAnchor anchor = locateEpubPage(currentPage, lastEpubSourceWord);
            if (anchor != null) {
                return anchor;
            }
            pageWordsByPage.remove(currentPage);
            if (attempt + 1 < EPUB_PAGE_TEXT_ATTEMPTS && !waitForPageTextRetry()) {
                return null;
            }
        }

        for (int distance = 1; distance <= EPUB_NEARBY_PAGE_LIMIT; distance++) {
            int forwardPage = currentPage + distance;
            if (forwardPage < pageCount) {
                EpubAnchor anchor = locateEpubPage(forwardPage, lastEpubSourceWord);
                if (anchor != null) {
                    return anchor;
                }
            }
        }

        for (int distance = 1; distance <= EPUB_NEARBY_PAGE_LIMIT; distance++) {
            int previousPage = currentPage - distance;
            if (previousPage < 0) {
                break;
            }
            EpubAnchor previous = locateEpubPage(previousPage, lastEpubSourceWord);
            if (previous != null) {
                EpubReadingTimeIndex.Position afterPrevious = epubIndex.positionAt(
                        previous.position.sourceWord + previous.pageWords.size());
                if (afterPrevious != null) {
                    return new EpubAnchor(afterPrevious, Collections.emptyList());
                }
            }
        }
        return null;
    }

    private EpubAnchor locateEpubPage(int page, int previousWordHint) {
        List<String> pageWords = getPageWords(page);
        if (pageWords.isEmpty()) {
            return null;
        }
        EpubReadingTimeIndex.Position position = epubIndex.locate(pageWords, previousWordHint);
        return position == null ? null : new EpubAnchor(position, pageWords);
    }

    private static boolean waitForPageTextRetry() {
        try {
            Thread.sleep(EPUB_PAGE_TEXT_RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void postUnavailable(int requestGeneration,
                                 boolean calculateChapter,
                                 boolean calculateBook,
                                 Callback callback) {
        if (calculateChapter) {
            postChapter(requestGeneration, -1, 0, callback);
        }
        if (calculateBook) {
            postBook(requestGeneration, -1, 0, callback);
        }
    }

    private void postChapter(int requestGeneration,
                             int words,
                             int wordsPerMinute,
                             Callback callback) {
        int minutes = ReadingTimeRemainingHelper.minutesForWords(words, wordsPerMinute);
        mainHandler.post(() -> {
            if (!isShutdown && generation.get() == requestGeneration) {
                callback.onChapterResult(words, minutes);
            }
        });
    }

    private void postBook(int requestGeneration,
                          int words,
                          int wordsPerMinute,
                          Callback callback) {
        int minutes = ReadingTimeRemainingHelper.minutesForWords(words, wordsPerMinute);
        mainHandler.post(() -> {
            if (!isShutdown && generation.get() == requestGeneration) {
                callback.onBookResult(words, minutes);
            }
        });
    }

    private int getWordsForPage(int zeroBasedPage) {
        Integer cached = wordsByPage.get(zeroBasedPage);
        if (cached != null) {
            return cached;
        }
        int wordCount;
        try {
            wordCount = controller.getWordCountForPage(zeroBasedPage);
        } catch (RuntimeException error) {
            wordCount = 0;
        }
        wordsByPage.put(zeroBasedPage, wordCount);
        return wordCount;
    }

    private List<String> getPageWords(int zeroBasedPage) {
        List<String> cached = pageWordsByPage.get(zeroBasedPage);
        if (cached != null) {
            return cached;
        }
        List<String> words;
        try {
            words = controller.getWordsForPage(zeroBasedPage);
        } catch (RuntimeException error) {
            return Collections.emptyList();
        }
        if (words == null || words.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> stableWords = Collections.unmodifiableList(new ArrayList<>(words));
        pageWordsByPage.put(zeroBasedPage, stableWords);
        return stableWords;
    }

    private static final class EpubAnchor {
        final EpubReadingTimeIndex.Position position;
        final List<String> pageWords;

        EpubAnchor(EpubReadingTimeIndex.Position position, List<String> pageWords) {
            this.position = position;
            this.pageWords = pageWords;
        }
    }

    public synchronized void cancel() {
        generation.incrementAndGet();
        if (activeRequest != null) {
            activeRequest.cancel(true);
            activeRequest = null;
        }
    }

    public synchronized void shutdown() {
        if (isShutdown) {
            return;
        }
        isShutdown = true;
        generation.incrementAndGet();
        if (activeRequest != null) {
            activeRequest.cancel(true);
            activeRequest = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        wordsByPage.clear();
        pageWordsByPage.clear();
        epubIndex = null;
        epubVisibleBookEndWord = -1;
    }
}
