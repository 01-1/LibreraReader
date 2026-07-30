package com.foobnix.pdf.info;

import android.content.Context;
import android.content.res.Resources;

import org.ebookdroid.droids.mupdf.codec.TextWord;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ReadingTimeRemainingHelper {

    public static final int DEFAULT_WORDS_PER_MINUTE = 200;
    public static final int MIN_WORDS_PER_MINUTE = 50;
    public static final int MAX_WORDS_PER_MINUTE = 1000;

    private ReadingTimeRemainingHelper() {
    }

    public static int countWords(CharSequence text) {
        return tokenizeWords(text).size();
    }

    public static List<String> tokenizeWords(CharSequence text) {
        if (text == null || text.length() == 0) {
            return Collections.emptyList();
        }

        String value = text.toString().replace("\u00AD", "");
        BreakIterator iterator = BreakIterator.getWordInstance(Locale.ROOT);
        iterator.setText(value);
        List<String> words = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next();
             end != BreakIterator.DONE;
             start = end, end = iterator.next()) {
            if (containsLetterOrDigit(value, start, end)) {
                words.add(value.substring(start, end).toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    public static int countTextWords(TextWord[][] lines) {
        return tokenizeTextWords(lines).size();
    }

    public static List<String> tokenizeTextWords(TextWord[][] lines) {
        if (lines == null || lines.length == 0) {
            return Collections.emptyList();
        }
        boolean hasExplicitWhitespace = false;
        for (TextWord[] line : lines) {
            if (line == null) {
                continue;
            }
            for (TextWord word : line) {
                String value = word == null ? null : word.getWord();
                if (value != null && containsWhitespace(value)) {
                    hasExplicitWhitespace = true;
                    break;
                }
            }
            if (hasExplicitWhitespace) {
                break;
            }
        }
        StringBuilder text = new StringBuilder();
        for (TextWord[] line : lines) {
            if (line == null) {
                continue;
            }
            for (TextWord word : line) {
                if (word != null && word.getWord() != null) {
                    text.append(word.getWord());
                    if (!hasExplicitWhitespace) {
                        text.append(' ');
                    }
                }
            }
            text.append('\n');
        }
        return tokenizeWords(text);
    }

    private static boolean containsWhitespace(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isWhitespace(codePoint) ||
                    codePoint == 0x00A0) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
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

    public static String formatChapter(Context context, int words, int minutes) {
        if (words <= 0) {
            return context.getString(R.string.chapter_reading_time_unavailable);
        }
        return context.getString(R.string.reading_time_left_in_chapter,
                                 formatDuration(context.getResources(), minutes));
    }

    public static String formatBook(Context context, int words, int minutes) {
        if (words <= 0) {
            return context.getString(R.string.book_reading_time_unavailable);
        }
        return context.getString(R.string.reading_time_left_in_book,
                                 formatDuration(context.getResources(), minutes));
    }

    public static String combine(Context context, String chapter, String book) {
        boolean hasChapter = chapter != null && chapter.length() > 0;
        boolean hasBook = book != null && book.length() > 0;
        if (hasChapter && hasBook) {
            return context.getString(R.string.reading_time_remaining_combined, chapter, book);
        }
        if (hasChapter) {
            return chapter;
        }
        return hasBook ? book : "";
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
