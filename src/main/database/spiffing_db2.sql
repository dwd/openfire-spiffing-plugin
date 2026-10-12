CREATE TABLE ofSpiffingCatalog (
  entryID               VARCHAR(40)     NOT NULL,
  entryName             VARCHAR(256)    NOT NULL,
  selector              VARCHAR(256),
  format                VARCHAR(16)     NOT NULL,
  label                 VARCHAR(2000)   NOT NULL,
  isDefault             INTEGER         NOT NULL,
  CONSTRAINT ofSpiffingCatalog_pk PRIMARY KEY (entryID)
);

CREATE TABLE ofSpiffingPolicy (
  policyID              VARCHAR(128)    NOT NULL,
  policyName            VARCHAR(256)    NOT NULL,
  spif                  CLOB            NOT NULL,
  clearance             CLOB,
  clearanceFormat       VARCHAR(16),
  defaultLabel          CLOB,
  defaultLabelFormat    VARCHAR(16),
  isDefault             INTEGER         NOT NULL DEFAULT 0,
  CONSTRAINT ofSpiffingPolicy_pk PRIMARY KEY (policyID)
);

CREATE TABLE ofSpiffingClearance (
  clearanceID           VARCHAR(40)     NOT NULL,
  domain                VARCHAR(256)    NOT NULL,
  policyID              VARCHAR(128)    NOT NULL,
  clearance             CLOB            NOT NULL,
  format                VARCHAR(16)     NOT NULL,
  CONSTRAINT ofSpiffingClearance_pk PRIMARY KEY (clearanceID)
);

INSERT INTO ofVersion (name, version) VALUES ('spiffing', 1);
