package com.ssafy.scenecrew.document.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/** 시험용 PDF를 만든다. */
final class Pdfs {

    private Pdfs() {
    }

    static byte[] pages(int count) {
        return build(count, null);
    }

    /** 열 때 암호가 필요한 PDF */
    static byte[] passwordProtected() {
        return build(1, "user-password");
    }

    private static byte[] build(int count, String userPassword) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < count; i++) {
                pdf.addPage(new PDPage());
            }
            if (userPassword != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-password", userPassword,
                        new AccessPermission());
                policy.setEncryptionKeyLength(128);
                pdf.protect(policy);
            }
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
