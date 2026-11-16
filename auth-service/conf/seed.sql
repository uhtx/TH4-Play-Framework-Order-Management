USE auth_service_db;
-- Demo only: SHA-256 hex of password "123456". Replace with a proper password-hashing
-- scheme (BCrypt/Argon2) for any real deployment.
INSERT INTO User (UserName, Password)
VALUES ('student', SHA2('123456', 256))
ON DUPLICATE KEY UPDATE Password = VALUES(Password);
