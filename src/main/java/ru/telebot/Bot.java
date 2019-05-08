package ru.telebot;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.telebot.dao.DbHelper;
import ru.telebot.domain.Chat;
import ru.telebot.domain.Session;
import ru.telebot.domain.State;
import ru.telebot.handlers.AuthorizationRequestHandler;
import ru.telebot.handlers.BotUpdatesHandler;
import ru.telebot.handlers.UpdatesHandler;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;

public class Bot implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(Bot.class);

    private static int BOT_ID;
    private static String BOT_KEY;
    private static boolean PROXY_ENABLED;
    private static String PROXY_HOST;
    private static int PROXY_PORT;
    private static String PROXY_USER;
    private static String PROXY_PASS;

    private static String BOT_OWNER;

    private static HikariDataSource dataSource = null;
    private static final ConcurrentMap<String, Client> openSessions = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, Session> botSessions = new ConcurrentHashMap<>();

    private static Client worker;
    private static Client bot;

    private static final ExecutorService executor = new ThreadPoolExecutor(1, 30,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>());


    public Bot() throws BotException {
        logger.debug("init Bot");
        try {
            dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(Config.getValue("jdbc.url"));
            dataSource.setUsername(Config.getValue("jdbc.username"));
            dataSource.setPassword(Config.getValue("jdbc.password"));
            dataSource.setAutoCommit(false);
            dataSource.setMaximumPoolSize(10);
            dataSource.setMinimumIdle(1);
            dataSource.setConnectionTimeout(35000);
            dataSource.setMaxLifetime(3600000);
            dataSource.setDriverClassName(Config.getValue("jdbc.driver_class"));
            dataSource.setRegisterMbeans(false);
            BOT_ID = Config.getIntValue("bot.id");
            BOT_KEY = Config.getValue("bot.key");
            PROXY_ENABLED = Boolean.parseBoolean(Config.getValue("proxy.enabled"));
            PROXY_HOST = Config.getValue("proxy.host");
            PROXY_PORT = Config.getIntValue("proxy.port");
            PROXY_USER = Config.getValue("proxy.user");
            PROXY_PASS = Config.getValue("proxy.password");
            logger.debug("testing connection...");
            DbHelper.testConnection(dataSource);
            logger.debug("connection is OK");

            logger.debug("init completed");
        } catch (HikariPool.PoolInitializationException | SQLException ex) {
            logger.error(ex.getMessage(), ex);
            logger.debug("init failed");
            throw new BotException(ex);
        }
    }

    @Override
    public void run() {
        if (worker != null) {
            logger.error("cannot run more than one bot");
            return;
        }
        //worker = createClient(Config.getValue("bot.owner"));
        createBot();

        try {
            List<Session> sessions = DbHelper.getSessions(dataSource);
            sessions.forEach(s -> createClient(s.getPhone()));
        }catch (SQLException ex){
            logger.error(ex.getMessage(), ex);
            return;
        }

        while (true) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                break;
            }
        }

    }

    private static Client createClient(String phone) {
        UpdatesHandler handler = new UpdatesHandler(phone);
        Client client = Client.create(handler, e -> logger.error(e.getMessage(), e), e -> logger.error(e.getMessage(), e));
        openSessions.put(phone, client);
        if (PROXY_ENABLED) {

            client.send(new TdApi.AddProxy(PROXY_HOST, PROXY_PORT, true, new TdApi.ProxyTypeSocks5(PROXY_USER, PROXY_PASS)),
                    object -> logger.debug("execution completed " + object));
        }
        return client;
    }

    private static void createBot() {
        BotUpdatesHandler handler = new BotUpdatesHandler();
        bot = Client.create(handler, e -> logger.error(e.getMessage(), e), e -> logger.error(e.getMessage(), e));
        if (PROXY_ENABLED) {

            bot.send(new TdApi.AddProxy(PROXY_HOST, PROXY_PORT,true, new TdApi.ProxyTypeSocks5(PROXY_USER, PROXY_PASS)),
                    object -> logger.debug("execution completed " + object));
        }
    }

    public static void onNewMessage(TdApi.UpdateNewMessage message, String phone) {
        logger.debug("new message arrived for " + phone);
        if(worker == null){
            logger.error("worker not started, message ignored");
            return;
        }
        try {
            List<Chat> chats = DbHelper.getChatsToForward(dataSource, phone, message.message.chatId);
            for (Chat chat : chats) {
                if (!DbHelper.messageWasForwarderToChannel(dataSource, message.message.id, chat.getChatIdTo())) {
                    worker.send(new TdApi.ForwardMessages(chat.getChatIdTo(), message.message.chatId, new long[]{message.message.id}, true, true, false), object -> {
                        logger.debug("message " + message.message.id + " from chat " + message.message.chatId + " was forwarded to chat " + chat.getChatIdTo());
                        try {
                            DbHelper.addForwardedMessage(dataSource, message.message.id, chat.getChatIdTo());
                        } catch (SQLException ex) {
                            logger.error(ex.getMessage(), ex);
                        }
                    });

                }

            }
        }catch (SQLException ex){
            logger.error(ex.getMessage(), ex);
        }
    }

    public static void onAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState, String phone) {
        switch (authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:

                TdApi.TdlibParameters parameters = new TdApi.TdlibParameters();
                parameters.databaseDirectory = "tdlib/" + phone;
                parameters.useMessageDatabase = true;
                parameters.useSecretChats = true;
                try {
                    parameters.apiId = Config.getIntValue("app.id");
                    parameters.apiHash = Config.getValue("app.hash");
                }catch (BotException ex){
                    logger.error(ex.getMessage(), ex);
                }
                parameters.systemLanguageCode = "en";
                parameters.deviceModel = "Desktop";
                parameters.systemVersion = "Unknown";
                parameters.applicationVersion = "1.0";
                parameters.enableStorageOptimizer = true;
                parameters.useTestDc = false;

                openSessions.get(phone).send(new TdApi.SetTdlibParameters(parameters), new AuthorizationRequestHandler());
                logger.info("set tdlib parameters for " + phone);
                break;
            case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
                openSessions.get(phone).send(new TdApi.CheckDatabaseEncryptionKey(), new AuthorizationRequestHandler());
                logger.info("check database encryption key for " + phone);
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR: {
                openSessions.get(phone).send(new TdApi.SetAuthenticationPhoneNumber(phone, false, false), new AuthorizationRequestHandler());
                logger.info("send phone for auth " + phone);
                break;
            }
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR: {

                logger.debug("waiting code to confirm auth for " + phone);
                Future<String> codeFuture = executor.submit(() -> waitCodeForPhone(phone));

                String code = null;
                try {
                    code = codeFuture.get(5, TimeUnit.MINUTES);
                    openSessions.get(phone).send(new TdApi.CheckAuthenticationCode(code, "", ""), new AuthorizationRequestHandler());
                    logger.info("send code " + code + " for auth " + phone);
                } catch (InterruptedException e) {
                    logger.error("cannot retrieve auth code for " + phone + ", waiting was interrupted");
                } catch (ExecutionException e) {
                    logger.error("cannot retrieve auth code for " + phone, e);
                } catch (TimeoutException e) {
                    codeFuture.cancel(true);
                    logger.error("Timeout. Cannot retrieve auth code for " + phone, e);
                }
                break;
            }
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR: {
                logger.debug("waiting password to confirm auth for " + phone);
                Future<String> passFuture = executor.submit(() -> waitPasswordForPhone(phone));
                String password = null;
                try {
                    password = passFuture.get(5, TimeUnit.MINUTES);
                    openSessions.get(phone).send(new TdApi.CheckAuthenticationPassword(password), new AuthorizationRequestHandler());
                    logger.info("send password *** for " + phone);
                } catch (InterruptedException e) {
                    logger.error("cannot retrieve password for " + phone + ", waiting was interrupted");
                } catch (ExecutionException e) {
                    logger.error("cannot retrieve password for " + phone, e);
                } catch (TimeoutException e) {
                    passFuture.cancel(true);
                    logger.error("Timeout. Cannot retrieve password for " + phone, e);
                }
                break;
            }
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                logger.info("Authorised " + phone);
                openSessions.get(phone).send(new TdApi.GetMe(), res -> {
                    int clientId = ((TdApi.User)res).id;
                    logger.debug("authorized client id = " + clientId);
                    botSessions.put(clientId, new Session(phone, State.AUTHORIZED));
                });

                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                logger.info("Logged out " + phone);
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                logger.info("Closing... " + phone);
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                logger.info("Closed " + phone);
                openSessions.remove(phone);
                try {
                    DbHelper.deleteSession(dataSource, phone);
                }catch (SQLException ex){
                    logger.error(ex.getMessage(), ex);
                }
                break;
            default:
                logger.warn("Unsupported authorization state: " + authorizationState);
        }
    }

    public static void onBotAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState) {
        switch (authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:

                TdApi.TdlibParameters parameters = new TdApi.TdlibParameters();
                parameters.databaseDirectory = "tdlib/BOT";
                parameters.useMessageDatabase = true;
                parameters.useSecretChats = true;
                try {
                    parameters.apiId = Config.getIntValue("app.id");
                    parameters.apiHash = Config.getValue("app.hash");
                }catch (BotException ex){
                    logger.error(ex.getMessage(), ex);
                }
                parameters.systemLanguageCode = "en";
                parameters.deviceModel = "Desktop";
                parameters.systemVersion = "Unknown";
                parameters.applicationVersion = "1.0";
                parameters.enableStorageOptimizer = true;
                parameters.useTestDc = false;

                bot.send(new TdApi.SetTdlibParameters(parameters), new AuthorizationRequestHandler());
                logger.info("set tdlib parameters for BOT");
                break;
            case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
                bot.send(new TdApi.CheckDatabaseEncryptionKey(), new AuthorizationRequestHandler());
                logger.info("check database encryption key for BOT");
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR: {
                bot.send(new TdApi.CheckAuthenticationBotToken(BOT_ID + ":" + BOT_KEY),  new AuthorizationRequestHandler());
                logger.info("send phone for auth BOT");
                break;
            }

            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                logger.info("Authorised BOT");
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                logger.info("Logged out BOT");
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                logger.info("Closing... BOT");
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                logger.info("Closed BOT");
                break;
            default:
                logger.warn("Unsupported authorization state: " + authorizationState);
        }
    }

    // call this only with timeout!
    private static String waitPasswordForPhone(String phone) throws InterruptedException {
        while (true) {
            try {
                String password = DbHelper.getPassword(dataSource, phone);
                if (password != null) {
                    return password;
                }
            }catch (SQLException ex){
                logger.error(ex.getMessage(), ex);
            }

            Thread.sleep(1000);
        }
    }

    // call this only with timeout!
    private static String waitCodeForPhone(String phone) throws InterruptedException {
        while (true) {
            try {
                String code = DbHelper.getAuthCode(dataSource, phone);
                if (code != null) {
                    DbHelper.deleteAuthCode(dataSource, phone);
                    return code;
                }
            }catch (SQLException ex){
                logger.error(ex.getMessage(), ex);
            }
            Thread.sleep(1000);
        }
    }

    public static void onNewBotMessage(TdApi.UpdateNewMessage message) {
        logger.debug("new message arrived for BOT");

        if(message.message.senderUserId != BOT_ID) {
            replyToUser(message);
        }else{
            logger.debug("this is my message, skip");
        }
    }

    private static void replyToUser(TdApi.UpdateNewMessage userMessage) {

        switch(userMessage.message.content.getConstructor()){
            case TdApi.MessageText.CONSTRUCTOR:
                TdApi.MessageText mess = (TdApi.MessageText) userMessage.message.content;


                if(!isBotCommand(mess.text.entities)){
                    logger.debug("not bot command, ignore this");
                    break;
                }
                String command = getBotCommand(mess.text.text, mess.text.entities);
                logger.debug("command: '" + command + "'");
                if("/start".equals(command)){
                    TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Please enter /login XXXX where XXX your phone number", null), true, true);
                    bot.send(new TdApi.SendMessage(userMessage.message.chatId, 0, false, false, null, text), object -> {
                        logger.debug("sent " + object.toString());
                    });
                    botSessions.put(userMessage.message.senderUserId, new Session(null, State.CONFIRM_AUTH));

                }
                if("/login".equals(command)){
                    logger.debug("command: " + mess.text);
                    String phone = getParam(mess.text.text);
                    if(phone == null){
                        TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Please enter /login XXXX where XXX your phone number", null), true, true);
                        bot.send(new TdApi.SendMessage(userMessage.message.chatId, 0, false, false, null, text), object -> {
                            logger.debug("sent " + object.toString());

                        });
                    }else{
                        botSessions.put(userMessage.message.senderUserId, new Session(phone, State.CONFIRM_AUTH));
                        createClient(phone);
                    }
                }
                if("/auth".equals(command)){
                    logger.debug("command: " + mess.text);
                    Session session = botSessions.get(userMessage.message.senderUserId);
                    String code = getParam(mess.text.text);
                    if(code == null) {
                        TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Please enter /auth XXXX where XXX auth code", null), true, true);
                        bot.send(new TdApi.SendMessage(userMessage.message.chatId, 0, false, false, null, text), object -> {
                            logger.debug("sent " + object.toString());

                        });
                    }else {
                        if (session != null) {
                            try {
                                DbHelper.setAuthCode(dataSource, session.getPhone(), code.substring(0, 5));
                            } catch (SQLException e) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }
                }
                break;
            default:
                logger.debug("new message class :" + userMessage.message.content.getClass().getSimpleName());
                break;
        }

    }

    private static String getParam(String command) {
        String[] split = command.split(" ");
        if(split.length == 2){
            return split[1];
        }
        return null;
    }

    private static boolean isBotCommand(TdApi.TextEntity[] entities) {
        if(entities != null){
            for(TdApi.TextEntity ent : entities){
                if(ent.type instanceof TdApi.TextEntityTypeBotCommand){
                    return true;
                }
            }
        }
        return false;
    }
    private static String getBotCommand(String text, TdApi.TextEntity[] entities) {
        if(entities != null){
            for(TdApi.TextEntity ent : entities){
                if(ent.type instanceof TdApi.TextEntityTypeBotCommand){
                    return text.substring( ent.offset, ent.offset + ent.length);
                }
            }
        }
        return "";
    }
}

