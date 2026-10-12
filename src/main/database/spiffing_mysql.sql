CREATE TABLE ofSpiffingCatalog (
  entryID               VARCHAR(40)     NOT NULL,
  entryName             VARCHAR(256)    NOT NULL,
  selector              VARCHAR(256),
  format                VARCHAR(16)     NOT NULL,
  label                 TEXT            NOT NULL,
  isDefault             INTEGER         NOT NULL,
  PRIMARY KEY (entryID)
) ENGINE=InnoDB;

CREATE TABLE ofSpiffingPolicy (
  policyID              VARCHAR(128)    NOT NULL,
  policyName            VARCHAR(256)    NOT NULL,
  spif                  TEXT            NOT NULL,
  clearance             TEXT,
  clearanceFormat       VARCHAR(16),
  defaultLabel          TEXT,
  defaultLabelFormat    VARCHAR(16),
  isDefault             INTEGER         NOT NULL DEFAULT 0,
  PRIMARY KEY (policyID)
) ENGINE=InnoDB;

CREATE TABLE ofSpiffingClearance (
  clearanceID           VARCHAR(40)     NOT NULL,
  domain                VARCHAR(256)    NOT NULL,
  policyID              VARCHAR(128)    NOT NULL,
  clearance             TEXT            NOT NULL,
  format                VARCHAR(16)     NOT NULL,
  PRIMARY KEY (clearanceID)
) ENGINE=InnoDB;

INSERT INTO ofVersion (name, version) VALUES ('spiffing', 1);
