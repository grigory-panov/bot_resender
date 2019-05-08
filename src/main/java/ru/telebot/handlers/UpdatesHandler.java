package ru.telebot.handlers;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import ru.telebot.Bot;

public class UpdatesHandler implements Client.ResultHandler {

    private final String phone;

    public UpdatesHandler(String phone) {
        this.phone = phone;
    }

    @Override
    public void onResult(TdApi.Object object) {
        switch (object.getConstructor()) {
            case TdApi.UpdateAuthorizationState.CONSTRUCTOR:
                Bot.onAuthorizationStateUpdated(((TdApi.UpdateAuthorizationState) object).authorizationState, phone);
                break;
            case TdApi.UpdateNewMessage.CONSTRUCTOR:
                Bot.onNewMessage((TdApi.UpdateNewMessage) object, phone);
                break;
            default:
                break;
        }
    }

}
