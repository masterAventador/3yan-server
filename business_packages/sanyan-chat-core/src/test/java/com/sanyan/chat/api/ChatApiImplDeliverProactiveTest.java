package com.sanyan.chat.api;

import com.sanyan.chat.SenderType;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.chat.internal.DeliveryService;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void listRecentProactive_maps_to_dto_and_passes_pageable() {
        MessageEntity e = new MessageEntity();
        e.setId(9L);
        e.setUserId(1L);
        e.setSenderType(SenderType.AI);
        e.setContent("早安");
        e.setProactive(true);
        when(repository.findByUserIdAndIsProactiveTrueOrderByIdDesc(eq(1L), any()))
                .thenReturn(List.of(e));

        List<MessageDto> dtos = chatApi.listRecentProactive(1L, 5);

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).content()).isEqualTo("早安");
    }
}
