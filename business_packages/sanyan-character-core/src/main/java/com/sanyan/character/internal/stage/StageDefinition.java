package com.sanyan.character.internal.stage;

import com.sanyan.character.internal.intimacy.IntimacyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StageDefinition {
    public static final String[] NAMES = {"陌生人", "朋友", "暧昧", "恋人", "老夫老妻"};

    private final IntimacyProperties props;

    public int stageFor(int score) {
        var s = props.getStages();
        if (score < s.getStrangerEnd()) return 0;
        if (score < s.getFriendEnd())   return 1;
        if (score < s.getAmbiguousEnd()) return 2;
        if (score < s.getLoverEnd())    return 3;
        return 4;
    }

    public String nameOf(int stage) {
        return NAMES[stage];
    }

    public int nextThresholdOf(int stage) {
        var s = props.getStages();
        return switch (stage) {
            case 0 -> s.getStrangerEnd();
            case 1 -> s.getFriendEnd();
            case 2 -> s.getAmbiguousEnd();
            case 3 -> s.getLoverEnd();
            default -> Integer.MAX_VALUE;
        };
    }
}
