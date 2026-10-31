package edu.campus.rag.service.support;

import edu.campus.rag.common.Api;
import edu.campus.rag.common.exception.BusinessException;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
public class DocumentParser {
  public record Part(String text, String section, Integer pageStart, Integer pageEnd) {}

  private static final Pattern ARTICLE = Pattern.compile("(?m)(?=第[一二三四五六七八九十百千万零〇0-9]+[条章节])");

  public List<Part> parse(Path file, String type) {
    try {
      var pages = new ArrayList<Part>();
      if ("PDF".equals(type)) {
        try (var pdf = Loader.loadPDF(file.toFile())) {
          Api.require(!pdf.isEncrypted(), 400, "T-0001", "不支持加密 PDF");
          Api.require(pdf.getNumberOfPages() <= 200, 400, "T-0003", "PDF 超过 200 页");
          var stripper = new PDFTextStripper();
          for (int i = 1; i <= pdf.getNumberOfPages(); i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            pages.add(new Part(stripper.getText(pdf), null, i, i));
          }
        }
      } else if ("DOCX".equals(type)) {
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(20L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(2L * 1024 * 1024);
        try (var in = Files.newInputStream(file);
            var doc = new XWPFDocument(in);
            var extractor = new XWPFWordExtractor(doc)) {
          pages.add(new Part(extractor.getText(), null, null, null));
        }
      } else {
        var decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        pages.add(
            new Part(
                decoder.decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString(),
                null,
                null,
                null));
      }
      long count = pages.stream().mapToLong(p -> p.text.codePointCount(0, p.text.length())).sum();
      Api.require(count <= 300000, 400, "T-0003", "提取文本超过 30 万字符");
      var parts = new ArrayList<Part>();
      for (var page : pages) {
        String clean =
            page.text.replace("\uFEFF", "").replace("\u0000", "").replace("\r\n", "\n").strip();
        for (String block : ARTICLE.split(clean)) {
          if (block.isBlank()) continue;
          String section = null;
          var matcher = Pattern.compile("^第[一二三四五六七八九十百千万零〇0-9]+[条章节]").matcher(block.strip());
          if (matcher.find()) section = matcher.group();
          int[] cps = block.strip().codePoints().toArray();
          for (int start = 0; start < cps.length; ) {
            int end = Math.min(start + 1200, cps.length);
            parts.add(
                new Part(
                    new String(cps, start, end - start), section, page.pageStart, page.pageEnd));
            if (end == cps.length) break;
            start = end - 150;
          }
        }
      }
      Api.require(!parts.isEmpty(), 400, "T-0002", "没有可提取文本，扫描件需先 OCR");
      Api.require(parts.size() <= 400, 400, "T-0003", "分块超过 400 块");
      return parts;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw Api.fail(400, "T-0001", "文档加密、损坏或解析失败");
    }
  }
}
