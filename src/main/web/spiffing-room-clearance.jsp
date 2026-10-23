<%@ page contentType="text/html; charset=UTF-8"
    import="org.jivesoftware.openfire.XMPPServer,
            org.igniterealtime.openfire.spiffing.SpiffingPlugin,
            org.igniterealtime.openfire.spiffing.ConfigurationService,
            org.igniterealtime.openfire.spiffing.RoomClearanceService,
            org.igniterealtime.openfire.spiffing.RoomLabelService,
            org.igniterealtime.openfire.spiffing.SecurityPolicy,
            org.igniterealtime.openfire.spiffing.RoomClearance,
            org.igniterealtime.openfire.spiffing.RoomLabel,
            org.igniterealtime.openfire.spiffing.SettingsForm,
            org.igniterealtime.openfire.spiffing.LabelFormat,
            org.jivesoftware.util.CookieUtils,
            org.jivesoftware.util.ParamUtils,
            org.jivesoftware.util.StringUtils,
            java.util.HashMap,
            java.util.List,
            java.util.Map,
            java.util.Optional" %>
<%@ taglib uri="admin" prefix="admin" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%
    request.setCharacterEncoding("UTF-8");
    String roomJid = ParamUtils.getParameter(request, "roomJid");
    if (roomJid == null || roomJid.isBlank()) {
        roomJid = ParamUtils.getParameter(request, "roomJID");
    }
    if (roomJid == null || roomJid.isBlank()) {
        String roomName = ParamUtils.getParameter(request, "roomName");
        String mucName = ParamUtils.getParameter(request, "mucName");
        if (roomName != null && mucName != null) {
            roomJid = roomName + "@" + mucName;
        }
    }
    if (roomJid == null || roomJid.isBlank()) {
        response.sendRedirect("muc-room-summary.jsp");
        return;
    }
    final String finalRoomJid = roomJid.trim();

    final SpiffingPlugin plugin = (SpiffingPlugin) XMPPServer.getInstance().getPluginManager().getPluginByName("Spiffing").orElseThrow();
    final ConfigurationService configService = plugin.configurationService();
    final RoomClearanceService roomClearanceService = plugin.roomClearanceService();
    final RoomLabelService roomLabelService = plugin.roomLabelService();

    String error = null;

    if ("POST".equals(request.getMethod())) {
        final Cookie csrfCookie = CookieUtils.getCookie(request, "csrf");
        final String csrfParam = request.getParameter("csrf");
        try {
            SettingsForm.validateCsrf(request.getMethod(), csrfCookie != null ? csrfCookie.getValue() : null, csrfParam);
            final String action = request.getParameter("action");
            String policyId = request.getParameter("policyId");
            SecurityPolicy targetPolicy = policyId != null ? configService.policy(policyId).orElse(null) : null;
            io.cridland.spiffing.Spif spif = null;
            if (targetPolicy != null && targetPolicy.spif() != null) {
                try {
                    spif = new io.cridland.spiffing.Site().load(targetPolicy.spif());
                } catch (Exception ignored) {}
            }

            if ("saveRoomClearance".equals(action)) {
                final HashMap<String, String> fields = new HashMap<>();
                fields.put("roomJid", finalRoomJid);
                fields.put("policyId", policyId);
                fields.put("clearanceMode", request.getParameter("clearanceMode"));
                fields.put("clearance", request.getParameter("clearance"));
                fields.put("format", request.getParameter("clearanceFormat"));

                String[] classifications = request.getParameterValues("clearanceClassifications");
                String[] categories = request.getParameterValues("clearanceCategories");

                RoomClearance rc = SettingsForm.parseRoomClearance(
                    fields,
                    classifications != null ? List.of(classifications) : List.of(),
                    categories != null ? List.of(categories) : List.of(),
                    spif
                );
                roomClearanceService.save(rc);
                response.sendRedirect("spiffing-room-clearance.jsp?roomJid=" + java.net.URLEncoder.encode(finalRoomJid, java.nio.charset.StandardCharsets.UTF_8) + "&saved=true");
                return;
            } else if ("deleteRoomClearance".equals(action)) {
                if (policyId != null && !policyId.isBlank()) {
                    roomClearanceService.delete(finalRoomJid, policyId);
                    response.sendRedirect("spiffing-room-clearance.jsp?roomJid=" + java.net.URLEncoder.encode(finalRoomJid, java.nio.charset.StandardCharsets.UTF_8) + "&saved=true");
                    return;
                }
            } else if ("saveRoomLabel".equals(action)) {
                final HashMap<String, String> fields = new HashMap<>();
                fields.put("roomJid", finalRoomJid);
                fields.put("policyId", policyId);
                fields.put("labelMode", request.getParameter("labelMode"));
                fields.put("label", request.getParameter("label"));
                fields.put("format", request.getParameter("labelFormat"));
                boolean hasOwnDefault = ParamUtils.getBooleanParameter(request, "hasOwnDefaultLabel");
                fields.put("defaultLabelMode", request.getParameter("defaultLabelMode"));
                fields.put("defaultLabel", hasOwnDefault ? request.getParameter("defaultLabel") : null);
                fields.put("defaultLabelFormat", request.getParameter("defaultLabelFormat"));

                String labelClassification = request.getParameter("labelClassification");
                String[] labelCategories = request.getParameterValues("labelCategories");
                String defaultLabelClassification = hasOwnDefault ? request.getParameter("defaultLabelClassification") : null;
                String[] defaultLabelCategories = request.getParameterValues("defaultLabelCategories");

                RoomLabel rl = SettingsForm.parseRoomLabel(
                    fields,
                    labelClassification,
                    labelCategories != null ? List.of(labelCategories) : List.of(),
                    defaultLabelClassification,
                    defaultLabelCategories != null ? List.of(defaultLabelCategories) : List.of(),
                    spif
                );
                roomLabelService.save(rl);
                response.sendRedirect("spiffing-room-clearance.jsp?roomJid=" + java.net.URLEncoder.encode(finalRoomJid, java.nio.charset.StandardCharsets.UTF_8) + "&saved=true");
                return;
            } else if ("deleteRoomLabel".equals(action)) {
                roomLabelService.delete(finalRoomJid);
                response.sendRedirect("spiffing-room-clearance.jsp?roomJid=" + java.net.URLEncoder.encode(finalRoomJid, java.nio.charset.StandardCharsets.UTF_8) + "&saved=true");
                return;
            }
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            error = "Could not be saved: " + e.getMessage();
        }
    }

    final String csrf = StringUtils.randomString(32);
    CookieUtils.setCookie(request, response, "csrf", csrf, -1);
    pageContext.setAttribute("csrf", csrf);
    pageContext.setAttribute("error", error);
    pageContext.setAttribute("roomJid", finalRoomJid);

    List<SecurityPolicy> policies = configService.policies();
    List<RoomClearance> roomClearances = roomClearanceService.list(finalRoomJid);
    Optional<RoomLabel> roomLabel = roomLabelService.get(finalRoomJid);
    Map<String, String> policyNamesById = new HashMap<>();
    for (SecurityPolicy p : policies) {
        policyNamesById.put(p.id(), p.name());
    }

    Map<String, SettingsForm.PolicyClearanceMetadata> policyMetadataMap = new HashMap<>();
    for (SecurityPolicy p : policies) {
        SettingsForm.PolicyClearanceMetadata m = SettingsForm.inspectPolicy(p);
        if (m != null) {
            policyMetadataMap.put(p.id(), m);
        }
    }
    pageContext.setAttribute("policyMetadataMap", policyMetadataMap);

    pageContext.setAttribute("policies", policies);
    pageContext.setAttribute("roomClearances", roomClearances);
    pageContext.setAttribute("roomLabel", roomLabel.orElse(null));
    pageContext.setAttribute("policyNamesById", policyNamesById);
    pageContext.setAttribute("formats", LabelFormat.values());
%>
<html>
<head><title>Spiffing room clearance</title><meta name="pageID" content="spiffing-room-clearance"/></head>
<body>
<c:if test="${not empty error}"><admin:infoBox type="error"><c:out value="${error}"/></admin:infoBox></c:if>
<c:if test="${param.saved eq 'true' and empty error}"><admin:infoBox type="success">Saved.</admin:infoBox></c:if>

<admin:contentBox title="Clearances for room ${roomJid}">
    <p>Labels of messages entering this room are checked against these clearances, in addition to any server, peer, and user clearance checks.</p>
    <c:choose>
        <c:when test="${empty roomClearances}">
            <p>This room currently has no clearance configured for any loaded policy.</p>
        </c:when>
        <c:otherwise>
            <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                <thead>
                    <tr>
                        <th width="40%">Policy</th>
                        <th width="20%">Format</th>
                        <th width="40%">Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="rc" items="${roomClearances}">
                        <tr>
                            <td><c:out value="${empty policyNamesById[rc.policyId()] ? rc.policyId() : policyNamesById[rc.policyId()]}"/> (<code><c:out value="${rc.policyId()}"/></code>)</td>
                            <td><c:out value="${rc.format()}"/></td>
                            <td>
                                <form action="spiffing-room-clearance.jsp" method="post" style="display:inline;" onsubmit="return confirm('Delete this room clearance?');">
                                    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
                                    <input type="hidden" name="roomJid" value="<c:out value='${roomJid}'/>"/>
                                    <input type="hidden" name="action" value="deleteRoomClearance"/>
                                    <input type="hidden" name="policyId" value="<c:out value='${rc.policyId()}'/>"/>
                                    <button type="submit">Delete</button>
                                </form>
                            </td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
        </c:otherwise>
    </c:choose>
</admin:contentBox>

<c:choose>
    <c:when test="${empty policies}">
        <admin:infoBox type="info">No security policies have been configured yet. Add one under <a href="spiffing-settings.jsp">Spiffing security labels</a> before setting a room clearance.</admin:infoBox>
    </c:when>
    <c:otherwise>
        <form action="spiffing-room-clearance.jsp" method="post">
            <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
            <input type="hidden" name="roomJid" value="<c:out value='${roomJid}'/>"/>
            <input type="hidden" name="action" value="saveRoomClearance"/>
            <admin:contentBox title="Add / Update Room Clearance">
                <p><label for="policyId">Policy</label>
                    <select id="policyId" name="policyId" onchange="updateRoomClearancePolicy()">
                        <c:forEach var="p" items="${policies}">
                            <option value="${p.id()}">${p.name()} (${p.id()})</option>
                        </c:forEach>
                    </select></p>

                <div style="margin-bottom: 12px; background: #f9f9f9; padding: 10px; border: 1px solid #e5e5e5; border-radius: 4px;">
                    <label style="margin-right: 15px; font-weight: bold;">Configuration Mode:</label>
                    <label style="margin-right: 20px; cursor: pointer;">
                        <input type="radio" name="clearanceMode" id="clearanceModeBuilder" value="builder" checked="checked" onchange="toggleRoomClearanceMode()"/>
                        <strong>Interactive Builder</strong>
                    </label>
                    <label style="cursor: pointer;">
                        <input type="radio" name="clearanceMode" id="clearanceModeRaw" value="raw" onchange="toggleRoomClearanceMode()"/>
                        <strong>Raw Payload / File Upload</strong>
                    </label>
                </div>

                <p><label for="clearanceFormat">Format</label>
                    <select id="clearanceFormat" name="clearanceFormat">
                        <c:forEach var="format" items="${formats}"><option value="${format}">${format}</option></c:forEach>
                    </select></p>

                <div id="roomClearanceBuilderContainer" style="margin-bottom: 15px;">
                    <c:forEach var="p" items="${policies}" varStatus="pSt">
                        <c:set var="meta" value="${policyMetadataMap[p.id()]}"/>
                        <div id="room_clearance_policy_builder_${p.id()}" class="room-clearance-policy-builder-panel" style="${pSt.first ? '' : 'display:none;'}">
                            <c:if test="${not empty meta}">
                                <div style="margin-bottom: 12px;">
                                    <p><strong><c:out value="${p.name()}"/> &mdash; Classification Levels</strong></p>
                                    <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                        <thead><tr><th width="5%">Grant</th><th width="35%">Classification</th><th width="20%">LACV</th><th width="20%">Hierarchy</th><th width="20%">Color</th></tr></thead>
                                        <tbody>
                                            <c:forEach var="cls" items="${meta.classifications()}" varStatus="st">
                                                <tr>
                                                    <td><input type="checkbox" name="clearanceClassifications" value="${cls.lacv()}" id="room_cls_${pSt.index}_${st.index}"/></td>
                                                    <td><label for="room_cls_${pSt.index}_${st.index}"><strong><c:out value="${cls.name()}"/></strong></label></td>
                                                    <td><code><c:out value="${cls.lacv()}"/></code></td>
                                                    <td><c:out value="${cls.hierarchy()}"/></td>
                                                    <td>
                                                        <c:if test="${not empty cls.color()}"><span style="display:inline-block; width:14px; height:14px; background:${cls.color()}; border:1px solid #ccc; vertical-align:middle; margin-right:4px;"></span><c:out value="${cls.color()}"/></c:if>
                                                        <c:if test="${empty cls.color()}">-</c:if>
                                                    </td>
                                                </tr>
                                            </c:forEach>
                                        </tbody>
                                    </table>
                                </div>
                                <div style="margin-bottom: 12px;">
                                    <p><strong><c:out value="${p.name()}"/> &mdash; Security Categories</strong></p>
                                    <c:forEach var="ts" items="${meta.clearanceTagSets()}" varStatus="tsSt">
                                        <fieldset style="margin-top: 8px; margin-bottom: 10px; padding: 10px; border: 1px solid #ddd; border-radius: 4px;">
                                            <legend style="font-weight: bold; padding: 0 6px;"><c:out value="${ts.name()}"/></legend>
                                            <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                                <thead><tr><th width="5%">Grant</th><th width="45%">Category</th><th width="25%">Tag / Type</th><th width="25%">LACV</th></tr></thead>
                                                <tbody>
                                                    <c:forEach var="cat" items="${ts.categories()}" varStatus="catSt">
                                                        <tr>
                                                            <td><input type="checkbox" name="clearanceCategories" value="${cat.spec()}" id="room_cat_${pSt.index}_${tsSt.index}_${catSt.index}"/></td>
                                                            <td><label for="room_cat_${pSt.index}_${tsSt.index}_${catSt.index}"><c:out value="${cat.name()}"/></label></td>
                                                            <td><c:out value="${cat.tagName()}"/> (<em><c:out value="${cat.tagType()}"/></em>)</td>
                                                            <td><code><c:out value="${cat.lacv()}"/></code></td>
                                                        </tr>
                                                    </c:forEach>
                                                </tbody>
                                            </table>
                                        </fieldset>
                                    </c:forEach>
                                </div>
                            </c:if>
                        </div>
                    </c:forEach>
                </div>

                <div id="roomClearanceRawContainer" style="display: none; margin-bottom: 15px;">
                    <p><label for="clearance">Clearance payload (maximum 64 KiB)</label></p>
                    <textarea id="clearance" name="clearance" cols="100" rows="6" maxlength="65536"></textarea>
                </div>

                <p><input type="submit" value="Save Room Clearance"/></p>
            </admin:contentBox>
        </form>

        <admin:contentBox title="Room Label">
            <p>A room's own ambient classification. When set, joining users and peers are checked against it:
               a local user needs one of their own user clearances to authorize this label, and a remote user needs
               their domain's peer clearance; without a passing clearance, the join is denied.</p>
            <c:choose>
                <c:when test="${empty roomLabel}">
                    <p>This room currently has no label configured.</p>
                </c:when>
                <c:otherwise>
                    <p><strong>Policy:</strong> <c:out value="${empty policyNamesById[roomLabel.policyId()] ? roomLabel.policyId() : policyNamesById[roomLabel.policyId()]}"/> (<code><c:out value="${roomLabel.policyId()}"/></code>)</p>
                    <p><strong>Format:</strong> <c:out value="${roomLabel.format()}"/></p>
                    <p><strong>Default label:</strong>
                        <c:choose>
                            <c:when test="${roomLabel.hasOwnDefaultLabel()}">Configured separately (<c:out value="${roomLabel.defaultLabelFormat()}"/>)</c:when>
                            <c:otherwise>Falls back to the room label above.</c:otherwise>
                        </c:choose>
                    </p>
                    <form action="spiffing-room-clearance.jsp" method="post" onsubmit="return confirm('Delete this room label?');">
                        <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
                        <input type="hidden" name="roomJid" value="<c:out value='${roomJid}'/>"/>
                        <input type="hidden" name="action" value="deleteRoomLabel"/>
                        <button type="submit">Delete Room Label</button>
                    </form>
                </c:otherwise>
            </c:choose>
        </admin:contentBox>

        <form action="spiffing-room-clearance.jsp" method="post">
            <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
            <input type="hidden" name="roomJid" value="<c:out value='${roomJid}'/>"/>
            <input type="hidden" name="action" value="saveRoomLabel"/>
            <admin:contentBox title="Add / Update Room Label">
                <p><label for="labelPolicyId">Policy</label>
                    <select id="labelPolicyId" name="policyId" onchange="updateRoomLabelPolicy()">
                        <c:forEach var="p" items="${policies}">
                            <option value="${p.id()}">${p.name()} (${p.id()})</option>
                        </c:forEach>
                    </select></p>

                <div style="margin-bottom: 12px; background: #f9f9f9; padding: 10px; border: 1px solid #e5e5e5; border-radius: 4px;">
                    <label style="margin-right: 15px; font-weight: bold;">Configuration Mode:</label>
                    <label style="margin-right: 20px; cursor: pointer;">
                        <input type="radio" name="labelMode" id="labelModeBuilder" value="builder" checked="checked" onchange="toggleRoomLabelMode()"/>
                        <strong>Interactive Builder</strong>
                    </label>
                    <label style="cursor: pointer;">
                        <input type="radio" name="labelMode" id="labelModeRaw" value="raw" onchange="toggleRoomLabelMode()"/>
                        <strong>Raw Payload</strong>
                    </label>
                </div>

                <p><label for="labelFormat">Format</label>
                    <select id="labelFormat" name="labelFormat">
                        <c:forEach var="format" items="${formats}"><option value="${format}">${format}</option></c:forEach>
                    </select></p>

                <div id="roomLabelBuilderContainer" style="margin-bottom: 15px;">
                    <c:forEach var="p" items="${policies}" varStatus="pSt">
                        <c:set var="meta" value="${policyMetadataMap[p.id()]}"/>
                        <div id="room_label_policy_builder_${p.id()}" class="room-label-policy-builder-panel" style="${pSt.first ? '' : 'display:none;'}">
                            <c:if test="${not empty meta}">
                                <div style="margin-bottom: 12px;">
                                    <p><strong>Classification Level</strong> (select one)</p>
                                    <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                        <thead><tr><th width="5%">Select</th><th width="35%">Classification</th><th width="20%">LACV</th><th width="20%">Hierarchy</th><th width="20%">Color</th></tr></thead>
                                        <tbody>
                                            <c:forEach var="cls" items="${meta.classifications()}" varStatus="st">
                                                <tr>
                                                    <td><input type="radio" name="labelClassification" value="${cls.lacv()}" id="room_label_cls_${pSt.index}_${st.index}"/></td>
                                                    <td><label for="room_label_cls_${pSt.index}_${st.index}"><strong><c:out value="${cls.name()}"/></strong></label></td>
                                                    <td><code><c:out value="${cls.lacv()}"/></code></td>
                                                    <td><c:out value="${cls.hierarchy()}"/></td>
                                                    <td>-</td>
                                                </tr>
                                            </c:forEach>
                                        </tbody>
                                    </table>
                                </div>
                                <div style="margin-bottom: 12px;">
                                    <p><strong>Security Categories</strong></p>
                                    <c:forEach var="ts" items="${meta.labelTagSets()}" varStatus="tsSt">
                                        <fieldset style="margin-top: 8px; margin-bottom: 10px; padding: 10px; border: 1px solid #ddd; border-radius: 4px;">
                                            <legend style="font-weight: bold; padding: 0 6px;"><c:out value="${ts.name()}"/></legend>
                                            <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                                <thead><tr><th width="5%">Include</th><th width="45%">Category</th><th width="25%">Tag / Type</th><th width="25%">LACV</th></tr></thead>
                                                <tbody>
                                                    <c:forEach var="cat" items="${ts.categories()}" varStatus="catSt">
                                                        <tr>
                                                            <td><input type="checkbox" name="labelCategories" value="${cat.spec()}" id="room_label_cat_${pSt.index}_${tsSt.index}_${catSt.index}"/></td>
                                                            <td><label for="room_label_cat_${pSt.index}_${tsSt.index}_${catSt.index}"><c:out value="${cat.name()}"/></label></td>
                                                            <td><c:out value="${cat.tagName()}"/> (<em><c:out value="${cat.tagType()}"/></em>)</td>
                                                            <td><code><c:out value="${cat.lacv()}"/></code></td>
                                                        </tr>
                                                    </c:forEach>
                                                </tbody>
                                            </table>
                                        </fieldset>
                                    </c:forEach>
                                </div>
                            </c:if>
                        </div>
                    </c:forEach>
                </div>

                <div id="roomLabelRawContainer" style="display: none; margin-bottom: 15px;">
                    <p><label for="label">Label payload (maximum 64 KiB)</label></p>
                    <textarea id="label" name="label" cols="100" rows="6" maxlength="65536"></textarea>
                </div>

                <hr style="margin: 20px 0; border: 0; border-top: 1px solid #ddd;"/>
                <h4>Default Message Label for This Room</h4>
                <p>Not automatically the same as the room label above. When unset, the room label itself is used to stamp this room's unlabelled messages.</p>
                <p>
                    <label style="cursor: pointer;">
                        <input type="checkbox" name="hasOwnDefaultLabel" id="hasOwnDefaultLabel" value="true" onchange="toggleHasOwnDefaultLabel()"/>
                        This room has its own default label, different from the room label above
                    </label>
                </p>
                <div id="ownDefaultLabelContainer" style="display:none;">
                    <div style="margin-bottom: 12px; background: #f9f9f9; padding: 10px; border: 1px solid #e5e5e5; border-radius: 4px;">
                        <label style="margin-right: 15px; font-weight: bold;">Configuration Mode:</label>
                        <label style="margin-right: 20px; cursor: pointer;">
                            <input type="radio" name="defaultLabelMode" id="defaultLabelModeBuilder" value="builder" checked="checked" onchange="toggleRoomDefaultLabelMode()"/>
                            <strong>Interactive Builder</strong>
                        </label>
                        <label style="cursor: pointer;">
                            <input type="radio" name="defaultLabelMode" id="defaultLabelModeRaw" value="raw" onchange="toggleRoomDefaultLabelMode()"/>
                            <strong>Raw Payload</strong>
                        </label>
                    </div>
                    <p><label for="defaultLabelFormat">Default label format</label>
                        <select id="defaultLabelFormat" name="defaultLabelFormat">
                            <c:forEach var="format" items="${formats}"><option value="${format}">${format}</option></c:forEach>
                        </select></p>
                    <div id="roomDefaultLabelBuilderContainer" style="margin-bottom: 15px;">
                        <c:forEach var="p" items="${policies}" varStatus="pSt">
                            <c:set var="meta" value="${policyMetadataMap[p.id()]}"/>
                            <div id="room_default_label_policy_builder_${p.id()}" class="room-default-label-policy-builder-panel" style="${pSt.first ? '' : 'display:none;'}">
                                <c:if test="${not empty meta}">
                                    <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                        <thead><tr><th width="5%">Select</th><th width="35%">Classification</th><th width="20%">LACV</th><th width="40%">Hierarchy</th></tr></thead>
                                        <tbody>
                                            <c:forEach var="cls" items="${meta.classifications()}" varStatus="st">
                                                <tr>
                                                    <td><input type="radio" name="defaultLabelClassification" value="${cls.lacv()}" id="room_default_label_cls_${pSt.index}_${st.index}"/></td>
                                                    <td><label for="room_default_label_cls_${pSt.index}_${st.index}"><strong><c:out value="${cls.name()}"/></strong></label></td>
                                                    <td><code><c:out value="${cls.lacv()}"/></code></td>
                                                    <td><c:out value="${cls.hierarchy()}"/></td>
                                                </tr>
                                            </c:forEach>
                                        </tbody>
                                    </table>
                                    <c:forEach var="ts" items="${meta.labelTagSets()}" varStatus="tsSt">
                                        <fieldset style="margin-top: 8px; margin-bottom: 10px; padding: 10px; border: 1px solid #ddd; border-radius: 4px;">
                                            <legend style="font-weight: bold; padding: 0 6px;"><c:out value="${ts.name()}"/></legend>
                                            <c:forEach var="cat" items="${ts.categories()}" varStatus="catSt">
                                                <label style="display:block;">
                                                    <input type="checkbox" name="defaultLabelCategories" value="${cat.spec()}" id="room_default_label_cat_${pSt.index}_${tsSt.index}_${catSt.index}"/>
                                                    <c:out value="${cat.name()}"/>
                                                </label>
                                            </c:forEach>
                                        </fieldset>
                                    </c:forEach>
                                </c:if>
                            </div>
                        </c:forEach>
                    </div>
                    <div id="roomDefaultLabelRawContainer" style="display: none; margin-bottom: 15px;">
                        <p><label for="defaultLabel">Default label payload (maximum 64 KiB)</label></p>
                        <textarea id="defaultLabel" name="defaultLabel" cols="100" rows="6" maxlength="65536"></textarea>
                    </div>
                </div>

                <p><input type="submit" value="Save Room Label"/></p>
            </admin:contentBox>
        </form>
    </c:otherwise>
</c:choose>

<script type="text/javascript">
    function toggleRoomClearanceMode() {
        var isBuilder = document.getElementById("clearanceModeBuilder").checked;
        document.getElementById("roomClearanceBuilderContainer").style.display = isBuilder ? "" : "none";
        document.getElementById("roomClearanceRawContainer").style.display = isBuilder ? "none" : "";
    }
    function updateRoomClearancePolicy() {
        var selectedPolicyId = document.getElementById("policyId").value;
        var panels = document.querySelectorAll(".room-clearance-policy-builder-panel");
        for (var i = 0; i < panels.length; i++) { panels[i].style.display = "none"; }
        var activePanel = document.getElementById("room_clearance_policy_builder_" + selectedPolicyId);
        if (activePanel) { activePanel.style.display = ""; }
    }

    function toggleRoomLabelMode() {
        var isBuilder = document.getElementById("labelModeBuilder").checked;
        document.getElementById("roomLabelBuilderContainer").style.display = isBuilder ? "" : "none";
        document.getElementById("roomLabelRawContainer").style.display = isBuilder ? "none" : "";
    }
    function updateRoomLabelPolicy() {
        var selectedPolicyId = document.getElementById("labelPolicyId").value;
        var panels = document.querySelectorAll(".room-label-policy-builder-panel");
        for (var i = 0; i < panels.length; i++) { panels[i].style.display = "none"; }
        var activePanel = document.getElementById("room_label_policy_builder_" + selectedPolicyId);
        if (activePanel) { activePanel.style.display = ""; }
        var defaultPanels = document.querySelectorAll(".room-default-label-policy-builder-panel");
        for (var j = 0; j < defaultPanels.length; j++) { defaultPanels[j].style.display = "none"; }
        var activeDefaultPanel = document.getElementById("room_default_label_policy_builder_" + selectedPolicyId);
        if (activeDefaultPanel) { activeDefaultPanel.style.display = ""; }
    }

    function toggleHasOwnDefaultLabel() {
        var checked = document.getElementById("hasOwnDefaultLabel").checked;
        document.getElementById("ownDefaultLabelContainer").style.display = checked ? "" : "none";
    }
    function toggleRoomDefaultLabelMode() {
        var isBuilder = document.getElementById("defaultLabelModeBuilder").checked;
        document.getElementById("roomDefaultLabelBuilderContainer").style.display = isBuilder ? "" : "none";
        document.getElementById("roomDefaultLabelRawContainer").style.display = isBuilder ? "none" : "";
    }
</script>

</body>
</html>
