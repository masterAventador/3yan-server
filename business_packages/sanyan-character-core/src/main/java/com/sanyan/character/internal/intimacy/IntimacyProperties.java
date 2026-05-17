package com.sanyan.character.internal.intimacy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("sanyan.intimacy")
@Data
public class IntimacyProperties {
    private Stages stages = new Stages();
    private Delta delta = new Delta();
    private Rules rules = new Rules();
    private Ai ai = new Ai();

    @Data
    public static class Stages {
        int strangerEnd;
        int friendEnd;
        int ambiguousEnd;
        int loverEnd;
    }

    @Data
    public static class Delta {
        int messageSent = 1;
        int messageDailyCap = 50;
        int dailyLogin = 10;
        int streakBonusPerDay = 5;
        int streakBonusCap = 50;
    }

    @Data
    public static class Rules {
        int deepNightChat = 50;
        int firstHonestShare = 30;
    }

    @Data
    public static class Ai {
        int qualityMaxScore = 20;
        int triggerEveryNMessages = 10;
    }
}
