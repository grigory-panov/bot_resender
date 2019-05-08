package ru.telebot.domain;

public class Session {
    private String phone;
    private State state;

    public Session() {
    }

    public Session(String phone, State state) {
        this.phone = phone;
        this.state = state;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }
}
