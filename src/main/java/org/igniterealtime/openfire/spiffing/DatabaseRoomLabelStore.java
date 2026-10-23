package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.database.DbConnectionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Persists each room's label (and optional distinct default label) in the {@code ofSpiffingRoomLabel}
 * table (see {@code src/main/database}), one row per room JID.
 */
final class DatabaseRoomLabelStore implements RoomLabelStore {
    private static final String GET = "SELECT roomJID, policyID, label, labelFormat, defaultLabel, defaultLabelFormat FROM ofSpiffingRoomLabel WHERE roomJID = ?";
    private static final String INSERT = "INSERT INTO ofSpiffingRoomLabel (roomJID, policyID, label, labelFormat, defaultLabel, defaultLabelFormat) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String UPDATE = "UPDATE ofSpiffingRoomLabel SET policyID = ?, label = ?, labelFormat = ?, defaultLabel = ?, defaultLabelFormat = ? WHERE roomJID = ?";
    private static final String DELETE = "DELETE FROM ofSpiffingRoomLabel WHERE roomJID = ?";

    @Override
    public Optional<RoomLabel> get(String roomJid) {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(GET);
            pstmt.setString(1, roomJid);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return Optional.of(map(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read Spiffing room label for room: " + roomJid, e);
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
    }

    @Override
    public void save(RoomLabel roomLabel) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            boolean exists = get(roomLabel.roomJid()).isPresent();
            if (exists) {
                pstmt = con.prepareStatement(UPDATE);
                pstmt.setString(1, roomLabel.policyId());
                pstmt.setString(2, roomLabel.label());
                pstmt.setString(3, roomLabel.format().name());
                pstmt.setString(4, roomLabel.defaultLabel());
                pstmt.setString(5, roomLabel.defaultLabelFormat() != null ? roomLabel.defaultLabelFormat().name() : null);
                pstmt.setString(6, roomLabel.roomJid());
            } else {
                pstmt = con.prepareStatement(INSERT);
                pstmt.setString(1, roomLabel.roomJid());
                pstmt.setString(2, roomLabel.policyId());
                pstmt.setString(3, roomLabel.label());
                pstmt.setString(4, roomLabel.format().name());
                pstmt.setString(5, roomLabel.defaultLabel());
                pstmt.setString(6, roomLabel.defaultLabelFormat() != null ? roomLabel.defaultLabelFormat().name() : null);
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save Spiffing room label for room: " + roomLabel.roomJid(), e);
        } finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    @Override
    public void delete(String roomJid) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(DELETE);
            pstmt.setString(1, roomJid);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete Spiffing room label for room: " + roomJid, e);
        } finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    private static RoomLabel map(ResultSet rs) throws SQLException {
        String roomJid = rs.getString(1);
        String policyId = rs.getString(2);
        String label = rs.getString(3);
        LabelFormat format = LabelFormat.valueOf(rs.getString(4));
        String defaultLabel = rs.getString(5);
        String defaultLabelFormatStr = rs.getString(6);
        LabelFormat defaultLabelFormat = defaultLabelFormatStr != null ? LabelFormat.valueOf(defaultLabelFormatStr) : null;
        return new RoomLabel(roomJid, policyId, label, format, defaultLabel, defaultLabelFormat);
    }
}
