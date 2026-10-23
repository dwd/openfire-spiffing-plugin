-- Adds room clearance and room label storage: ofSpiffingRoomClearance holds the clearance(s) that
-- gate labels of messages entering a room, and ofSpiffingRoomLabel holds a room's own ambient
-- classification (checked against a joining user's/peer's clearance) plus its optional, separately
-- configurable default label used to stamp the room's own unlabelled messages.

CREATE TABLE ofSpiffingRoomClearance (
  roomJID               VARCHAR2(255)   NOT NULL,
  policyID              VARCHAR2(128)   NOT NULL,
  clearance             LONG            NOT NULL,
  format                VARCHAR2(16)    NOT NULL,
  CONSTRAINT ofSpiffingRoomClearance_pk PRIMARY KEY (roomJID, policyID)
);

CREATE TABLE ofSpiffingRoomLabel (
  roomJID               VARCHAR2(255)   NOT NULL,
  policyID              VARCHAR2(128)   NOT NULL,
  label                 LONG            NOT NULL,
  labelFormat           VARCHAR2(16)    NOT NULL,
  defaultLabel          CLOB,
  defaultLabelFormat    VARCHAR2(16),
  CONSTRAINT ofSpiffingRoomLabel_pk PRIMARY KEY (roomJID)
);

UPDATE ofVersion SET version = 2 WHERE name = 'spiffing';

commit;
