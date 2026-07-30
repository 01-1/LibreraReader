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

    private static void write(ZipOutputStream output, String name, String contents)
            throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(contents.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
