CREATE DATABASE IF NOT EXISTS ProductCatalog;

-- Table for product details
CREATE TABLE IF NOT EXISTS ProductCatalog.Products (
    ProductId Int32,
    ProductName String,
    Description String,
    Price Decimal(10, 2)
) ENGINE = MergeTree() ORDER BY ProductId;

-- Table for inventory levels
CREATE TABLE IF NOT EXISTS ProductCatalog.Inventory (
    ProductId Int32,
    StockLevel Int32,
    LastUpdated DateTime DEFAULT now()
) ENGINE = MergeTree() ORDER BY ProductId;

-- Insert Sample Data
INSERT INTO ProductCatalog.Products (ProductId, ProductName, Description, Price) VALUES
(101, 'Wireless Mouse X', 'Ergonomic wireless mouse with 2.4GHz connectivity and adjustable DPI.', 25.99),
(102, 'Mechanical Keyboard Pro', 'Full-sized mechanical keyboard with RGB backlighting and blue switches.', 89.99),
(103, 'USB-C Hub Elite', '7-in-1 USB-C hub with HDMI, USB 3.0, SD card reader, and Power Delivery.', 45.00),
(104, 'Noise-Cancelling Headphones', 'Over-ear headphones with active noise cancellation and 30-hour battery life.', 120.50),
(999, 'Dummy Product', 'This is a test product for demonstration purposes.', 9.99);

INSERT INTO ProductCatalog.Inventory (ProductId, StockLevel) VALUES
(101, 150),
(102, 75),
(103, 200),
(104, 30),
(999, 0); -- A product with zero stock