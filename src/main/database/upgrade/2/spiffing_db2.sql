-- Adds room clearance and room label storage: ofSpiffingRoomClearance holds the clearance(s) that
-- gate labels of messages entering a room, and ofSpiffingRoomLabel holds a room's own ambient
-- classification (checked against a joining user's/peer's clearance) plus its optional, separately
-- configurable default label used to stamp the room's own unlabelled messages.

CREATE TABLE ofSpiffingRoomClearance (
  roomJID               VARCHAR(255)    NOT NULL,
  policyID              VARCHAR(128)    NOT NULL,
  clearance             CLOB            NOT NULL,
  format                VARCHAR(16)     NOT NULL,
  CONSTRAINT ofSpiffingRoomClearance_pk PRIMARY KEY (roomJID, policyID)
);

CREATE TABLE ofSpiffingRoomLabel (
  roomJID               VARCHAR(255)    NOT NULL,
  policyID              VARCHAR(128)    NOT NULL,
  label                 CLOB            NOT NULL,
  labelFormat           VARCHAR(16)     NOT NULL,
  defaultLabel          CLOB,
  defaultLabelFormat    VARCHAR(16),
  CONSTRAINT ofSpiffingRoomLabel_pk PRIMARY KEY (roomJID)
);

UPDATE ofVersion SET version = 2 WHERE name = 'spiffing';
