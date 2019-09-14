package ru.telebot;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.telebot.dao.DbHelper;
import ru.telebot.domain.*;
import ru.telebot.domain.Session;
import ru.telebot.handlers.AuthorizationRequestHandler;
import ru.telebot.handlers.BotUpdatesHandler;
import ru.telebot.handlers.QueueHandler;
import ru.telebot.handlers.UpdatesHandler;

import javax.jms.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static String[] WORKERS;

    public static int SENDING_DELAY;

    private static AtomicInteger currentWorkerIndex = new AtomicInteger(0);

    private static HikariDataSource dataSource = null;
    private static final ConcurrentMap<String, Client> openSessions = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ExpiryEntity> codeStorage = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ExpiryEntity> passwordStorage = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Long, ExpiryEntity> channelNameStorage = new ConcurrentHashMap<>();


    private static Client worker = null;
    private static Client bot = null;
    private static javax.jms.Session jmsSession;
    private static MessageProducer producer;

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
            SENDING_DELAY = Config.getIntValueOrDefault("sending.delay", 1000);
            WORKERS = Config.getValue("bot.workers").split(";");
            logger.debug("testing connection...");
            DbHelper.testConnection(dataSource);
            logger.debug("connection is OK");

            logger.debug("init completed");

            List<Session> sessions = DbHelper.getSessions(dataSource);
            logger.debug("sessions count : " + sessions.size());
            sessions.forEach(s -> logger.debug(s.toString()));

            List<Chat> destinations = DbHelper.getAllPossibleDestinations(dataSource);
            logger.debug("destination count: " + destinations.size());
            destinations.forEach(dest -> logger.debug(dest.toString()));

            List<Chat> links = DbHelper.getAllLinks(dataSource);
            logger.debug("links count: " + links.size());
            links.forEach(link -> logger.debug(link.toString()));

            ConnectionFactory connectionFactory = new ActiveMQConnectionFactory("tcp://127.0.0.1:61616");
            Connection jmsConnection = connectionFactory.createConnection();
            jmsConnection.start();
            jmsSession = jmsConnection.createSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE);
            Queue queue = jmsSession.createQueue("oneWindow");
            producer = jmsSession.createProducer(queue);
            MessageConsumer consumer = jmsSession.createConsumer(queue);
            consumer.setMessageListener(new QueueHandler());

        } catch (HikariPool.PoolInitializationException | SQLException | JMSException ex) {
            logger.error(ex.getMessage(), ex);
            logger.debug("init failed");
            throw new BotException(ex);
        }
    }

    public static boolean isReady() {
        return worker != null;
    }

    @Override
    public void run() {
        if (bot != null) {
            logger.error("cannot run more than one bot");
            return;
        }

        createBot();
        try {
            List<Session> sessions = DbHelper.getSessions(dataSource);
            sessions.stream().filter(s -> State.AUTHORIZED.equals(s.getAuthState())).forEach(s -> createClient(s.getPhone(), s.getClientId()));
        } catch (SQLException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        while (true) {
            try {
                Thread.sleep(10000);
                final LocalDateTime now = LocalDateTime.now();
                codeStorage.values().removeIf(ent -> ent.getExpireTime().isBefore(now));
                passwordStorage.values().removeIf(ent -> ent.getExpireTime().isBefore(now));
                channelNameStorage.values().removeIf(ent -> ent.getExpireTime().isBefore(now));
            } catch (InterruptedException ex) {
                break;
            }
        }

    }

    private static Client createClient(String phone, long clientId) {
        Client old = openSessions.get(phone);
        if (old != null) {
            old.close();
        }
        UpdatesHandler handler = new UpdatesHandler(phone, clientId);
        Client client = Client.create(handler, e -> logger.error(e.getMessage(), e), e -> logger.error(e.getMessage(), e));
        openSessions.put(phone, client);
        if (BOT_OWNER.equals(phone)) {
            worker = client;
        }
        if (PROXY_ENABLED) {

            client.send(new TdApi.AddProxy(PROXY_HOST, PROXY_PORT, true, new TdApi.ProxyTypeSocks5(PROXY_USER, PROXY_PASS)),
                    object -> logger.debug("execution completed " + object));
        }
        return client;
    }

    private static void createBot() {
        if (bot != null) {
            bot.close();
        }
        BotUpdatesHandler handler = new BotUpdatesHandler();
        bot = Client.create(handler, e -> logger.error(e.getMessage(), e), e -> logger.error(e.getMessage(), e));
        if (PROXY_ENABLED) {

            bot.send(new TdApi.AddProxy(PROXY_HOST, PROXY_PORT, true, new TdApi.ProxyTypeSocks5(PROXY_USER, PROXY_PASS)),
                    object -> logger.debug("execution completed " + object));
        }
    }

    private static TdApi.InputMessageContent createNewMessage(TdApi.UpdateNewMessage message, String channelName, int date) {
        TdApi.InputMessageContent inputMessageContent = null;
        if (message.message.content instanceof TdApi.MessageText) {
            TdApi.MessageText messageText = (TdApi.MessageText) message.message.content;
            String text = channelName + " : " + date + " \n";
            TdApi.TextEntity[] entity = shiftEntity(messageText.text.entities, text.length());
            TdApi.FormattedText formattedText = new TdApi.FormattedText(text + messageText.text.text, entity);
            inputMessageContent = new TdApi.InputMessageText(formattedText, false, true);
        } else if (message.message.content instanceof TdApi.MessagePhoto) {
            TdApi.MessagePhoto messagePhoto = (TdApi.MessagePhoto) message.message.content;
            String text = channelName + " : " + date + " \n";
            TdApi.TextEntity[] entity = shiftEntity(messagePhoto.caption.entities, text.length());
            TdApi.FormattedText formattedText = new TdApi.FormattedText(text + messagePhoto.caption.text, entity);
            inputMessageContent = new TdApi.InputMessagePhoto(new TdApi.InputFileRemote(messagePhoto.photo.sizes[0].photo.remote.id), null, null, 0, 0, formattedText, 0);
        } else if (message.message.content instanceof TdApi.MessageAnimation) {
            TdApi.MessageAnimation messageAnimation = (TdApi.MessageAnimation) message.message.content;
            String text = channelName + " : " + date + " \n";
            TdApi.TextEntity[] entity = shiftEntity(messageAnimation.caption.entities, text.length());
            TdApi.FormattedText formattedText = new TdApi.FormattedText(text + messageAnimation.caption.text, entity);
            inputMessageContent = new TdApi.InputMessageAnimation(new TdApi.InputFileRemote(messageAnimation.animation.animation.remote.id), null, messageAnimation.animation.duration, messageAnimation.animation.width, messageAnimation.animation.height, formattedText);
        } else if (message.message.content instanceof TdApi.MessageVideo) {
            TdApi.MessageVideo messageVideo = (TdApi.MessageVideo) message.message.content;
            String text = channelName + " : " + date + " \n";
            TdApi.TextEntity[] entity = shiftEntity(messageVideo.caption.entities, text.length());
            TdApi.FormattedText formattedText = new TdApi.FormattedText(text + messageVideo.caption.text, entity);
            inputMessageContent = new TdApi.InputMessageVideo(new TdApi.InputFileRemote(messageVideo.video.video.remote.id), null, null, messageVideo.video.duration, messageVideo.video.width, messageVideo.video.height, messageVideo.video.supportsStreaming, formattedText, 0);
        } else if (message.message.content instanceof TdApi.MessageDocument) {
            TdApi.MessageDocument messageDocument = (TdApi.MessageDocument) message.message.content;
            String text = channelName + " : " + date + " \n";
            TdApi.TextEntity[] entity = shiftEntity(messageDocument.caption.entities, text.length());
            TdApi.FormattedText formattedText = new TdApi.FormattedText(text + messageDocument.caption.text, entity);
            inputMessageContent = new TdApi.InputMessageDocument(new TdApi.InputFileRemote(messageDocument.document.document.remote.id), null, formattedText);

        } else {
            logger.debug("unsupported type for forward: " + message.message.content.getClass().getSimpleName());
        }
        return inputMessageContent;
    }

    public static void onNewMessage(TdApi.UpdateNewMessage message, String phone) {
        logger.debug("new message arrived for " + phone);
        if (!isReady()) {
            logger.error("worker not started, message ignored");
            return;
        }
        try {
            List<Chat> chats = DbHelper.getChatsToForward(dataSource, phone, message.message.chatId);
            for (Chat chat : chats) {
                if (!DbHelper.messageWasForwarderToChannel(dataSource, message.message.id, chat.getChatIdTo())) {

                    logger.debug("message type: " + message.message.content.getClass().getSimpleName());
                    final int date = message.message.forwardInfo != null ? message.message.forwardInfo.date : message.message.date;
                    Client.ResultHandler handler = (object) -> {
                        if (object.getConstructor() == TdApi.Chat.CONSTRUCTOR) {
                            TdApi.Chat chatObject = (TdApi.Chat) object;
                            logger.debug("chat title is " + chatObject.title);
                            if (chatObject.id != 0) {
                                channelNameStorage.put(chatObject.id, new ExpiryEntity(chatObject.title, LocalDateTime.now().plusDays(1)));
                            }
                            addMessageToQueue(chat.getChatIdTo(), message, chatObject.title, date);

                        } else if (object.getConstructor() == TdApi.User.CONSTRUCTOR) {

                            TdApi.User chatUser = (TdApi.User) object;
                            String userName = getFormattedName(chatUser);
                            logger.debug("chat user is " + userName);
                            if (chatUser.id != 0) {
                                channelNameStorage.put((long) chatUser.id, new ExpiryEntity(userName, LocalDateTime.now().plusDays(1)));
                            }

                            addMessageToQueue(chat.getChatIdTo(), message, userName, date);

                        } else {
                            logger.debug(object.toString());
                        }

                    };
                    if (message.message.forwardInfo != null) {
                        if (message.message.forwardInfo.origin.getConstructor() == TdApi.MessageForwardOriginChannel.CONSTRUCTOR) {
                            long chatId = ((TdApi.MessageForwardOriginChannel) message.message.forwardInfo.origin).chatId;
                            ExpiryEntity chatName = channelNameStorage.get(chatId);
                            if (chatName != null) {
                                logger.debug("get chat name " + chatName.getValue() + " from cache");
                                TdApi.Chat knownChat = new TdApi.Chat();
                                knownChat.title = chatName.getValue();
                                handler.onResult(knownChat);
                            } else {
                                logger.debug("trying to get channel header " + chatId);
                                openSessions.get(phone).send(new TdApi.GetChat(chatId), handler);
                            }
                        } else if (message.message.forwardInfo.origin.getConstructor() == TdApi.MessageForwardOriginUser.CONSTRUCTOR) {
                            int senderUserId = ((TdApi.MessageForwardOriginUser) message.message.forwardInfo.origin).senderUserId;
                            ExpiryEntity chatName = channelNameStorage.get((long) senderUserId);
                            if (chatName != null) {
                                logger.debug("get user name " + chatName.getValue() + " from cache");
                                TdApi.User knownUser = new TdApi.User();
                                knownUser.lastName = chatName.getValue();
                                handler.onResult(knownUser);
                            } else {
                                logger.debug("trying to get user chat header " + senderUserId);
                                openSessions.get(phone).send(new TdApi.GetUser(senderUserId), handler);
                            }
                        } else {
                            TdApi.Chat hiddenUser = new TdApi.Chat();
                            hiddenUser.title = "Hidden user";
                            handler.onResult(hiddenUser);
                        }
                    } else {
                        TdApi.Chat knownChat = new TdApi.Chat();
                        knownChat.title = chat.getName().substring(0, chat.getName().indexOf("->"));
                        handler.onResult(knownChat);
                    }
                }


            }
        } catch (SQLException ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    private static void addMessageToQueue(long chatIdTo, TdApi.UpdateNewMessage message, String title, int date) {
        try {
            ObjectMessage objectMessage = jmsSession.createObjectMessage(message);
            objectMessage.setLongProperty(QueueHandler.CHAT_ID_PROPERTY, chatIdTo);
            objectMessage.setStringProperty(QueueHandler.TITLE_PROPERTY, title);
            objectMessage.setIntProperty(QueueHandler.DATE_PROPERTY, date);
            producer.send(objectMessage);
            logger.debug("put message " + message.message.id + " to queue");
        } catch (JMSException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static void forwardMessage(long chatIdTo, TdApi.UpdateNewMessage message, String title, int date) throws SQLException {
        // here is potentially message can be forwarded twice.
        if (!DbHelper.messageWasForwarderToChannel(dataSource, message.message.id, chatIdTo)) {
            TdApi.InputMessageContent inputMessageContent = createNewMessage(message, title, date);
            Client robin = getNextClientForResend();
            if (robin == null) {
                robin = worker;
                logger.error("no client configured for round-robin, will use admin account");
            }
            robin.send(new TdApi.SendMessage(chatIdTo, 0, false, true, null, inputMessageContent), res -> {
                if (res.getConstructor() == TdApi.Error.CONSTRUCTOR) {
                    TdApi.Error error = (TdApi.Error) res;
                    logger.error(error.code + " : " + error.message);
                } else {
                    logger.debug("message " + message.message.id + " from chat " + message.message.chatId + " was forwarded to chat " + chatIdTo);
                    try {
                        DbHelper.addForwardedMessage(dataSource, message.message.id, chatIdTo);
                    } catch (SQLException ex) {
                        logger.error(ex.getMessage(), ex);
                    }
                }
            }, error -> {
                logger.error(error.getMessage(), error);
            });
        }
    }

    private static Client getNextClientForResend() {
        int count = 0;
        Client client = null;
        while (count++ < WORKERS.length) {
            String robin = WORKERS[currentWorkerIndex.getAndAdd(1)];
            if (currentWorkerIndex.get() >= WORKERS.length) {
                currentWorkerIndex.set(0);
            }
            client = openSessions.get(robin);
            if (client != null) {
                return client;
            }
        }
        return null;
    }

    private static String getFormattedName(TdApi.User user) {
        String username = user.username;
        if (username == null || username.isEmpty()) {
            username = (user.firstName != null ? user.firstName : "") + " " +
                    (user.lastName != null ? user.lastName : "");
        } else {
            username = '@' + username;
        }
        return username.trim();
    }

    private static TdApi.TextEntity[] shiftEntity(TdApi.TextEntity[] entities, int length) {
        for (int i = 0; i < entities.length; i++) {
            entities[i].offset += length;
        }
        return entities;
    }

    public static void onAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState, String phone, long clientId) {
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

                openSessions.get(phone).send(new TdApi.SetTdlibParameters(parameters), new AuthorizationRequestHandler(clientId));
                logger.info("set tdlib parameters for " + phone);
                break;
            case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
                openSessions.get(phone).send(new TdApi.CheckDatabaseEncryptionKey(), new AuthorizationRequestHandler(clientId));
                logger.info("check database encryption key for " + phone);
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR: {
                openSessions.get(phone).send(new TdApi.SetAuthenticationPhoneNumber(phone, false, false), new AuthorizationRequestHandler(clientId));
                logger.info("send phone for auth " + phone);
                break;
            }
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR: { // сюда заходим если требуется авторизация по коду
                // запрашиваем у пользоветеля код

                Session session = null;
                try {
                    session = DbHelper.getSessionByPhone(dataSource, phone);
                    if (session != null) {
                        session.setAuthState(State.CONFIRM_AUTH);
                        session.setCurrentAction("auth_code");
                        DbHelper.save(dataSource, session);
                    }

                } catch (SQLException ex) {
                    logger.error(ex.getMessage(), ex);
                }
                if (session != null) {
                    replyToUser(session.getClientId(), "Please enter confirm code + any random character, if you will not add random character to the end of the code, Telegram automatically will expire this auth code.");
                }

                logger.debug("waiting code to confirm auth for " + phone);
                executor.submit(() -> waitCodeForPhone(phone, clientId)); // ждем код 3 мин, он должен поступить через интерфейс бота
                break;
            }
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR: { // сюда заходим если требуется авторизация по паролю
                // запрашиваем у пользоветеля пароль
                Session session = null;
                try {
                    session = DbHelper.getSessionByPhone(dataSource, phone);
                    if (session != null) {
                        session.setAuthState(State.CONFIRM_AUTH);
                        session.setCurrentAction("auth_password");
                        DbHelper.save(dataSource, session);
                    }

                } catch (SQLException ex) {
                    logger.error(ex.getMessage(), ex);
                }
                if (session != null) {
                    replyToUser(session.getClientId(), "Please enter password");
                }


                logger.debug("waiting password to confirm auth for " + phone);
                executor.submit(() -> waitPasswordForPhone(phone, clientId)); // ждем пароль 3 мин, он должен поступить через интерфейс бота
                break;
            }
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                logger.info("Authorised " + phone);
                openSessions.get(phone).send(new TdApi.GetMe(), res -> {
                    TdApi.User user = (TdApi.User) res;
                    int authClientId = user.id;
                    logger.debug("authorized client id = " + authClientId);
                    try {
                        Session session = DbHelper.getSessionByClientId(dataSource, authClientId);
                        session.setPhone(phone);
                        session.setAuthState(State.AUTHORIZED);
                        session.setFirstParam("");
                        session.setCurrentAction("");
                        DbHelper.save(dataSource, session);
                        String username = getFormattedName(user);
                        DbHelper.updateUserName(dataSource, phone, username);
                    } catch (SQLException ex) {
                        logger.info("Cannot save session for " + phone, ex);
                    }
                });

                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                logger.info("Logging out... " + phone);
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                logger.info("Closing... " + phone);
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                logger.info("Closed " + phone);
                Client client = openSessions.remove(phone);
                if (client != null) {
                    client.close();
                }
                try {
                    Session session = DbHelper.getSessionByPhone(dataSource, phone);
                    if (session != null) {
                        session.setPhone(phone);
                        session.setAuthState(State.LOGIN);
                        session.setFirstParam("");
                        session.setCurrentAction("");
                        DbHelper.save(dataSource, session);
                    }
                } catch (SQLException ex) {
                    logger.error(ex.getMessage(), ex);
                }
                break;
            default:
                logger.warn("Unsupported authorization state: " + authorizationState);
        }

    }

    public static void onBotAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState) {
        long clientId = 0L; // no client for bot
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


                bot.send(new TdApi.SetTdlibParameters(parameters), new AuthorizationRequestHandler(clientId));
                logger.info("set tdlib parameters for BOT");
                break;
            case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
                bot.send(new TdApi.CheckDatabaseEncryptionKey(), new AuthorizationRequestHandler(clientId));
                logger.info("check database encryption key for BOT");
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR: {
                bot.send(new TdApi.CheckAuthenticationBotToken(BOT_ID + ":" + BOT_KEY), new AuthorizationRequestHandler(clientId));
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


    private static void waitPasswordForPhone(String phone, long clientId) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < (3 * 60 * 1000)) {
            if (passwordStorage.containsKey(phone)) {
                String pass = passwordStorage.get(phone).getValue();
                passwordStorage.remove(phone);
                openSessions.get(phone).send(new TdApi.CheckAuthenticationPassword(pass), new AuthorizationRequestHandler(clientId));
                logger.info("send password *** for " + phone);
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
                break;
            }
        }
        logger.debug("no password received in 3 minutes, give up");
    }


    private static void waitCodeForPhone(String phone, long clientId) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < (3 * 60 * 1000)) {
            if (codeStorage.containsKey(phone)) {
                String code = codeStorage.get(phone).getValue();
                codeStorage.remove(phone);
                openSessions.get(phone).send(new TdApi.CheckAuthenticationCode(code, "", ""), new AuthorizationRequestHandler(clientId));
                logger.info("send code " + code + " for auth " + phone);
                return;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
                break;
            }
        }
        logger.debug("no code received in 3 minutes, give up");
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
                    handleStartCommand(session);
                } else if ("/login".equals(command)) {
                    handleLoginCommand(session);
                } else if ("/list".equals(command)) {
                    handleListCommand(session);
                } else if ("/create".equals(command)) {
                    handleCreateCommand(session);
                } else if ("/delete".equals(command)) {
                    handleDeleteCommand(session);
                } else if ("/create_destination".equals(command)) {
                    handleCreateDestinationCommand(session);
                } else if ("/delete_destination".equals(command)) {
                    handleDeleteDestinationCommand(session);
                } else if ("/list_destination".equals(command)) {
                    handleListDestinationCommand(session);
                } else if ("/list_user".equals(command)) {
                    handleListUserCommand(session);
                } else if ("/create_user".equals(command)) {
                    handleCreateUserCommand(session);
                } else if ("/delete_user".equals(command)) {
                    handleDeleteUserCommand(session);
                } else if ("/list_permission".equals(command)) {
                    handleListPermissionCommand(session);
                } else if ("/create_permission".equals(command)) {
                    handleCreatePermissionCommand(session);
                } else if ("/delete_permission".equals(command)) {
                    handleDeletePermissionCommand(session);

                } else { // handle text

                    if ("login".equals(session.getCurrentAction())) {
                        handleLoginAction(session, mess.text.text);
                    } else if ("auth_code".equals(session.getCurrentAction())) {
                        logger.debug("handle " + session.getCurrentAction());
                        codeStorage.put(session.getPhone(), new ExpiryEntity(mess.text.text.substring(0, 5), LocalDateTime.now().plusMinutes(5)));
                    } else if ("auth_password".equals(session.getCurrentAction())) {
                        logger.debug("handle " + session.getCurrentAction());
                        passwordStorage.put(session.getPhone(), new ExpiryEntity(mess.text.text, LocalDateTime.now().plusMinutes(5)));
                    } else if ("create_source".equals(session.getCurrentAction())) {
                        handleCreateSource(session, userMessage.message);
                    } else if ("create_destination".equals(session.getCurrentAction())) {
                        handleCreateDestination(session, mess.text.text);
                    } else if ("delete_link".equals(session.getCurrentAction())) {
                        handleDeleteLink(session, mess.text.text);
                    } else if ("create_destination_source".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        handleCreateDestinationSource(session, userMessage.message);
                    } else if ("delete_destination_source".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        handleDeleteDestinationSource(session, mess.text.text);
                    } else if ("create_user".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        handleCreateUser(session, mess.text.text);
                    } else if ("delete_user".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        handleDeleteUser(session, mess.text.text);
                    } else if ("list_permission".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        handleListPermission(session, mess.text.text);
                    } else if ("select_create_permission".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        handleSelectCreatePermission(session, mess.text.text);
                    } else if ("select_delete_permission".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        handleSelectDeletePermission(session, mess.text.text);
                    } else if ("create_permission".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        handleCreatePermission(session, mess.text.text);
                    } else if ("delete_permission".equals(session.getCurrentAction()) && BOT_OWNER.equals(session.getPhone())) {
                        handleDeletePermission(session, mess.text.text);

                    } else {
                        logger.debug("unrecognized command " + mess.text.text);
                        replyToUser(userMessage.message.chatId, "Use /list /create or /delete command");
                    }

                }

                break;
            case TdApi.MessageContact.CONSTRUCTOR:
                if ("login".equals(session.getCurrentAction())) {
                    TdApi.MessageContact contact = (TdApi.MessageContact) userMessage.message.content;
                    logger.debug(contact.contact.toString());
                    String contactPhone = contact.contact.phoneNumber.replace("+", "");
                    handleLoginAction(session, contactPhone);
                }
                break;
            default:
                logger.debug("new message class :" + userMessage.message.content.getClass().getSimpleName());
                break;
        }

    }

    private static void handleDeletePermissionCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            List<User> users = DbHelper.getUsers(dataSource);
            if (users.isEmpty()) {
                replyToUser(session.getClientId(), "No users, try to add some with /create_user");
                return;
            }
            session.setCurrentAction("select_delete_permission");
            DbHelper.save(dataSource, session);
            TdApi.KeyboardButton[][] rows = new TdApi.KeyboardButton[users.size()][1];
            for (int i = 0; i < users.size(); i++) {
                rows[i][0] = new TdApi.KeyboardButton(users.get(i).toString(), new TdApi.KeyboardButtonTypeText());
            }
            TdApi.ReplyMarkupShowKeyboard keyboard = new TdApi.ReplyMarkupShowKeyboard(rows, true, true, true);
            TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Select user to revoke permission to destination", null), true, true);
            bot.send(new TdApi.SendMessage(session.getClientId(), 0, false, false, keyboard, text), object -> {
                logger.debug("sent " + object.toString());
            });
        } else {
            replyToUser(session.getClientId(), "Not authorized to delete permissions");
        }
    }

    private static void handleCreatePermissionCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            List<User> users = DbHelper.getUsers(dataSource);
            if (users.isEmpty()) {
                replyToUser(session.getClientId(), "No users, try to add some with /create_user");
                return;
            }
            session.setCurrentAction("select_create_permission");
            DbHelper.save(dataSource, session);
            TdApi.KeyboardButton[][] rows = new TdApi.KeyboardButton[users.size()][1];
            for (int i = 0; i < users.size(); i++) {
                rows[i][0] = new TdApi.KeyboardButton(users.get(i).toString(), new TdApi.KeyboardButtonTypeText());
            }
            TdApi.ReplyMarkupShowKeyboard keyboard = new TdApi.ReplyMarkupShowKeyboard(rows, true, true, true);
            TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Select user to allow him destination", null), true, true);
            bot.send(new TdApi.SendMessage(session.getClientId(), 0, false, false, keyboard, text), object -> {
                logger.debug("sent " + object.toString());
            });
        } else {
            replyToUser(session.getClientId(), "Not authorized to create permissions");
        }
    }

    private static void handleListPermissionCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            List<User> users = DbHelper.getUsers(dataSource);
            if (users.isEmpty()) {
                replyToUser(session.getClientId(), "No users, try to add some with /create_user");
                return;
            }
            session.setCurrentAction("list_permission");
            DbHelper.save(dataSource, session);
            TdApi.KeyboardButton[][] rows = new TdApi.KeyboardButton[users.size()][1];
            for (int i = 0; i < users.size(); i++) {
                rows[i][0] = new TdApi.KeyboardButton(users.get(i).toString(), new TdApi.KeyboardButtonTypeText());
            }
            TdApi.ReplyMarkupShowKeyboard keyboard = new TdApi.ReplyMarkupShowKeyboard(rows, true, true, true);
            TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Select user to show allowed destination to him", null), true, true);
            bot.send(new TdApi.SendMessage(session.getClientId(), 0, false, false, keyboard, text), object -> {
                logger.debug("sent " + object.toString());
            });
        } else {
            replyToUser(session.getClientId(), "Not authorized to list permissions");
        }
    }

    private static void handleListPermission(Session session, String text) throws SQLException {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            session.setCurrentAction("");
            DbHelper.save(dataSource, session);
            try {
                List<Chat> allowedChats = DbHelper.getAllowedDestinations(dataSource, text.split(" ")[0]);
                if (allowedChats.isEmpty()) {
                    replyToUser(session.getClientId(), "No allowed destination for " + text.split(" ")[0]);
                }
                final StringBuffer sb = new StringBuffer();
                allowedChats.forEach(chat -> sb.append(chat.toString()).append("\n"));
                replyToUser(session.getClientId(), sb.toString());
            } catch (SQLException ex) {
                replyToUser(session.getClientId(), "Ups, error");
            }
        } else {
            replyToUser(session.getClientId(), "not authorized to show list permissions");
        }
    }

    private static void handleSelectCreatePermission(Session session, String message) throws SQLException {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            List<Chat> destinations = DbHelper.getAllPossibleDestinations(dataSource);
            if (destinations.isEmpty()) {
                session.setCurrentAction("");
                DbHelper.save(dataSource, session);
                replyToUser(session.getClientId(), "No destinations, try to add some with /create_destination");
                return;
            }
            session.setFirstParam(message.split(" ")[0]);
            session.setCurrentAction("create_permission");
            DbHelper.save(dataSource, session);
            TdApi.KeyboardButton[][] rows = new TdApi.KeyboardButton[destinations.size()][1];
            for (int i = 0; i < destinations.size(); i++) {
                rows[i][0] = new TdApi.KeyboardButton(destinations.get(i).getName(), new TdApi.KeyboardButtonTypeText());
            }
            TdApi.ReplyMarkupShowKeyboard keyboard = new TdApi.ReplyMarkupShowKeyboard(rows, true, true, true);
            TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Select destination for " + message, null), true, true);
            bot.send(new TdApi.SendMessage(session.getClientId(), 0, false, false, keyboard, text), object -> {
                logger.debug("sent " + object.toString());
            });
        } else {
            replyToUser(session.getClientId(), "not authorized to create permissions");
        }
    }

    private static void handleCreatePermission(Session session, String message) throws SQLException {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            try {
                boolean isOk = DbHelper.allowDestinationToUser(dataSource, message, session.getFirstParam());
                session.setFirstParam("");
                session.setCurrentAction("");
                DbHelper.save(dataSource, session);
                if (isOk) {
                    replyToUser(session.getClientId(), "Permission granted.");
                } else {
                    replyToUser(session.getClientId(), "Destination not found.");
                }
            } catch (SQLException ex) {
                logger.error(ex.getMessage(), ex);
                replyToUser(session.getClientId(), "Ups, error. Maybe you trying to add same permission twice?");
            }

        } else {
            replyToUser(session.getClientId(), "not authorized to create permissions");
        }
    }

    private static void handleSelectDeletePermission(Session session, String message) throws SQLException {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            List<Chat> destinations = DbHelper.getAllowedDestinations(dataSource, message.split(" ")[0]);
            if (destinations.isEmpty()) {
                session.setCurrentAction("");
                DbHelper.save(dataSource, session);
                replyToUser(session.getClientId(), "No allowed destination for " + message + ", nothing to do");
                return;
            }
            session.setFirstParam(message.split(" ")[0]);
            session.setCurrentAction("delete_permission");
            DbHelper.save(dataSource, session);
            TdApi.KeyboardButton[][] rows = new TdApi.KeyboardButton[destinations.size()][1];
            for (int i = 0; i < destinations.size(); i++) {
                rows[i][0] = new TdApi.KeyboardButton(destinations.get(i).getName(), new TdApi.KeyboardButtonTypeText());
            }
            TdApi.ReplyMarkupShowKeyboard keyboard = new TdApi.ReplyMarkupShowKeyboard(rows, true, true, true);
            TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Select destination for " + message + " to removing", null), true, true);
            bot.send(new TdApi.SendMessage(session.getClientId(), 0, false, false, keyboard, text), object -> {
                logger.debug("sent " + object.toString());
            });
        } else {
            replyToUser(session.getClientId(), "not authorized to delete permissions");
        }
    }

    private static void handleDeletePermission(Session session, String message) throws SQLException {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            try {
                boolean isOk = DbHelper.denyDestinationToUser(dataSource, message, session.getFirstParam());
                session.setFirstParam("");
                session.setCurrentAction("");
                DbHelper.save(dataSource, session);
                if (isOk) {
                    replyToUser(session.getClientId(), "Permission revoked.");
                } else {
                    replyToUser(session.getClientId(), "Permission not found, nothing to do.");
                }
            } catch (SQLException ex) {
                logger.error(ex.getMessage(), ex);
                replyToUser(session.getClientId(), "Ups, error. Check log files for details.");
            }

        } else {
            replyToUser(session.getClientId(), "not authorized to delete permissions");
        }
    }

    private static void handleCreateUser(Session session, String text) throws SQLException {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            session.setCurrentAction("");
            DbHelper.save(dataSource, session);
            try {
                DbHelper.createUser(dataSource, text);
                replyToUser(session.getClientId(), "User created");
            } catch (SQLException ex) {
                replyToUser(session.getClientId(), "User not created");
            }
        } else {
            replyToUser(session.getClientId(), "not authorized to create user");
        }
    }

    private static void handleDeleteDestinationSource(Session session, String text) throws SQLException {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {

            session.setCurrentAction("");
            DbHelper.save(dataSource, session);
            int rows = DbHelper.deleteDestinationByName(dataSource, text);
            if (rows >= 1) {
                replyToUser(session.getClientId(), "Deleted destination channel : " + text);
            } else {
                replyToUser(session.getClientId(), "Not found");
            }

        } else {
            replyToUser(session.getClientId(), "not authorized to delete destination channel");
        }
    }

    private static void handleDeleteUser(Session session, String text) throws SQLException {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            session.setCurrentAction("");
            DbHelper.save(dataSource, session);

            int rows = DbHelper.deleteUser(dataSource, text);
            if (rows == 1) {
                replyToUser(session.getClientId(), "Deleted");
                Client client = openSessions.get(text);
                if (client != null) {
                    client.send(new TdApi.LogOut(), obj -> {
                        logger.debug(obj.toString());
                    });
                }
            } else {
                replyToUser(session.getClientId(), "Not found");
            }
        } else {
            replyToUser(session.getClientId(), "not authorized to delete user");
        }
    }

    private static void handleCreateDestinationSource(Session session, TdApi.Message message) throws SQLException {

        logger.debug("handle " + session.getCurrentAction());

        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            if (message.forwardInfo != null && message.forwardInfo.origin instanceof TdApi.MessageForwardOriginChannel) {
                session.setCurrentAction("");
                DbHelper.save(dataSource, session);

                final TdApi.MessageForwardOriginChannel channel = (TdApi.MessageForwardOriginChannel) message.forwardInfo.origin;

                openSessions.get(session.getPhone()).send(new TdApi.GetChat(channel.chatId), result -> {
                    if (result.getConstructor() == TdApi.Chat.CONSTRUCTOR) {
                        TdApi.Chat chat = (TdApi.Chat) result;
                        try {
                            DbHelper.createPossibleDestination(dataSource, channel.chatId, chat.title);
                            replyToUser(session.getClientId(), "New destination created : " + chat.title);
                        } catch (SQLException e) {
                            logger.error(e.getMessage(), e);
                        }
                    } else if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
                        replyToUser(session.getClientId(), "Error :" + ((TdApi.Error) result).message);
                    } else {
                        logger.error("returned : " + result.toString());
                    }

                });

            } else {
                replyToUser(session.getClientId(), "forward message from channel");
            }
        } else {
            replyToUser(session.getClientId(), "not authorized to create destination channel");
        }
    }

    private static void handleDeleteLink(Session session, String text) throws SQLException {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED) {
            int rowsCount = DbHelper.deleteLinkByName(dataSource, session.getPhone(), text);
            if (rowsCount >= 1) {
                replyToUser(session.getClientId(), "Link deleted");
            } else {
                replyToUser(session.getClientId(), "Link not found");
            }
        } else {
            replyToUser(session.getClientId(), "not authorized to delete link");
        }
    }

    private static void handleCreateDestination(Session session, String text) throws SQLException {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED) {
            Long source = Long.parseLong(session.getFirstParam().substring(0, session.getFirstParam().indexOf('_')));
            String sourceTitle = session.getFirstParam().substring(session.getFirstParam().indexOf('_') + 1);

            Chat dest = DbHelper.getDestination(dataSource, text);
            if (dest != null) {
                session.setCurrentAction("");
                session.setFirstParam("");
                DbHelper.save(dataSource, session);

                DbHelper.createLink(dataSource, session.getPhone(), source, sourceTitle, dest);
                replyToUser(session.getClientId(), "New link created : " + sourceTitle + " -> " + dest.getName());
            } else {
                replyToUser(session.getClientId(), "Destination was not found");
            }
        } else {
            replyToUser(session.getClientId(), "not authorized to create link");
        }

    }

    private static void handleCreateSource(Session session, TdApi.Message message) {
        logger.debug("handle " + session.getCurrentAction());
        if (session.getAuthState() == State.AUTHORIZED) {
            if (message.forwardInfo != null && message.forwardInfo.origin instanceof TdApi.MessageForwardOriginChannel) {

                final TdApi.MessageForwardOriginChannel channel = (TdApi.MessageForwardOriginChannel) message.forwardInfo.origin;
                openSessions.get(session.getPhone()).send(new TdApi.GetChat(channel.chatId), result -> {
                    TdApi.Chat chat = (TdApi.Chat) result;
                    try {
                        session.setFirstParam(String.valueOf(channel.chatId) + "_" + chat.title);
                        session.setCurrentAction("create_destination");
                        DbHelper.save(dataSource, session);
                        List<Chat> destinations = DbHelper.getAllowedDestinations(dataSource, session.getPhone());
                        TdApi.KeyboardButton[][] rows = new TdApi.KeyboardButton[destinations.size()][1];
                        for (int i = 0; i < destinations.size(); i++) {
                            rows[i][0] = new TdApi.KeyboardButton(destinations.get(i).getName(), new TdApi.KeyboardButtonTypeText());
                        }
                        TdApi.ReplyMarkupShowKeyboard keyboard = new TdApi.ReplyMarkupShowKeyboard(rows, true, true, true);
                        TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("source channel is : " + chat.title + "\nSelect destination channel", null), true, true);
                        bot.send(new TdApi.SendMessage(session.getClientId(), 0, false, false, keyboard, text), object -> {
                            logger.debug("sent " + object.toString());

                        });
                    } catch (SQLException e) {
                        logger.error(e.getMessage(), e);
                    }
                });
            } else {
                replyToUser(session.getClientId(), "forward message from source channel");
            }
        } else {
            replyToUser(session.getClientId(), "not authorized to create link");
        }
    }

    private static void handleLoginAction(Session session, String text) throws SQLException {
        logger.debug("handle login phone " + text);
        if (DbHelper.isPhoneAllowed(dataSource, text)) {
            session.setPhone(text);
            session.setAuthState(State.CONFIRM_AUTH);
            session.setCurrentAction("");
            DbHelper.save(dataSource, session);
            createClient(text, session.getClientId());
        } else {
            logger.debug("phone not allowed");
            replyToUser(session.getClientId(), "This phone number is not allowed, please contact admin " + BOT_OWNER);
        }
    }

    private static void handleDeleteUserCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            replyToUser(session.getClientId(), "Input user phone to delete");
            session.setCurrentAction("delete_user");
            DbHelper.save(dataSource, session);
        } else {
            replyToUser(session.getClientId(), "Not authorized to delete user");
        }
    }

    private static void handleCreateUserCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            replyToUser(session.getClientId(), "Input user phone to create");
            session.setCurrentAction("create_user");
            DbHelper.save(dataSource, session);
        } else {
            replyToUser(session.getClientId(), "Not authorized to create user");
        }
    }

    private static void handleListUserCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            List<User> users = DbHelper.getUsers(dataSource);
            if (users.isEmpty()) {
                replyToUser(session.getClientId(), "No bot users, use /create_user");
            } else {
                StringBuilder sb = new StringBuilder();
                users.forEach(user -> sb.append(user).append('\n'));
                replyToUser(session.getClientId(), sb.toString());
            }
        } else {
            replyToUser(session.getClientId(), "Not authorized to list user");
        }

    }

    private static void handleListDestinationCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {

            List<Chat> destinations = DbHelper.getAllPossibleDestinations(dataSource);
            if (destinations.isEmpty()) {
                replyToUser(session.getClientId(), "No destinations, use /create_destination");
            } else {
                StringBuilder sb = new StringBuilder();
                destinations.forEach(chat -> sb.append(chat).append('\n'));
                replyToUser(session.getClientId(), sb.toString());
            }

        } else {
            replyToUser(session.getClientId(), "Not authorized list destination");
        }
    }

    private static void handleDeleteDestinationCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            List<Chat> destinations = DbHelper.getAllPossibleDestinations(dataSource);

            session.setCurrentAction("delete_destination_source");
            DbHelper.save(dataSource, session);

            TdApi.KeyboardButton[][] rows = new TdApi.KeyboardButton[destinations.size()][1];
            for (int i = 0; i < destinations.size(); i++) {
                rows[i][0] = new TdApi.KeyboardButton(destinations.get(i).getName(), new TdApi.KeyboardButtonTypeText());
            }
            TdApi.ReplyMarkupShowKeyboard keyboard = new TdApi.ReplyMarkupShowKeyboard(rows, true, true, true);
            TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Select what to delete", null), true, true);
            bot.send(new TdApi.SendMessage(session.getClientId(), 0, false, false, keyboard, text), object -> {
                logger.debug("sent " + object.toString());

            });

        } else {
            replyToUser(session.getClientId(), "Not authorized to delete destination");
        }
    }

    private static void handleCreateDestinationCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED && BOT_OWNER.equals(session.getPhone())) {
            replyToUser(session.getClientId(), "Forward message from source channel");
            session.setCurrentAction("create_destination_source");
            DbHelper.save(dataSource, session);
        } else {
            replyToUser(session.getClientId(), "Not authorized to create destination");
        }
    }

    private static void handleDeleteCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED) {

            session.setCurrentAction("delete_link");
            DbHelper.save(dataSource, session);

            List<Chat> ownChats = DbHelper.getOwnChats(dataSource, session.getPhone());

            TdApi.KeyboardButton[][] rows = new TdApi.KeyboardButton[ownChats.size()][1];
            for (int i = 0; i < ownChats.size(); i++) {
                rows[i][0] = new TdApi.KeyboardButton(ownChats.get(i).getName(), new TdApi.KeyboardButtonTypeText());
            }
            TdApi.ReplyMarkupShowKeyboard keyboard = new TdApi.ReplyMarkupShowKeyboard(rows, true, true, true);
            TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Select what to delete", null), true, true);
            bot.send(new TdApi.SendMessage(session.getClientId(), 0, false, false, keyboard, text), object -> {
                logger.debug("sent " + object.toString());

            });

        } else {
            replyToUser(session.getClientId(), "Not authorized to delete");
        }
    }

    private static void handleStartCommand(Session session) throws SQLException {
        session.setAuthState(State.LOGIN);
        session.setCurrentAction("");
        session.setPhone("");
        DbHelper.save(dataSource, session);
    }

    private static void handleLoginCommand(Session session) throws SQLException {
        session.setAuthState(State.LOGIN);
        session.setCurrentAction("login");
        DbHelper.save(dataSource, session);
        TdApi.KeyboardButton[][] rows = new TdApi.KeyboardButton[1][1];
        rows[0][0] = new TdApi.KeyboardButton("Phone", new TdApi.KeyboardButtonTypeRequestPhoneNumber());
        TdApi.ReplyMarkupShowKeyboard keyboard = new TdApi.ReplyMarkupShowKeyboard(rows, true, true, true);
        TdApi.InputMessageContent text = new TdApi.InputMessageText(new TdApi.FormattedText("Please input phone number", null), true, true);
        bot.send(new TdApi.SendMessage(session.getClientId(), 0, false, false, keyboard, text), object -> {
            logger.debug("sent " + object.toString());
        });
    }


    private static void handleListCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED) {

            List<Chat> ownChats = DbHelper.getOwnChats(dataSource, session.getPhone());
            List<Chat> allowedDestinations = DbHelper.getAllowedDestinations(dataSource, session.getPhone());
            if (ownChats.isEmpty()) {
                replyToUser(session.getClientId(), "No chats, use /create");
            } else {
                StringBuilder sb = new StringBuilder();
                ownChats.forEach(chat -> {
                    sb.append(chat);
                    if (allowedDestinations.stream().noneMatch(dest -> dest.getChatIdTo() == chat.getChatIdTo())) {
                        sb.append(" (denied)");
                    }
                    sb.append('\n');
                });
                replyToUser(session.getClientId(), sb.toString());
            }
        } else {
            replyToUser(session.getClientId(), "Not authorized to get list");
        }
    }

    private static void handleCreateCommand(Session session) throws SQLException {
        if (session.getAuthState() == State.AUTHORIZED) {
            replyToUser(session.getClientId(), "Forward message from source channel");
            session.setCurrentAction("create_source");
            DbHelper.save(dataSource, session);
        } else {
            replyToUser(session.getClientId(), "Not authorized to create");
        }
    }

    public static void replyToUser(long chatId, String textStr) {
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

