package com.foobnix.pdf.info;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class EpubReadingTimeIndexHelperTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void countsRemainingWordsFromSpineOrderInsteadOfRenderedPages() throws Exception {
        File epub = temporaryFolder.newFile("sample.epub");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(epub))) {
            write(output,
                  "META-INF/container.xml",
                  "<?xml version=\"1.0\"?>" +
                          "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/>" +
                          "</rootfiles></container>");
            write(output,
                  "OPS/book.opf",
                  "<?xml version=\"1.0\"?>" +
                          "<package><manifest>" +
                          "<item id=\"second\" href=\"text/second.xhtml\"/>" +
                          "<item id=\"first\" href=\"text/first.xhtml\"/>" +
                          "</manifest><spine>" +
                          "<itemref idref=\"first\"/><itemref idref=\"second\"/>" +
                          "</spine></package>");
            write(output,
                  "OPS/text/second.xhtml",
                  "<html><body>nu xi omicron pi rho sigma tau</body></html>");
            write(output,
                  "OPS/text/first.xhtml",
                  "<html><body>alpha beta gamma delta epsilon zeta eta theta iota " +
                          "kappa lambda mu</body></html>");
        }

        EpubReadingTimeIndex index = EpubReadingTimeIndex.load(epub);
        List<String> pageWords =
                ReadingTimeRemainingHelper.tokenizeWords("gamma delta epsilon zeta eta");
        EpubReadingTimeIndex.Position position = index.locate(pageWords, -1);

        assertNotNull(position);
        assertEquals(2, position.sourceWord);
        assertEquals(10, position.chapterWordsRemaining);
        assertEquals(17, position.bookWordsRemaining);

        EpubReadingTimeIndex.Position chapterBoundary = index.positionAt(12);
        assertNotNull(chapterBoundary);
        assertEquals(12, chapterBoundary.sourceWord);
        assertEquals(7, chapterBoundary.chapterWordsRemaining);
        assertEquals(7, chapterBoundary.bookWordsRemaining);

        EpubReadingTimeIndex.Position bookEnd = index.positionAt(19);
        assertNotNull(bookEnd);
        assertEquals(19, bookEnd.sourceWord);
        assertEquals(0, bookEnd.chapterWordsRemaining);
        assertEquals(0, bookEnd.bookWordsRemaining);
    }

    @Test
    public void acceptsNamespacePrefixesAndToleratesRenderedTextDifferences()
            throws Exception {
        File epub = temporaryFolder.newFile("prefixed.epub");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(epub))) {
            write(output,
                  "META-INF/container.xml",
                  "<?xml version=\"1.0\"?>" +
                          "<ocf:container xmlns:ocf=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                          "<ocf:rootfiles><ocf:rootfile full-path=\"OPS/book.opf\"/>" +
                          "</ocf:rootfiles></ocf:container>");
            write(output,
                  "OPS/book.opf",
                  "<?xml version=\"1.0\"?>" +
                          "<opf:package xmlns:opf=\"http://www.idpf.org/2007/opf\">" +
                          "<opf:manifest><opf:item id=\"chapter\" href=\"chapter.xhtml\"/>" +
                          "</opf:manifest><opf:spine><opf:itemref idref=\"chapter\"/>" +
                          "</opf:spine></opf:package>");
            write(output,
                  "OPS/chapter.xhtml",
                  "<html><body>alpha beta gamma delta epsilon zeta eta theta iota " +
                          "kappa lambda mu nu xi omicron pi rho sigma tau upsilon " +
                          "phi chi psi omega</body></html>");
        }

        EpubReadingTimeIndex index = EpubReadingTimeIndex.load(epub);
        List<String> renderedWords = ReadingTimeRemainingHelper.tokenizeWords(
                "alpha beta inserted gamma delta epsilon zeta eta theta iota " +
                        "kappa lambda mu nu xi omicron pi rho sigma tau");
        EpubReadingTimeIndex.Position position = index.locate(renderedWords, -1);

        assertNotNull(position);
        assertEquals(0, position.sourceWord);
        assertEquals(24, position.chapterWordsRemaining);
        assertEquals(24, position.bookWordsRemaining);
    }

    @Test
    public void visibleBookEndExcludesDuplicatedTrailingSpineContent() throws Exception {
        File epub = temporaryFolder.newFile("duplicated-tail.epub");
        String first = "alpha beta gamma delta epsilon zeta eta theta iota " +
                "kappa lambda mu";
        String last = "nu xi omicron pi rho sigma tau";
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(epub))) {
            write(output,
                  "META-INF/container.xml",
                  "<?xml version=\"1.0\"?>" +
                          "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/>" +
                          "</rootfiles></container>");
            write(output,
                  "OPS/book.opf",
                  "<?xml version=\"1.0\"?>" +
                          "<package><manifest>" +
                          "<item id=\"first\" href=\"first.xhtml\"/>" +
                          "<item id=\"last\" href=\"last.xhtml\"/>" +
                          "<item id=\"duplicate-first\" href=\"duplicate-first.xhtml\"/>" +
                          "<item id=\"duplicate-last\" href=\"duplicate-last.xhtml\"/>" +
                          "</manifest><spine>" +
                          "<itemref idref=\"first\"/><itemref idref=\"last\"/>" +
                          "<itemref idref=\"duplicate-first\"/>" +
                          "<itemref idref=\"duplicate-last\"/>" +
                          "</spine></package>");
            write(output, "OPS/first.xhtml", "<html><body>" + first + "</body></html>");
            write(output, "OPS/last.xhtml", "<html><body>" + last + "</body></html>");
            write(output,
                  "OPS/duplicate-first.xhtml",
                  "<html><body>" + first + "</body></html>");
            write(output,
                  "OPS/duplicate-last.xhtml",
                  "<html><body>" + last + "</body></html>");
        }

        EpubReadingTimeIndex index = EpubReadingTimeIndex.load(epub);
        List<String> currentPageWords =
                ReadingTimeRemainingHelper.tokenizeWords("gamma delta epsilon zeta eta");
        List<String> finalPageWords = ReadingTimeRemainingHelper.tokenizeWords(last);
        EpubReadingTimeIndex.Position current = index.locate(currentPageWords, -1);
        assertNotNull(current);
        EpubReadingTimeIndex.Position visibleFinalPage = index.locateAtOrAfter(
                finalPageWords,
                current.sourceWord,
                current.sourceWord);
        int visibleEnd = index.sourceWordAfterPage(visibleFinalPage, finalPageWords);
        EpubReadingTimeIndex.Position bounded = index.limitBookEnd(current, visibleEnd);

        assertNotNull(visibleFinalPage);
        assertNotNull(bounded);
        assertEquals(2, current.sourceWord);
        assertEquals(12, visibleFinalPage.sourceWord);
        assertEquals(19, visibleEnd);
        assertEquals(17, bounded.bookWordsRemaining);
        assertEquals(36, current.bookWordsRemaining);

        EpubReadingTimeIndex.Position atVisibleEnd = index.positionAt(visibleEnd);
        EpubReadingTimeIndex.Position completed = index.limitBookEnd(atVisibleEnd, visibleEnd);
        assertNotNull(completed);
        assertEquals(0, completed.bookWordsRemaining);
    }

    private static void write(ZipOutputStream output, String name, String contents)
            throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(contents.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
