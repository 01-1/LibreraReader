package com.foobnix.pdf.info;

import android.content.Context;
import android.content.res.Resources;

import java.text.BreakIterator;
import java.util.Locale;

public final class ReadingTimeRemainingHelper {

    public static final int DEFAULT_WORDS_PER_MINUTE = 200;
    public static final int MIN_WORDS_PER_MINUTE = 50;
    public static final int MAX_WORDS_PER_MINUTE = 1000;

    private ReadingTimeRemainingHelper() {
    }

    public static int countWords(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 0;
        }

        String value = text.toString();
        BreakIterator iterator = BreakIterator.getWordInstance(Locale.ROOT);
        iterator.setText(value);
        int count = 0;
        int start = iterator.first();
        for (int end = iterator.next();
             end != BreakIterator.DONE;
             start = end, end = iterator.next()) {
            if (containsLetterOrDigit(value, start, end)) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsLetterOrDigit(String value, int start, int end) {
        for (int offset = start; offset < end; ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    public static int sanitizeWordsPerMinute(int wordsPerMinute) {
        return Math.max(MIN_WORDS_PER_MINUTE,
                        Math.min(MAX_WORDS_PER_MINUTE, wordsPerMinute));
    }

    public static Estimate calculate(int chapterWordsRemaining,
                                     int bookWordsRemaining,
                                     int wordsPerMinute) {
        int safeChapterWords = Math.max(0, chapterWordsRemaining);
        int safeBookWords = Math.max(safeChapterWords, bookWordsRemaining);
        int safeWordsPerMinute = sanitizeWordsPerMinute(wordsPerMinute);
        return new Estimate(safeChapterWords,
                            safeBookWords,
                            minutesForWords(safeChapterWords, safeWordsPerMinute),
                            minutesForWords(safeBookWords, safeWordsPerMinute));
    }

    static int minutesForWords(int words, int wordsPerMinute) {
        if (words <= 0) {
            return 0;
        }
        int safeWordsPerMinute = sanitizeWordsPerMinute(wordsPerMinute);
        return Math.max(1, (words + safeWordsPerMinute - 1) / safeWordsPerMinute);
    }

    public static String format(Context context, Estimate estimate) {
        if (estimate == null || estimate.bookWords == 0) {
            return context.getString(R.string.reading_time_unavailable);
        }
        String chapter = context.getString(R.string.reading_time_left_in_chapter,
                                           formatDuration(context.getResources(),
                                                          estimate.chapterMinutes));
        String book = context.getString(R.string.reading_time_left_in_book,
                                        formatDuration(context.getResources(),
                                                       estimate.bookMinutes));
        return context.getString(R.string.reading_time_remaining_combined, chapter, book);
    }

    private static String formatDuration(Resources resources, int totalMinutes) {
        int safeMinutes = Math.max(0, totalMinutes);
        int hours = safeMinutes / 60;
        int minutes = safeMinutes % 60;
        if (hours == 0) {
            return resources.getQuantityString(R.plurals.reading_time_minutes,
                                               minutes,
                                               minutes);
        }

        String hoursText = resources.getQuantityString(R.plurals.reading_time_hours,
                                                       hours,
                                                       hours);
        if (minutes == 0) {
            return hoursText;
        }
        String minutesText = resources.getQuantityString(R.plurals.reading_time_minutes,
                                                         minutes,
                                                         minutes);
        return resources.getString(R.string.reading_time_hours_and_minutes,
                                   hoursText,
                                   minutesText);
    }

    public static final class Estimate {
        public final int chapterWords;
        public final int bookWords;
        public final int chapterMinutes;
        public final int bookMinutes;

        public Estimate(int chapterWords,
                        int bookWords,
                        int chapterMinutes,
                        int bookMinutes) {
            this.chapterWords = Math.max(0, chapterWords);
            this.bookWords = Math.max(this.chapterWords, bookWords);
            this.chapterMinutes = Math.max(0, chapterMinutes);
            this.bookMinutes = Math.max(this.chapterMinutes, bookMinutes);
        }
    }
}
