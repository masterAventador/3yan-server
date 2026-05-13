package com.sanyan.common.error;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ErrCodeConflictDetector implements ApplicationRunner {

    private final List<Class<? extends ErrCode>> errCodeEnums;

    @Override
    public void run(ApplicationArguments args) {
        Map<Integer, String> seen = new HashMap<>();
        for (Class<? extends ErrCode> clazz : errCodeEnums) {
            if (!clazz.isEnum()) continue;
            for (ErrCode code : clazz.getEnumConstants()) {
                String location = clazz.getSimpleName() + "." + ((Enum<?>) code).name();
                String existing = seen.put(code.getCode(), location);
                if (existing != null) {
                    throw new IllegalStateException(String.format(
                        "ErrCode 冲突：code=%d 被 %s 和 %s 同时定义",
                        code.getCode(), existing, location));
                }
            }
        }
        log.info("ErrCode 冲突扫描通过，共 {} 个 enum，{} 个码", errCodeEnums.size(), seen.size());
    }
}
