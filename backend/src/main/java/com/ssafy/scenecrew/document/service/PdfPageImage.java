package com.ssafy.scenecrew.document.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;

/**
 * PDF 한 쪽을 PNG로 그린다(V9-D62 공연장 평면도 배경, S15P31A202-372).
 *
 * 긴 변을 AI 평면도 읽기(ai/plan floor_plan.render)와 같은 1600px로 맞춘다. 평면도 도형 좌표는 그 쪽 그림 기준
 * 0~1 비율이므로, 같은 쪽을 그리면 크기가 달라도 도형이 그림 위 같은 자리에 겹친다.
 *
 * 쪽 안에 평면도로 보이는 큰 그림(래스터 이미지)이 있으면 그 자리를 {@link Crop}(0~1, 왼쪽 위 기준)으로 함께 돌려준다.
 * 화면은 쪽 전체 대신 그 영역만 크게 보인다(제목·범례·설명 글 제외). 도형 좌표 기준은 쪽 전체 그대로다.
 */
public final class PdfPageImage {
    /** ai/plan/floor_plan.py RENDER_LONG_SIDE와 같은 값 */
    public static final int LONG_SIDE = 1600;
    /** 평면도 그림으로 볼 최소 넓이(쪽 넓이 대비). 작은 로고·아이콘은 빼려는 것이다 */
    static final double MIN_IMAGE_AREA = 0.1;
    /** 그림 둘레에 남길 여백(쪽 크기 대비) */
    static final double CROP_PADDING = 0.01;

    public record Crop(double x, double y, double width, double height) {
        /** 응답 헤더 값 "x,y,width,height"(소수 4자리) */
        public String header() {
            return String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", x, y, width, height);
        }
    }

    /** crop은 평면도로 보이는 그림이 없으면 null이다 */
    public record Rendered(byte[] png, int width, int height, Crop crop) {
    }

    /** 쪽이 없거나(1부터 셈) PDF가 아니면 */
    public static final class PageNotFound extends Exception {
        PageNotFound(String message) {
            super(message);
        }
    }

    private PdfPageImage() {
    }

    public static Rendered render(byte[] pdf, int pageNumber) throws PageNotFound {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
                throw new PageNotFound("PDF에 " + pageNumber + "쪽이 없습니다.");
            }
            PDPage page = document.getPage(pageNumber - 1);
            boolean turned = page.getRotation() % 180 != 0;
            float w = turned ? page.getCropBox().getHeight() : page.getCropBox().getWidth();
            float h = turned ? page.getCropBox().getWidth() : page.getCropBox().getHeight();
            // pdfbox는 쪽 크기×배율을 내림해 픽셀 수를 정하므로 반 픽셀을 더해 긴 변이 정확히 LONG_SIDE가 되게 한다
            float scale = (LONG_SIDE + 0.5f) / Math.max(w, h);
            BufferedImage image = new PDFRenderer(document).renderImage(pageNumber - 1, scale, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            Crop crop = page.getRotation() % 360 == 0 ? largestImage(page) : null;
            return new Rendered(out.toByteArray(), image.getWidth(), image.getHeight(), crop);
        } catch (IOException e) {
            throw new PageNotFound("PDF를 그릴 수 없습니다: " + e.getClass().getSimpleName());
        }
    }

    /** 쪽에 그린 래스터 이미지 가운데 가장 큰 것의 자리(여백 포함). 쪽의 10%보다 작으면 null. 회전한 쪽은 다루지 않는다 */
    static Crop largestImage(PDPage page) throws IOException {
        ImageBounds bounds = new ImageBounds();
        bounds.processPage(page);
        if (bounds.best == null) return null;
        PDRectangle box = page.getCropBox();
        double pw = box.getWidth(), ph = box.getHeight();
        double[] b = bounds.best; // minX, minY, maxX, maxY (PDF 좌표, 왼쪽 아래 기준)
        double x0 = Math.max(0, (b[0] - box.getLowerLeftX()) / pw - CROP_PADDING);
        double x1 = Math.min(1, (b[2] - box.getLowerLeftX()) / pw + CROP_PADDING);
        double y0 = Math.max(0, (box.getUpperRightY() - b[3]) / ph - CROP_PADDING);
        double y1 = Math.min(1, (box.getUpperRightY() - b[1]) / ph + CROP_PADDING);
        if ((x1 - x0) * (y1 - y0) < MIN_IMAGE_AREA || x1 - x0 >= 0.999 && y1 - y0 >= 0.999) return null;
        return new Crop(x0, y0, x1 - x0, y1 - y0);
    }

    /** 이미지 그리기(Do)마다 현재 변환 행렬로 이미지 단위 사각형이 놓인 자리를 구해 가장 넓은 것을 남긴다 */
    private static final class ImageBounds extends PDFStreamEngine {
        double[] best;
        double bestArea;

        ImageBounds() {
            addOperator(new Concatenate(this));
            addOperator(new SetGraphicsStateParameters(this));
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new SetMatrix(this));
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name) {
                PDXObject object = getResources().getXObject(name);
                if (object instanceof PDImageXObject) {
                    record(getGraphicsState().getCurrentTransformationMatrix());
                    return;
                }
                if (object instanceof PDFormXObject form) {
                    showForm(form);
                    return;
                }
            }
            super.processOperator(operator, operands);
        }

        private void record(Matrix m) {
            double[][] corners = { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 1 } };
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] c : corners) {
                double x = m.getScaleX() * c[0] + m.getShearX() * c[1] + m.getTranslateX();
                double y = m.getShearY() * c[0] + m.getScaleY() * c[1] + m.getTranslateY();
                minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
            double area = (maxX - minX) * (maxY - minY);
            if (area > bestArea) {
                bestArea = area;
                best = new double[] { minX, minY, maxX, maxY };
            }
        }
    }
}
