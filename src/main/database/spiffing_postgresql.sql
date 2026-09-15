CREATE TABLE ofSpiffingCatalog (
  entryID               VARCHAR(40)     NOT NULL,
  entryName             VARCHAR(256)    NOT NULL,
  selector              VARCHAR(256),
  format                VARCHAR(16)     NOT NULL,
  label                 TEXT            NOT NULL,
  isDefault             INTEGER         NOT NULL,
  CONSTRAINT ofSpiffingCatalog_pk PRIMARY KEY (entryID)
);

INSERT INTO ofVersion (name, version) VALUES ('spiffing', 1);
