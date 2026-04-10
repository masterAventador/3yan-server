package com.sanyan.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaServiceTest {

    @Test
    void uploadVoice_rejectsFileTooLarge() {
        CosService mockCos = Mockito.mock(CosService.class);
        MediaService service = new MediaService(mockCos);
        byte[] bigFile = new byte[6 * 1024 * 1024]; // 6MB
        MockMultipartFile file = new MockMultipartFile("file", "test.m4a", "audio/mp4", bigFile);

        assertThatThrownBy(() -> service.uploadVoice(1L, file, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件过大");
    }

    @Test
    void uploadVoice_rejectsDurationOutOfRange() {
        CosService mockCos = Mockito.mock(CosService.class);
        MediaService service = new MediaService(mockCos);
        MockMultipartFile file = new MockMultipartFile("file", "test.m4a", "audio/mp4", new byte[100]);

        assertThatThrownBy(() -> service.uploadVoice(1L, file, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.uploadVoice(1L, file, 61))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uploadVoice_callsCosAndReturnsData() throws Exception {
        CosService mockCos = Mockito.mock(CosService.class);
        Mockito.when(mockCos.upload(Mockito.any(), Mockito.anyString(), Mockito.eq("audio/mp4")))
                .thenReturn("https://3yan-1258800826.cos.ap-beijing.myqcloud.com/voice/user/1/xxx.m4a");

        MediaService service = new MediaService(mockCos);
        MockMultipartFile file = new MockMultipartFile("file", "test.m4a", "audio/mp4", new byte[100]);

        var result = service.uploadVoice(1L, file, 12);

        assertThat(result.getUrl()).contains("voice/user/1/");
        assertThat(result.getDuration()).isEqualTo(12);
    }
}
