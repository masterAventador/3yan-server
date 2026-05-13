package com.sanyan.common.test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 仅供 IT 测试使用：业务模块本身不含 main 类（main 类在 bootstrap），
 * Spring Boot Test 需要一个 {@code @SpringBootConfiguration} 作为上下文根，
 * 提供这个空配置即可让 {@code @WebMvcTest} / {@code @DataJpaTest} 正常启动。
 *
 * <p>由于本类位于 {@code com.sanyan.common.test} 包，业务模块的测试通过包路径
 * 自动发现不到，需在 {@code @WebMvcTest} / {@code @DataJpaTest} 测试类上加：
 * <pre>{@code
 *   @ContextConfiguration(classes = TestApplication.class)
 * }</pre>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestApplication {}
