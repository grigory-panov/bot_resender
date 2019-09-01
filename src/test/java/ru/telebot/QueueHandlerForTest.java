package ru.telebot;

import javax.jms.Message;
import javax.jms.MessageListener;

public class QueueHandlerForTest implements MessageListener {
    @Override
    public void onMessage(Message message) {
        try {
            Thread.sleep(1000);
            System.out.println("received new message");
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
