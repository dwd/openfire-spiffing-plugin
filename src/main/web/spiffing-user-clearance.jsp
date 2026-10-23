<%@ page contentType="text/html; charset=UTF-8"
    import="org.jivesoftware.openfire.XMPPServer,
            org.jivesoftware.openfire.user.User,
            org.jivesoftware.openfire.user.UserManager,
            org.jivesoftware.openfire.user.UserNotFoundException,
            org.igniterealtime.openfire.spiffing.SpiffingPlugin,
            org.igniterealtime.openfire.spiffing.ConfigurationService,
            org.igniterealtime.openfire.spiffing.UserClearanceService,
            org.igniterealtime.openfire.spiffing.SecurityPolicy,
            org.igniterealtime.openfire.spiffing.UserClearance,
            org.igniterealtime.openfire.spiffing.SettingsForm,
            org.igniterealtime.openfire.spiffing.LabelFormat,
            org.jivesoftware.util.CookieUtils,
            org.jivesoftware.util.ParamUtils,
            org.jivesoftware.util.StringUtils,
            java.util.HashMap,
            java.util.List,
            java.util.Map" %>
<%@ taglib uri="admin" prefix="admin" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%
    request.setCharacterEncoding("UTF-8");
    final String username = ParamUtils.getParameter(request, "username");
    if (username == null || username.isBlank()) {
        response.sendRedirect("user-summary.jsp");
        return;
    }
    User user = null;
    try {
        user = UserManager.getInstance().getUser(username);
    } catch (UserNotFoundException e) {
        response.sendRedirect("user-summary.jsp");
        return;
    }

    final SpiffingPlugin plugin = (SpiffingPlugin) XMPPServer.getInstance().getPluginManager().getPluginByName("Spiffing").orElseThrow();
    final ConfigurationService configService = plugin.configurationService();
    final UserClearanceService clearanceService = plugin.userClearanceService();

    String error = null;

    if ("POST".equals(request.getMethod())) {
        final Cookie csrfCookie = CookieUtils.getCookie(request, "csrf");
        final String csrfParam = request.getParameter("csrf");
        try {
            SettingsForm.validateCsrf(request.getMethod(), csrfCookie != null ? csrfCookie.getValue() : null, csrfParam);
            final String action = request.getParameter("action");
            if ("saveUserClearance".equals(action)) {
                final HashMap<String, String> fields = new HashMap<>();
                fields.put("username", username);
                fields.put("policyId", request.getParameter("policyId"));
                fields.put("clearanceMode", request.getParameter("clearanceMode"));
                fields.put("clearance", request.getParameter("clearance"));
                fields.put("format", request.getParameter("clearanceFormat"));

                String policyId = request.getParameter("policyId");
                SecurityPolicy targetPolicy = policyId != null ? configService.policy(policyId).orElse(null) : null;
                io.cridland.spiffing.Spif spif = null;
                if (targetPolicy != null && targetPolicy.spif() != null) {
                    try {
                        spif = new io.cridland.spiffing.Site().load(targetPolicy.spif());
                    } catch (Exception ignored) {}
                }

                String[] classifications = request.getParameterValues("clearanceClassifications");
                String[] categories = request.getParameterValues("clearanceCategories");

                UserClearance uc = SettingsForm.parseUserClearance(
                    fields,
                    classifications != null ? List.of(classifications) : List.of(),
                    categories != null ? List.of(categories) : List.of(),
                    spif
                );
                clearanceService.save(uc);
                response.sendRedirect("spiffing-user-clearance.jsp?username=" + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8) + "&saved=true");
                return;
            } else if ("deleteUserClearance".equals(action)) {
                String policyId = request.getParameter("policyId");
                if (policyId != null && !policyId.isBlank()) {
                    clearanceService.delete(username, policyId);
                    response.sendRedirect("spiffing-user-clearance.jsp?username=" + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8) + "&saved=true");
                    return;
                }
            }
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            error = "User clearance could not be saved: " + e.getMessage();
        }
    }

    final String csrf = StringUtils.randomString(32);
    CookieUtils.setCookie(request, response, "csrf", csrf, -1);
    pageContext.setAttribute("csrf", csrf);
    pageContext.setAttribute("error", error);
    pageContext.setAttribute("username", username);

    List<SecurityPolicy> policies = configService.policies();
    List<UserClearance> userClearances = clearanceService.list(username);
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
    pageContext.setAttribute("userClearances", userClearances);
    pageContext.setAttribute("policyNamesById", policyNamesById);
    pageContext.setAttribute("formats", LabelFormat.values());
%>
<html>
<head><title>Spiffing user clearance</title><meta name="pageID" content="spiffing-user-clearance"/></head>
<body>
<c:if test="${not empty error}"><admin:infoBox type="error"><c:out value="${error}"/></admin:infoBox></c:if>
<c:if test="${param.saved eq 'true' and empty error}"><admin:infoBox type="success">User clearance saved.</admin:infoBox></c:if>

<admin:contentBox title="Clearances for ${username}">
    <c:choose>
        <c:when test="${empty userClearances}">
            <p>This user currently has no clearance configured for any loaded policy.</p>
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
                    <c:forEach var="uc" items="${userClearances}">
                        <tr>
                            <td><c:out value="${empty policyNamesById[uc.policyId()] ? uc.policyId() : policyNamesById[uc.policyId()]}"/> (<code><c:out value="${uc.policyId()}"/></code>)</td>
                            <td><c:out value="${uc.format()}"/></td>
                            <td>
                                <form action="spiffing-user-clearance.jsp" method="post" style="display:inline;" onsubmit="return confirm('Delete this user clearance?');">
                                    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
                                    <input type="hidden" name="username" value="<c:out value='${username}'/>"/>
                                    <input type="hidden" name="action" value="deleteUserClearance"/>
                                    <input type="hidden" name="policyId" value="<c:out value='${uc.policyId()}'/>"/>
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
        <admin:infoBox type="info">No security policies have been configured yet. Add one under <a href="spiffing-settings.jsp">Spiffing security labels</a> before setting a user clearance.</admin:infoBox>
    </c:when>
    <c:otherwise>
        <form action="spiffing-user-clearance.jsp" method="post">
            <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"/>
            <input type="hidden" name="username" value="<c:out value='${username}'/>"/>
            <input type="hidden" name="action" value="saveUserClearance"/>
            <admin:contentBox title="Add / Update Clearance">
                <p><label for="policyId">Policy</label>
                    <select id="policyId" name="policyId" onchange="updateUserClearancePolicy()">
                        <c:forEach var="p" items="${policies}">
                            <option value="${p.id()}">${p.name()} (${p.id()})</option>
                        </c:forEach>
                    </select></p>

                <div style="margin-bottom: 12px; background: #f9f9f9; padding: 10px; border: 1px solid #e5e5e5; border-radius: 4px;">
                    <label style="margin-right: 15px; font-weight: bold;">Configuration Mode:</label>
                    <label style="margin-right: 20px; cursor: pointer;">
                        <input type="radio" name="clearanceMode" id="clearanceModeBuilder" value="builder" checked="checked" onchange="toggleUserClearanceMode()"/>
                        <strong>Interactive Builder</strong>
                    </label>
                    <label style="cursor: pointer;">
                        <input type="radio" name="clearanceMode" id="clearanceModeRaw" value="raw" onchange="toggleUserClearanceMode()"/>
                        <strong>Raw Payload / File Upload</strong>
                    </label>
                </div>

                <p><label for="clearanceFormat">Format</label>
                    <select id="clearanceFormat" name="clearanceFormat">
                        <c:forEach var="format" items="${formats}"><option value="${format}">${format}</option></c:forEach>
                    </select></p>

                <div id="userClearanceBuilderContainer" style="margin-bottom: 15px;">
                    <c:forEach var="p" items="${policies}" varStatus="pSt">
                        <c:set var="meta" value="${policyMetadataMap[p.id()]}"/>
                        <div id="user_policy_builder_${p.id()}" class="user-policy-builder-panel" style="${pSt.first ? '' : 'display:none;'}">
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
                                                            <td><input type="checkbox" name="clearanceClassifications" value="${cls.lacv()}" id="user_cls_${pSt.index}_${st.index}"/></td>
                                                            <td><label for="user_cls_${pSt.index}_${st.index}"><strong><c:out value="${cls.name()}"/></strong></label></td>
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
                                        <c:when test="${empty meta.clearanceTagSets()}">
                                            <p style="color: #888;"><em>No non-informative security categories defined in policy.</em></p>
                                        </c:when>
                                        <c:otherwise>
                                            <c:forEach var="ts" items="${meta.clearanceTagSets()}" varStatus="tsSt">
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
                                                                    <td><input type="checkbox" name="clearanceCategories" value="${cat.spec()}" id="user_cat_${pSt.index}_${tsSt.index}_${catSt.index}"/></td>
                                                                    <td><label for="user_cat_${pSt.index}_${tsSt.index}_${catSt.index}"><c:out value="${cat.name()}"/></label></td>
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

                <div id="userClearanceRawContainer" style="display: none; margin-bottom: 15px;">
                    <p><label for="clearanceFile">Upload clearance file (.xml, .b64, .ber, .der, .txt): </label>
                        <input type="file" id="clearanceFile" accept=".xml,.b64,.ber,.der,.txt,text/xml,application/xml,text/plain"/>
                    </p>
                    <p><label for="clearance">Clearance payload (maximum 64 KiB)</label></p>
                    <textarea id="clearance" name="clearance" cols="100" rows="6" maxlength="65536"></textarea>
                </div>

                <p><input type="submit" value="Save User Clearance"/></p>
            </admin:contentBox>
        </form>
    </c:otherwise>
</c:choose>

<script type="text/javascript">
    function toggleUserClearanceMode() {
        var isBuilder = document.getElementById("clearanceModeBuilder").checked;
        var builderBox = document.getElementById("userClearanceBuilderContainer");
        var rawBox = document.getElementById("userClearanceRawContainer");
        if (builderBox && rawBox) {
            builderBox.style.display = isBuilder ? "" : "none";
            rawBox.style.display = isBuilder ? "none" : "";
        }
    }

    function updateUserClearancePolicy() {
        var select = document.getElementById("policyId");
        if (!select) return;
        var selectedPolicyId = select.value;
        var panels = document.querySelectorAll(".user-policy-builder-panel");
        for (var i = 0; i < panels.length; i++) {
            panels[i].style.display = "none";
        }
        var activePanel = document.getElementById("user_policy_builder_" + selectedPolicyId);
        if (activePanel) {
            activePanel.style.display = "";
        }
    }

    document.addEventListener("DOMContentLoaded", function() {
        var fileInput = document.getElementById("clearanceFile");
        var textarea = document.getElementById("clearance");
        var formatSelect = document.getElementById("clearanceFormat");
        if (!fileInput || !textarea) return;

        function handleFile(file) {
            if (!file) return;
            if (file.size > 65536) {
                alert("The selected file exceeds the 64 KiB limit.");
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
                    textarea.value = btoa(binary);
                    if (formatSelect) formatSelect.value = "ESS";
                };
                reader.onerror = function() { alert("Failed to read the binary file."); };
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
                        } else {
                            formatSelect.value = "ESS";
                        }
                    }
                };
                reader.onerror = function() { alert("Failed to read the file."); };
                reader.readAsText(file, "UTF-8");
            }
        }

        fileInput.addEventListener("change", function(e) {
            var file = e.target.files && e.target.files[0];
            handleFile(file);
        });
    });
</script>

</body>
</html>
