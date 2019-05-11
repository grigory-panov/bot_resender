package ru.telebot.domain;

public class Chat {
    private long chatIdFrom;
    private long chatIdTo;
    private String owner;
    private String name;

    public long getChatIdFrom() {
        return chatIdFrom;
    }

    public void setChatIdFrom(long chatIdFrom) {
        this.chatIdFrom = chatIdFrom;
    }

    public long getChatIdTo() {
        return chatIdTo;
    }

    public void setChatIdTo(long chatIdTo) {
        this.chatIdTo = chatIdTo;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "Chat{" + name + ", " + chatIdFrom + " -> " + chatIdTo + ", owner=" + owner + '}';
    }
}
