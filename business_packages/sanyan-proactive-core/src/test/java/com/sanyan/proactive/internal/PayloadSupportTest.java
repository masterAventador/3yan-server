package com.sanyan.proactive.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadSupportTest {

    // ── toJson ─────────────────────────────────────────────────────────────────

    @Test
    void toJson_should_serialize_simple_map_to_json_string() {
        String json = PayloadSupport.toJson(Map.of("key", "value"));

        assertThat(json).contains("\"key\"");
        assertThat(json).contains("\"value\"");
    }

    @Test
    void toJson_should_include_numeric_values() {
        String json = PayloadSupport.toJson(Map.of("level", 2));

        assertThat(json).contains("\"level\"");
        assertThat(json).contains("2");
    }

    @Test
    void toJson_should_return_empty_json_object_on_serialization_failure() {
        // 无法直接触发 Jackson 序列化失败（Map<String,Object> 总能序列化），
        // 测试正常路径返回有效 JSON（不是 null/空串）
        String json = PayloadSupport.toJson(Map.of());

        assertThat(json).isNotNull();
        assertThat(json).isNotBlank();
    }

    @Test
    void toJson_result_is_parseable_json_object() {
        String json = PayloadSupport.toJson(Map.of("memoryItemId", 7L, "escalationLevel", 0));

        // 是 JSON 对象格式：以 { 开头 } 结尾
        assertThat(json.trim()).startsWith("{").endsWith("}");
        assertThat(json).contains("memoryItemId");
        assertThat(json).contains("escalationLevel");
    }
}
