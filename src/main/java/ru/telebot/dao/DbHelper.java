package ru.telebot.dao;

import com.zaxxer.hikari.HikariDataSource;
import ru.telebot.domain.Chat;
import ru.telebot.domain.Session;
import ru.telebot.domain.State;
import ru.telebot.domain.User;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DbHelper {

    public static void testConnection(DataSource ds) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select 1")) {
                ps.executeQuery().close();
                connection.commit();
            }
        }
    }

    public static List<Session> getSessions(DataSource ds) throws SQLException {

        final List<Session> sessions = new ArrayList<>();
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from user_session")) {
                ResultSet resultSet = ps.executeQuery();
                while (resultSet.next()){
                    sessions.add(mapSession(resultSet));
                }
                resultSet.close();
                connection.commit();
            }
        }
        return sessions;
    }

    private static Session mapSession(ResultSet resultSet) throws SQLException {
        Session session = new Session();
        session.setPhone(resultSet.getString("phone"));
        session.setAuthState(Enum.valueOf(State.class, resultSet.getString("auth_state")));
        session.setCurrentAction(resultSet.getString("current_action"));
        session.setClientId(resultSet.getLong("client_id"));
        session.setFirstParam(resultSet.getString("first_param"));
        return session;
    }

    public static List<Chat> getChatsToForward(DataSource ds, String phone, long chatId) throws SQLException {
        final List<Chat> chats = new ArrayList<>();
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from user_chat where chat_id_from = ? and owner = ?")) {
                ps.setLong(1, chatId);
                ps.setString(2, phone);
                ResultSet resultSet = ps.executeQuery();
                while (resultSet.next()){
                    Chat chat = new Chat();
                    chat.setOwner(resultSet.getString("owner"));
                    chat.setChatIdFrom(resultSet.getLong("chat_id_from"));
                    chat.setChatIdTo(resultSet.getLong("chat_id_to"));
                    chat.setName(resultSet.getString("chat_name"));
                    chats.add(chat);
                }
                resultSet.close();
                connection.commit();
            }
        }
        return chats;
    }

    public static List<Chat> getOwnChats(DataSource ds, String phone) throws SQLException {
        final List<Chat> chats = new ArrayList<>();
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from user_chat where owner = ?")) {
                ps.setString(1, phone);
                ResultSet resultSet = ps.executeQuery();
                while (resultSet.next()){
                    Chat chat = new Chat();
                    chat.setOwner(resultSet.getString("owner"));
                    chat.setChatIdFrom(resultSet.getLong("chat_id_from"));
                    chat.setChatIdTo(resultSet.getLong("chat_id_to"));
                    chat.setName(resultSet.getString("chat_name"));
                    chats.add(chat);
                }
                resultSet.close();
                connection.commit();
            }
        }
        return chats;
    }

    public static boolean messageWasForwarderToChannel(DataSource ds, long messageId, long chatId) throws SQLException {

        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from forwarded_message where msg_id = ? and chat_id = ?")) {
                ps.setLong(1, messageId);
                ps.setLong(2, chatId);
                ResultSet resultSet = ps.executeQuery();
                boolean result = resultSet.next();
                resultSet.close();
                connection.commit();
                return result;
            }
        }
    }

    public static void addForwardedMessage(DataSource ds, long messageId, long chatId) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("insert into forwarded_message (msg_id, chat_id) values (?, ?)")) {
                ps.setLong(1, messageId);
                ps.setLong(2, chatId);
                ps.executeUpdate();
                connection.commit();
            }
        }
    }



    public static void deleteSession(DataSource ds, String phone) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("delete from user_session where phone = ?")) {
                ps.setString(1, phone);
                ps.executeUpdate();
                connection.commit();
            }
        }
    }

    public static Session getSessionByClientId(DataSource ds, long clientId) throws SQLException  {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from user_session where client_id = ?")) {
                ps.setLong(1, clientId);
                ResultSet resultSet = ps.executeQuery();
                Session session = new Session();
                session.setAuthState(State.LOGIN);
                session.setClientId(clientId);
                if(resultSet.next()){
                    session = mapSession(resultSet);
                }
                resultSet.close();
                connection.commit();
                return session;

            }
        }
    }
    public static void save(DataSource ds, Session session) throws SQLException  {
        try (Connection connection = ds.getConnection()) {

            try (PreparedStatement ps = connection.prepareStatement("select * from user_session where client_id = ?")) {
                ps.setLong(1, session.getClientId());
                ResultSet resultSet = ps.executeQuery();

                if(resultSet.next()){
                    resultSet.close();
                    try(PreparedStatement ps2 = connection.prepareStatement("update user_session set " +
                            "phone = ?, " +
                            "auth_state = ?, " +
                            "current_action = ?, " +
                            "first_param = ? " +
                            "where  client_id = ?")){
                        ps2.setString(1, session.getPhone());
                        ps2.setString(2, session.getAuthState().toString());
                        ps2.setString(3, session.getCurrentAction());
                        ps2.setString(4, session.getFirstParam());
                        ps2.setLong(5, session.getClientId());
                        ps2.executeUpdate();
                    }
                }else{
                    try(PreparedStatement ps2 = connection.prepareStatement("insert into user_session (phone, auth_state, current_action, first_param, client_id) " +
                            "values (? ,? ,? ,? ,? )")){
                        ps2.setString(1, session.getPhone());
                        ps2.setString(2, session.getAuthState().toString());
                        ps2.setString(3, session.getCurrentAction());
                        ps2.setString(4, session.getFirstParam());
                        ps2.setLong(5, session.getClientId());
                        ps2.executeUpdate();
                    }
                }

                connection.commit();
            }
        }
    }

    public static Session getSessionByPhone(DataSource ds, String phone) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from user_session where phone = ?")) {
                ps.setString(1, phone);
                ResultSet resultSet = ps.executeQuery();
                Session session = null;
                if(resultSet.next()){
                    session = mapSession(resultSet);
                }
                resultSet.close();
                connection.commit();
                return session;
            }
        }
    }

    public static List<Chat> getPossibleDestinations(DataSource ds) throws SQLException {

        final List<Chat> chats = new ArrayList<>();
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from possible_destination")) {

                ResultSet resultSet = ps.executeQuery();
                while (resultSet.next()){
                    Chat chat = new Chat();
                    chat.setChatIdTo(resultSet.getLong("chat_id"));
                    chat.setName(resultSet.getString("chat_name"));
                    chats.add(chat);
                }
                resultSet.close();
                connection.commit();
            }
        }
        return chats;
    }

    public static void createPossibleDestination(DataSource ds, long chatId, String name) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("insert into possible_destination (chat_id, chat_name) values (?, ?)")) {
                ps.setLong(1, chatId);
                ps.setString(2, name);
                ps.executeUpdate();
                connection.commit();
            }
        }

    }
    public static int deleteDestination(DataSource ds, Long destination) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("delete from possible_destination where  chat_id =?")) {
                ps.setLong(1, destination);
                int res = ps.executeUpdate();
                connection.commit();
                return res;
            }
        }

    }

    public static int deleteDestinationByName(DataSource ds, String destinationName) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("delete from possible_destination where chat_name =?")) {
                ps.setString(1, destinationName);
                int res = ps.executeUpdate();
                connection.commit();
                return res;
            }
        }

    }

    public static void createLink(DataSource ds, String phone, Long source, String sourceTitle, Chat destination) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("insert into user_chat (owner, chat_id_from, chat_id_to, chat_name) values (?, ?, ?, ?)")) {
                ps.setString(1, phone);
                ps.setLong(2, source);
                ps.setLong(3, destination.getChatIdTo());
                ps.setString(4, sourceTitle + " -> " + destination.getName());
                ps.executeUpdate();
                connection.commit();
            }
        }

    }

    public static int deleteLink(DataSource ds, String phone, Long source, Long destination) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("delete from user_chat where owner=? and chat_id_from=? and chat_id_to = ?")) {
                ps.setString(1, phone);
                ps.setLong(2, source);
                ps.setLong(3, destination);
                int res = ps.executeUpdate();
                connection.commit();
                return res;
            }
        }

    }

    public static int deleteLinkByName(DataSource ds, String phone, String name) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("delete from user_chat where owner=? and chat_name=?")) {
                ps.setString(1, phone);
                ps.setString(2, name);
                int res = ps.executeUpdate();
                connection.commit();
                return res;
            }
        }

    }

    public static List<Chat> getAllLinks(DataSource ds) throws SQLException {
        final List<Chat> chats = new ArrayList<>();
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from user_chat")) {

                ResultSet resultSet = ps.executeQuery();
                while (resultSet.next()){
                    Chat chat = new Chat();
                    chat.setChatIdFrom(resultSet.getLong("chat_id_from"));
                    chat.setChatIdTo(resultSet.getLong("chat_id_to"));
                    chat.setOwner(resultSet.getString("owner"));
                    chat.setName(resultSet.getString("chat_name"));
                    chats.add(chat);
                }
                resultSet.close();
                connection.commit();
            }
        }
        return chats;
    }

    public static Chat getDestination(DataSource ds, String destinationName) throws SQLException {

        Chat chat = null;

        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from possible_destination where chat_name = ?")) {

                ps.setString(1, destinationName);
                ResultSet resultSet = ps.executeQuery();

                while (resultSet.next()){
                    chat = new Chat();
                    chat.setChatIdTo(resultSet.getLong("chat_id"));
                    chat.setName(resultSet.getString("chat_name"));
                }
                resultSet.close();
                connection.commit();
            }
        }
        return chat;
    }

    public static void createUser(DataSource ds, String phone) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("insert into user (phone) values (?)")) {
                ps.setString(1, phone);
                ps.executeUpdate();
                connection.commit();
            }
        }
    }

    public static boolean isPhoneAllowed(DataSource ds, String phone) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from user where phone = ?")) {
                ps.setString(1, phone);
                ResultSet resultSet = ps.executeQuery();
                boolean result = false;
                if (resultSet.next()){
                    result = true;
                }
                connection.commit();
                return result;
            }
        }
    }
    public static int deleteUser(DataSource ds, String phone) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("delete from user where phone=?")) {
                ps.setString(1, phone);
                int res = ps.executeUpdate();
                connection.commit();
                return res;
            }
        }

    }

    public static List<User> getUsers(DataSource ds) throws SQLException {

        List<User> result = new ArrayList<>();

        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from user")) {
                ResultSet resultSet = ps.executeQuery();

                while (resultSet.next()){
                    User user = new User();
                    user.setPhone(resultSet.getString("phone"));
                    user.setUserName(resultSet.getString("username"));
                    result.add(user);
                }
                resultSet.close();
                connection.commit();
            }
        }
        return result;
    }

    public static void updateUserName(HikariDataSource ds, String phone, String username) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("update user set username = ? where phone = ?")) {
                ps.setString(1, username);
                ps.setString(2, phone);
                ps.executeUpdate();
                connection.commit();
            }
        }
    }
}
