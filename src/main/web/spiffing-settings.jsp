<%@ page contentType="text/html; charset=UTF-8"
    import="org.jivesoftware.openfire.XMPPServer,
            org.igniterealtime.openfire.spiffing.SpiffingPlugin,
            org.igniterealtime.openfire.spiffing.ConfigurationService,
            org.igniterealtime.openfire.spiffing.SecurityPolicy,
            org.igniterealtime.openfire.spiffing.PeerClearance,
            org.igniterealtime.openfire.spiffing.SettingsForm,
            org.igniterealtime.openfire.spiffing.LabelFormat,
            org.igniterealtime.openfire.spiffing.EnforcementMode,
            org.igniterealtime.openfire.spiffing.SpiffingProperties,
            org.jivesoftware.util.CookieUtils,
            org.jivesoftware.util.StringUtils,
            java.util.HashMap,
            java.util.List,
            java.util.Optional" %>
<%@ taglib uri="admin" prefix="admin" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%
    request.setCharacterEncoding("UTF-8");
    final SpiffingPlugin plugin = (SpiffingPlugin) XMPPServer.getInstance().getPluginManager().getPluginByName("Spiffing").orElseThrow();
    final ConfigurationService configService = plugin.configurationService();

    String error = null;
    String editPolicyId = request.getParameter("editPolicy");
    SecurityPolicy editingPolicy = null;
    if (editPolicyId != null && !editPolicyId.isBlank()) {
        editingPolicy = configService.policy(editPolicyId).orElse(null);
    }

    if ("POST".equals(request.getMethod())) {
        final Cookie csrfCookie = CookieUtils.getCookie(request, "csrf");
        final String csrfParam = request.getParameter("csrf");
        try {
            SettingsForm.validateCsrf(request.getMethod(), csrfCookie != null ? csrfCookie.getValue() : null, csrfParam);
            final String action = request.getParameter("action");
            if ("savePolicy".equals(action)) {
                final HashMap<String, String> fields = new HashMap<>();
                fields.put("spif", request.getParameter("spif"));
                fields.put("clearance", request.getParameter("clearance"));
                fields.put("clearanceFormat", request.getParameter("clearanceFormat"));
                fields.put("defaultLabel", request.getParameter("defaultLabel"));
                fields.put("defaultLabelFormat", request.getParameter("defaultLabelFormat"));
                fields.put("isDefault", Boolean.toString(request.getParameter("isDefault") != null));
                SecurityPolicy policy = SettingsForm.parsePolicy(fields);
                configService.savePolicy(policy);
                response.sendRedirect("spiffing-settings.jsp?saved=true");
                return;
            } else if ("deletePolicy".equals(action)) {
                String policyId = request.getParameter("policyId");
                if (policyId != null && !policyId.isBlank()) {
                    configService.deletePolicy(policyId);
                    response.sendRedirect("spiffing-settings.jsp?saved=true");
                    return;
                }
            } else if ("setDefaultPolicy".equals(action)) {
                String policyId = request.getParameter("policyId");
                if (policyId != null && !policyId.isBlank()) {
                    configService.setDefaultPolicy(policyId);
                    response.sendRedirect("spiffing-settings.jsp?saved=true");
                    return;
                }
            } else if ("savePeerClearance".equals(action)) {
                final HashMap<String, String> fields = new HashMap<>();
                fields.put("domain", request.getParameter("peerDomain"));
                fields.put("policyId", request.getParameter("peerPolicyId"));
                fields.put("clearance", request.getParameter("peerClearance"));
                fields.put("format", request.getParameter("peerClearanceFormat"));
                PeerClearance pc = SettingsForm.parsePeerClearance(fields);
                configService.savePeerClearance(pc);
                response.sendRedirect("spiffing-settings.jsp?saved=true");
                return;
            } else if ("deletePeerClearance".equals(action)) {
                String clearanceId = request.getParameter("clearanceId");
                if (clearanceId != null && !clearanceId.isBlank()) {
                    configService.deletePeerClearance(clearanceId);
                    response.sendRedirect("spiffing-settings.jsp?saved=true");
                    return;
                }
            } else if ("saveGlobalSettings".equals(action)) {
                String enforcement = request.getParameter("enforcementMode");
                if (enforcement != null) {
                    SpiffingProperties.setEnforcementMode(EnforcementMode.valueOf(enforcement));
                }
                String outputFormat = request.getParameter("outputFormat");
                if (outputFormat != null) {
                    SpiffingProperties.setOutputFormat(LabelFormat.valueOf(outputFormat));
                }
                boolean strip = request.getParameter("stripDefaultLabelForFederation") != null;
                SpiffingProperties.setStripDefaultLabelForFederation(strip);
                configService.reload();
                response.sendRedirect("spiffing-settings.jsp?saved=true");
                return;
            }
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            error = "Settings could not be saved: " + e.getMessage();
        }
    }

    final String csrf = StringUtils.randomString(32);
    CookieUtils.setCookie(request, response, "csrf", csrf, -1);
    pageContext.setAttribute("csrf", csrf);
    pageContext.setAttribute("error", error);

    List<SecurityPolicy> policies = configService.policies();
    List<PeerClearance> peerClearances = configService.peerClearances();
    boolean hasPolicies = !policies.isEmpty();
    boolean hasClearance = policies.stream().anyMatch(p -> p.clearance() != null);
    boolean hasDefaultLabel = policies.stream().anyMatch(p -> p.defaultLabel() != null);

    pageContext.setAttribute("policies", policies);
    pageContext.setAttribute("peerClearances", peerClearances);
    pageContext.setAttribute("hasPolicies", hasPolicies);
    pageContext.setAttribute("hasClearance", hasClearance);
    pageContext.setAttribute("hasDefaultLabel", hasDefaultLabel);
    pageContext.setAttribute("corrupted", configService.isCorrupted());
    pageContext.setAttribute("editingPolicy", editingPolicy);

    pageContext.setAttribute("enforcementMode", SpiffingProperties.getEnforcementMode().name());
    pageContext.setAttribute("outputFormat", SpiffingProperties.getOutputFormat().name());
    pageContext.setAttribute("stripDefaultLabelForFederation", SpiffingProperties.isStripDefaultLabelForFederation());

    pageContext.setAttribute("formats", LabelFormat.values());
    pageContext.setAttribute("enforcementModes", EnforcementMode.values());
%>
<html>
<head><title>Spiffing security labels</title><meta name="pageID" content="spiffing-settings"/></head>
<body>
<c:if test="${not empty error}"><admin:infoBox type="error"><c:out value="${error}"/></admin:infoBox></c:if>
<c:if test="${param.saved eq 'true' and empty error}"><admin:infoBox type="success">Settings saved.</admin:infoBox></c:if>

<c:choose>
    <c:when test="${not hasPolicies}">
        <admin:infoBox type="info">The plugin is not configured, so it is not active: no security policies have been uploaded yet. Messages pass through unaffected until a policy is added below.</admin:infoBox>
    </c:when>
    <c:when test="${corrupted}">
        <admin:infoBox type="warning">Stored settings are incomplete or corrupted, so inbound messages are blocked until a valid configuration is saved.</admin:infoBox>
    </c:when>
    <c:otherwise>
        <c:if test="${not hasClearance}">
            <admin:infoBox type="warning">Notice: No server clearance is configured. Labels will be checked for policy validity, but clearance authorization checks are bypassed until a clearance is configured on at least one policy.</admin:infoBox>
        </c:if>
        <c:if test="${not hasDefaultLabel}">
            <admin:infoBox type="warning">Notice: No default message label is configured. Unlabelled messages will pass through unstamped until a default label is configured on a policy.</admin:infoBox>
        </c:if>
        <c:choose>
            <c:when test="${enforcementMode eq 'ENFORCE'}"><p>Enforcement mode: messages that fail the label check are rejected.</p></c:when>
            <c:otherwise><admin:infoBox type="info">Warn mode: messages that fail the label check are logged and let through unchanged.</admin:infoBox></c:otherwise>
        </c:choose>
    </c:otherwise>
</c:choose>

<admin:contentBox title="Configured Security Policies">
    <c:choose>
        <c:when test="${empty policies}">
            <p>No policies currently configured. Add an Open XML SPIF policy document below.</p>
        </c:when>
        <c:otherwise>
            <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                <thead>
                    <tr>
                        <th width="25%">Policy Name</th>
                        <th width="25%">Policy ID</th>
                        <th width="15%">Server Clearance</th>
                        <th width="15%">Default Label</th>
                        <th width="10%">Stamping Default</th>
                        <th width="10%">Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="p" items="${policies}">
                        <tr>
                            <td><strong><c:out value="${p.name()}"/></strong></td>
                            <td><code><c:out value="${p.id()}"/></code></td>
                            <td>
                                <c:choose>
                                    <c:when test="${not empty p.clearance()}"><span style="color:green;">Configured (${p.clearanceFormat()})</span></c:when>
                                    <c:otherwise><span style="color:#888;">None</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${not empty p.defaultLabel()}"><span style="color:green;">Configured (${p.defaultLabelFormat()})</span></c:when>
                                    <c:otherwise><span style="color:#888;">None</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td align="center">
                                <c:choose>
                                    <c:when test="${p.isDefault()}"><strong>Yes</strong></c:when>
                                    <c:otherwise>
                                        <form action="spiffing-settings.jsp" method="post" style="display:inline;">
                                            <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
                                            <input type="hidden" name="action" value="setDefaultPolicy"/>
                                            <input type="hidden" name="policyId" value="<c:out value='${p.id()}'/>"/>
                                            <button type="submit">Set Default</button>
                                        </form>
                                    </c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <a href="spiffing-settings.jsp?editPolicy=<c:out value='${p.id()}'/>">Edit</a>
                                |
                                <form action="spiffing-settings.jsp" method="post" style="display:inline;" onsubmit="return confirm('Delete this policy?');">
                                    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
                                    <input type="hidden" name="action" value="deletePolicy"/>
                                    <input type="hidden" name="policyId" value="<c:out value='${p.id()}'/>"/>
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

<form action="spiffing-settings.jsp" method="post">
    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
    <input type="hidden" name="action" value="savePolicy"/>
    <admin:contentBox title="${empty editingPolicy ? 'Add Security Policy' : 'Update Security Policy'}">
        <p><label for="spif"><strong>Open XML SPIF document</strong> (required, maximum 1 MiB)</label></p>
        <p><label for="spifFile">Upload SPIF file: </label>
            <input type="file" id="spifFile" accept=".xml,.spif,text/xml,application/xml"/>
        </p>
        <p><label for="spif">Or paste / edit the Open XML SPIF document below:</label></p>
        <textarea id="spif" name="spif" cols="100" rows="14" maxlength="1048576" required><c:out value="${empty editingPolicy ? '' : editingPolicy.spif()}"/></textarea>
        <script type="text/javascript">
            document.addEventListener("DOMContentLoaded", function() {
                var spifFileInput = document.getElementById("spifFile");
                var spifTextarea = document.getElementById("spif");
                if (spifFileInput && spifTextarea) {
                    spifFileInput.addEventListener("change", function(e) {
                        var file = e.target.files && e.target.files[0];
                        if (file) {
                            if (file.size > 1048576) {
                                alert("The selected file exceeds the 1 MiB limit.");
                                spifFileInput.value = "";
                                return;
                            }
                            var reader = new FileReader();
                            reader.onload = function(evt) {
                                spifTextarea.value = evt.target.result;
                            };
                            reader.onerror = function() {
                                alert("Failed to read the selected file.");
                            };
                            reader.readAsText(file, "UTF-8");
                        }
                    });

                    spifTextarea.addEventListener("dragover", function(e) {
                        e.preventDefault();
                    });
                    spifTextarea.addEventListener("drop", function(e) {
                        if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                            e.preventDefault();
                            var file = e.dataTransfer.files[0];
                            if (file.size > 1048576) {
                                alert("The dropped file exceeds the 1 MiB limit.");
                                return;
                            }
                            var reader = new FileReader();
                            reader.onload = function(evt) {
                                spifTextarea.value = evt.target.result;
                            };
                            reader.onerror = function() {
                                alert("Failed to read the dropped file.");
                            };
                            reader.readAsText(file, "UTF-8");
                        }
                    });
                }
            });
        </script>

        <p><label for="clearanceFormat">Server clearance format</label>
            <select id="clearanceFormat" name="clearanceFormat">
                <c:forEach var="format" items="${formats}">
                    <option value="${format}" ${empty editingPolicy ? (format eq 'XML' ? 'selected' : '') : (format eq editingPolicy.clearanceFormat() ? 'selected' : '')}>${format}</option>
                </c:forEach>
            </select></p>
        <p><label for="clearance">Server clearance payload (optional, maximum 64 KiB)</label></p>
        <textarea id="clearance" name="clearance" cols="100" rows="6" maxlength="65536"><c:out value="${empty editingPolicy ? '' : editingPolicy.clearance()}"/></textarea>

        <p><label for="defaultLabelFormat">Default message label format</label>
            <select id="defaultLabelFormat" name="defaultLabelFormat">
                <c:forEach var="format" items="${formats}">
                    <option value="${format}" ${empty editingPolicy ? (format eq 'XML' ? 'selected' : '') : (format eq editingPolicy.defaultLabelFormat() ? 'selected' : '')}>${format}</option>
                </c:forEach>
            </select></p>
        <p><label for="defaultLabel">Default message label payload (optional, maximum 64 KiB)</label></p>
        <textarea id="defaultLabel" name="defaultLabel" cols="100" rows="6" maxlength="65536"><c:out value="${empty editingPolicy ? '' : editingPolicy.defaultLabel()}"/></textarea>

        <p><label for="isDefault">
            <input type="checkbox" id="isDefault" name="isDefault" ${empty editingPolicy ? '' : (editingPolicy.isDefault() ? 'checked' : '')}/>
            Use this policy's default label for stamping unlabelled messages</label></p>

        <input type="submit" value="${empty editingPolicy ? 'Add Policy' : 'Update Policy'}"/>
        <c:if test="${not empty editingPolicy}">
            <a href="spiffing-settings.jsp">Cancel editing</a>
        </c:if>
    </admin:contentBox>
</form>

<admin:contentBox title="Peer Clearances">
    <c:choose>
        <c:when test="${empty peerClearances}">
            <p>No peer clearances configured. Peer clearances check inbound and outbound messages with federated servers.</p>
        </c:when>
        <c:otherwise>
            <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                <thead>
                    <tr>
                        <th width="30%">Domain</th>
                        <th width="30%">Policy ID</th>
                        <th width="20%">Format</th>
                        <th width="20%">Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="pc" items="${peerClearances}">
                        <tr>
                            <td><c:out value="${empty pc.domain() ? '(Default / All Peers)' : pc.domain()}"/></td>
                            <td><code><c:out value="${pc.policyId()}"/></code></td>
                            <td><c:out value="${pc.format()}"/></td>
                            <td>
                                <form action="spiffing-settings.jsp" method="post" style="display:inline;">
                                    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
                                    <input type="hidden" name="action" value="deletePeerClearance"/>
                                    <input type="hidden" name="clearanceId" value="<c:out value='${pc.id()}'/>"/>
                                    <button type="submit">Delete</button>
                                </form>
                            </td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
        </c:otherwise>
    </c:choose>

    <c:if test="${not empty policies}">
        <h4>Add Peer Clearance</h4>
        <form action="spiffing-settings.jsp" method="post">
            <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
            <input type="hidden" name="action" value="savePeerClearance"/>
            <p><label for="peerDomain">Peer Domain (leave blank for default peer clearance)</label>
                <input type="text" id="peerDomain" name="peerDomain" size="40"/></p>
            <p><label for="peerPolicyId">Policy</label>
                <select id="peerPolicyId" name="peerPolicyId">
                    <c:forEach var="p" items="${policies}">
                        <option value="${p.id()}">${p.name()} (${p.id()})</option>
                    </c:forEach>
                </select></p>
            <p><label for="peerClearanceFormat">Format</label>
                <select id="peerClearanceFormat" name="peerClearanceFormat">
                    <c:forEach var="format" items="${formats}"><option value="${format}">${format}</option></c:forEach>
                </select></p>
            <p><label for="peerClearance">Clearance payload (maximum 64 KiB)</label></p>
            <textarea id="peerClearance" name="peerClearance" cols="100" rows="6" maxlength="65536" required></textarea>
            <p><input type="submit" value="Add Peer Clearance"/></p>
        </form>
    </c:if>
</admin:contentBox>

<form action="spiffing-settings.jsp" method="post">
    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
    <input type="hidden" name="action" value="saveGlobalSettings"/>
    <admin:contentBox title="Global Operational Settings">
        <p><label for="enforcementMode">Enforcement Mode</label>
            <select id="enforcementMode" name="enforcementMode">
                <c:forEach var="mode" items="${enforcementModes}">
                    <option value="${mode}" ${mode eq enforcementMode ? 'selected' : ''}>${mode}</option>
                </c:forEach>
            </select></p>
        <p>Warn logs any security label violation and lets the message through unchanged. Enforce rejects the message with an error.</p>

        <p><label for="outputFormat">Format for stamping unlabelled messages</label>
            <select id="outputFormat" name="outputFormat">
                <c:forEach var="format" items="${formats}">
                    <option value="${format}" ${format eq outputFormat ? 'selected' : ''}>${format}</option>
                </c:forEach>
            </select></p>

        <p><label for="stripDefaultLabelForFederation">
            <input type="checkbox" id="stripDefaultLabelForFederation" name="stripDefaultLabelForFederation" ${stripDefaultLabelForFederation ? 'checked' : ''}/>
            Strip the default label before sending a message to another server</label></p>

        <input type="submit" value="Save Global Settings"/>
    </admin:contentBox>
</form>

</body>
</html>
