package com.ssafy.scenecrew.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.ssafy.scenecrew.document.service.DocumentInspector.Inspected;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

class DocumentInspectorTest {

    @Test
    void pdfIsRecognizedByContentWithRealPageCount() {
        Inspected pdf = DocumentInspector.inspect(new DocumentUpload("계획서.txt", Pdfs.pages(30)));

        assertThat(pdf.mime()).isEqualTo(DocumentInspector.PDF);
        assertThat(pdf.pageCount()).isEqualTo(30);
    }

    @Test
    void pdfOver30PagesOrBrokenOrLockedIsRejected() {
        assertCode(new DocumentUpload("a.pdf", Pdfs.pages(31)), ErrorCode.PAYLOAD_TOO_LARGE);
        assertCode(new DocumentUpload("a.pdf", "%PDF-1.7 깨진 파일".getBytes(StandardCharsets.UTF_8)),
                ErrorCode.INVALID_REQUEST);
        assertCode(new DocumentUpload("a.pdf", Pdfs.passwordProtected()), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void textIsMarkdownOrPlainByExtensionAndMustBeUtf8() {
        byte[] text = "# 운영 안내\n입장 10시".getBytes(StandardCharsets.UTF_8);

        assertThat(DocumentInspector.inspect(new DocumentUpload("안내.MD", text)).mime())
                .isEqualTo(DocumentInspector.MARKDOWN);
        Inspected plain = DocumentInspector.inspect(new DocumentUpload("안내.txt", text));
        assertThat(plain.mime()).isEqualTo(DocumentInspector.TEXT);
        assertThat(plain.pageCount()).isNull();

        // 이름만 .pdf인 글자 파일, 지원하지 않는 확장자, UTF-8이 아닌 파일, 바이너리는 거절한다.
        assertCode(new DocumentUpload("안내.pdf", text), ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertCode(new DocumentUpload("안내.docx", text), ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertCode(new DocumentUpload("안내.txt", "운영".getBytes(java.nio.charset.Charset.forName("EUC-KR"))),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertCode(new DocumentUpload("안내.txt", new byte[] {'a', 0, 'b'}), ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertCode(new DocumentUpload("안내.txt", new byte[0]), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void fileOver20MbIsRejected() {
        byte[] large = Arrays.copyOf("a".getBytes(), (int) DocumentInspector.MAX_FILE_BYTES + 1);
        Arrays.fill(large, (byte) 'a');

        assertCode(new DocumentUpload("큰 파일.txt", large), ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void longNameIsCutButKeepsExtensionSoTextIsStillAccepted() {
        String longName = "가".repeat(250) + ".Md";

        Inspected inspected = DocumentInspector.inspect(new DocumentUpload(longName, "# 안내".getBytes(StandardCharsets.UTF_8)));

        assertThat(inspected.mime()).isEqualTo(DocumentInspector.MARKDOWN);
        assertThat(inspected.originalName()).endsWith(".Md").hasSize(200);
    }

    @Test
    void nameDropsPathAndControlCharsAndIsComposed() {
        String decomposed = Normalizer.normalize("안내.md", Normalizer.Form.NFD);

        assertThat(DocumentInspector.cleanName("C:\\Users\\me\\" + decomposed)).isEqualTo("안내.md");
        assertThat(DocumentInspector.cleanName("../../etc/a\u0000b.txt")).isEqualTo("ab.txt");
        assertThat(DocumentInspector.cleanName("  ")).isEqualTo("document");
        assertThat(DocumentInspector.cleanName(null)).isEqualTo("document");
    }

    private static void assertCode(DocumentUpload upload, ErrorCode code) {
        assertThatThrownBy(() -> DocumentInspector.inspect(upload)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
