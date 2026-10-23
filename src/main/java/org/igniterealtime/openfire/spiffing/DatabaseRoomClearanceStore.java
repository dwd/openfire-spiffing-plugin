package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.database.DbConnectionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists room clearances in the {@code ofSpiffingRoomClearance} table (see {@code src/main/database}),
 * keyed by {@code (roomJID, policyID)}, mirroring {@link DatabasePolicyStore}'s peer-clearance handling.
 */
final class DatabaseRoomClearanceStore implements RoomClearanceStore {
    private static final String LIST = "SELECT roomJID, policyID, clearance, format FROM ofSpiffingRoomClearance WHERE roomJID = ? ORDER BY policyID";
    private static final String EXISTS = "SELECT 1 FROM ofSpiffingRoomClearance WHERE roomJID = ? AND policyID = ?";
    private static final String INSERT = "INSERT INTO ofSpiffingRoomClearance (roomJID, policyID, clearance, format) VALUES (?, ?, ?, ?)";
    private static final String UPDATE = "UPDATE ofSpiffingRoomClearance SET clearance = ?, format = ? WHERE roomJID = ? AND policyID = ?";
    private static final String DELETE = "DELETE FROM ofSpiffingRoomClearance WHERE roomJID = ? AND policyID = ?";

    @Override
    public List<RoomClearance> list(String roomJid) {
        List<RoomClearance> result = new ArrayList<>();
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(LIST);
            pstmt.setString(1, roomJid);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add(new RoomClearance(rs.getString(1), rs.getString(2), rs.getString(3), LabelFormat.valueOf(rs.getString(4))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read Spiffing room clearances for room: " + roomJid, e);
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
        return result;
    }

    @Override
    public void save(RoomClearance roomClearance) {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(EXISTS);
            pstmt.setString(1, roomClearance.roomJid());
            pstmt.setString(2, roomClearance.policyId());
            rs = pstmt.executeQuery();
            boolean exists = rs.next();
            DbConnectionManager.closeStatement(pstmt);

            if (exists) {
                pstmt = con.prepareStatement(UPDATE);
                pstmt.setString(1, roomClearance.clearance());
                pstmt.setString(2, roomClearance.format().name());
                pstmt.setString(3, roomClearance.roomJid());
                pstmt.setString(4, roomClearance.policyId());
            } else {
                pstmt = con.prepareStatement(INSERT);
                pstmt.setString(1, roomClearance.roomJid());
                pstmt.setString(2, roomClearance.policyId());
                pstmt.setString(3, roomClearance.clearance());
                pstmt.setString(4, roomClearance.format().name());
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save Spiffing room clearance for room: " + roomClearance.roomJid(), e);
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
    }

    @Override
    public void delete(String roomJid, String policyId) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(DELETE);
            pstmt.setString(1, roomJid);
            pstmt.setString(2, policyId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete Spiffing room clearance for room: " + roomJid, e);
        } finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }
}
