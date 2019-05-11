package ru.telebot.domain;

import java.time.LocalDateTime;

public class ExpiryEntity {
    private LocalDateTime expireTime;
    private String value;

    public ExpiryEntity(String value, LocalDateTime expireTime) {
        this.expireTime = expireTime;
        this.value = value;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public String getValue() {
        return value;
    }

}
