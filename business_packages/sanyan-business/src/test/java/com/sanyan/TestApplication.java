package com.sanyan;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 仅供 IT 测试使用：sanyan-business 模块本身不含 main 类（main 类在 bootstrap），
 * Spring Boot Test 需要一个 {@code @SpringBootConfiguration} 作为上下文根，
 * 提供这个空配置即可让 {@code @WebMvcTest} / {@code @DataJpaTest} 正常启动。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestApplication {}
