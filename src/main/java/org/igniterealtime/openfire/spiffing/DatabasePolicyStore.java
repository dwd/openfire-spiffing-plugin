package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.database.DbConnectionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists security policies and peer clearances in the {@code ofSpiffingPolicy} and {@code ofSpiffingClearance} tables.
 */
final class DatabasePolicyStore implements PolicyStore {
    private static final String LIST_POLICIES = "SELECT policyID, policyName, spif, clearance, clearanceFormat, defaultLabel, defaultLabelFormat, isDefault FROM ofSpiffingPolicy ORDER BY policyName";
    private static final String GET_POLICY = "SELECT policyID, policyName, spif, clearance, clearanceFormat, defaultLabel, defaultLabelFormat, isDefault FROM ofSpiffingPolicy WHERE policyID = ?";
    private static final String INSERT_POLICY = "INSERT INTO ofSpiffingPolicy (policyID, policyName, spif, clearance, clearanceFormat, defaultLabel, defaultLabelFormat, isDefault) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE_POLICY = "UPDATE ofSpiffingPolicy SET policyName = ?, spif = ?, clearance = ?, clearanceFormat = ?, defaultLabel = ?, defaultLabelFormat = ?, isDefault = ? WHERE policyID = ?";
    private static final String DELETE_POLICY = "DELETE FROM ofSpiffingPolicy WHERE policyID = ?";
    private static final String CLEAR_DEFAULT_POLICY = "UPDATE ofSpiffingPolicy SET isDefault = 0";
    private static final String SET_DEFAULT_POLICY = "UPDATE ofSpiffingPolicy SET isDefault = 1 WHERE policyID = ?";

    private static final String LIST_PEER_CLEARANCES = "SELECT clearanceID, domain, policyID, clearance, format FROM ofSpiffingClearance ORDER BY domain, policyID";
    private static final String GET_PEER_CLEARANCE = "SELECT clearanceID, domain, policyID, clearance, format FROM ofSpiffingClearance WHERE clearanceID = ?";
    private static final String INSERT_PEER_CLEARANCE = "INSERT INTO ofSpiffingClearance (clearanceID, domain, policyID, clearance, format) VALUES (?, ?, ?, ?, ?)";
    private static final String UPDATE_PEER_CLEARANCE = "UPDATE ofSpiffingClearance SET domain = ?, policyID = ?, clearance = ?, format = ? WHERE clearanceID = ?";
    private static final String DELETE_PEER_CLEARANCE = "DELETE FROM ofSpiffingClearance WHERE clearanceID = ?";
    private static final String DELETE_PEER_CLEARANCES_FOR_POLICY = "DELETE FROM ofSpiffingClearance WHERE policyID = ?";

    @Override
    public List<SecurityPolicy> listPolicies() {
        List<SecurityPolicy> policies = new ArrayList<>();
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(LIST_POLICIES);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                policies.add(mapPolicy(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read Spiffing policies from database.", e);
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
        return policies;
    }

    @Override
    public Optional<SecurityPolicy> getPolicy(String id) {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(GET_POLICY);
            pstmt.setString(1, id);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return Optional.of(mapPolicy(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read Spiffing policy from database: " + id, e);
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
    }

    @Override
    public void savePolicy(SecurityPolicy policy) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            boolean exists = getPolicy(policy.id()).isPresent();
            if (exists) {
                pstmt = con.prepareStatement(UPDATE_POLICY);
                pstmt.setString(1, policy.name());
                pstmt.setString(2, policy.spif());
                pstmt.setString(3, policy.clearance());
                pstmt.setString(4, policy.clearanceFormat() != null ? policy.clearanceFormat().name() : null);
                pstmt.setString(5, policy.defaultLabel());
                pstmt.setString(6, policy.defaultLabelFormat() != null ? policy.defaultLabelFormat().name() : null);
                pstmt.setInt(7, policy.isDefault() ? 1 : 0);
                pstmt.setString(8, policy.id());
            } else {
                pstmt = con.prepareStatement(INSERT_POLICY);
                pstmt.setString(1, policy.id());
                pstmt.setString(2, policy.name());
                pstmt.setString(3, policy.spif());
                pstmt.setString(4, policy.clearance());
                pstmt.setString(5, policy.clearanceFormat() != null ? policy.clearanceFormat().name() : null);
                pstmt.setString(6, policy.defaultLabel());
                pstmt.setString(7, policy.defaultLabelFormat() != null ? policy.defaultLabelFormat().name() : null);
                pstmt.setInt(8, policy.isDefault() ? 1 : 0);
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save Spiffing policy: " + policy.id(), e);
        } finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    @Override
    public void deletePolicy(String id) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(DELETE_PEER_CLEARANCES_FOR_POLICY);
            pstmt.setString(1, id);
            pstmt.executeUpdate();
            DbConnectionManager.closeStatement(pstmt);

            pstmt = con.prepareStatement(DELETE_POLICY);
            pstmt.setString(1, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete Spiffing policy: " + id, e);
        } finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    @Override
    public void setDefaultPolicy(String id) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(CLEAR_DEFAULT_POLICY);
            pstmt.executeUpdate();
            DbConnectionManager.closeStatement(pstmt);

            if (id != null && !id.isBlank()) {
                pstmt = con.prepareStatement(SET_DEFAULT_POLICY);
                pstmt.setString(1, id);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot set default Spiffing policy.", e);
        } finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    @Override
    public List<PeerClearance> listPeerClearances() {
        List<PeerClearance> list = new ArrayList<>();
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(LIST_PEER_CLEARANCES);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                list.add(new PeerClearance(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    LabelFormat.valueOf(rs.getString(5))
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read Spiffing peer clearances.", e);
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
        return list;
    }

    @Override
    public void savePeerClearance(PeerClearance peerClearance) {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(GET_PEER_CLEARANCE);
            pstmt.setString(1, peerClearance.id());
            rs = pstmt.executeQuery();
            boolean exists = rs.next();
            DbConnectionManager.closeStatement(pstmt);

            if (exists) {
                pstmt = con.prepareStatement(UPDATE_PEER_CLEARANCE);
                pstmt.setString(1, peerClearance.domain());
                pstmt.setString(2, peerClearance.policyId());
                pstmt.setString(3, peerClearance.clearance());
                pstmt.setString(4, peerClearance.format().name());
                pstmt.setString(5, peerClearance.id());
            } else {
                pstmt = con.prepareStatement(INSERT_PEER_CLEARANCE);
                pstmt.setString(1, peerClearance.id());
                pstmt.setString(2, peerClearance.domain());
                pstmt.setString(3, peerClearance.policyId());
                pstmt.setString(4, peerClearance.clearance());
                pstmt.setString(5, peerClearance.format().name());
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save Spiffing peer clearance: " + peerClearance.id(), e);
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
    }

    @Override
    public void deletePeerClearance(String id) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(DELETE_PEER_CLEARANCE);
            pstmt.setString(1, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete Spiffing peer clearance: " + id, e);
        } finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    private static SecurityPolicy mapPolicy(ResultSet rs) throws SQLException {
        String id = rs.getString(1);
        String name = rs.getString(2);
        String spif = rs.getString(3);
        String clearance = rs.getString(4);
        String clearanceFormatStr = rs.getString(5);
        LabelFormat clearanceFormat = clearanceFormatStr != null ? LabelFormat.valueOf(clearanceFormatStr) : null;
        String defaultLabel = rs.getString(6);
        String defaultLabelFormatStr = rs.getString(7);
        LabelFormat defaultLabelFormat = defaultLabelFormatStr != null ? LabelFormat.valueOf(defaultLabelFormatStr) : null;
        boolean isDefault = rs.getInt(8) != 0;
        return new SecurityPolicy(id, name, spif, clearance, clearanceFormat, defaultLabel, defaultLabelFormat, isDefault);
    }
}
