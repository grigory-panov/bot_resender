CREATE TABLE user_chat (owner VARCHAR (30) NOT NULL, chat_id_from BIGINT NOT NULL, chat_id_to BIGINT NOT NULL, chat_name varchar (200), PRIMARY KEY (owner, chat_id_from, chat_id_to));
CREATE TABLE forwarded_message (msg_id BIGINT NOT NULL, chat_id BIGINT NOT NULL, PRIMARY KEY (msg_id, chat_id));
CREATE TABLE auth_codes (phone VARCHAR (30) NOT NULL, password VARCHAR (1000), code VARCHAR (10), PRIMARY KEY (phone));
CREATE TABLE possible_destination (chat_id BIGINT PRIMARY KEY, chat_name VARCHAR(100));
CREATE TABLE user_session(
  client_id BIGINT not null PRIMARY KEY,
  phone VARCHAR (30),
  auth_state VARCHAR(30),
  current_action VARCHAR(100),
  first_param VARCHAR(100)
);
CREATE TABLE user ( phone VARCHAR (30) PRIMARY KEY , username varchar(200) null);