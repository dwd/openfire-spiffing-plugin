<%@ page contentType="text/html; charset=UTF-8"
    import="org.jivesoftware.openfire.XMPPServer,
            org.igniterealtime.openfire.spiffing.SpiffingPlugin,
            org.igniterealtime.openfire.spiffing.CatalogEntry,
            org.igniterealtime.openfire.spiffing.CatalogForm,
            org.igniterealtime.openfire.spiffing.LabelFormat,
            java.util.HashMap,
            org.jivesoftware.util.CookieUtils,
            org.jivesoftware.util.StringUtils" %>
<%@ taglib uri="admin" prefix="admin" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%
    request.setCharacterEncoding("UTF-8");
    final SpiffingPlugin plugin = (SpiffingPlugin) XMPPServer.getInstance().getPluginManager().getPluginByName("Spiffing").orElseThrow();
    String error = null;
    String success = null;
    if ("POST".equals(request.getMethod())) {
        final Cookie csrfCookie = CookieUtils.getCookie(request, "csrf");
        final String csrfParam = request.getParameter("csrf");
        final String action = request.getParameter("action");
        try {
            if ("remove".equals(action)) {
                CatalogForm.remove(request.getMethod(), csrfCookie == null ? null : csrfCookie.getValue(), csrfParam,
                    request.getParameter("id"), plugin.catalogService());
                success = "Catalogue entry removed.";
            } else {
                final HashMap<String, String> fields = new HashMap<>();
                fields.put("name", request.getParameter("name"));
                fields.put("selector", request.getParameter("selector"));
                fields.put("format", request.getParameter("format"));
                fields.put("label", request.getParameter("label"));
                fields.put("isDefault", request.getParameter("isDefault"));
                CatalogForm.add(request.getMethod(), csrfCookie == null ? null : csrfCookie.getValue(), csrfParam,
                    fields, plugin.catalogService());
                success = "Catalogue entry added.";
            }
        } catch (IllegalArgumentException e) {
            error = e.getMessage() != null ? e.getMessage() : "The catalogue entry was not saved.";
        } catch (RuntimeException e) {
            error = "The catalogue could not be updated. Check the server configuration and try again.";
        }
    }
    final String csrf = StringUtils.randomString(32);
    CookieUtils.setCookie(request, response, "csrf", csrf, -1);
    pageContext.setAttribute("csrf", csrf);
    pageContext.setAttribute("error", error);
    pageContext.setAttribute("success", success);
    pageContext.setAttribute("configured", plugin.isConfigured());
    pageContext.setAttribute("entries", plugin.catalogService().entries());
    pageContext.setAttribute("formats", LabelFormat.values());
%>
<html>
<head><title>Spiffing label catalogue</title><meta name="pageID" content="spiffing-catalog"/></head>
<body>
<c:if test="${not empty error}"><admin:infoBox type="error"><c:out value="${error}"/></admin:infoBox></c:if>
<c:if test="${not empty success and empty error}"><admin:infoBox type="success"><c:out value="${success}"/></admin:infoBox></c:if>
<c:if test="${not configured}"><admin:infoBox type="warning">Save a valid policy and clearance on the Spiffing security labels page before adding catalogue entries.</admin:infoBox></c:if>
<p>Catalogue entries are named labels offered to clients via XEP-0258 label catalogue discovery
    (<code>urn:xmpp:sec-label:catalog:2</code>). This is independent from the default label used to stamp
    unlabelled messages. Only local clients may request the catalogue.</p>
<admin:contentBox title="Current catalogue">
    <c:choose>
        <c:when test="${empty entries}"><p>No catalogue entries have been added yet.</p></c:when>
        <c:otherwise>
            <table class="jive-table">
                <thead>
                    <tr><th>Name</th><th>Selector</th><th>Format</th><th>Default</th><th></th></tr>
                </thead>
                <tbody>
                    <c:forEach var="entry" items="${entries}">
                        <tr>
                            <td><c:out value="${entry.name()}"/></td>
                            <td><c:out value="${entry.selector()}"/></td>
                            <td><c:out value="${entry.format()}"/></td>
                            <td><c:out value="${entry.isDefault() ? 'Yes' : ''}"/></td>
                            <td>
                                <form action="spiffing-catalog.jsp" method="post" style="display:inline">
                                    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
                                    <input type="hidden" name="action" value="remove"/>
                                    <input type="hidden" name="id" value="<c:out value='${entry.id()}'/>"/>
                                    <input type="submit" value="Remove"/>
                                </form>
                            </td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
        </c:otherwise>
    </c:choose>
</admin:contentBox>
<admin:contentBox title="Add a catalogue entry">
    <form action="spiffing-catalog.jsp" method="post">
        <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
        <input type="hidden" name="action" value="add"/>
        <p><label for="name">Name</label></p>
        <input type="text" id="name" name="name" size="60" maxlength="256" required/>
        <p><label for="selector">Selector (optional, e.g. <code>Classified|SECRET</code>)</label></p>
        <input type="text" id="selector" name="selector" size="60" maxlength="256"/>
        <p><label for="format">Format</label>
            <select id="format" name="format">
                <c:forEach var="format" items="${formats}"><option value="${format}">${format}</option></c:forEach>
            </select></p>
        <p><label for="label">Label (maximum 64 KiB of text)</label></p>
        <textarea id="label" name="label" cols="100" rows="10" maxlength="65536" required></textarea>
        <p><label><input type="checkbox" name="isDefault" value="true"/> Make this the catalogue default entry</label></p>
        <p>The label must pass both policy validation and the server clearance check before it can be added.</p>
        <input type="submit" value="Add entry"/>
    </form>
</admin:contentBox>
</body>
</html>
