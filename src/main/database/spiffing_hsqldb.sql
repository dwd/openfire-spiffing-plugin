CREATE TABLE ofSpiffingCatalog (
  entryID               VARCHAR(40)     NOT NULL,
  entryName             VARCHAR(256)    NOT NULL,
  selector              VARCHAR(256),
  format                VARCHAR(16)     NOT NULL,
  label                 LONGVARCHAR     NOT NULL,
  isDefault             INTEGER         NOT NULL,
  CONSTRAINT ofSpiffingCatalog_pk PRIMARY KEY (entryID)
);

CREATE TABLE ofSpiffingPolicy (
  policyID              VARCHAR(128)    NOT NULL,
  policyName            VARCHAR(256)    NOT NULL,
  spif                  LONGVARCHAR     NOT NULL,
  clearance             LONGVARCHAR,
  clearanceFormat       VARCHAR(16),
  defaultLabel          LONGVARCHAR,
  defaultLabelFormat    VARCHAR(16),
  isDefault             INTEGER         DEFAULT 0 NOT NULL,
  CONSTRAINT ofSpiffingPolicy_pk PRIMARY KEY (policyID)
);

CREATE TABLE ofSpiffingClearance (
  clearanceID           VARCHAR(40)     NOT NULL,
  domain                VARCHAR(256)    NOT NULL,
  policyID              VARCHAR(128)    NOT NULL,
  clearance             LONGVARCHAR     NOT NULL,
  format                VARCHAR(16)     NOT NULL,
  CONSTRAINT ofSpiffingClearance_pk PRIMARY KEY (clearanceID)
);

CREATE TABLE ofSpiffingRoomClearance (
  roomJID               VARCHAR(255)    NOT NULL,
  policyID              VARCHAR(128)    NOT NULL,
  clearance             LONGVARCHAR     NOT NULL,
  format                VARCHAR(16)     NOT NULL,
  CONSTRAINT ofSpiffingRoomClearance_pk PRIMARY KEY (roomJID, policyID)
);

CREATE TABLE ofSpiffingRoomLabel (
  roomJID               VARCHAR(255)    NOT NULL,
  policyID              VARCHAR(128)    NOT NULL,
  label                 LONGVARCHAR     NOT NULL,
  labelFormat           VARCHAR(16)     NOT NULL,
  defaultLabel          LONGVARCHAR,
  defaultLabelFormat    VARCHAR(16),
  CONSTRAINT ofSpiffingRoomLabel_pk PRIMARY KEY (roomJID)
);

INSERT INTO ofVersion (name, version) VALUES ('spiffing', 2);
