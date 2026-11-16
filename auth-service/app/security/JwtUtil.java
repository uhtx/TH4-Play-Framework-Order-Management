package security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.typesafe.config.ConfigFactory;
import java.time.Instant;
import java.util.Date;

public final class JwtUtil {
    private JwtUtil() {}

    private static String secret() {
        return ConfigFactory.load().getString("jwt.secret");
    }

    public static String create(String username) {
        Instant now = Instant.now();
        return JWT.create()
            .withIssuer(ConfigFactory.load().getString("jwt.issuer"))
            .withSubject(username)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(3600)))
            .sign(Algorithm.HMAC256(secret()));
    }

    public static DecodedJWT verify(String token) {
        return JWT.require(Algorithm.HMAC256(secret()))
            .withIssuer(ConfigFactory.load().getString("jwt.issuer"))
            .build().verify(token);
    }
}
