package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.database.DbConnectionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists catalogue entries in the {@code ofSpiffingCatalog} table (see {@code src/main/database}).
 * Clearing the previous default and inserting a new default entry are two separate statements, not one
 * transaction; a crash between them leaves zero default entries, recoverable by re-adding one as default.
 */
final class DatabaseCatalogStore implements CatalogStore {
    private static final String LIST = "SELECT entryID, entryName, selector, format, label, isDefault FROM ofSpiffingCatalog ORDER BY entryName";
    private static final String INSERT = "INSERT INTO ofSpiffingCatalog (entryID, entryName, selector, format, label, isDefault) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String DELETE = "DELETE FROM ofSpiffingCatalog WHERE entryID = ?";
    private static final String CLEAR_DEFAULT = "UPDATE ofSpiffingCatalog SET isDefault = 0";

    @Override
    public List<CatalogEntry> list() {
        List<CatalogEntry> entries = new ArrayList<>();
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(LIST);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                entries.add(new CatalogEntry(rs.getString(1), rs.getString(2), rs.getString(3),
                    LabelFormat.valueOf(rs.getString(4)), rs.getString(5), rs.getInt(6) != 0));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read Spiffing catalogue entries.", e);
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
        return entries;
    }

    @Override
    public void add(CatalogEntry entry) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(INSERT);
            pstmt.setString(1, entry.id());
            pstmt.setString(2, entry.name());
            pstmt.setString(3, entry.selector());
            pstmt.setString(4, entry.format().name());
            pstmt.setString(5, entry.payload());
            pstmt.setInt(6, entry.isDefault() ? 1 : 0);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save the Spiffing catalogue entry.", e);
        } finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    @Override
    public void remove(String id) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(DELETE);
            pstmt.setString(1, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot remove the Spiffing catalogue entry.", e);
        } finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }

    @Override
    public void clearDefault() {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(CLEAR_DEFAULT);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update Spiffing catalogue entries.", e);
        } finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
    }
}
