package security;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Db {
    private Db() {}

    public static Connection connection() throws SQLException {
        Config c = ConfigFactory.load();
        return DriverManager.getConnection(
            "jdbc:mysql://" + c.getString("db.host") + ":" + c.getInt("db.port") +
            "/" + c.getString("db.name") +
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            c.getString("db.user"), c.getString("db.password")
        );
    }
}
