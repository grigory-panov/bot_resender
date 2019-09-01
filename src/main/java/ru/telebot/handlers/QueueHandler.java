package ru.telebot.handlers;

import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.telebot.Bot;

import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;

public class QueueHandler implements MessageListener {
    public static final String CHAT_ID_PROPERTY = "chat_id_prop";
    public static final String TITLE_PROPERTY = "title_prop";
    public static final String DATE_PROPERTY = "date_prop";

    private static final Logger logger = LoggerFactory.getLogger(QueueHandler.class);

    @Override
    public void onMessage(Message message) {
        ObjectMessage objMessage = (ObjectMessage) message;

        while (!Bot.isReady()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }

        try {
            Thread.sleep(Bot.SENDING_DELAY);
            //in case of Exception here message will not acknowledged
            TdApi.UpdateNewMessage newMessage = (TdApi.UpdateNewMessage) objMessage.getObject();
            logger.debug("processing message " + newMessage.message.id);
            Bot.forwardMessage(objMessage.getLongProperty(CHAT_ID_PROPERTY), newMessage, objMessage.getStringProperty(TITLE_PROPERTY), objMessage.getIntProperty(DATE_PROPERTY));
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
