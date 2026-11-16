package com.ssafy.scenecrew.document.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 문서 하나의 실제 내용을 검사한다(BE05, P08). 확장자와 클라이언트 Content-Type은 믿지 않는다.
 * PDF는 내용이 %PDF-로 시작하고 PDFBox로 열려야 하며 30쪽 이하. MD·TXT는 UTF-8 글자여야 하고,
 * 둘은 내용으로 구분할 수 없어 이 경우에만 확장자로 고른다.
 */
final class DocumentInspector {
    static final long MAX_FILE_BYTES = 20_971_520L;
    static final int MAX_PDF_PAGES = 30;
    static final String PDF = "application/pdf";
    static final String MARKDOWN = "text/markdown";
    static final String TEXT = "text/plain";
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_EXTENSION_LENGTH = 10;

    private DocumentInspector() {
    }

    /** 검사를 통과한 문서. pageCount는 PDF만 값이 있다. */
    record Inspected(String originalName, String mime, byte[] content, Integer pageCount) {
    }

    static Inspected inspect(DocumentUpload upload) {
        String name = cleanName(upload.originalName());
        byte[] content = upload.content();
        if (content == null || content.length == 0) {
            throw invalid(ErrorCode.INVALID_REQUEST, name, "빈 파일입니다.");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "문서는 파일당 20MB까지 올릴 수 있습니다.",
                    Map.of("fileName", name, "maxBytes", MAX_FILE_BYTES));
        }
        String extension = extension(name);
        if (startsWithPdfMarker(content)) {
            return new Inspected(name, PDF, content, pdfPages(name, content));
        }
        String mime = switch (extension) {
            case "md", "markdown" -> MARKDOWN;
            case "txt" -> TEXT;
            default -> throw invalid(ErrorCode.UNSUPPORTED_MEDIA_TYPE, name, "PDF, MD, TXT 문서만 올릴 수 있습니다.");
        };
        checkText(name, content);
        return new Inspected(name, mime, content, null);
    }

    private static int pdfPages(String name, byte[] content) {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            int pages = pdf.getNumberOfPages();
            if (pages < 1) {
                throw invalid(ErrorCode.INVALID_REQUEST, name, "쪽이 없는 PDF입니다.");
            }
            if (pages > MAX_PDF_PAGES) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "PDF는 30쪽까지 올릴 수 있습니다.",
                        Map.of("fileName", name, "maxPages", MAX_PDF_PAGES, "pages", pages));
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw invalid(ErrorCode.INVALID_REQUEST, name, "암호가 걸린 PDF는 올릴 수 없습니다.");
        } catch (IOException e) {
            throw invalid(ErrorCode.INVALID_REQUEST, name, "PDF를 열 수 없습니다.");
        }
    }

    private static void checkText(String name, byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw invalid(ErrorCode.UNSUPPORTED_MEDIA_TYPE, name, "MD·TXT 문서는 UTF-8이어야 합니다.");
        }
        if (text.indexOf('\0') >= 0) {
            throw invalid(ErrorCode.UNSUPPORTED_MEDIA_TYPE, name, "글자 문서가 아닙니다.");
        }
    }

    private static boolean startsWithPdfMarker(byte[] content) {
        byte[] marker = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (content.length < marker.length) {
            return false;
        }
        for (int i = 0; i < marker.length; i++) {
            if (content[i] != marker[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 경로를 떼고 제어 문자를 지운 이름(최대 200자, 자를 때도 확장자 유지). macOS가 보내는 분리형 한글(NFD)은 합쳐서(NFC) 저장한다.
     * 이름은 DB original_name과 다운로드 Content-Disposition에만 쓴다.
     */
    static String cleanName(String originalName) {
        String name = originalName == null ? "" : originalName;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            return "document";
        }
        if (name.codePointCount(0, name.length()) <= MAX_NAME_LENGTH) {
            return name;
        }
        // 형식을 확장자로 고르는 경우(MD·TXT)가 있으므로 자를 때도 확장자는 남긴다.
        String extension = extension(name);
        String suffix = extension.isEmpty() || extension.length() > MAX_EXTENSION_LENGTH
                ? "" : name.substring(name.lastIndexOf('.'));
        String base = suffix.isEmpty() ? name : name.substring(0, name.length() - suffix.length());
        int keep = MAX_NAME_LENGTH - suffix.length();
        return base.substring(0, base.offsetByCodePoints(0, keep)) + suffix;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static BusinessException invalid(ErrorCode code, String name, String message) {
        return new BusinessException(code, message, Map.of("fileName", name));
    }
}
