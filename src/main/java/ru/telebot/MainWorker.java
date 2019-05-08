package ru.telebot;

import org.drinkless.tdlib.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOError;
import java.io.IOException;

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
            Log.setVerbosityLevel(Config.getIntValue("tdlib.log_level"));
            if (!Log.setFilePath(Config.getValue("tdlib.log_file"))) {
                throw new IOError(new IOException("Write access to the directory " + Config.getValue("tdlib.log_file") + "is required"));
            }
            Bot obj = null;
            try {
                obj = new Bot();
            }catch(BotException ex){
                throw ex;
            }
            Thread bot = new Thread(obj);
            bot.start();
            while (bot.isAlive()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    bot.interrupt();
                    Thread.sleep(100);
                    throw ex;
                }
            }
        }catch (Throwable ex){
            logger.error(ex.getMessage(), ex);
            throw ex;
        }

    }

}
