package com.sanyan.common.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WsMessageDeserializeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void should_deserialize_ack_frame_with_ackMsgId() throws Exception {
        WsMessage msg = mapper.readValue("{\"type\":\"ack\",\"ackMsgId\":777}", WsMessage.class);

        assertThat(msg.getType()).isEqualTo(WsEventType.ACK);
        assertThat(msg.getAckMsgId()).isEqualTo(777L);
    }

    @Test
    void ackMsgId_should_be_null_when_absent() throws Exception {
        WsMessage msg = mapper.readValue("{\"type\":\"send_message\",\"content\":\"hi\"}", WsMessage.class);

        assertThat(msg.getAckMsgId()).isNull();
    }
}
