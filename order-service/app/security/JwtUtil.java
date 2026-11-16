package security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.typesafe.config.ConfigFactory;

public final class JwtUtil {
    private JwtUtil() {}

    public static DecodedJWT verify(String token) {
        var c = ConfigFactory.load();
        return JWT.require(Algorithm.HMAC256(c.getString("jwt.secret")))
            .withIssuer(c.getString("jwt.issuer"))
            .build().verify(token);
    }
}
