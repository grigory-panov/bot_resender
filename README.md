# bot_resender
Telegram bot for resend messages from private channels to different channels.

How to use

- start converation with @resender1_bot
- /login xxxxxxxxxxxx
- /auth xxxxx_plus_any_random_digits if you send code as is, telegram invalidate it

next parts is not implemented yet
- /create 
- /list
- /delete
- /create_destination ( admin only )


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
