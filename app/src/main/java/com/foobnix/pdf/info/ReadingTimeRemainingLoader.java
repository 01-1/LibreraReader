package com.foobnix.pdf.info;

import android.os.Handler;
import android.os.Looper;

import com.foobnix.pdf.info.ReadingTimeRemainingHelper.Estimate;
import com.foobnix.pdf.info.wrapper.DocumentController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReadingTimeRemainingLoader {

    public interface Callback {
        void onResult(Estimate estimate);
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
    private final AtomicInteger generation = new AtomicInteger();

    private Future<?> activeRequest;
    private int cachedPageCount = -1;
    private boolean isShutdown;

    public ReadingTimeRemainingLoader(DocumentController controller) {
        this.controller = controller;
    }

    public synchronized void load(int currentPage,
                                  int pageCount,
                                  int chapterEndPage,
                                  int wordsPerMinute,
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
            cachedPageCount = pageCount;
        }

        final int safePageCount = Math.max(0, pageCount);
        final int safeCurrentPage = Math.max(1, Math.min(Math.max(1, safePageCount), currentPage));
        final int safeChapterEnd = Math.max(safeCurrentPage,
                                            Math.min(Math.max(1, safePageCount), chapterEndPage));
        final int safeWordsPerMinute =
                ReadingTimeRemainingHelper.sanitizeWordsPerMinute(wordsPerMinute);

        activeRequest = executor.submit(new Runnable() {
            @Override public void run() {
                int chapterWords = 0;
                int bookWords = 0;
                for (int page = safeCurrentPage; page <= safePageCount; page++) {
                    if (Thread.currentThread().isInterrupted() ||
                            generation.get() != requestGeneration) {
                        return;
                    }
                    int pageWords = getWordsForPage(page - 1);
                    bookWords += pageWords;
                    if (page <= safeChapterEnd) {
                        chapterWords += pageWords;
                    }
                }

                final Estimate estimate =
                        ReadingTimeRemainingHelper.calculate(chapterWords,
                                                             bookWords,
                                                             safeWordsPerMinute);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (!isShutdown && generation.get() == requestGeneration) {
                            callback.onResult(estimate);
                        }
                    }
                });
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
            String text = controller.getTextForPage(zeroBasedPage);
            wordCount = ReadingTimeRemainingHelper.countWords(text);
        } catch (RuntimeException error) {
            wordCount = 0;
        }
        wordsByPage.put(zeroBasedPage, wordCount);
        return wordCount;
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
    }
}
