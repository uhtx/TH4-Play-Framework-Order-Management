package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.auth0.jwt.exceptions.JWTVerificationException;
import play.mvc.*;
import security.Db;
import security.JwtUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;

public class AuthController extends Controller {
    public Result health() {
        return ok(play.libs.Json.newObject().put("service", "auth-service").put("status", "ok"));
    }

    public Result login(Http.Request request) {
        JsonNode body = request.body().asJson();
        if (body == null || !body.hasNonNull("userName") || !body.hasNonNull("password")) {
            return badRequest(play.libs.Json.newObject().put("message", "Cần userName và password."));
        }

        String username = body.get("userName").asText().trim();
        String submittedPassword = body.get("password").asText();
        if (username.isEmpty() || submittedPassword.isEmpty()) {
            return badRequest(play.libs.Json.newObject().put("message", "Tài khoản/mật khẩu không được rỗng."));
        }

        // Bài TH2 cho phép client gửi password Base64 hoặc MD5.
        // Demo này hỗ trợ password rõ (để Postman dễ test) hoặc Base64; DB lưu SHA-256.
        String decoded = submittedPassword;
        try {
            byte[] bytes = Base64.getDecoder().decode(submittedPassword);
            String candidate = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!candidate.isEmpty()) decoded = candidate;
        } catch (IllegalArgumentException ignored) { }

        try (Connection conn = Db.connection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT IdUser FROM User WHERE UserName = ? AND Password = SHA2(?, 256)")) {
            ps.setString(1, username);
            ps.setString(2, decoded);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return unauthorized(play.libs.Json.newObject().put("message", "Sai tài khoản hoặc mật khẩu."));
                }
                String token = JwtUtil.create(username);
                try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE User SET Token = ? WHERE IdUser = ?")) {
                    update.setString(1, token);
                    update.setInt(2, rs.getInt("IdUser"));
                    update.executeUpdate();
                }
                ObjectNode out = play.libs.Json.newObject();
                out.put("token", token);
                out.put("tokenType", "Bearer");
                out.put("expiresIn", 3600);
                out.put("userName", username);
                return ok(out);
            }
        } catch (Exception e) {
            play.Logger.error("Auth login failed", e);
            return internalServerError(play.libs.Json.newObject().put("message", "Lỗi xử lý đăng nhập. Kiểm tra cấu hình database."));
        }
    }

    public Result validate(Http.Request request) {
        String authorization = request.getHeaders().get("Authorization").orElse("");
        if (!authorization.startsWith("Bearer ")) {
            return unauthorized(play.libs.Json.newObject().put("valid", false).put("message", "Thiếu Bearer token."));
        }
        String token = authorization.substring("Bearer ".length()).trim();
        try {
            String username = JwtUtil.verify(token).getSubject();
            return ok(play.libs.Json.newObject().put("valid", true).put("userName", username));
        } catch (JWTVerificationException e) {
            return unauthorized(play.libs.Json.newObject().put("valid", false).put("message", "Token không hợp lệ hoặc hết hạn."));
        }
    }
}
