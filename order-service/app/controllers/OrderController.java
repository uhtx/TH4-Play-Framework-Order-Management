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
import java.util.ArrayList;
import java.util.List;

public class OrderController extends Controller {

    private Result requireToken(Http.Request request) {
        String authorization = request.getHeaders().get("Authorization").orElse("");
        if (!authorization.startsWith("Bearer ")) {
            return unauthorized(message("Thiếu Bearer token."));
        }
        try {
            JwtUtil.verify(authorization.substring(7).trim());
            return null;
        } catch (Exception e) {
            return unauthorized(message("Token không hợp lệ hoặc hết hạn."));
        }
    }

    public Result health() {
        return ok(Json.newObject().put("service", "order-service").put("status", "ok"));
    }

    public Result list(Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;

        try (Connection c = Db.connection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id,customer_name,customer_email,total_amount,status,created_at,updated_at " +
                 "FROM orders ORDER BY id DESC");
             ResultSet rs = ps.executeQuery()) {
            ArrayNode arr = Json.newArray();
            while (rs.next()) arr.add(orderRow(rs));
            return ok(arr);
        } catch (Exception e) {
            e.printStackTrace();
            return internalServerError(message("Không thể đọc danh sách đơn hàng."));
        }
    }

    public Result get(Long id, Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        if (id == null || id <= 0) return badRequest(message("id phải là số nguyên dương."));

        try (Connection c = Db.connection()) {
            ObjectNode order = findOrder(c, id);
            if (order == null) return notFound(message("Không tìm thấy đơn hàng."));
            order.set("items", findItems(c, id));
            return ok(order);
        } catch (Exception e) {
            e.printStackTrace();
            return internalServerError(message("Không thể đọc đơn hàng."));
        }
    }

    public Result create(Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;

        JsonNode body = request.body().asJson();
        String validation = validateCreate(body);
        if (validation != null) return badRequest(message(validation));

        String token = request.getHeaders().get("Authorization").orElse("").substring(7).trim();
        List<ProductSnapshot> products = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        try {
            for (JsonNode item : body.get("items")) {
                long productId = item.get("product_id").asLong();
                int quantity = item.get("quantity").asInt();
                ProductSnapshot product = ProductClient.getProduct(token, productId);
                if (product == null) return notFound(message("Không tìm thấy sản phẩm: " + productId));
                if (product.quantity < quantity) {
                    return badRequest(message("Không đủ tồn kho cho sản phẩm: " + product.name +
                        ". Tồn kho: " + product.quantity + ", yêu cầu: " + quantity + "."));
                }
                BigDecimal itemTotal = product.price.multiply(BigDecimal.valueOf(quantity));
                products.add(new ProductSnapshot(product.id, product.name, product.price, product.quantity, quantity, itemTotal));
                total = total.add(itemTotal);
            }

            try (Connection c = Db.connection()) {
                c.setAutoCommit(false);
                try {
                    long orderId;
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO orders(customer_name,customer_email,total_amount,status) VALUES(?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, body.get("customer_name").asText().trim());
                        if (body.hasNonNull("customer_email")) ps.setString(2, body.get("customer_email").asText().trim());
                        else ps.setNull(2, Types.VARCHAR);
                        ps.setBigDecimal(3, total);
                        ps.setString(4, "pending");
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (!keys.next()) throw new SQLException("Không lấy được ID đơn hàng.");
                            orderId = keys.getLong(1);
                        }
                    }

                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,total_price) VALUES(?,?,?,?,?,?)")) {
                        for (ProductSnapshot p : products) {
                            ps.setLong(1, orderId);
                            ps.setLong(2, p.id);
                            ps.setString(3, p.name);
                            ps.setInt(4, p.requestedQuantity);
                            ps.setBigDecimal(5, p.price);
                            ps.setBigDecimal(6, p.itemTotal);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }

                    c.commit();
                    ObjectNode created = findOrder(c, orderId);
                    created.set("items", findItems(c, orderId));
                    return created(created);
                } catch (Exception e) {
                    try { c.rollback(); } catch (SQLException ignored) {}
                    throw e;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return internalServerError(message("Không thể tạo đơn hàng."));
        }
    }

    public Result update(Long id, Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        if (id == null || id <= 0) return badRequest(message("id phải là số nguyên dương."));

        JsonNode body = request.body().asJson();
        if (body == null || !body.isObject() || !body.hasNonNull("status")) {
            return badRequest(message("status là bắt buộc."));
        }
        String status = body.get("status").asText().trim();
        if (!List.of("pending", "completed", "cancelled").contains(status)) {
            return badRequest(message("status chỉ được pending, completed hoặc cancelled."));
        }

        try (Connection c = Db.connection();
             PreparedStatement ps = c.prepareStatement("UPDATE orders SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setLong(2, id);
            if (ps.executeUpdate() == 0) return notFound(message("Không tìm thấy đơn hàng."));
            return get(id, request);
        } catch (Exception e) {
            e.printStackTrace();
            return internalServerError(message("Không thể cập nhật trạng thái đơn hàng."));
        }
    }

    public Result delete(Long id, Http.Request request) {
        Result rejected = requireToken(request);
        if (rejected != null) return rejected;
        if (id == null || id <= 0) return badRequest(message("id phải là số nguyên dương."));

        try (Connection c = Db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM orders WHERE id=?")) {
            ps.setLong(1, id);
            if (ps.executeUpdate() == 0) return notFound(message("Không tìm thấy đơn hàng."));
            return ok(message("Xóa đơn hàng thành công."));
        } catch (Exception e) {
            e.printStackTrace();
            return internalServerError(message("Không thể xóa đơn hàng."));
        }
    }

    private String validateCreate(JsonNode b) {
        if (b == null || !b.isObject()) return "Body phải là JSON object.";
        if (!b.hasNonNull("customer_name") || !b.get("customer_name").isTextual()
                || b.get("customer_name").asText().trim().isEmpty()) return "customer_name là bắt buộc.";
        if (b.get("customer_name").asText().trim().length() > 255) return "customer_name tối đa 255 ký tự.";
        if (b.hasNonNull("customer_email") && !b.get("customer_email").isTextual()) return "customer_email phải là chuỗi hoặc null.";
        if (!b.hasNonNull("items") || !b.get("items").isArray() || b.get("items").size() == 0) return "items phải là mảng và không được rỗng.";
        for (JsonNode item : b.get("items")) {
            if (!item.isObject() || !item.hasNonNull("product_id") || !item.hasNonNull("quantity")) return "Mỗi item cần product_id và quantity.";
            if (!item.get("product_id").canConvertToLong() || item.get("product_id").asLong() <= 0) return "product_id phải là số nguyên dương.";
            if (!item.get("quantity").canConvertToInt() || item.get("quantity").asInt() <= 0) return "quantity phải là số nguyên dương.";
        }
        return null;
    }

    private ObjectNode findOrder(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,customer_name,customer_email,total_amount,status,created_at,updated_at FROM orders WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? orderRow(rs) : null;
            }
        }
    }

    private ArrayNode findItems(Connection c, long orderId) throws SQLException {
        ArrayNode arr = Json.newArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,order_id,product_id,product_name,quantity,unit_price,total_price FROM order_items WHERE order_id=? ORDER BY id")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) arr.add(itemRow(rs));
            }
        }
        return arr;
    }

    private ObjectNode orderRow(ResultSet rs) throws SQLException {
        ObjectNode n = Json.newObject();
        n.put("id", rs.getLong("id"));
        n.put("customer_name", rs.getString("customer_name"));
        String email = rs.getString("customer_email");
        if (email == null) n.putNull("customer_email"); else n.put("customer_email", email);
        n.put("total_amount", rs.getBigDecimal("total_amount"));
        n.put("status", rs.getString("status"));
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        if (created != null) n.put("created_at", created.toString());
        if (updated != null) n.put("updated_at", updated.toString());
        return n;
    }

    private ObjectNode itemRow(ResultSet rs) throws SQLException {
        ObjectNode n = Json.newObject();
        n.put("id", rs.getLong("id"));
        n.put("order_id", rs.getLong("order_id"));
        n.put("product_id", rs.getLong("product_id"));
        n.put("product_name", rs.getString("product_name"));
        n.put("quantity", rs.getInt("quantity"));
        n.put("unit_price", rs.getBigDecimal("unit_price"));
        n.put("total_price", rs.getBigDecimal("total_price"));
        return n;
    }

    private ObjectNode message(String text) {
        return Json.newObject().put("message", text);
    }

    private static final class ProductSnapshot {
        final long id; final String name; final BigDecimal price; final int stock; final int requestedQuantity; final BigDecimal itemTotal;
        ProductSnapshot(long id, String name, BigDecimal price, int stock, int requestedQuantity, BigDecimal itemTotal) {
            this.id = id; this.name = name; this.price = price; this.stock = stock; this.requestedQuantity = requestedQuantity; this.itemTotal = itemTotal;
        }
    }
}
