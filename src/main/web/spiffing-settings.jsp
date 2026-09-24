<%@ page contentType="text/html; charset=UTF-8"
    import="org.jivesoftware.openfire.XMPPServer,
            org.igniterealtime.openfire.spiffing.SpiffingPlugin,
            org.igniterealtime.openfire.spiffing.Settings,
            org.igniterealtime.openfire.spiffing.SettingsForm,
            java.util.HashMap,
            org.igniterealtime.openfire.spiffing.LabelFormat,
            org.igniterealtime.openfire.spiffing.EnforcementMode,
            org.jivesoftware.util.CookieUtils,
            org.jivesoftware.util.StringUtils" %>
<%@ taglib uri="admin" prefix="admin" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%
    request.setCharacterEncoding("UTF-8");
    final SpiffingPlugin plugin = (SpiffingPlugin) XMPPServer.getInstance().getPluginManager().getPluginByName("Spiffing").orElseThrow();
    final Settings saved = plugin.getSettings();
    String policy = saved == null ? "" : saved.policy();
    String clearance = saved == null ? "" : saved.clearance();
    String label = saved == null ? "" : saved.defaultLabel();
    String clearanceFormat = saved == null ? "XML" : saved.clearanceFormat().name();
    String labelFormat = saved == null ? "XML" : saved.labelFormat().name();
    String outputFormat = saved == null ? "ESS" : saved.outputFormat().name();
    String enforcementMode = saved == null ? EnforcementMode.WARN.name() : saved.enforcementMode().name();
    boolean stripDefaultLabelForFederation = saved != null && saved.stripDefaultLabelForFederation();
    String peerClearance = saved == null ? "" : saved.peerClearance();
    String peerClearanceFormat = saved == null ? "ESS" : saved.peerClearanceFormat().name();
    String error = null;
    if ("POST".equals(request.getMethod())) {
        final Cookie csrfCookie = CookieUtils.getCookie(request, "csrf");
        final String csrfParam = request.getParameter("csrf");
        if (csrfCookie == null || csrfParam == null || !csrfCookie.getValue().equals(csrfParam)) {
            error = "The form expired. Please try again.";
        } else {
            policy = request.getParameter("policy");
            clearance = request.getParameter("clearance");
            label = request.getParameter("label");
            clearanceFormat = request.getParameter("clearanceFormat");
            labelFormat = request.getParameter("labelFormat");
            outputFormat = request.getParameter("outputFormat");
            enforcementMode = request.getParameter("enforcementMode");
            // A checkbox is absent from the submission entirely when unchecked.
            stripDefaultLabelForFederation = request.getParameter("stripDefaultLabelForFederation") != null;
            peerClearance = request.getParameter("peerClearance");
            peerClearanceFormat = request.getParameter("peerClearanceFormat");
            try {
                final HashMap<String, String> fields = new HashMap<>();
                fields.put("policy", policy);
                fields.put("clearance", clearance);
                fields.put("label", label);
                fields.put("clearanceFormat", clearanceFormat);
                fields.put("labelFormat", labelFormat);
                fields.put("outputFormat", outputFormat);
                fields.put("enforcementMode", enforcementMode);
                fields.put("stripDefaultLabelForFederation", Boolean.toString(stripDefaultLabelForFederation));
                fields.put("peerClearance", peerClearance);
                fields.put("peerClearanceFormat", peerClearanceFormat);
                SettingsForm.save(request.getMethod(), csrfCookie.getValue(), csrfParam, fields, plugin::save);
                response.sendRedirect("spiffing-settings.jsp?saved=true");
                return;
            } catch (IllegalArgumentException e) {
                error = "Settings were not saved. Check the documents and formats, and ensure the default label is valid under the policy and allowed by the clearance.";
            } catch (RuntimeException e) {
                error = "Settings could not be saved. Check the server configuration and try again.";
            }
        }
    }
    final String csrf = StringUtils.randomString(32);
    CookieUtils.setCookie(request, response, "csrf", csrf, -1);
    pageContext.setAttribute("csrf", csrf);
    pageContext.setAttribute("error", error);
    pageContext.setAttribute("configured", plugin.isConfigured());
    pageContext.setAttribute("corrupted", plugin.isCorrupted());
    pageContext.setAttribute("policy", policy);
    pageContext.setAttribute("clearance", clearance);
    pageContext.setAttribute("label", label);
    pageContext.setAttribute("clearanceFormat", clearanceFormat);
    pageContext.setAttribute("labelFormat", labelFormat);
    pageContext.setAttribute("outputFormat", outputFormat);
    pageContext.setAttribute("enforcementMode", enforcementMode);
    pageContext.setAttribute("stripDefaultLabelForFederation", stripDefaultLabelForFederation);
    pageContext.setAttribute("peerClearance", peerClearance);
    pageContext.setAttribute("peerClearanceFormat", peerClearanceFormat);
    pageContext.setAttribute("formats", LabelFormat.values());
    pageContext.setAttribute("enforcementModes", EnforcementMode.values());
%>
<html>
<head><title>Spiffing security labels</title><meta name="pageID" content="spiffing-settings"/></head>
<body>
<c:if test="${not empty error}"><admin:infoBox type="error"><c:out value="${error}"/></admin:infoBox></c:if>
<c:if test="${param.saved eq 'true' and empty error}"><admin:infoBox type="success">Settings saved.</admin:infoBox></c:if>
<c:choose>
    <c:when test="${configured}"><p>Inbound messages are checked against the server clearance. Unlabelled messages receive the default label.</p></c:when>
    <c:when test="${corrupted}"><admin:infoBox type="warning">Stored settings are incomplete or corrupted, so inbound messages are blocked until a valid configuration is saved.</admin:infoBox></c:when>
    <c:otherwise><admin:infoBox type="info">The plugin is not configured, so it is not active: no policy, default label, or server clearance has been saved yet. Messages pass through unaffected until settings are saved here.</admin:infoBox></c:otherwise>
</c:choose>
<c:if test="${configured}">
    <c:choose>
        <c:when test="${enforcementMode eq 'ENFORCE'}"><p>Enforcement mode: messages that fail the label check are rejected.</p></c:when>
        <c:otherwise><admin:infoBox type="info">Warn mode: messages that fail the label check are logged and let through unchanged.</admin:infoBox></c:otherwise>
    </c:choose>
</c:if>
<p>Provide an Open XML SPIF policy, a server clearance, and a default label belonging to that policy.
    XML means Spiffy XML; NATO means NATO XML; ESS means base64-encoded ASN.1 (an RFC 5912 clearance or ESS label).
    Paste the label payload itself, without the XEP-0258 envelope. All fields are required.</p>
<form action="spiffing-settings.jsp" method="post">
    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
    <admin:contentBox title="Server policy and clearance">
        <p><label for="policy">Open XML SPIF policy (maximum 1 MiB of text)</label></p>
        <textarea id="policy" name="policy" cols="100" rows="18" maxlength="1048576" required><c:out value="${policy}"/></textarea>
        <p><label for="clearanceFormat">Clearance format</label>
            <select id="clearanceFormat" name="clearanceFormat">
                <c:forEach var="format" items="${formats}"><option value="${format}" ${format eq clearanceFormat ? 'selected' : ''}>${format}</option></c:forEach>
            </select></p>
        <p><label for="clearance">Server clearance (maximum 64 KiB of text)</label></p>
        <textarea id="clearance" name="clearance" cols="100" rows="10" maxlength="65536" required><c:out value="${clearance}"/></textarea>
    </admin:contentBox>
    <admin:contentBox title="Default message label">
        <p><label for="labelFormat">Input format</label>
            <select id="labelFormat" name="labelFormat">
                <c:forEach var="format" items="${formats}"><option value="${format}" ${format eq labelFormat ? 'selected' : ''}>${format}</option></c:forEach>
            </select></p>
        <p><label for="label">Default label (maximum 64 KiB of text)</label></p>
        <textarea id="label" name="label" cols="100" rows="10" maxlength="65536" required><c:out value="${label}"/></textarea>
        <p><label for="outputFormat">Format for stamping messages</label>
            <select id="outputFormat" name="outputFormat">
                <c:forEach var="format" items="${formats}"><option value="${format}" ${format eq outputFormat ? 'selected' : ''}>${format}</option></c:forEach>
            </select></p>
        <p>The default label must pass both policy validation and the server clearance check before these settings can be saved.</p>
    </admin:contentBox>
    <admin:contentBox title="Enforcement">
        <p><label for="enforcementMode">On a failed label check</label>
            <select id="enforcementMode" name="enforcementMode">
                <c:forEach var="mode" items="${enforcementModes}"><option value="${mode}" ${mode eq enforcementMode ? 'selected' : ''}>${mode}</option></c:forEach>
            </select></p>
        <p>Warn logs the failure and lets the message through unchanged. Enforce rejects the message with an error. Warn is the safe default for staged rollout.</p>
        <p><label for="stripDefaultLabelForFederation">
            <input type="checkbox" id="stripDefaultLabelForFederation" name="stripDefaultLabelForFederation" ${stripDefaultLabelForFederation ? 'checked' : ''}/>
            Strip the default label before sending a message to another server</label></p>
        <p>When enabled, an outbound message whose label's display marking matches the default label's is stripped before it
            leaves for a remote server, so the default is not gratuitously exposed to other domains. A label with a different
            or absent display marking is never touched. Off by default.</p>
    </admin:contentBox>
    <admin:contentBox title="Peer clearance">
        <p><label for="peerClearanceFormat">Peer clearance format</label>
            <select id="peerClearanceFormat" name="peerClearanceFormat">
                <c:forEach var="format" items="${formats}"><option value="${format}" ${format eq peerClearanceFormat ? 'selected' : ''}>${format}</option></c:forEach>
            </select></p>
        <p><label for="peerClearance">Peer clearance (maximum 64 KiB of text, optional)</label></p>
        <textarea id="peerClearance" name="peerClearance" cols="100" rows="10" maxlength="65536"><c:out value="${peerClearance}"/></textarea>
        <p>Optional. When set, a message's effective label is also checked against this clearance on ingress
            (for messages arriving from another server) and on egress (for messages leaving to another server),
            in addition to the server clearance check above, following the same warn/enforce setting. Leave blank
            to skip peer-clearance checking entirely.</p>
    </admin:contentBox>
    <input type="submit" value="Save settings"/>
</form>
</body>
</html>
