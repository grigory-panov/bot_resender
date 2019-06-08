package ru.telebot.handlers;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import ru.telebot.Bot;

public class UpdatesHandler implements Client.ResultHandler {

    private final String phone;
    private final long clientId;

    public UpdatesHandler(String phone, long clientId) {
        this.phone = phone;
        this.clientId = clientId;
    }

    @Override
    public void onResult(TdApi.Object object) {
        switch (object.getConstructor()) {
            case TdApi.UpdateAuthorizationState.CONSTRUCTOR:
                Bot.onAuthorizationStateUpdated(((TdApi.UpdateAuthorizationState) object).authorizationState, phone, clientId);
                break;
            case TdApi.UpdateNewMessage.CONSTRUCTOR:
                Bot.onNewMessage((TdApi.UpdateNewMessage) object, phone);
                break;
            default:
                break;
        }
    }

}
