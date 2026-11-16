package com.ssafy.scenecrew.global.text;

import java.util.regex.Pattern;
import com.ibm.icu.lang.UCharacter;

/** AI Python 3.12의 re whitespace/str.casefold와 같은 비교 기준. 표시 원문은 따로 보존한다. */
public final class UnicodeText {
    // Python whitespace는 Unicode White_Space에 U+001C..001F 구분자를 더한다.
    private static final String WHITESPACE = "[\\s\\x1c-\\x1f]+";
    private static final Pattern SPACES = Pattern.compile(WHITESPACE, Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern EDGES = Pattern.compile("\\A" + WHITESPACE + "|" + WHITESPACE + "\\z",
            Pattern.UNICODE_CHARACTER_CLASS);

    private UnicodeText() { }

    public static boolean isBlank(String value) {
        return value.isEmpty() || SPACES.matcher(value).matches();
    }

    public static String strip(String value) {
        return EDGES.matcher(value).replaceAll("");
    }

    public static String collapseWhitespace(String value) {
        return SPACES.matcher(value).replaceAll(" ").strip();
    }

    /** NFC/NFKC 변환은 하지 않는다. 이름 비교는 공백 제거와 기본 전체 casefold만 적용한다. */
    public static String nameKey(String value) {
        return UCharacter.foldCase(SPACES.matcher(value).replaceAll(""), true);
    }
}
