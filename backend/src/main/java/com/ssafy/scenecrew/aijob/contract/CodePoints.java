package com.ssafy.scenecrew.aijob.contract;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * 문자열 길이를 코드포인트로 센다. AI 계약(pydantic max_length)과 BE 공개 API(028 등)는 코드포인트로 세는데,
 * Bean Validation의 @Size는 UTF-16 단위로 세어 이모지 20자 이름(UTF-16 40)을 잘못 거절한다(#280 리뷰). null은 통과한다.
 */
@Documented
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, TYPE_USE})
@Constraint(validatedBy = CodePoints.Validator.class)
public @interface CodePoints {
    int max();

    String message() default "코드포인트 {max}자 이하여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<CodePoints, String> {
        private int max;

        @Override
        public void initialize(CodePoints annotation) {
            this.max = annotation.max();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.codePointCount(0, value.length()) <= max;
        }
    }
}
