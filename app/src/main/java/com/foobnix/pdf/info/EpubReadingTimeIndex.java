package com.foobnix.pdf.info;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A word index built from the EPUB spine. Reflowed screen pages are deliberately
 * not used as the book-length unit because they change with layout and font size.
 */
final class EpubReadingTimeIndex {

    private static final int MATCH_WORDS = 16;
    private static final int SCORE_WORDS = 120;

    private final List<String> words;
    private final List<SpineSection> sections;

    private EpubReadingTimeIndex(List<String> words, List<SpineSection> sections) {
        this.words = words;
        this.sections = sections;
    }

    static EpubReadingTimeIndex load(File epub) throws IOException {
        try (ZipFile zip = new ZipFile(epub)) {
            Map<String, ZipEntry> entries = entriesByLowercaseName(zip);
            ZipEntry containerEntry = entries.get("meta-inf/container.xml");
            if (containerEntry == null) {
                throw new IOException("EPUB container.xml is missing");
            }

            Document container = parseXml(zip, containerEntry);
            Element rootFile = container.getElementsByTag("rootfile").first();
            if (rootFile == null || rootFile.attr("full-path").length() == 0) {
                throw new IOException("EPUB package path is missing");
            }

            String packagePath = normalizePath(rootFile.attr("full-path"));
            ZipEntry packageEntry = entries.get(packagePath.toLowerCase(Locale.ROOT));
            if (packageEntry == null) {
                throw new IOException("EPUB package document is missing");
            }

            Document packageDocument = parseXml(zip, packageEntry);
            Map<String, String> manifest = new HashMap<>();
            for (Element item : packageDocument.getElementsByTag("item")) {
                String id = item.attr("id");
                String href = item.attr("href");
                if (id.length() > 0 && href.length() > 0) {
                    manifest.put(id, resolvePath(packagePath, href));
                }
            }

            Element spine = packageDocument.getElementsByTag("spine").first();
            if (spine == null) {
                throw new IOException("EPUB spine is missing");
            }

            List<String> allWords = new ArrayList<>();
            List<SpineSection> spineSections = new ArrayList<>();
            for (Element itemRef : spine.getElementsByTag("itemref")) {
                if ("no".equalsIgnoreCase(itemRef.attr("linear"))) {
                    continue;
                }
                String contentPath = manifest.get(itemRef.attr("idref"));
                if (contentPath == null) {
                    continue;
                }
                ZipEntry contentEntry = entries.get(contentPath.toLowerCase(Locale.ROOT));
                if (contentEntry == null) {
                    continue;
                }

                Document content = parseXml(zip, contentEntry);
                content.select("script, style, nav, svg").remove();
                Element body = content.body();
                String text = body == null ? content.text() : body.text();
                List<String> sectionWords = ReadingTimeRemainingHelper.tokenizeWords(text);
                int start = allWords.size();
                allWords.addAll(sectionWords);
                spineSections.add(new SpineSection(start, allWords.size()));
            }

            if (allWords.isEmpty() || spineSections.isEmpty()) {
                throw new IOException("EPUB spine contains no readable text");
            }
            return new EpubReadingTimeIndex(Collections.unmodifiableList(allWords),
                                            Collections.unmodifiableList(spineSections));
        }
    }

    Position locate(List<String> pageWords, int previousWordHint) {
        if (pageWords == null || pageWords.isEmpty()) {
            return null;
        }
        int wordIndex = findBestStart(pageWords, previousWordHint);
        if (wordIndex < 0) {
            return null;
        }
        SpineSection section = findSection(wordIndex);
        if (section == null) {
            return null;
        }
        return new Position(wordIndex,
                            Math.max(0, section.endWord - wordIndex),
                            Math.max(0, words.size() - wordIndex));
    }

    private int findBestStart(List<String> pageWords, int previousWordHint) {
        int sampleLength = Math.min(MATCH_WORDS, pageWords.size());
        int[] offsets = new int[] {
                0,
                Math.max(0, pageWords.size() / 4),
                Math.max(0, pageWords.size() / 2)
        };
        int bestStart = -1;
        int bestScore = -1;
        int bestDistance = Integer.MAX_VALUE;

        for (int requestedOffset : offsets) {
            int sampleOffset = Math.min(requestedOffset, pageWords.size() - sampleLength);
            for (int source = 0; source + sampleLength <= words.size(); source++) {
                if (!matches(words, source, pageWords, sampleOffset, sampleLength)) {
                    continue;
                }
                int candidateStart = source - sampleOffset;
                if (candidateStart < 0 || candidateStart >= words.size()) {
                    continue;
                }
                int score = score(candidateStart, pageWords);
                int distance = previousWordHint < 0 ? candidateStart :
                        Math.abs(candidateStart - previousWordHint);
                if (score > bestScore || score == bestScore && distance < bestDistance) {
                    bestStart = candidateStart;
                    bestScore = score;
                    bestDistance = distance;
                }
            }
            if (bestScore >= Math.min(SCORE_WORDS, pageWords.size())) {
                break;
            }
        }
        return bestStart;
    }

    private int score(int sourceStart, List<String> pageWords) {
        int limit = Math.min(SCORE_WORDS,
                             Math.min(pageWords.size(), words.size() - sourceStart));
        int score = 0;
        for (int i = 0; i < limit; i++) {
            if (words.get(sourceStart + i).equals(pageWords.get(i))) {
                score++;
            }
        }
        return score;
    }

    private static boolean matches(List<String> source,
                                   int sourceOffset,
                                   List<String> target,
                                   int targetOffset,
                                   int length) {
        for (int i = 0; i < length; i++) {
            if (!source.get(sourceOffset + i).equals(target.get(targetOffset + i))) {
                return false;
            }
        }
        return true;
    }

    private SpineSection findSection(int wordIndex) {
        for (SpineSection section : sections) {
            if (wordIndex >= section.startWord && wordIndex < section.endWord) {
                return section;
            }
        }
        return null;
    }

    private static Document parseXml(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            return Jsoup.parse(input, null, "", Parser.xmlParser());
        }
    }

    private static Map<String, ZipEntry> entriesByLowercaseName(ZipFile zip) {
        Map<String, ZipEntry> entries = new HashMap<>();
        for (java.util.Enumeration<? extends ZipEntry> iterator = zip.entries();
             iterator.hasMoreElements(); ) {
            ZipEntry entry = iterator.nextElement();
            entries.put(normalizePath(entry.getName()).toLowerCase(Locale.ROOT), entry);
        }
        return entries;
    }

    private static String resolvePath(String packagePath, String href) throws IOException {
        String withoutFragment = href.split("#", 2)[0].split("\\?", 2)[0];
        String decoded = URLDecoder.decode(withoutFragment, "UTF-8");
        int slash = packagePath.lastIndexOf('/');
        String base = slash < 0 ? "" : packagePath.substring(0, slash + 1);
        return normalizePath(base + decoded);
    }

    private static String normalizePath(String path) {
        ArrayDeque<String> parts = new ArrayDeque<>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.length() == 0 || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
            } else {
                parts.addLast(part);
            }
        }
        StringBuilder normalized = new StringBuilder();
        for (String part : parts) {
            if (normalized.length() > 0) {
                normalized.append('/');
            }
            normalized.append(part);
        }
        return normalized.toString();
    }

    static final class Position {
        final int sourceWord;
        final int chapterWordsRemaining;
        final int bookWordsRemaining;

        Position(int sourceWord, int chapterWordsRemaining, int bookWordsRemaining) {
            this.sourceWord = sourceWord;
            this.chapterWordsRemaining = chapterWordsRemaining;
            this.bookWordsRemaining = bookWordsRemaining;
        }
    }

    private static final class SpineSection {
        final int startWord;
        final int endWord;

        SpineSection(int startWord, int endWord) {
            this.startWord = startWord;
            this.endWord = endWord;
        }
    }
}
