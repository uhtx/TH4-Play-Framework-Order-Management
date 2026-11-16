package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.mvc.*;
import security.Db;
import security.AuthAction;
import com.auth0.jwt.exceptions.JWTVerificationException;

import java.math.BigDecimal;
import java.sql.*;
import java.util.concurrent.CompletionStage;

public class ProductController extends Controller {
    public Result health() {
        return ok(play.libs.Json.newObject().put("service", "product-service").put("status", "ok"));
    }

    private Result requireToken(Http.Request request) {
        String auth = request.getHeaders().get("Authorization").orElse("");
        if (!auth.startsWith("Bearer ")) {
            return unauthorized(play.libs.Json.newObject().put("message", "Thiếu Bearer token."));
        }
        try {
            security.JwtUtil.verify(auth.substring(7).trim());
            return null;
        } catch (Exception e) {
            return unauthorized(play.libs.Json.newObject().put("message", "Token không hợp lệ hoặc hết hạn."));
        }
    }

    public Result list(Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        try (Connection c = Db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT id,name,description,price,quantity,created_at,updated_at FROM products ORDER BY id DESC");
             ResultSet rs = ps.executeQuery()) {
            com.fasterxml.jackson.databind.node.ArrayNode arr = play.libs.Json.newArray();
            while (rs.next()) arr.add(row(rs));
            return ok(arr);
        } catch (Exception e) { return serverError("Không thể đọc danh sách sản phẩm."); }
    }

    public Result get(Long id, Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        if (id == null || id <= 0) return badRequest(message("id phải là số nguyên dương."));
        try (Connection c = Db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT id,name,description,price,quantity,created_at,updated_at FROM products WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return notFound(message("Không tìm thấy sản phẩm."));
                return ok(row(rs));
            }
        } catch (Exception e) { return serverError("Không thể đọc sản phẩm."); }
    }

    public Result create(Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        JsonNode b = request.body().asJson();
        String error = validate(b);
        if (error != null) return badRequest(message(error));
        try (Connection c = Db.connection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO products(name,description,price,quantity) VALUES(?,?,?,?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, b.get("name").asText().trim());
            ps.setString(2, b.hasNonNull("description") ? b.get("description").asText() : null);
            ps.setBigDecimal(3, new BigDecimal(b.get("price").asText()));
            ps.setInt(4, b.get("quantity").asInt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long newId = keys.getLong(1);
                    try (PreparedStatement read = c.prepareStatement(
                        "SELECT id,name,description,price,quantity,created_at,updated_at FROM products WHERE id=?")) {
                        read.setLong(1, newId);
                        try (ResultSet createdRow = read.executeQuery()) {
                            if (createdRow.next()) return created(row(createdRow));
                        }
                    }
                }
            }
            return serverError("Không lấy được sản phẩm vừa tạo.");
        } catch (Exception e) { return serverError("Không thể tạo sản phẩm."); }
    }

    public Result update(Long id, Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        if (id == null || id <= 0) return badRequest(message("id phải là số nguyên dương."));
        JsonNode b = request.body().asJson();
        String error = validate(b);
        if (error != null) return badRequest(message(error));
        try (Connection c = Db.connection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE products SET name=?,description=?,price=?,quantity=? WHERE id=?")) {
            ps.setString(1, b.get("name").asText().trim());
            ps.setString(2, b.hasNonNull("description") ? b.get("description").asText() : null);
            ps.setBigDecimal(3, new BigDecimal(b.get("price").asText()));
            ps.setInt(4, b.get("quantity").asInt());
            ps.setLong(5, id);
            if (ps.executeUpdate() == 0) return notFound(message("Không tìm thấy sản phẩm."));
            return get(id, request);
        } catch (Exception e) { return serverError("Không thể cập nhật sản phẩm."); }
    }

    public Result delete(Long id, Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        if (id == null || id <= 0) return badRequest(message("id phải là số nguyên dương."));
        try (Connection c = Db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM products WHERE id=?")) {
            ps.setLong(1, id);
            if (ps.executeUpdate() == 0) return notFound(message("Không tìm thấy sản phẩm."));
            return ok(message("Xóa sản phẩm thành công."));
        } catch (Exception e) { return serverError("Không thể xóa sản phẩm."); }
    }

    private String validate(JsonNode b) {
        if (b == null || !b.isObject()) return "Body phải là JSON object.";
        if (!b.hasNonNull("name") || !b.get("name").isTextual() || b.get("name").asText().trim().isEmpty())
            return "name là bắt buộc.";
        if (b.get("name").asText().trim().length() > 255) return "name tối đa 255 ký tự.";
        if (!b.hasNonNull("price")) return "price là bắt buộc.";
        if (!b.get("price").isNumber() || b.get("price").decimalValue().compareTo(BigDecimal.ZERO) < 0)
            return "price phải là số không âm.";
        if (!b.hasNonNull("quantity") || !b.get("quantity").canConvertToInt() || b.get("quantity").asInt() < 0)
            return "quantity phải là số nguyên không âm.";
        if (b.hasNonNull("description") && !b.get("description").isTextual())
            return "description phải là chuỗi hoặc null.";
        return null;
    }

    private ObjectNode row(ResultSet rs) throws SQLException {
        ObjectNode n = play.libs.Json.newObject();
        n.put("id", rs.getLong("id"));
        n.put("name", rs.getString("name"));
        String desc = rs.getString("description");
        if (desc == null) n.putNull("description"); else n.put("description", desc);
        n.put("price", rs.getBigDecimal("price"));
        n.put("quantity", rs.getInt("quantity"));
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        if (created != null) n.put("created_at", created.toString());
        if (updated != null) n.put("updated_at", updated.toString());
        return n;
    }

    private ObjectNode message(String s) {
        return play.libs.Json.newObject().put("message", s);
    }
}
