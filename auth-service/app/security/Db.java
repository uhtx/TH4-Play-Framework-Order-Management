package security;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import play.Configuration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Db {
    private Db() {}

    public static Connection connection() throws SQLException {
        Config c = ConfigFactory.load();
        String host = c.getString("db.host");
        int port = c.getInt("db.port");
        String name = c.getString("db.name");
        String user = c.getString("db.user");
        String password = c.getString("db.password");
        return DriverManager.getConnection(
            "jdbc:mysql://" + host + ":" + port + "/" + name +
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            user, password
        );
    }
}
