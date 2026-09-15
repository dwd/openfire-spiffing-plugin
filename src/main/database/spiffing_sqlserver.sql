CREATE TABLE ofSpiffingCatalog (
  entryID               NVARCHAR(40)    NOT NULL,
  entryName             NVARCHAR(256)   NOT NULL,
  selector              NVARCHAR(256),
  format                NVARCHAR(16)    NOT NULL,
  label                 NTEXT           NOT NULL,
  isDefault             INTEGER         NOT NULL,
  CONSTRAINT ofSpiffingCatalog_pk PRIMARY KEY (entryID)
);

INSERT INTO ofVersion (name, version) VALUES ('spiffing', 1);
