package com.foobnix.pdf.info;

import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.LOG;
import com.foobnix.pdf.info.wrapper.DocumentController;

import org.ebookdroid.BookType;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReadingTimeRemainingLoader {

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
    private boolean epubIndexFailed;
    private int lastEpubSourceWord = -1;
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
            if (epubIndex == null && !epubIndexFailed) {
                epubIndex = EpubReadingTimeIndex.load(controller.getCurrentBook());
            }
            List<String> pageWords = getPageWords(currentPage - 1);
            EpubReadingTimeIndex.Position position = epubIndex == null ? null :
                    epubIndex.locate(pageWords, lastEpubSourceWord);
            if (position == null) {
                postUnavailable(requestGeneration,
                                calculateChapter,
                                calculateBook,
                                callback);
                return;
            }
            lastEpubSourceWord = position.sourceWord;
            if (calculateChapter) {
                int chapterWords = position.chapterWordsRemaining;
                if (chapterEndPage < pageCount) {
                    List<String> nextChapterWords = getPageWords(chapterEndPage);
                    EpubReadingTimeIndex.Position nextChapter =
                            epubIndex.locate(nextChapterWords,
                                             position.sourceWord + pageWords.size());
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
                postBook(requestGeneration,
                         position.bookWordsRemaining,
                         wordsPerMinute,
                         callback);
            }
        } catch (IOException error) {
            LOG.e(error);
            epubIndexFailed = true;
            postUnavailable(requestGeneration, calculateChapter, calculateBook, callback);
        } catch (RuntimeException error) {
            LOG.e(error);
            postUnavailable(requestGeneration, calculateChapter, calculateBook, callback);
        }
    }

    private void postUnavailable(int requestGeneration,
                                 boolean calculateChapter,
                                 boolean calculateBook,
                                 Callback callback) {
        if (calculateChapter) {
            postChapter(requestGeneration, 0, 0, callback);
        }
        if (calculateBook) {
            postBook(requestGeneration, 0, 0, callback);
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
        List<String> words = controller.getWordsForPage(zeroBasedPage);
        if (words == null) {
            words = java.util.Collections.emptyList();
        }
        pageWordsByPage.put(zeroBasedPage, words);
        return words;
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
    }
}
