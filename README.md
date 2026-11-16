# TH4 - Order Management Service (Play Framework)

## Kiến trúc
- Auth Service: `http://localhost:9000`
- Product Service: `http://localhost:9001`
- Order Service: `http://localhost:9002`
- Order DB: `order_service_db` (độc lập với Product DB)

Order Service xác minh JWT bằng cùng `jwt.secret`/`jwt.issuer` của Auth Service và gọi Product Service qua HTTP để kiểm tra tồn kho khi tạo/cập nhật order item. Không truy cập trực tiếp database của Product Service.

## Cài đặt
Yêu cầu JDK 11+, sbt 1.x và MySQL 8+.

1. Chạy `auth-service/conf/schema.sql`.
2. Chạy `product-service/conf/schema.sql`.
3. Chạy `order-service/conf/schema.sql`.
4. Sửa password MySQL trong ba file `conf/application.conf`.
5. Giữ `jwt.secret` và `jwt.issuer` giống nhau giữa Auth, Product và Order Service.

## Chạy
Mở 3 terminal:

```bash
cd auth-service
sbt run
```

```bash
cd product-service
sbt run
```

```bash
cd order-service
sbt run
```

## API Order
Tất cả API `/orders` và `/order_items` cần `Authorization: Bearer <TOKEN>`.

| Method | Endpoint | Chức năng |
|---|---|---|
| GET | `/orders` | Danh sách đơn hàng |
| GET | `/orders/:id` | Chi tiết đơn hàng + order items |
| POST | `/orders` | Tạo đơn, kiểm tra tồn kho qua Product Service |
| PUT | `/orders/:id` | Cập nhật status |
| DELETE | `/orders/:id` | Xóa đơn hàng |
| GET | `/order_items` | Danh sách item |
| GET | `/order_items/:id` | Chi tiết item |
| POST | `/order_items` | Tạo item, kiểm tra tồn kho |
| PUT | `/order_items/:id` | Cập nhật item và tính lại tổng đơn |
| DELETE | `/order_items/:id` | Xóa item và tính lại tổng đơn |

### POST `/orders`
```json
{
  "customer_name": "Nguyen Van A",
  "customer_email": "a@gmail.com",
  "items": [
    { "product_id": 1, "quantity": 2 }
  ]
}
```

### PUT `/orders/1`
```json
{ "status": "completed" }
```

### POST `/order_items`
```json
{
  "order_id": 1,
  "product_id": 1,
  "quantity": 1
}
```

## Lưu ý
- `product_id` là ID tham chiếu logic tới Product Service; không có foreign key cross-database.
- `product_name` và `unit_price` được snapshot vào `order_items` tại thời điểm tạo item.
- Bài yêu cầu kiểm tra tồn kho; service không tự ý sửa database Product Service để trừ kho.
