package ru.telebot;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.telebot.dao.DbHelper;
import ru.telebot.domain.Chat;
import ru.telebot.domain.ExpiryEntity;
import ru.telebot.domain.Session;
import ru.telebot.domain.State;
import ru.telebot.handlers.AuthorizationRequestHandler;
import ru.telebot.handlers.BotUpdatesHandler;
import ru.telebot.handlers.UpdatesHandler;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

public class Bot implements Runnable, AutoCloseable {
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
    private static final ConcurrentMap<String, ExpiryEntity> codeStorage = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ExpiryEntity> passwordStorage = new ConcurrentHashMap<>();

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
            BOT_OWNER = Config.getValue("bot.owner");
            PROXY_ENABLED = Boolean.parseBoolean(Config.getValue("proxy.enabled"));
            PROXY_HOST = Config.getValue("proxy.host");
            PROXY_PORT = Config.getIntValue("proxy.port");
            PROXY_USER = Config.getValue("proxy.user");
            PROXY_PASS = Config.getValue("proxy.password");
            logger.debug("testing connection...");
            DbHelper.testConnection(dataSource);
            logger.debug("connection is OK");

            logger.debug("init completed");

            List<Session> sessions = DbHelper.getSessions(dataSource);
            logger.debug("sessions count : " + sessions.size());
            sessions.forEach(s -> logger.debug(s.toString()));

            List<Chat> destinations = DbHelper.getPossibleDestinations(dataSource);
            logger.debug("destination count: " + destinations.size());
            destinations.forEach(dest ->  logger.debug(dest.toString()));

            List<Chat> links = DbHelper.getAllLinks(dataSource);
            logger.debug("links count: " + links.size());
            links.forEach(link -> logger.debug(link.toString()));


        } catch (HikariPool.PoolInitializationException | SQLException ex) {
            logger.error(ex.getMessage(), ex);
            logger.debug("init failed");
            throw new BotException(ex);
        }
    }

    @Override
    public void run() {
        if (bot != null) {
            logger.error("cannot run more than one bot");
            return;
        }
        //worker = createClient(Config.getValue("bot.owner"));
        createBot();

        try {
            List<Session> sessions = DbHelper.getSessions(dataSource);
            sessions.forEach(s -> createClient(s.getPhone()));
        } catch (SQLException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        while (true) {
            try {
                Thread.sleep(500);
                final LocalDateTime now = LocalDateTime.now();
                codeStorage.values().removeIf(ent -> ent.getExpireTime().isBefore(now));
                passwordStorage.values().removeIf(ent -> ent.getExpireTime().isBefore(now));
            } catch (InterruptedException ex) {
                break;
            }
        }

    }

    private static Client createClient(String phone) {
        Client old = openSessions.get(phone);
        if(old != null){
            old.close();
        }
        UpdatesHandler handler = new UpdatesHandler(phone);
        Client client = Client.create(handler, e -> logger.error(e.getMessage(), e), e -> logger.error(e.getMessage(), e));
        openSessions.put(phone, client);
        if(BOT_OWNER.equals(phone)){
            worker = client;
        }
        if (PROXY_ENABLED) {

            client.send(new TdApi.AddProxy(PROXY_HOST, PROXY_PORT, true, new TdApi.ProxyTypeSocks5(PROXY_USER, PROXY_PASS)),
                    object -> logger.debug("execution completed " + object));
        }
        return client;
    }

    private static void createBot() {
        if(bot != null){
            bot.close();
        }
        BotUpdatesHandler handler = new BotUpdatesHandler();
        bot = Client.create(handler, e -> logger.error(e.getMessage(), e), e -> logger.error(e.getMessage(), e));
        if (PROXY_ENABLED) {

            bot.send(new TdApi.AddProxy(PROXY_HOST, PROXY_PORT, true, new TdApi.ProxyTypeSocks5(PROXY_USER, PROXY_PASS)),
                    object -> logger.debug("execution completed " + object));
        }
    }

    public static void onNewMessage(TdApi.UpdateNewMessage message, String phone) {
        logger.debug("new message arrived for " + phone);
        if (worker == null) {
            logger.error("worker not started, message ignored");
            return;
        }
        try {
            List<Chat> chats = DbHelper.getChatsToForward(dataSource, phone, message.message.chatId);
            for (Chat chat : chats) {
                if (!DbHelper.messageWasForwarderToChannel(dataSource, message.message.id, chat.getChatIdTo())) {
                    logger.debug("message type: " + message.message.content.getClass().getSimpleName());
                    TdApi.InputMessageContent inputMessageContent ;
                    if(message.message.content instanceof TdApi.MessageText) {
                        TdApi.MessageText messageText = (TdApi.MessageText) message.message.content;
                        String text = chat.getName().substring(0, chat.getName().indexOf("->")) + "\n";
                        TdApi.TextEntity[] entity = shiftEntity(messageText.text.entities, text.length());
                        TdApi.FormattedText formattedText = new TdApi.FormattedText(text + messageText.text.text, entity);
                        inputMessageContent = new TdApi.InputMessageText(formattedText, false, true);
                    } else if(message.message.content instanceof TdApi.MessagePhoto){
                        TdApi.MessagePhoto messagePhoto = (TdApi.MessagePhoto) message.message.content;
                        String text = "Forwarded from " + chat.getName().substring(0, chat.getName().indexOf("->")) + " : " + message.message.date +" \n";
                        TdApi.TextEntity[] entity = shiftEntity(messagePhoto.caption.entities, text.length());
                        TdApi.FormattedText formattedText = new TdApi.FormattedText(text + messagePhoto.caption.text, entity);
                        inputMessageContent = new TdApi.InputMessagePhoto(new TdApi.InputFileRemote(messagePhoto.photo.sizes[0].photo.remote.id), null, null, 0, 0, formattedText, 0);
                    }else{
                        inputMessageContent = new TdApi.InputMessageForwarded(message.message.chatId, message.message.id, false);
                    }
                    worker.send(new TdApi.SendMessage(chat.getChatIdTo(), 0, false, true, null, inputMessageContent), object -> {
                        logger.debug("message " + message.message.id + " from chat " + message.message.chatId + " was forwarded to chat " + chat.getChatIdTo());
                        try {
                            DbHelper.addForwardedMessage(dataSource, message.message.id, chat.getChatIdTo());
                        } catch (SQLException ex) {
                            logger.error(ex.getMessage(), ex);
                        }
                    });

                }

            }
        } catch (SQLException ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    private static TdApi.TextEntity[] shiftEntity(TdApi.TextEntity[] entities, int length) {
        for (int i = 0; i < entities.length; i++){
            entities[i].offset += length;
        }
        return entities;
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
                } catch (BotException ex) {
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
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR: { // сюда заходим если требуется авторизация по коду
                // запрашиваем у пользоветеля код

                Session session = null;
                try {
                    session = DbHelper.getSessionByPhone(dataSource, phone);
                    if(session != null) {
                        session.setAuthState(State.CONFIRM_AUTH);
                        session.setCurrentAction("auth_code");
                        DbHelper.save(dataSource, session);
                    }

                }catch (SQLException ex){
                    logger.error(ex.getMessage(), ex);
                }
                if(session != null) {
                    replyToUser(session.getClientId(), "Please enter confirm code + any random character");
                }

                logger.debug("waiting code to confirm auth for " + phone);
                Future<String> codeFuture = executor.submit(() -> waitCodeForPhone(phone)); // ждем код 5 мин, он должен поступиьб через интерфейс бота
                String code = null;
                try {
                    code = codeFuture.get(2, TimeUnit.MINUTES);
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
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR: { // сюда заходим если требуется авторизация по паролю
                // запрашиваем у пользоветеля пароль
                Session session = null;
                try {
                    session = DbHelper.getSessionByPhone(dataSource, phone);
                    if(session != null) {
                        session.setAuthState(State.CONFIRM_AUTH);
                        session.setCurrentAction("auth_password");
                        DbHelper.save(dataSource, session);
                    }

                }catch (SQLException ex){
                    logger.error(ex.getMessage(), ex);
                }
                if(session != null) {
                    replyToUser(session.getClientId(), "Please enter password");
                }


                logger.debug("waiting password to confirm auth for " + phone);
                Future<String> passFuture = executor.submit(() -> waitPasswordForPhone(phone)); // ждем код 5 мин, он должен поступиьб через интерфейс бота
                String password = null;
                try {
                    password = passFuture.get(2, TimeUnit.MINUTES);
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
                    int clientId = ((TdApi.User) res).id;
                    logger.debug("authorized client id = " + clientId);
                    try {
                        Session session = DbHelper.getSessionByClientId(dataSource, clientId);
                        session.setPhone(phone);
                        session.setAuthState(State.AUTHORIZED);
                        session.setFirstParam("");
                        session.setCurrentAction("");
                        DbHelper.save(dataSource, session);
                    } catch (SQLException ex) {
                        logger.info("Cannot save session for " + phone, ex);
                    }
                });

                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                logger.info("Logging out... " + phone);
                Client client = openSessions.remove(phone);
                if(client != null){
                    client.close();
                }
                try {
                    DbHelper.deleteSession(dataSource, phone);
                } catch (SQLException ex) {
                    logger.error(ex.getMessage(), ex);
                }

                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                logger.info("Closing... " + phone);
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                logger.info("Closed " + phone);
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
                } catch (BotException ex) {
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
                bot.send(new TdApi.CheckAuthenticationBotToken(BOT_ID + ":" + BOT_KEY), new AuthorizationRequestHandler());
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
            if (passwordStorage.containsKey(phone)) {
                String pass =  passwordStorage.get(phone).getValue();
                passwordStorage.remove(phone);
                return pass;
            }
            Thread.sleep(1000);
        }
    }

    // call this only with timeout!
    private static String waitCodeForPhone(String phone) throws InterruptedException {
        while (true) {
            if (codeStorage.containsKey(phone)) {
                String code = codeStorage.get(phone).getValue();
                codeStorage.remove(phone);
                return code;
            }
            Thread.sleep(1000);
        }
    }

    public static void onNewBotMessage(TdApi.UpdateNewMessage message) {
        logger.debug("new message arrived for BOT");

        if (message.message.senderUserId != BOT_ID) {
            try {
                replyToUser(message);
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
            }
        } else {
            logger.debug("this is BOT message, skip");
        }
    }

    private static void replyToUser(TdApi.UpdateNewMessage userMessage) throws SQLException {

        Session session = DbHelper.getSessionByClientId(dataSource, userMessage.message.senderUserId);
        switch (userMessage.message.content.getConstructor()) {
            case TdApi.MessageText.CONSTRUCTOR:
                TdApi.MessageText mess = (TdApi.MessageText) userMessage.message.content;

                String command = getBotCommand(mess.text.text, mess.text.entities);
                logger.debug("command: '" + command + "'");
                logger.debug("message:" + userMessage);
                if ("/start".equals(command)) {
                    session.setAuthState(State.LOGIN);
                    session.setCurrentAction("");
                    session.setPhone("");
                    DbHelper.save(dataSource, session);

                } else if ("/login".equals(command)) {
                    session.setAuthState(State.LOGIN);
                    session.setCurrentAction("login");
                    DbHelper.save(dataSource, session);
                    replyToUser(userMessage.message.chatId, "Please enter phone number");

                } else if ("/list".equals(command)) {

                    if (session.getAuthState() == State.AUTHORIZED) {

                        List<Chat> ownChats = DbHelper.getOwnChats(dataSource, session.getPhone());
                        if(ownChats.isEmpty()){
                            replyToUser(userMessage.message.chatId, "No chats, use /create");
                        }else {
                            StringBuilder sb = new StringBuilder();
                            ownChats.forEach(chat -> sb.append(chat).append('\n'));
                            replyToUser(userMessage.message.chatId, sb.toString());
                        }

                    } else {
                        replyToUser(userMessage.message.chatId, "Not authorized");
                    }

                } else if ("/create".equals(command)) {

                    if (session.getAuthState() == State.AUTHORIZED) {
                        replyToUser(userMessage.message.chatId, "Forward message from source channel");
                        session.setCurrentAction("create_source");
                        DbHelper.save(dataSource, session);
                    } else {
                        replyToUser(userMessage.message.chatId, "Not authorized");
                    }

                } else if ("/delete".equals(command)) {

                    if (session.getAuthState() == State.AUTHORIZED) {
                        replyToUser(userMessage.message.chatId, "Select what to delete (id from space id to)");

                        List<Chat> ownChats = DbHelper.getOwnChats(dataSource, session.getPhone());
                        StringBuilder sb = new StringBuilder();
                        ownChats.forEach(chat -> sb.append(chat).append('\n'));
                        replyToUser(userMessage.message.chatId, sb.toString());

                        session.setCurrentAction("delete_source");
                        DbHelper.save(dataSource, session);
                    } else {
                        replyToUser(userMessage.message.chatId, "Not authorized");
                    }
                } else if ("/create_destination".equals(command)) {

                    if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
                        replyToUser(userMessage.message.chatId, "Forward message from source channel");
                        session.setCurrentAction("create_destination_source");
                        DbHelper.save(dataSource, session);

                    } else {
                        replyToUser(userMessage.message.chatId, "Not authorized");
                    }
                } else if ("/delete_destination".equals(command) && BOT_OWNER.equals(session.getPhone())) {

                    if (session.getAuthState() == State.AUTHORIZED) {
                        replyToUser(userMessage.message.chatId, "Select what to delete");

                        List<Chat> destinations = DbHelper.getPossibleDestinations(dataSource);
                        StringBuilder sb = new StringBuilder();
                        destinations.forEach(chat -> sb.append(chat).append('\n'));
                        replyToUser(userMessage.message.chatId, sb.toString());

                        session.setCurrentAction("delete_destination_source");
                        DbHelper.save(dataSource, session);

                    } else {
                        replyToUser(userMessage.message.chatId, "Not authorized");
                    }
                } else if ("/list_destination".equals(command) && BOT_OWNER.equals(session.getPhone())) {

                    if (session.getAuthState() == State.AUTHORIZED) {

                        List<Chat> destinations = DbHelper.getPossibleDestinations(dataSource);
                        if (destinations.isEmpty()) {
                            replyToUser(userMessage.message.chatId, "No destinations, use /create_destination");
                        } else {
                            StringBuilder sb = new StringBuilder();
                            destinations.forEach(chat -> sb.append(chat).append('\n'));
                            replyToUser(userMessage.message.chatId, sb.toString());
                        }

                    } else {
                        replyToUser(userMessage.message.chatId, "Not authorized");
                    }
                } else if ("/list_user".equals(command) && BOT_OWNER.equals(session.getPhone())) {
                    if (session.getAuthState() == State.AUTHORIZED) {
                        List<String> users = DbHelper.getUsers(dataSource);
                        if(users.isEmpty()){
                            replyToUser(userMessage.message.chatId, "No bot users, use /create_user");
                        }else {
                            StringBuilder sb = new StringBuilder();
                            users.forEach(user -> sb.append(user).append('\n'));
                            replyToUser(userMessage.message.chatId, sb.toString());
                        }
                    }

                } else if ("/create_user".equals(command) && BOT_OWNER.equals(session.getPhone())) {
                    if (session.getAuthState() == State.AUTHORIZED) {
                        replyToUser(userMessage.message.chatId, "Input user phone to create");
                        session.setCurrentAction("create_user");
                        DbHelper.save(dataSource, session);
                    }

                } else if ("/delete_user".equals(command) && BOT_OWNER.equals(session.getPhone())) {
                    if (session.getAuthState() == State.AUTHORIZED) {
                        replyToUser(userMessage.message.chatId, "Input user phone to delete");
                        session.setCurrentAction("delete_user");
                        DbHelper.save(dataSource, session);
                    }

                } else { // handle text

                    if ("login".equals(session.getCurrentAction())) {

                        logger.debug("handle login phone " + mess.text.text);
                        if(DbHelper.isPhoneAllowed(dataSource, mess.text.text)) {
                            session.setPhone(mess.text.text);
                            session.setAuthState(State.CONFIRM_AUTH);
                            session.setCurrentAction("");
                            DbHelper.save(dataSource, session);
                            createClient(mess.text.text);
                        }else{
                            logger.debug("phone not allowed");
                        }
                    } else if ("auth_code".equals(session.getCurrentAction())) {
                        logger.debug("handle " + session.getCurrentAction());
                        codeStorage.put(session.getPhone(), new ExpiryEntity(mess.text.text.substring(0, 5), LocalDateTime.now().plusMinutes(5)));
                    } else if ("auth_password".equals(session.getCurrentAction())) {
                        passwordStorage.put(session.getPhone(), new ExpiryEntity(mess.text.text, LocalDateTime.now().plusMinutes(5)));
                        logger.debug("handle " + session.getCurrentAction());
                    } else if ("create_source".equals(session.getCurrentAction())) {
                        logger.debug("handle " + session.getCurrentAction());
                        if (userMessage.message.forwardInfo != null && userMessage.message.forwardInfo.origin instanceof TdApi.MessageForwardOriginChannel) {

                            final TdApi.MessageForwardOriginChannel channel = (TdApi.MessageForwardOriginChannel)userMessage.message.forwardInfo.origin;
                            openSessions.get(session.getPhone()).send(new TdApi.GetChat(channel.chatId), result -> {
                                TdApi.Chat chat = (TdApi.Chat) result;
                                try {
                                    session.setFirstParam(String.valueOf(channel.chatId) + "_" + chat.title);
                                    session.setCurrentAction("create_destination");
                                    DbHelper.save(dataSource, session);

                                    replyToUser(userMessage.message.chatId, "select destination channel");
                                    List<Chat> destinations = DbHelper.getPossibleDestinations(dataSource);

                                    StringBuilder sb = new StringBuilder();
                                    destinations.forEach(dest -> { dest.setOwner(""); sb.append(dest).append('\n'); });
                                    replyToUser(userMessage.message.chatId, sb.toString());
                                } catch (SQLException e) {
                                    logger.error(e.getMessage(), e);
                                }
                            });
                        }else{
                            replyToUser(userMessage.message.chatId, "forward message from channel");
                        }
                    } else if ("create_destination".equals(session.getCurrentAction())) {
                        logger.debug("handle " + session.getCurrentAction());
                        try {
                            Long source = Long.parseLong(session.getFirstParam().substring(0, session.getFirstParam().indexOf('_')));
                            String sourceTitle = session.getFirstParam().substring(session.getFirstParam().indexOf('_') + 1);
                            Long destination = Long.parseLong(mess.text.text);
                            Chat dest = DbHelper.getDestination(dataSource, destination);
                            if (dest != null) {

                                session.setCurrentAction("");
                                session.setFirstParam("");
                                DbHelper.save(dataSource, session);

                                DbHelper.createLink(dataSource, session.getPhone(), source, sourceTitle, dest);
                                replyToUser(userMessage.message.chatId, "New link created");
                            }
                        }catch (NumberFormatException ex){
                            replyToUser(userMessage.message.chatId, "Input destination chatId");
                        }
                    } else if ("delete_source".equals(session.getCurrentAction())) {
                        try {
                            Long sourceId = Long.parseLong(mess.text.text.substring(0, mess.text.text.indexOf(' ')));
                            Long destinationId = Long.parseLong(mess.text.text.substring(mess.text.text.indexOf(' ') + 1));
                            int rowsCount = DbHelper.deleteLink(dataSource, session.getPhone(), sourceId, destinationId);
                            if(rowsCount == 1){
                                replyToUser(userMessage.message.chatId, "Link deleted");
                            }else{
                                replyToUser(userMessage.message.chatId, "Link not found");
                            }
                        }catch (NumberFormatException ex){
                            replyToUser(userMessage.message.chatId, "Input from_id space to_id");
                        }

                    } else if ("create_destination_source".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        logger.debug("handle " + session.getCurrentAction());
                        if (userMessage.message.forwardInfo != null && userMessage.message.forwardInfo.origin instanceof TdApi.MessageForwardOriginChannel) {
                            session.setCurrentAction("");
                            DbHelper.save(dataSource, session);

                            final TdApi.MessageForwardOriginChannel channel = (TdApi.MessageForwardOriginChannel)userMessage.message.forwardInfo.origin;

                            openSessions.get(session.getPhone()).send(new TdApi.GetChat(channel.chatId), result -> {
                                TdApi.Chat chat = (TdApi.Chat) result;
                                try {
                                    DbHelper.createPossibleDestination(dataSource, channel.chatId, chat.title);
                                    replyToUser(userMessage.message.chatId, "New destination created");
                                } catch (SQLException e) {
                                    logger.error(e.getMessage(), e);
                                }

                            });

                        }else{
                            replyToUser(userMessage.message.chatId, "forward message from channel");
                        }
                    } else if ("delete_destination_source".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        logger.debug("handle " + session.getCurrentAction());
                        try {
                            Long destination = Long.parseLong(mess.text.text);
                            session.setCurrentAction("");
                            DbHelper.save(dataSource, session);
                            int rows = DbHelper.deleteDestination(dataSource, destination);
                            if(rows == 1) {
                                replyToUser(userMessage.message.chatId, "Deleted");
                            }else{
                                replyToUser(userMessage.message.chatId, "Not found");
                            }
                        }catch (NumberFormatException ex){
                            replyToUser(userMessage.message.chatId, "Input destination chatId to delete");
                        }
                    } else if ("create_user".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {

                        session.setCurrentAction("");
                        DbHelper.save(dataSource, session);
                        try {
                            DbHelper.createUser(dataSource, mess.text.text);
                            replyToUser(userMessage.message.chatId, "User created");
                        }catch (SQLException ex){
                            replyToUser(userMessage.message.chatId, "User not created");
                        }

                    } else if ("delete_user".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        session.setCurrentAction("");
                        DbHelper.save(dataSource, session);
                        int rows = DbHelper.deleteUser(dataSource, mess.text.text);
                        if(rows == 1) {
                            replyToUser(userMessage.message.chatId, "Deleted");
                        }else{
                            replyToUser(userMessage.message.chatId, "Not found");
                        }

                    } else {
                        logger.debug("unrecognized command " + mess.text.text);
                        replyToUser(userMessage.message.chatId, "Use /list /create or /delete command");
                    }

                }

                break;
            default:
                logger.debug("new message class :" + userMessage.message.content.getClass().getSimpleName());
                break;
        }

    }

    private static void replyToUser(long chatId, String textStr) {
        TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText(textStr, null), true, true);
        bot.send(new TdApi.SendMessage(chatId, 0, false, false, null, text), object -> {
            logger.debug("sent " + object.toString());

        });
    }

    private static String getBotCommand(String text, TdApi.TextEntity[] entities) {
        if (entities != null) {
            for (TdApi.TextEntity ent : entities) {
                if (ent.type instanceof TdApi.TextEntityTypeBotCommand) {
                    return text.substring(ent.offset, ent.offset + ent.length);
                }
            }
        }
        return "";
    }

    @Override
    public void close() throws Exception {
        openSessions.values().forEach(Client::close);
    }
}

