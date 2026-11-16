package security;

import com.auth0.jwt.exceptions.JWTVerificationException;
import play.mvc.*;
import java.util.concurrent.CompletionStage;

/** JWT middleware/action: rejects requests without a valid signed token. */
public class AuthAction extends Action.Simple {
    @Override
    public CompletionStage<Result> call(Http.Request request) {
        String authorization = request.getHeaders().get("Authorization").orElse("");
        if (!authorization.startsWith("Bearer ")) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                Results.unauthorized(play.libs.Json.newObject().put("message", "Thiếu Bearer token."))
            );
        }
        try {
            JwtUtil.verify(authorization.substring(7).trim());
            return delegate.call(request);
        } catch (JWTVerificationException | IllegalArgumentException e) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                Results.unauthorized(play.libs.Json.newObject().put("message", "Token không hợp lệ hoặc hết hạn."))
            );
        }
    }
}
