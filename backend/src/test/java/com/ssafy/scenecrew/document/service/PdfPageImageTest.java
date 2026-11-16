package com.ssafy.scenecrew.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** V9-D62: PDF 쪽 그림과, 쪽 안의 평면도 그림 영역(crop) 찾기 */
class PdfPageImageTest {

    @Test void cropIsTheLargestImageWithPadding() throws Exception {
        // 가로 A4(842×595)에 그림 하나를 (100,95)~(700,495) 자리에 그린다(PDF 좌표는 왼쪽 아래 기준)
        byte[] pdf = pdfWithImage(842, 595, 100, 95, 600, 400);
        var r = PdfPageImage.render(pdf, 1);
        assertThat(Math.max(r.width(), r.height())).isEqualTo(1600);
        var c = r.crop();
        assertThat(c).isNotNull();
        double pad = PdfPageImage.CROP_PADDING;
        assertThat(c.x()).isCloseTo(100.0 / 842 - pad, within(1e-3));
        assertThat(c.y()).isCloseTo((595.0 - 495) / 595 - pad, within(1e-3)); // 왼쪽 위 기준
        assertThat(c.width()).isCloseTo(600.0 / 842 + 2 * pad, within(1e-3));
        assertThat(c.height()).isCloseTo(400.0 / 595 + 2 * pad, within(1e-3));
        assertThat(c.header()).matches("\\d\\.\\d{4},\\d\\.\\d{4},\\d\\.\\d{4},\\d\\.\\d{4}");
    }

    @Test void smallImageOrNoImageHasNoCrop() throws Exception {
        assertThat(PdfPageImage.render(pdfWithImage(842, 595, 10, 10, 60, 40), 1).crop()).isNull(); // 로고 크기
        assertThat(PdfPageImage.render(Pdfs.pages(1), 1).crop()).isNull();
    }

    @Test void demoPdfFloorPlanPageCropsToTheDrawing() throws Exception {
        Path pdf = Path.of("..", "docs", "output", "demo-pdf-20261111", "씬크루_쇼케이스_라이브_운영계획서_평면도포함_v2.pdf");
        Assumptions.assumeTrue(Files.exists(pdf), "시연 PDF v2 없음");
        var c = PdfPageImage.render(Files.readAllBytes(pdf), 5).crop();
        assertThat(c).isNotNull();
        // 평면도 그림은 쪽 가운데에 있고 제목·설명 글은 뺀다. AI가 읽은 A·B 구역(x 0.62~0.76, y 0.34~0.55)이 안에 들어온다
        assertThat(c.y()).isGreaterThan(0.1);
        assertThat(c.x()).isLessThan(0.62);
        assertThat(c.x() + c.width()).isGreaterThan(0.76);
        assertThat(c.y()).isLessThan(0.34);
        assertThat(c.y() + c.height()).isGreaterThan(0.55).isLessThan(0.9);
    }

    static byte[] pdfWithImage(float pw, float ph, float x, float y, float w, float h) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(new PDRectangle(pw, ph));
            doc.addPage(page);
            var img = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < 60; i++) for (int j = 0; j < 40; j++) img.setRGB(i, j, 0x88cc88);
            try (var c = new PDPageContentStream(doc, page)) {
                c.drawImage(LosslessFactory.createFromImage(doc, img), x, y, w, h);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
