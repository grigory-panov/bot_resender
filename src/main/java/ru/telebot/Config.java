package ru.telebot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Properties;

public class Config {

    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static Properties props = null;

    public static void init() throws BotException {
        logger.debug("init Bot");
        String fileName = System.getProperty("bot.config");
        if (fileName == null) {
            throw new BotException("bot.config variable not set, call bot with -Dbot.config=path_to_file parameter");
        }
        props = new Properties();
        try (FileInputStream fis = new FileInputStream(Paths.get(fileName).toFile())) {
            props.load(fis);
            props.stringPropertyNames().forEach(name -> logger.info(name + " : " + props.getProperty(name)));
        } catch (IOException ex) {
            logger.error(ex.getMessage(), ex);
            throw new BotException(ex);
        }
    }

    public static String getValue(String name) throws BotException {
        if(props == null) {
            throw new BotException("Config is not inited!");
        }
        return props.getProperty(name);
    }

    public static int getIntValue(String name) throws BotException {
        if(props == null) {
            throw new BotException("Config is not inited!");
        }
        return Integer.parseInt(props.getProperty(name));
    }

    public static int getIntValueOrDefault(String name, int defaultValue) throws BotException {
        if (props == null) {
            throw new BotException("Config is not inited!");
        }
        String value = props.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }
}
