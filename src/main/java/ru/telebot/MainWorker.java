package ru.telebot;

import org.apache.activemq.broker.BrokerFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.usage.MemoryUsage;
import org.apache.activemq.usage.StoreUsage;
import org.apache.activemq.usage.SystemUsage;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOError;
import java.io.IOException;
import java.net.URI;

public class MainWorker {

    /**
     * Example class for TDLib usage from Java.
     */
    private static final Logger logger = LoggerFactory.getLogger(Bot.class);

    static {
        System.loadLibrary("tdjni");
    }


    public static void main(String[] args) throws Exception {
        try {
            Config.init();
            Client.execute(new TdApi.SetLogVerbosityLevel(Config.getIntValue("tdlib.log_level")));

            if (Client.execute(new TdApi.SetLogStream(new TdApi.LogStreamFile(Config.getValue("tdlib.log_file"), 100000000L))) instanceof TdApi.Error) {
                throw new IOError(new IOException("Write access to the file " + Config.getValue("tdlib.log_file") + " is required"));
            }

            BrokerService broker = BrokerFactory.createBroker(new URI("broker:(tcp://localhost:61616)"));
            broker.setPersistent(true);
            SystemUsage systemUsage = new SystemUsage();
            MemoryUsage memoryUsage = new MemoryUsage();
            memoryUsage.setPercentOfJvmHeap(5);

            systemUsage.setMemoryUsage(memoryUsage);
            StoreUsage storeUsage = new StoreUsage();
            storeUsage.setLimit(500 * 1024 * 1024);
            systemUsage.setStoreUsage(storeUsage);
            broker.setConsumerSystemUsage(systemUsage);
            broker.start();

            Bot obj = null;
            try {
                obj = new Bot();
            } catch (BotException ex) {
                logger.error(ex.getMessage(), ex);
                throw ex;
            }
            Thread bot = new Thread(obj);
            bot.start();
            while (bot.isAlive()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    obj.close();
                    bot.interrupt();
                    Thread.sleep(100);
                    throw ex;
                }
            }
        } catch (Throwable ex) {
            logger.error(ex.getMessage(), ex);
            throw ex;
        }

    }

}
