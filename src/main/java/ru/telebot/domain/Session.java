package ru.telebot.domain;

public class Session {
    private String phone;
    private State authState;
    private String currentAction;
    private Long botChatId;
    private Integer clientId;
    private String firstParam;

    public Session() {
    }

    public Session(String phone, State state) {
        this.phone = phone;
        this.authState = state;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public State getAuthState() {
        return authState;
    }

    public void setAuthState(State authState) {
        this.authState = authState;
    }

    public String getCurrentAction() {
        return currentAction;
    }

    public void setCurrentAction(String currentAction) {
        this.currentAction = currentAction;
    }

    public Long getBotChatId() {
        return botChatId;
    }

    public void setBotChatId(Long botChatId) {
        this.botChatId = botChatId;
    }

    public String getFirstParam() {
        return firstParam;
    }

    public void setFirstParam(String firstParam) {
        this.firstParam = firstParam;
    }

    public Integer getClientId() {
        return clientId;
    }

    public void setClientId(Integer clientId) {
        this.clientId = clientId;
    }

    @Override
    public String toString() {
        return "Session{" +
                "phone='" + phone + '\'' +
                ", authState=" + authState +
                ", currentAction='" + currentAction + '\'' +
                ", botChatId=" + botChatId +
                ", clientId=" + clientId +
                ", firstParam='" + firstParam + '\'' +
                '}';
    }
}
