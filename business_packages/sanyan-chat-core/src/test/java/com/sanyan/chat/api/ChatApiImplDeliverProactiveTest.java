package com.sanyan.chat.api;

import com.sanyan.chat.internal.DeliveryService;
import com.sanyan.chat.internal.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatApiImplDeliverProactiveTest {

    @Mock MessageRepository repository;
    @Mock DeliveryService deliveryService;
    @InjectMocks ChatApiImpl chatApi;

    @Test
    void deliverProactiveMessage_should_delegate_to_deliveryService_and_return_ids() {
        when(deliveryService.deliver(eq(1L), eq(99L), eq(List.of("早安", "今天也要开心")))).
                thenReturn(List.of(10L, 11L));

        List<Long> ids = chatApi.deliverProactiveMessage(1L, 99L, List.of("早安", "今天也要开心"));

        assertThat(ids).containsExactly(10L, 11L);
        verify(deliveryService).deliver(1L, 99L, List.of("早安", "今天也要开心"));
    }
}
