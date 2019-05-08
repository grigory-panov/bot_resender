package ru.telebot.handlers;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import ru.telebot.Bot;

public class BotUpdatesHandler implements Client.ResultHandler {

    @Override
    public void onResult(TdApi.Object object) {
            switch (object.getConstructor()) {
                case TdApi.UpdateAuthorizationState.CONSTRUCTOR:
                    Bot.onBotAuthorizationStateUpdated(((TdApi.UpdateAuthorizationState) object).authorizationState);
                    break;
                case TdApi.UpdateNewMessage.CONSTRUCTOR:
                    Bot.onNewBotMessage((TdApi.UpdateNewMessage) object);
                    break;
                default:
                    break;
            }

    }
}
