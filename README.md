# bot_resender
Telegram bot for resend messages from private channels to different channels.

## How to use

- start converation with @resender1_bot
- **/login**
- your phone number
- auth code from telegram + any random digit at the end, for example, Telegram code 12345 you should send 123450

After login you can create links.

### create new link
- **/create**
- forward message from source channel
- id of predefined destination channel

### list of configured links for account
- **/list**

### delete link
- **/delete**
- id source channel space id of destination channel

## Admin commands:

### creating destination channel
- **/create_destination**
- forward message from destination channel, you should have write permission in that channel
### list of destinations 
- **/list_destinations**

### delete configured destination

- **/delete_destination**
- id of destination channel

# config sample

    app.hash=xxxxxxxxxxxxxxxxxx
    app.id=xxxxxx
    bot.id=xxxxxxx
    bot.key=XXXXXXXXXXXXXXX

    tdlib.log_level=5
    tdlib.log_file=logs/tdlib.log
    jdbc.url=jdbc:sqlite:db/db.sqlite
    jdbc.username=sa
    jdbc.password=
    jdbc.driver_class=org.sqlite.JDBC
    bot.owner=xxxxxxxxxxx
    proxy.enabled=true
    proxy.host=127.0.0.1
    proxy.port=443
    proxy.user=xxx
    proxy.password=xxxxx
