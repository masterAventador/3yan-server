package com.sanyan.embedding.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@link EmbedRequest} 上的 Bean Validation 注解（@NotEmpty / @Size）真实生效。
 *
 * <p>纯契约模块的 -api 没有业务逻辑，TDD 的目标退化为"注解契约本身存在且生效"。
 * 一旦哪天有人把注解删了或写错（例如 @NotEmpty 写成 @NotNull），测试会立刻 fail。
 */
class EmbedRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void validRequest_passesValidation() {
        EmbedRequest req = new EmbedRequest(List.of("你好", "world"));
        Set<ConstraintViolation<EmbedRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "valid request should have no violations, got: " + violations);
    }

    @Test
    void nullTexts_violatesNotEmpty() {
        EmbedRequest req = new EmbedRequest(null);
        Set<ConstraintViolation<EmbedRequest>> violations = validator.validate(req);
        assertEquals(1, violations.size(), "null texts should trigger exactly one violation");
        assertEquals("texts", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    void emptyTexts_violatesNotEmpty() {
        EmbedRequest req = new EmbedRequest(List.of());
        Set<ConstraintViolation<EmbedRequest>> violations = validator.validate(req);
        assertEquals(1, violations.size(), "empty texts should trigger exactly one violation");
        assertEquals("texts", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    void singleTextExceedingMaxSize_violatesSize() {
        String tooLong = "a".repeat(2001);
        EmbedRequest req = new EmbedRequest(List.of("ok", tooLong));
        Set<ConstraintViolation<EmbedRequest>> violations = validator.validate(req);
        assertEquals(1, violations.size(), "one over-size text should trigger one violation, got: " + violations);
    }

    @Test
    void textAtExactlyMaxSize_passesValidation() {
        // boundary：恰好 2000 字符必须通过
        String atLimit = "a".repeat(2000);
        EmbedRequest req = new EmbedRequest(List.of(atLimit));
        Set<ConstraintViolation<EmbedRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "exactly 2000 chars should pass, got: " + violations);
    }
}
