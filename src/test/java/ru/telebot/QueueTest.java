package ru.telebot;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.usage.MemoryUsage;
import org.apache.activemq.usage.StoreUsage;
import org.apache.activemq.usage.SystemUsage;
import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import javax.jms.*;
import java.net.URI;

public class QueueTest {

    @Test
    public void name() throws Exception {

        BrokerService broker = BrokerFactory.createBroker(new URI("broker:(tcp://localhost:61616)"));
        broker.setPersistent(false);
        SystemUsage systemUsage = new SystemUsage();
        MemoryUsage memoryUsage = new MemoryUsage();
        memoryUsage.setPercentOfJvmHeap(5);

        systemUsage.setMemoryUsage(memoryUsage);
        StoreUsage storeUsage = new StoreUsage();
        storeUsage.setLimit(500 * 1024 * 1024);
        systemUsage.setStoreUsage(storeUsage);
        broker.setConsumerSystemUsage(systemUsage);
        broker.start();

        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory("tcp://localhost:61616");
        Connection jmsConnection = connectionFactory.createConnection();
        jmsConnection.start();
        Session jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = jmsSession.createQueue("oneWindow");
        MessageProducer producer = jmsSession.createProducer(queue);
        TdApi.UpdateNewMessage newMEssage = new TdApi.UpdateNewMessage();
        newMEssage.message = new TdApi.Message();
        newMEssage.message.content = new TdApi.MessageText(new TdApi.FormattedText("test", null), null);
        producer.send(jmsSession.createObjectMessage(newMEssage));
        producer.send(jmsSession.createObjectMessage(newMEssage));
        producer.send(jmsSession.createObjectMessage(newMEssage));
        producer.send(jmsSession.createObjectMessage(newMEssage));
        producer.send(jmsSession.createObjectMessage(newMEssage));

        MessageConsumer consumer = jmsSession.createConsumer(queue);
        consumer.setMessageListener(new QueueHandlerForTest());
        Thread.sleep(10000);

    }

}
