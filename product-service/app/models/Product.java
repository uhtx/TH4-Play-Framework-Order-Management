package models;

import java.math.BigDecimal;
import java.sql.Timestamp;

public class Product {
    public long id;
    public String name;
    public String description;
    public BigDecimal price;
    public int quantity;
    public Timestamp created_at;
    public Timestamp updated_at;
}
