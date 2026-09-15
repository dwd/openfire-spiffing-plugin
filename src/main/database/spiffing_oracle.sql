CREATE TABLE ofSpiffingCatalog (
  entryID               VARCHAR2(40)    NOT NULL,
  entryName             VARCHAR2(256)   NOT NULL,
  selector              VARCHAR2(256),
  format                VARCHAR2(16)    NOT NULL,
  label                 LONG            NOT NULL,
  isDefault             INTEGER         NOT NULL,
  CONSTRAINT ofSpiffingCatalog_pk PRIMARY KEY (entryID)
);

INSERT INTO ofVersion (name, version) VALUES ('spiffing', 1);

commit;
