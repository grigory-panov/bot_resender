package ru.telebot.handlers;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthorizationRequestHandler implements Client.ResultHandler{

    private static final Logger logger = LoggerFactory.getLogger(AuthorizationRequestHandler.class);

        @Override
        public void onResult(TdApi.Object object) {
            switch (object.getConstructor()) {
                case TdApi.Error.CONSTRUCTOR:
                    logger.error("Receive an error:" + object);
                    break;
                case TdApi.Ok.CONSTRUCTOR:
                    // result is already received through UpdateAuthorizationState, nothing to do
                    break;
                default:
                    logger.error("Receive wrong response from TDLib:" + object);
            }
        }

}
