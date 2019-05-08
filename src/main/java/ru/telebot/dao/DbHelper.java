package ru.telebot.dao;

import com.zaxxer.hikari.HikariDataSource;
import ru.telebot.domain.Chat;
import ru.telebot.domain.Session;

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
                    Session session = new Session();
                    session.setPhone(resultSet.getString("phone"));
                    sessions.add(session);
                }
                resultSet.close();
                connection.commit();
            }
        }
        return sessions;
    }

    public static List<Chat> getChatsToForward(DataSource ds, String phone, long chatId) throws SQLException {
        final List<Chat> chats = new ArrayList<>();
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select * from user_chat where chat_id_from = ? and owner = ?")) {
                ResultSet resultSet = ps.executeQuery();
                while (resultSet.next()){
                    Chat chat = new Chat();
                    chat.setOwner(resultSet.getString("owner"));
                    chat.setChatIdFrom(resultSet.getLong("chat_id_from"));
                    chat.setChatIdTo(resultSet.getLong("chat_id_to"));
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

    public static String getAuthCode(DataSource ds, String phone) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select code from auth_codes where phone = ?")) {
                ps.setString(1, phone);
                ResultSet resultSet = ps.executeQuery();
                String code = null;
                if(resultSet.next()){
                    code = resultSet.getString("code");
                }
                resultSet.close();
                connection.commit();
                return code;
            }
        }
    }
    public static void deleteAuthCode(DataSource ds, String phone) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("delete from auth_codes where phone = ?")) {
                ps.setString(1, phone);
                ps.executeUpdate();
                connection.commit();
            }
        }
    }

    public static String setAuthCode(DataSource ds, String phone, String code) throws SQLException {
        if(getAuthCode(ds, phone) == null){
            try (Connection connection = ds.getConnection()) {
                try (PreparedStatement ps = connection.prepareStatement("insert into auth_codes (code, phone) values (?, ?)")) {
                    ps.setString(1, code);
                    ps.setString(2, phone);
                    ps.executeUpdate();
                    connection.commit();
                    return code;
                }
            }
        }else {
            try (Connection connection = ds.getConnection()) {
                try (PreparedStatement ps = connection.prepareStatement("update auth_codes set code = ? where phone = ?")) {
                    ps.setString(1, code);
                    ps.setString(2, phone);
                    ps.executeUpdate();
                    connection.commit();
                    return code;
                }
            }
        }
    }

    public static String getPassword(DataSource ds, String phone) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("select password from auth_codes where phone = ?")) {
                ps.setString(1, phone);
                ResultSet resultSet = ps.executeQuery();
                String code = null;
                if(resultSet.next()){
                    code = resultSet.getString("password");
                }
                resultSet.close();
                connection.commit();
                return code;
            }
        }
    }

    public static void deleteSession(HikariDataSource ds, String phone) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("delete from user_session where phone = ?")) {
                ps.setString(1, phone);
                ps.executeUpdate();
                connection.commit();
            }
        }
    }
}
