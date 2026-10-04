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
            java.util.Map,
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
                fields.put("clearanceMode", request.getParameter("clearanceMode"));
                fields.put("clearance", request.getParameter("clearance"));
                fields.put("clearanceFormat", request.getParameter("clearanceFormat"));
                fields.put("defaultLabel", request.getParameter("defaultLabel"));
                fields.put("defaultLabelFormat", request.getParameter("defaultLabelFormat"));
                fields.put("isDefault", Boolean.toString(request.getParameter("isDefault") != null));

                String[] classifications = request.getParameterValues("clearanceClassifications");
                String[] categories = request.getParameterValues("clearanceCategories");
                SecurityPolicy policy = SettingsForm.parsePolicy(
                    fields,
                    classifications != null ? List.of(classifications) : List.of(),
                    categories != null ? List.of(categories) : List.of()
                );
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
                fields.put("clearanceMode", request.getParameter("peerClearanceMode"));
                fields.put("clearance", request.getParameter("peerClearance"));
                fields.put("format", request.getParameter("peerClearanceFormat"));

                String peerPolicyId = request.getParameter("peerPolicyId");
                SecurityPolicy targetPolicy = peerPolicyId != null ? configService.policy(peerPolicyId).orElse(null) : null;
                io.cridland.spiffing.Spif spif = null;
                if (targetPolicy != null && targetPolicy.spif() != null) {
                    try {
                        spif = new io.cridland.spiffing.Site().load(targetPolicy.spif());
                    } catch (Exception ignored) {}
                }

                String[] classifications = request.getParameterValues("peerClearanceClassifications");
                String[] categories = request.getParameterValues("peerClearanceCategories");

                PeerClearance pc = SettingsForm.parsePeerClearance(
                    fields,
                    classifications != null ? List.of(classifications) : List.of(),
                    categories != null ? List.of(categories) : List.of(),
                    spif
                );
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

    SettingsForm.PolicyClearanceMetadata editingMetadata = editingPolicy != null ? SettingsForm.inspectPolicy(editingPolicy) : null;
    pageContext.setAttribute("editingMetadata", editingMetadata);

    Map<String, SettingsForm.PolicyClearanceMetadata> policyMetadataMap = new HashMap<>();
    for (SecurityPolicy p : policies) {
        SettingsForm.PolicyClearanceMetadata m = SettingsForm.inspectPolicy(p);
        if (m != null) {
            policyMetadataMap.put(p.id(), m);
        }
    }
    pageContext.setAttribute("policyMetadataMap", policyMetadataMap);

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

        <hr style="margin: 20px 0; border: 0; border-top: 1px solid #ddd;"/>
        <p><strong>Server Clearance</strong> (optional, maximum 64 KiB)</p>
        <p class="description">Select the authorized classification levels and security categories for this server, or supply a raw clearance document.</p>

        <div style="margin-bottom: 12px; background: #f9f9f9; padding: 10px; border: 1px solid #e5e5e5; border-radius: 4px;">
            <label style="margin-right: 15px; font-weight: bold;">Configuration Mode:</label>
            <label style="margin-right: 20px; cursor: pointer;">
                <input type="radio" name="clearanceMode" id="clearanceModeBuilder" value="builder" checked="checked" onchange="toggleClearanceMode()"/>
                <strong>Interactive Builder</strong> (visual selection)
            </label>
            <label style="cursor: pointer;">
                <input type="radio" name="clearanceMode" id="clearanceModeRaw" value="raw" onchange="toggleClearanceMode()"/>
                <strong>Raw Payload / File Upload</strong>
            </label>
        </div>

        <p><label for="clearanceFormat">Server clearance format</label>
            <select id="clearanceFormat" name="clearanceFormat">
                <c:forEach var="format" items="${formats}">
                    <option value="${format}" ${empty editingPolicy ? (format eq 'XML' ? 'selected' : '') : (format eq editingPolicy.clearanceFormat() ? 'selected' : '')}>${format}</option>
                </c:forEach>
            </select>
        </p>

        <!-- Builder Mode Container -->
        <div id="clearanceBuilderContainer" style="margin-bottom: 15px;">
            <c:choose>
                <c:when test="${not empty editingMetadata}">
                    <div style="margin-bottom: 15px;">
                        <p><strong>Classification Levels</strong></p>
                        <c:choose>
                            <c:when test="${empty editingMetadata.classifications()}">
                                <p style="color: #888;"><em>No classifications defined in policy.</em></p>
                            </c:when>
                            <c:otherwise>
                                <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                    <thead>
                                        <tr>
                                            <th width="5%">Grant</th>
                                            <th width="35%">Classification</th>
                                            <th width="20%">LACV</th>
                                            <th width="20%">Hierarchy</th>
                                            <th width="20%">Color</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <c:forEach var="cls" items="${editingMetadata.classifications()}" varStatus="st">
                                            <tr>
                                                <td>
                                                    <input type="checkbox" name="clearanceClassifications" value="${cls.lacv()}" id="edit_cls_${st.index}"
                                                        ${editingMetadata.activeClassificationLacvs().contains(cls.lacv()) ? 'checked="checked"' : ''}/>
                                                </td>
                                                <td><label for="edit_cls_${st.index}"><strong><c:out value="${cls.name()}"/></strong></label></td>
                                                <td><code><c:out value="${cls.lacv()}"/></code></td>
                                                <td><c:out value="${cls.hierarchy()}"/></td>
                                                <td>
                                                    <c:if test="${not empty cls.color()}">
                                                        <span style="display:inline-block; width:14px; height:14px; background:${cls.color()}; border:1px solid #ccc; vertical-align:middle; margin-right:4px;"></span>
                                                        <c:out value="${cls.color()}"/>
                                                    </c:if>
                                                    <c:if test="${empty cls.color()}">-</c:if>
                                                </td>
                                            </tr>
                                        </c:forEach>
                                    </tbody>
                                </table>
                            </c:otherwise>
                        </c:choose>
                    </div>

                    <div style="margin-bottom: 15px;">
                        <p><strong>Security Categories and Tag Sets</strong></p>
                        <c:choose>
                            <c:when test="${empty editingMetadata.tagSets()}">
                                <p style="color: #888;"><em>No non-informative security categories defined in policy.</em></p>
                            </c:when>
                            <c:otherwise>
                                <c:forEach var="ts" items="${editingMetadata.tagSets()}" varStatus="tsSt">
                                    <fieldset style="margin-top: 8px; margin-bottom: 12px; padding: 10px; border: 1px solid #ddd; border-radius: 4px;">
                                        <legend style="font-weight: bold; padding: 0 6px;"><c:out value="${ts.name()}"/> (<code><c:out value="${ts.id()}"/></code>)</legend>
                                        <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                            <thead>
                                                <tr>
                                                    <th width="5%">Grant</th>
                                                    <th width="45%">Category</th>
                                                    <th width="25%">Tag / Type</th>
                                                    <th width="25%">LACV</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                <c:forEach var="cat" items="${ts.categories()}" varStatus="catSt">
                                                    <tr>
                                                        <td>
                                                            <input type="checkbox" name="clearanceCategories" value="${cat.spec()}" id="edit_cat_${tsSt.index}_${catSt.index}"
                                                                ${editingMetadata.activeCategorySpecs().contains(cat.spec()) ? 'checked="checked"' : ''}/>
                                                        </td>
                                                        <td><label for="edit_cat_${tsSt.index}_${catSt.index}"><c:out value="${cat.name()}"/></label></td>
                                                        <td><c:out value="${cat.tagName()}"/> (<em><c:out value="${cat.tagType()}"/></em>)</td>
                                                        <td><code><c:out value="${cat.lacv()}"/></code></td>
                                                    </tr>
                                                </c:forEach>
                                            </tbody>
                                        </table>
                                    </fieldset>
                                </c:forEach>
                            </c:otherwise>
                        </c:choose>
                    </div>

                    <c:if test="${not empty editingMetadata.displayMarking()}">
                        <div style="background: #eef6ff; border: 1px solid #b8daff; padding: 10px; border-radius: 4px; margin-top: 10px;">
                            <strong>Policy Display Marking for Active Clearance:</strong>
                            <code style="font-size: 1.1em; color: #004085;"><c:out value="${editingMetadata.displayMarking()}"/></code>
                        </div>
                    </c:if>
                </c:when>
                <c:otherwise>
                    <div id="dynamicClearanceBuilder">
                        <p style="color: #666;"><em>Paste or upload an Open XML SPIF document above to populate classification levels and categories.</em></p>
                    </div>
                </c:otherwise>
            </c:choose>
        </div>

        <!-- Raw Payload Mode Container -->
        <div id="clearanceRawContainer" style="display: none; margin-bottom: 15px;">
            <p><label for="clearanceFile">Upload clearance file (.xml, .b64, .ber, .der, .txt): </label>
                <input type="file" id="clearanceFile" accept=".xml,.b64,.ber,.der,.txt,text/xml,application/xml,text/plain"/>
            </p>
            <p><label for="clearance">Or paste / edit the raw clearance payload below:</label></p>
            <textarea id="clearance" name="clearance" cols="100" rows="6" maxlength="65536"><c:out value="${empty editingPolicy ? '' : editingPolicy.clearance()}"/></textarea>
            <c:if test="${not empty editingMetadata and not empty editingMetadata.displayMarking()}">
                <div style="background: #eef6ff; border: 1px solid #b8daff; padding: 8px; border-radius: 4px; margin-top: 8px;">
                    <strong>Current Policy Display Marking:</strong>
                    <code style="font-size: 1.05em; color: #004085;"><c:out value="${editingMetadata.displayMarking()}"/></code>
                </div>
            </c:if>
        </div>

        <hr style="margin: 20px 0; border: 0; border-top: 1px solid #ddd;"/>
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
                <select id="peerPolicyId" name="peerPolicyId" onchange="updatePeerClearancePolicy()">
                    <c:forEach var="p" items="${policies}">
                        <option value="${p.id()}">${p.name()} (${p.id()})</option>
                    </c:forEach>
                </select></p>

            <div style="margin-bottom: 12px; background: #f9f9f9; padding: 10px; border: 1px solid #e5e5e5; border-radius: 4px;">
                <label style="margin-right: 15px; font-weight: bold;">Configuration Mode:</label>
                <label style="margin-right: 20px; cursor: pointer;">
                    <input type="radio" name="peerClearanceMode" id="peerClearanceModeBuilder" value="builder" checked="checked" onchange="togglePeerClearanceMode()"/>
                    <strong>Interactive Builder</strong>
                </label>
                <label style="cursor: pointer;">
                    <input type="radio" name="peerClearanceMode" id="peerClearanceModeRaw" value="raw" onchange="togglePeerClearanceMode()"/>
                    <strong>Raw Payload / File Upload</strong>
                </label>
            </div>

            <p><label for="peerClearanceFormat">Format</label>
                <select id="peerClearanceFormat" name="peerClearanceFormat">
                    <c:forEach var="format" items="${formats}"><option value="${format}">${format}</option></c:forEach>
                </select></p>

            <div id="peerClearanceBuilderContainer" style="margin-bottom: 15px;">
                <c:forEach var="p" items="${policies}" varStatus="pSt">
                    <c:set var="meta" value="${policyMetadataMap[p.id()]}"/>
                    <div id="peer_policy_builder_${p.id()}" class="peer-policy-builder-panel" style="${pSt.first ? '' : 'display:none;'}">
                        <c:if test="${not empty meta}">
                            <div style="margin-bottom: 12px;">
                                <p><strong><c:out value="${p.name()}"/> &mdash; Classification Levels</strong></p>
                                <c:choose>
                                    <c:when test="${empty meta.classifications()}">
                                        <p style="color: #888;"><em>No classifications defined in policy.</em></p>
                                    </c:when>
                                    <c:otherwise>
                                        <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                            <thead>
                                                <tr>
                                                    <th width="5%">Grant</th>
                                                    <th width="35%">Classification</th>
                                                    <th width="20%">LACV</th>
                                                    <th width="20%">Hierarchy</th>
                                                    <th width="20%">Color</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                <c:forEach var="cls" items="${meta.classifications()}" varStatus="st">
                                                    <tr>
                                                        <td><input type="checkbox" name="peerClearanceClassifications" value="${cls.lacv()}" id="peer_cls_${pSt.index}_${st.index}"/></td>
                                                        <td><label for="peer_cls_${pSt.index}_${st.index}"><strong><c:out value="${cls.name()}"/></strong></label></td>
                                                        <td><code><c:out value="${cls.lacv()}"/></code></td>
                                                        <td><c:out value="${cls.hierarchy()}"/></td>
                                                        <td>
                                                            <c:if test="${not empty cls.color()}">
                                                                <span style="display:inline-block; width:14px; height:14px; background:${cls.color()}; border:1px solid #ccc; vertical-align:middle; margin-right:4px;"></span>
                                                                <c:out value="${cls.color()}"/>
                                                            </c:if>
                                                            <c:if test="${empty cls.color()}">-</c:if>
                                                        </td>
                                                    </tr>
                                                </c:forEach>
                                            </tbody>
                                        </table>
                                    </c:otherwise>
                                </c:choose>
                            </div>

                            <div style="margin-bottom: 12px;">
                                <p><strong><c:out value="${p.name()}"/> &mdash; Security Categories</strong></p>
                                <c:choose>
                                    <c:when test="${empty meta.tagSets()}">
                                        <p style="color: #888;"><em>No security categories defined in policy.</em></p>
                                    </c:when>
                                    <c:otherwise>
                                        <c:forEach var="ts" items="${meta.tagSets()}" varStatus="tsSt">
                                            <fieldset style="margin-top: 8px; margin-bottom: 10px; padding: 10px; border: 1px solid #ddd; border-radius: 4px;">
                                                <legend style="font-weight: bold; padding: 0 6px;"><c:out value="${ts.name()}"/></legend>
                                                <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                                    <thead>
                                                        <tr>
                                                            <th width="5%">Grant</th>
                                                            <th width="45%">Category</th>
                                                            <th width="25%">Tag / Type</th>
                                                            <th width="25%">LACV</th>
                                                        </tr>
                                                    </thead>
                                                    <tbody>
                                                        <c:forEach var="cat" items="${ts.categories()}" varStatus="catSt">
                                                            <tr>
                                                                <td><input type="checkbox" name="peerClearanceCategories" value="${cat.spec()}" id="peer_cat_${pSt.index}_${tsSt.index}_${catSt.index}"/></td>
                                                                <td><label for="peer_cat_${pSt.index}_${tsSt.index}_${catSt.index}"><c:out value="${cat.name()}"/></label></td>
                                                                <td><c:out value="${cat.tagName()}"/> (<em><c:out value="${cat.tagType()}"/></em>)</td>
                                                                <td><code><c:out value="${cat.lacv()}"/></code></td>
                                                            </tr>
                                                        </c:forEach>
                                                    </tbody>
                                                </table>
                                            </fieldset>
                                        </c:forEach>
                                    </c:otherwise>
                                </c:choose>
                            </div>
                        </c:if>
                    </div>
                </c:forEach>
            </div>

            <div id="peerClearanceRawContainer" style="display: none; margin-bottom: 15px;">
                <p><label for="peerClearanceFile">Upload clearance file (.xml, .b64, .ber, .der, .txt): </label>
                    <input type="file" id="peerClearanceFile" accept=".xml,.b64,.ber,.der,.txt,text/xml,application/xml,text/plain"/>
                </p>
                <p><label for="peerClearance">Clearance payload (maximum 64 KiB)</label></p>
                <textarea id="peerClearance" name="peerClearance" cols="100" rows="6" maxlength="65536"></textarea>
            </div>

            <p><input type="submit" value="Add Peer Clearance"/></p>
        </form>
    </c:if>
</admin:contentBox>

<script type="text/javascript">
    function escapeHtml(str) {
        if (!str) return "";
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function toggleClearanceMode() {
        var isBuilder = document.getElementById("clearanceModeBuilder").checked;
        var builderBox = document.getElementById("clearanceBuilderContainer");
        var rawBox = document.getElementById("clearanceRawContainer");
        if (builderBox && rawBox) {
            builderBox.style.display = isBuilder ? "" : "none";
            rawBox.style.display = isBuilder ? "none" : "";
        }
    }

    function togglePeerClearanceMode() {
        var isBuilder = document.getElementById("peerClearanceModeBuilder").checked;
        var builderBox = document.getElementById("peerClearanceBuilderContainer");
        var rawBox = document.getElementById("peerClearanceRawContainer");
        if (builderBox && rawBox) {
            builderBox.style.display = isBuilder ? "" : "none";
            rawBox.style.display = isBuilder ? "none" : "";
        }
    }

    function updatePeerClearancePolicy() {
        var select = document.getElementById("peerPolicyId");
        if (!select) return;
        var selectedPolicyId = select.value;
        var panels = document.querySelectorAll(".peer-policy-builder-panel");
        for (var i = 0; i < panels.length; i++) {
            panels[i].style.display = "none";
        }
        var activePanel = document.getElementById("peer_policy_builder_" + selectedPolicyId);
        if (activePanel) {
            activePanel.style.display = "";
        }
    }

    function setupClearanceFileUpload(fileInputId, textareaId, formatSelectId) {
        var fileInput = document.getElementById(fileInputId);
        var textarea = document.getElementById(textareaId);
        var formatSelect = formatSelectId ? document.getElementById(formatSelectId) : null;
        if (!fileInput || !textarea) return;

        function handleFile(file) {
            if (!file) return;
            if (file.size > 65536) {
                alert("The selected clearance file exceeds the 64 KiB limit.");
                fileInput.value = "";
                return;
            }
            var isDer = file.name.endsWith(".der") || file.name.endsWith(".ber");
            if (isDer) {
                var reader = new FileReader();
                reader.onload = function(evt) {
                    var bytes = new Uint8Array(evt.target.result);
                    var binary = '';
                    for (var i = 0; i < bytes.byteLength; i++) {
                        binary += String.fromCharCode(bytes[i]);
                    }
                    var base64 = btoa(binary);
                    textarea.value = base64;
                    if (formatSelect) {
                        formatSelect.value = "ESS";
                    }
                };
                reader.onerror = function() {
                    alert("Failed to read the binary clearance file.");
                };
                reader.readAsArrayBuffer(file);
            } else {
                var reader = new FileReader();
                reader.onload = function(evt) {
                    var content = evt.target.result;
                    textarea.value = content;
                    if (formatSelect) {
                        var trimmed = content.trim();
                        if (trimmed.startsWith("<") && (trimmed.includes("ConfidentialityClearance") || trimmed.includes("confidentialityclearance"))) {
                            formatSelect.value = "NATO";
                        } else if (trimmed.startsWith("<")) {
                            formatSelect.value = "XML";
                        } else if (!trimmed.startsWith("<")) {
                            formatSelect.value = "ESS";
                        }
                    }
                };
                reader.onerror = function() {
                    alert("Failed to read the clearance file.");
                };
                reader.readAsText(file, "UTF-8");
            }
        }

        fileInput.addEventListener("change", function(e) {
            var file = e.target.files && e.target.files[0];
            handleFile(file);
        });

        textarea.addEventListener("dragover", function(e) {
            e.preventDefault();
        });
        textarea.addEventListener("drop", function(e) {
            if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                e.preventDefault();
                var file = e.dataTransfer.files[0];
                handleFile(file);
            }
        });
    }

    function parseSpifXmlAndRenderBuilder(spifXml, containerId) {
        var container = document.getElementById(containerId);
        if (!container) return;
        if (!spifXml || spifXml.trim() === "") {
            container.innerHTML = "<p style='color:#666;'><em>Paste or upload an Open XML SPIF document above to populate classification levels and categories.</em></p>";
            return;
        }
        try {
            var parser = new DOMParser();
            var doc = parser.parseFromString(spifXml, "text/xml");
            var parseError = doc.getElementsByTagName("parsererror");
            if (parseError.length > 0) {
                container.innerHTML = "<p style='color:#c00;'><em>Invalid SPIF XML syntax. Fix XML above to display the clearance builder.</em></p>";
                return;
            }

            var html = "";
            var classNodes = doc.querySelectorAll("securityClassification");
            html += "<div style='margin-bottom:15px;'><p><strong>Classification Levels</strong></p>";
            if (classNodes.length === 0) {
                html += "<p style='color:#888;'><em>No classifications defined in SPIF.</em></p>";
            } else {
                html += "<table class='jive-table' cellpadding='3' cellspacing='0' border='0' width='100%'><thead><tr><th width='5%'>Grant</th><th width='35%'>Classification</th><th width='20%'>LACV</th><th width='20%'>Hierarchy</th><th width='20%'>Color</th></tr></thead><tbody>";
                for (var i = 0; i < classNodes.length; i++) {
                    var node = classNodes[i];
                    var name = node.getAttribute("name") || "";
                    var lacv = node.getAttribute("lacv") || "";
                    var hierarchy = node.getAttribute("hierarchy") || "";
                    var color = node.getAttribute("color") || "";
                    var id = "dyn_cls_" + i;
                    html += "<tr>";
                    html += "<td><input type='checkbox' name='clearanceClassifications' value='" + escapeHtml(lacv) + "' id='" + id + "'/></td>";
                    html += "<td><label for='" + id + "'><strong>" + escapeHtml(name) + "</strong></label></td>";
                    html += "<td><code>" + escapeHtml(lacv) + "</code></td>";
                    html += "<td>" + escapeHtml(hierarchy) + "</td>";
                    html += "<td>" + (color ? "<span style='display:inline-block; width:14px; height:14px; background:" + escapeHtml(color) + "; border:1px solid #ccc; vertical-align:middle; margin-right:4px;'></span> " + escapeHtml(color) : "-") + "</td>";
                    html += "</tr>";
                }
                html += "</tbody></table>";
            }
            html += "</div>";

            var tagSetNodes = doc.querySelectorAll("securityCategoryTagSet");
            html += "<div style='margin-bottom:15px;'><p><strong>Security Categories and Tag Sets</strong></p>";
            var hasCategories = false;
            if (tagSetNodes.length > 0) {
                for (var s = 0; s < tagSetNodes.length; s++) {
                    var tsNode = tagSetNodes[s];
                    var tsId = tsNode.getAttribute("id") || "";
                    var tsName = tsNode.getAttribute("name") || tsId;
                    var tagNodes = tsNode.querySelectorAll("securityCategoryTag");
                    for (var t = 0; t < tagNodes.length; t++) {
                        var tagNode = tagNodes[t];
                        var tagType = tagNode.getAttribute("tagType") || tagNode.getAttribute("type") || "";
                        if (tagType.toLowerCase() === "informative") continue;
                        var tagName = tagNode.getAttribute("name") || tagType;
                        var catNodes = tagNode.querySelectorAll("securityCategory");
                        if (catNodes.length > 0) {
                            hasCategories = true;
                            html += "<fieldset style='margin-top:8px; margin-bottom:10px; padding:10px; border:1px solid #ddd; border-radius:4px;'><legend style='font-weight:bold; padding:0 6px;'>" + escapeHtml(tsName) + " &mdash; " + escapeHtml(tagName) + " (<em>" + escapeHtml(tagType) + "</em>)</legend><table class='jive-table' cellpadding='3' cellspacing='0' border='0' width='100%'><thead><tr><th width='5%'>Grant</th><th width='45%'>Category</th><th width='25%'>Tag / Type</th><th width='25%'>LACV</th></tr></thead><tbody>";
                            for (var c = 0; c < catNodes.length; c++) {
                                var catNode = catNodes[c];
                                var catName = catNode.getAttribute("name") || "";
                                var catLacv = catNode.getAttribute("lacv") || "";
                                var spec = tsId + "|" + tagType + "|" + catLacv;
                                var catId = "dyn_cat_" + s + "_" + t + "_" + c;
                                html += "<tr>";
                                html += "<td><input type='checkbox' name='clearanceCategories' value='" + escapeHtml(spec) + "' id='" + catId + "'/></td>";
                                html += "<td><label for='" + catId + "'>" + escapeHtml(catName) + "</label></td>";
                                html += "<td>" + escapeHtml(tagName) + " (<em>" + escapeHtml(tagType) + "</em>)</td>";
                                html += "<td><code>" + escapeHtml(catLacv) + "</code></td>";
                                html += "</tr>";
                            }
                            html += "</tbody></table></fieldset>";
                        }
                    }
                }
            }
            if (!hasCategories) {
                html += "<p style='color:#888;'><em>No security categories defined in SPIF.</em></p>";
            }
            html += "</div>";
            container.innerHTML = html;
        } catch (e) {
            container.innerHTML = "<p style='color:#c00;'><em>Failed to parse SPIF: " + escapeHtml(e.message) + "</em></p>";
        }
    }

    document.addEventListener("DOMContentLoaded", function() {
        setupClearanceFileUpload("clearanceFile", "clearance", "clearanceFormat");
        setupClearanceFileUpload("peerClearanceFile", "peerClearance", "peerClearanceFormat");

        var spifTextarea = document.getElementById("spif");
        if (spifTextarea) {
            spifTextarea.addEventListener("input", function() {
                if (document.getElementById("dynamicClearanceBuilder")) {
                    parseSpifXmlAndRenderBuilder(spifTextarea.value, "dynamicClearanceBuilder");
                }
            });
            spifTextarea.addEventListener("change", function() {
                if (document.getElementById("dynamicClearanceBuilder")) {
                    parseSpifXmlAndRenderBuilder(spifTextarea.value, "dynamicClearanceBuilder");
                }
            });
            if (document.getElementById("dynamicClearanceBuilder") && spifTextarea.value.trim().length > 0) {
                parseSpifXmlAndRenderBuilder(spifTextarea.value, "dynamicClearanceBuilder");
            }
        }
    });
</script>

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
