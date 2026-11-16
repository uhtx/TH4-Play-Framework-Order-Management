package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import security.Db;
import security.JwtUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.util.List;

public class OrderItemController extends Controller {

    private Result requireToken(Http.Request request) {
        String authorization = request.getHeaders().get("Authorization").orElse("");
        if (!authorization.startsWith("Bearer ")) return unauthorized(message("Thiếu Bearer token."));
        try { JwtUtil.verify(authorization.substring(7).trim()); return null; }
        catch (Exception e) { return unauthorized(message("Token không hợp lệ hoặc hết hạn.")); }
    }

    public Result list(Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        try (Connection c = Db.connection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id,order_id,product_id,product_name,quantity,unit_price,total_price FROM order_items ORDER BY id DESC");
             ResultSet rs = ps.executeQuery()) {
            ArrayNode arr = Json.newArray();
            while (rs.next()) arr.add(row(rs));
            return ok(arr);
        } catch (Exception e) { e.printStackTrace(); return internalServerError(message("Không thể đọc danh sách chi tiết đơn hàng.")); }
    }

    public Result get(Long id, Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        if (id == null || id <= 0) return badRequest(message("id phải là số nguyên dương."));
        try (Connection c = Db.connection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id,order_id,product_id,product_name,quantity,unit_price,total_price FROM order_items WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return notFound(message("Không tìm thấy chi tiết đơn hàng."));
                return ok(row(rs));
            }
        } catch (Exception e) { e.printStackTrace(); return internalServerError(message("Không thể đọc chi tiết đơn hàng.")); }
    }

    public Result create(Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        JsonNode b = request.body().asJson();
        String error = validateCreate(b);
        if (error != null) return badRequest(message(error));

        String token = request.getHeaders().get("Authorization").orElse("").substring(7).trim();
        long orderId = b.get("order_id").asLong();
        long productId = b.get("product_id").asLong();
        int quantity = b.get("quantity").asInt();

        try {
            JsonNode product = ProductClient.getProduct(token, productId);
            if (product == null) return notFound(message("Không tìm thấy sản phẩm: " + productId));
            if (product.get("quantity").asInt() < quantity) return badRequest(message("Không đủ tồn kho cho sản phẩm: " + product.get("name").asText()));
            BigDecimal unitPrice = product.get("price").decimalValue();
            BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity));

            try (Connection c = Db.connection()) {
                if (!orderExists(c, orderId)) return notFound(message("Không tìm thấy đơn hàng: " + orderId));
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,total_price) VALUES(?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, orderId);
                    ps.setLong(2, productId);
                    ps.setString(3, product.get("name").asText());
                    ps.setInt(4, quantity);
                    ps.setBigDecimal(5, unitPrice);
                    ps.setBigDecimal(6, total);
                    ps.executeUpdate();
                    long id;
                    try (ResultSet keys = ps.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Không lấy được ID item."); id = keys.getLong(1); }
                    recalculateOrderTotal(c, orderId);
                    try (PreparedStatement read = c.prepareStatement(
                            "SELECT id,order_id,product_id,product_name,quantity,unit_price,total_price FROM order_items WHERE id=?")) {
                        read.setLong(1, id);
                        try (ResultSet rs = read.executeQuery()) { if (rs.next()) return created(row(rs)); }
                    }
                }
            }
            return internalServerError(message("Không lấy được item vừa tạo."));
        } catch (Exception e) { e.printStackTrace(); return internalServerError(message("Không thể tạo chi tiết đơn hàng.")); }
    }

    public Result update(Long id, Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        if (id == null || id <= 0) return badRequest(message("id phải là số nguyên dương."));
        JsonNode b = request.body().asJson();
        if (b == null || !b.isObject() || !b.hasNonNull("quantity") || !b.get("quantity").canConvertToInt() || b.get("quantity").asInt() <= 0)
            return badRequest(message("quantity phải là số nguyên dương."));

        int quantity = b.get("quantity").asInt();
        String token = request.getHeaders().get("Authorization").orElse("").substring(7).trim();
        try (Connection c = Db.connection()) {
            long productId; long orderId;
            try (PreparedStatement find = c.prepareStatement("SELECT order_id,product_id FROM order_items WHERE id=?")) {
                find.setLong(1, id);
                try (ResultSet rs = find.executeQuery()) {
                    if (!rs.next()) return notFound(message("Không tìm thấy chi tiết đơn hàng."));
                    orderId = rs.getLong("order_id"); productId = rs.getLong("product_id");
                }
            }
            JsonNode product = ProductClient.getProduct(token, productId);
            if (product == null) return notFound(message("Không tìm thấy sản phẩm: " + productId));
            if (product.get("quantity").asInt() < quantity) return badRequest(message("Không đủ tồn kho cho sản phẩm: " + product.get("name").asText()));
            BigDecimal unitPrice = product.get("price").decimalValue();
            BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity));
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE order_items SET product_name=?,quantity=?,unit_price=?,total_price=? WHERE id=?")) {
                ps.setString(1, product.get("name").asText()); ps.setInt(2, quantity); ps.setBigDecimal(3, unitPrice); ps.setBigDecimal(4, total); ps.setLong(5, id); ps.executeUpdate();
            }
            recalculateOrderTotal(c, orderId);
            try (PreparedStatement ps = c.prepareStatement("SELECT id,order_id,product_id,product_name,quantity,unit_price,total_price FROM order_items WHERE id=?")) {
                ps.setLong(1, id); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return ok(row(rs)); }
            }
            return notFound(message("Không tìm thấy chi tiết đơn hàng."));
        } catch (Exception e) { e.printStackTrace(); return internalServerError(message("Không thể cập nhật chi tiết đơn hàng.")); }
    }

    public Result delete(Long id, Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        if (id == null || id <= 0) return badRequest(message("id phải là số nguyên dương."));
        try (Connection c = Db.connection()) {
            long orderId;
            try (PreparedStatement find = c.prepareStatement("SELECT order_id FROM order_items WHERE id=?")) {
                find.setLong(1, id);
                try (ResultSet rs = find.executeQuery()) {
                    if (!rs.next()) return notFound(message("Không tìm thấy chi tiết đơn hàng."));
                    orderId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM order_items WHERE id=?")) { ps.setLong(1, id); ps.executeUpdate(); }
            recalculateOrderTotal(c, orderId);
            return ok(message("Xóa chi tiết đơn hàng thành công."));
        } catch (Exception e) { e.printStackTrace(); return internalServerError(message("Không thể xóa chi tiết đơn hàng.")); }
    }

    private String validateCreate(JsonNode b) {
        if (b == null || !b.isObject()) return "Body phải là JSON object.";
        if (!b.hasNonNull("order_id") || !b.get("order_id").canConvertToLong() || b.get("order_id").asLong() <= 0) return "order_id phải là số nguyên dương.";
        if (!b.hasNonNull("product_id") || !b.get("product_id").canConvertToLong() || b.get("product_id").asLong() <= 0) return "product_id phải là số nguyên dương.";
        if (!b.hasNonNull("quantity") || !b.get("quantity").canConvertToInt() || b.get("quantity").asInt() <= 0) return "quantity phải là số nguyên dương.";
        return null;
    }

    private boolean orderExists(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM orders WHERE id=?")) { ps.setLong(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next(); } }
    }

    private void recalculateOrderTotal(Connection c, long orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE orders SET total_amount=(SELECT COALESCE(SUM(total_price),0) FROM order_items WHERE order_id=?) WHERE id=?")) {
            ps.setLong(1, orderId); ps.setLong(2, orderId); ps.executeUpdate();
        }
    }

    private ObjectNode row(ResultSet rs) throws SQLException {
        ObjectNode n = Json.newObject();
        n.put("id", rs.getLong("id")); n.put("order_id", rs.getLong("order_id")); n.put("product_id", rs.getLong("product_id"));
        n.put("product_name", rs.getString("product_name")); n.put("quantity", rs.getInt("quantity"));
        n.put("unit_price", rs.getBigDecimal("unit_price")); n.put("total_price", rs.getBigDecimal("total_price"));
        return n;
    }

    private ObjectNode message(String text) { return Json.newObject().put("message", text); }

    private static final class ProductClient {
        static JsonNode getProduct(String token, long productId) throws Exception {
            String baseUrl = com.typesafe.config.ConfigFactory.load().getString("product.service.base-url");
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl + "/products/" + productId))
                .header("Authorization", "Bearer " + token)
                .GET().build();
            java.net.http.HttpResponse<String> response = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            return Json.parse(response.body());
        }
    }
}
