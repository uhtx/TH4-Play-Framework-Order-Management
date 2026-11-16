package models;

import java.math.BigDecimal;

public class OrderItem {
    public long id;
    public long order_id;
    public long product_id;
    public String product_name;
    public int quantity;
    public BigDecimal unit_price;
    public BigDecimal total_price;
}
