CREATE TABLE ofSpiffingCatalog (
  entryID               NVARCHAR(40)    NOT NULL,
  entryName             NVARCHAR(256)   NOT NULL,
  selector              NVARCHAR(256),
  format                NVARCHAR(16)    NOT NULL,
  label                 NTEXT           NOT NULL,
  isDefault             INTEGER         NOT NULL,
  CONSTRAINT ofSpiffingCatalog_pk PRIMARY KEY (entryID)
);

CREATE TABLE ofSpiffingPolicy (
  policyID              NVARCHAR(128)   NOT NULL,
  policyName            NVARCHAR(256)   NOT NULL,
  spif                  NTEXT           NOT NULL,
  clearance             NTEXT,
  clearanceFormat       NVARCHAR(16),
  defaultLabel          NTEXT,
  defaultLabelFormat    NVARCHAR(16),
  isDefault             INTEGER         NOT NULL DEFAULT 0,
  CONSTRAINT ofSpiffingPolicy_pk PRIMARY KEY (policyID)
);

CREATE TABLE ofSpiffingClearance (
  clearanceID           NVARCHAR(40)    NOT NULL,
  domain                NVARCHAR(256)   NOT NULL,
  policyID              NVARCHAR(128)   NOT NULL,
  clearance             NTEXT           NOT NULL,
  format                NVARCHAR(16)    NOT NULL,
  CONSTRAINT ofSpiffingClearance_pk PRIMARY KEY (clearanceID)
);

INSERT INTO ofVersion (name, version) VALUES ('spiffing', 1);
