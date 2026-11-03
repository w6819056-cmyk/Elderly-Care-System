CREATE TABLE hello_module_probe (
 id BIGINT NOT NULL PRIMARY KEY,
 installed_at TIMESTAMP NOT NULL
);
INSERT INTO hello_module_probe(id,installed_at) VALUES(1,CURRENT_TIMESTAMP);
