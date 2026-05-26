package com.sanyan.memory.internal.item;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryItemExtractResultParserTest {

    @Test
    void parse_should_read_items_with_all_fields() {
        String json = """
                {
                  "items": [
                    { "action": "NEW",    "kind": "PLAN_EVENT", "content": "周三下午有面试", "dateHint": "2026-06-03", "targetId": null },
                    { "action": "UPDATE", "kind": "EMOTION",    "content": "面试很焦虑",     "dateHint": null,         "targetId": 55 },
                    { "action": "SKIP",   "kind": "PLAN_EVENT", "content": "",              "dateHint": null,         "targetId": 99 }
                  ]
                }
                """;

        MemoryItemExtractResult result = MemoryItemExtractResult.parse(json);

        assertThat(result.items()).hasSize(3);

        MemoryItemExtractResult.Item first = result.items().get(0);
        assertThat(first.action()).isEqualTo("NEW");
        assertThat(first.kind()).isEqualTo("PLAN_EVENT");
        assertThat(first.content()).isEqualTo("周三下午有面试");
        assertThat(first.dateHint()).isEqualTo("2026-06-03");
        assertThat(first.targetId()).isNull();

        MemoryItemExtractResult.Item second = result.items().get(1);
        assertThat(second.action()).isEqualTo("UPDATE");
        assertThat(second.targetId()).isEqualTo(55L);

        MemoryItemExtractResult.Item third = result.items().get(2);
        assertThat(third.action()).isEqualTo("SKIP");
        assertThat(third.targetId()).isEqualTo(99L);
    }

    @Test
    void parse_should_strip_markdown_code_fence() {
        String json = """
                ```json
                { "items": [ { "action": "NEW", "kind": "EMOTION", "content": "心情不错", "dateHint": null, "targetId": null } ] }
                ```
                """;

        MemoryItemExtractResult result = MemoryItemExtractResult.parse(json);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).kind()).isEqualTo("EMOTION");
    }

    @Test
    void parse_should_return_empty_items_on_blank_or_garbage() {
        assertThat(MemoryItemExtractResult.parse(null).items()).isEmpty();
        assertThat(MemoryItemExtractResult.parse("").items()).isEmpty();
        assertThat(MemoryItemExtractResult.parse("这不是 JSON").items()).isEmpty();
    }

    @Test
    void parse_should_default_items_to_empty_list_when_field_missing() {
        MemoryItemExtractResult result = MemoryItemExtractResult.parse("{}");
        assertThat(result.items()).isEmpty();
    }
}
