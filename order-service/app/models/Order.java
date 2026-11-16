package models;

import java.math.BigDecimal;
import java.sql.Timestamp;

public class Order {
    public long id;
    public String customer_name;
    public String customer_email;
    public BigDecimal total_amount;
    public String status;
    public Timestamp created_at;
    public Timestamp updated_at;
}
