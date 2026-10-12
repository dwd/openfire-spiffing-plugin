CREATE TABLE ofSpiffingCatalog (
  entryID               VARCHAR2(40)    NOT NULL,
  entryName             VARCHAR2(256)   NOT NULL,
  selector              VARCHAR2(256),
  format                VARCHAR2(16)    NOT NULL,
  label                 LONG            NOT NULL,
  isDefault             INTEGER         NOT NULL,
  CONSTRAINT ofSpiffingCatalog_pk PRIMARY KEY (entryID)
);

CREATE TABLE ofSpiffingPolicy (
  policyID              VARCHAR2(128)   NOT NULL,
  policyName            VARCHAR2(256)   NOT NULL,
  spif                  LONG            NOT NULL,
  clearance             LONG,
  clearanceFormat       VARCHAR2(16),
  defaultLabel          LONG,
  defaultLabelFormat    VARCHAR2(16),
  isDefault             INTEGER         DEFAULT 0 NOT NULL,
  CONSTRAINT ofSpiffingPolicy_pk PRIMARY KEY (policyID)
);

CREATE TABLE ofSpiffingClearance (
  clearanceID           VARCHAR2(40)    NOT NULL,
  domain                VARCHAR2(256)   NOT NULL,
  policyID              VARCHAR2(128)   NOT NULL,
  clearance             LONG            NOT NULL,
  format                VARCHAR2(16)    NOT NULL,
  CONSTRAINT ofSpiffingClearance_pk PRIMARY KEY (clearanceID)
);

INSERT INTO ofVersion (name, version) VALUES ('spiffing', 1);

commit;
