package edu.campus.rag;

import static org.junit.jupiter.api.Assertions.*;

import edu.campus.rag.common.exception.BusinessException;
import edu.campus.rag.service.support.DocumentParser;
import edu.campus.rag.service.support.ModelGateway;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ParserTest {
  @TempDir Path directory;
  DocumentParser parser = new DocumentParser();

  @Test
  void pdfExtractsTextAndRealPhysicalPages() throws Exception {
    Path file = directory.resolve("sample.pdf");
    try (var pdf = new PDDocument()) {
      for (int i = 0; i < 2; i++) {
        var page = new PDPage();
        pdf.addPage(page);
        try (var stream = new PDPageContentStream(pdf, page)) {
          stream.beginText();
          stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          stream.newLineAtOffset(50, 700);
          stream.showText("Campus rules page " + (i + 1));
          stream.endText();
        }
      }
      pdf.save(file.toFile());
    }
    var parts = parser.parse(file, "PDF");
    assertEquals(2, parts.size());
    assertEquals(1, parts.get(0).pageStart());
    assertEquals(2, parts.get(1).pageEnd());
    assertTrue(parts.get(0).text().contains("Campus rules"));
  }

  @Test
  void docxExtractsChineseTextWithoutInventingPages() throws Exception {
    Path file = directory.resolve("sample.docx");
    try (var doc = new XWPFDocument();
        var out = Files.newOutputStream(file)) {
      doc.createParagraph().createRun().setText("第十条 学生办理休学应提交申请材料。");
      doc.write(out);
    }
    var parts = parser.parse(file, "DOCX");
    assertEquals(1, parts.size());
    assertEquals("第十条", parts.get(0).section());
    assertNull(parts.get(0).pageStart());
    assertTrue(parts.get(0).text().contains("休学"));
  }

  @Test
  void corruptAndEncryptedPdfRejected() throws Exception {
    Path corrupt = directory.resolve("corrupt.pdf");
    Files.writeString(corrupt, "%PDF-broken");
    assertEquals(
        "T-0001", assertThrows(BusinessException.class, () -> parser.parse(corrupt, "PDF")).code);
    Path encrypted = directory.resolve("encrypted.pdf");
    try (var doc = new PDDocument()) {
      doc.addPage(new PDPage());
      doc.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
      doc.save(encrypted.toFile());
    }
    assertEquals(
        "T-0001", assertThrows(BusinessException.class, () -> parser.parse(encrypted, "PDF")).code);
  }

  @Test
  void scannedPdfAndPageLimitHaveDistinctErrors() throws Exception {
    Path blank = directory.resolve("blank.pdf");
    try (var doc = new PDDocument()) {
      doc.addPage(new PDPage());
      doc.save(blank.toFile());
    }
    assertEquals(
        "T-0002", assertThrows(BusinessException.class, () -> parser.parse(blank, "PDF")).code);
    Path large = directory.resolve("large.pdf");
    try (var doc = new PDDocument()) {
      for (int i = 0; i < 201; i++) doc.addPage(new PDPage());
      doc.save(large.toFile());
    }
    assertEquals(
        "T-0003", assertThrows(BusinessException.class, () -> parser.parse(large, "PDF")).code);
  }

  @Test
  void longArticleKeepsSectionAndOverlap() throws Exception {
    Path file = directory.resolve("long.txt");
    Files.writeString(file, "第十二条 " + "甲乙丙丁戊己庚辛壬癸".repeat(260));
    var parts = parser.parse(file, "TXT");
    assertTrue(parts.size() > 1);
    assertTrue(parts.stream().allMatch(p -> "第十二条".equals(p.section())));
    String first = parts.get(0).text(), second = parts.get(1).text();
    assertEquals(first.substring(first.length() - 150), second.substring(0, 150));
  }

  @Test
  void textAndChunkLimitsEnforced() throws Exception {
    Path file = directory.resolve("oversized.txt");
    Files.writeString(file, "学".repeat(300001));
    assertEquals(
        "T-0003", assertThrows(BusinessException.class, () -> parser.parse(file, "TXT")).code);
    Files.writeString(file, "第一条 x\n".repeat(401));
    assertEquals(
        "T-0003", assertThrows(BusinessException.class, () -> parser.parse(file, "TXT")).code);
  }

  @Test
  void utf8BudgetNeverSplitsUnicodeCodepoints() {
    String result = ModelGateway.budget("😀中文abc", 8);
    assertEquals("😀中", result);
    assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 8);
  }
}
