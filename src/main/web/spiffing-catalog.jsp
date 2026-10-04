<%@ page contentType="text/html; charset=UTF-8"
    import="org.jivesoftware.openfire.XMPPServer,
            org.igniterealtime.openfire.spiffing.SpiffingPlugin,
            org.igniterealtime.openfire.spiffing.CatalogEntry,
            org.igniterealtime.openfire.spiffing.CatalogForm,
            org.igniterealtime.openfire.spiffing.SettingsForm,
            org.igniterealtime.openfire.spiffing.SecurityPolicy,
            org.igniterealtime.openfire.spiffing.LabelFormat,
            java.util.HashMap,
            java.util.List,
            java.util.Map,
            org.jivesoftware.util.CookieUtils,
            org.jivesoftware.util.StringUtils" %>
<%@ taglib uri="admin" prefix="admin" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%
    request.setCharacterEncoding("UTF-8");
    final SpiffingPlugin plugin = (SpiffingPlugin) XMPPServer.getInstance().getPluginManager().getPluginByName("Spiffing").orElseThrow();
    String error = null;
    String success = null;
    List<SecurityPolicy> policies = plugin.configurationService().policies();
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
                fields.put("mode", request.getParameter("catalogMode"));
                fields.put("classification", request.getParameter("catalogClassification"));

                String policyId = request.getParameter("catalogPolicyId");
                io.cridland.spiffing.Spif selectedSpif = null;
                if (policyId != null && !policyId.isBlank()) {
                    for (SecurityPolicy p : policies) {
                        if (p.id().equals(policyId)) {
                            try {
                                selectedSpif = new io.cridland.spiffing.Site().load(p.spif());
                            } catch (Exception ignored) {}
                            break;
                        }
                    }
                }
                String[] catArray = request.getParameterValues("catalogCategories");
                List<String> catList = catArray != null ? List.of(catArray) : List.of();

                CatalogForm.add(request.getMethod(), csrfCookie == null ? null : csrfCookie.getValue(), csrfParam,
                    fields, catList, selectedSpif, plugin.catalogService());
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

    Map<String, SettingsForm.PolicyClearanceMetadata> policyMetadataMap = new HashMap<>();
    for (SecurityPolicy p : policies) {
        SettingsForm.PolicyClearanceMetadata m = SettingsForm.inspectPolicy(p);
        if (m != null) {
            policyMetadataMap.put(p.id(), m);
        }
    }
    pageContext.setAttribute("policies", policies);
    pageContext.setAttribute("policyMetadataMap", policyMetadataMap);
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

        <c:choose>
            <c:when test="${not empty policies}">
                <div style="margin-top: 12px; margin-bottom: 12px; background: #f9f9f9; padding: 10px; border: 1px solid #e5e5e5; border-radius: 4px;">
                    <label style="margin-right: 15px; font-weight: bold;">Configuration Mode:</label>
                    <label style="margin-right: 20px; cursor: pointer;">
                        <input type="radio" name="catalogMode" id="catalogModeBuilder" value="builder" checked="checked" onchange="toggleCatalogMode()"/>
                        <strong>Interactive Builder</strong> (select policy, classification and categories)
                    </label>
                    <label style="cursor: pointer;">
                        <input type="radio" name="catalogMode" id="catalogModeRaw" value="raw" onchange="toggleCatalogMode()"/>
                        <strong>Raw Payload / File Upload</strong> (paste or upload XML/ESS/NATO label)
                    </label>
                </div>

                <p><label for="format">Format</label>
                    <select id="format" name="format">
                        <c:forEach var="format" items="${formats}"><option value="${format}">${format}</option></c:forEach>
                    </select></p>

                <div id="catalogBuilderContainer" style="margin-bottom: 15px;">
                    <p><label for="catalogPolicyId">Security Policy</label>
                        <select id="catalogPolicyId" name="catalogPolicyId" onchange="updateCatalogPolicy()">
                            <c:forEach var="p" items="${policies}">
                                <option value="${p.id()}">${p.name()} (${p.id()})</option>
                            </c:forEach>
                        </select></p>

                    <c:forEach var="p" items="${policies}" varStatus="pSt">
                        <c:set var="meta" value="${policyMetadataMap[p.id()]}"/>
                        <div id="catalog_policy_builder_${p.id()}" class="catalog-policy-builder-panel" style="${pSt.first ? '' : 'display:none;'}">
                            <c:if test="${not empty meta}">
                                <div style="margin-bottom: 12px;">
                                    <p><strong><c:out value="${p.name()}"/> &mdash; Classification Level</strong> (select one)</p>
                                    <c:choose>
                                        <c:when test="${empty meta.classifications()}">
                                            <p style="color: #888;"><em>No classifications defined in policy.</em></p>
                                        </c:when>
                                        <c:otherwise>
                                            <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                                <thead>
                                                    <tr>
                                                        <th width="5%">Select</th>
                                                        <th width="35%">Classification</th>
                                                        <th width="20%">LACV</th>
                                                        <th width="20%">Hierarchy</th>
                                                        <th width="20%">Color</th>
                                                    </tr>
                                                </thead>
                                                <tbody>
                                                    <c:forEach var="c" items="${meta.classifications()}" varStatus="clsSt">
                                                        <tr>
                                                            <td>
                                                                <input type="radio" name="catalogClassification" value="${c.lacv()}" id="cat_cls_${pSt.index}_${clsSt.index}"
                                                                    ${clsSt.first ? 'checked="checked"' : ''}/>
                                                            </td>
                                                            <td>
                                                                <label for="cat_cls_${pSt.index}_${clsSt.index}">
                                                                    <strong><c:out value="${c.name()}"/></strong>
                                                                    <c:if test="${c.obsolete()}"><span style="color:#c00;">(Obsolete)</span></c:if>
                                                                </label>
                                                            </td>
                                                            <td><code><c:out value="${c.lacv()}"/></code></td>
                                                            <td><c:out value="${c.hierarchy()}"/></td>
                                                            <td>
                                                                <c:if test="${not empty c.color()}">
                                                                    <span style="display:inline-block; width:14px; height:14px; background:<c:out value='${c.color()}'/>; border:1px solid #ccc; vertical-align:middle; margin-right:4px;"></span>
                                                                    <c:out value="${c.color()}"/>
                                                                </c:if>
                                                                <c:if test="${empty c.color()}">-</c:if>
                                                            </td>
                                                        </tr>
                                                    </c:forEach>
                                                </tbody>
                                            </table>
                                        </c:otherwise>
                                    </c:choose>
                                </div>

                                <div style="margin-bottom: 12px;">
                                    <p><strong><c:out value="${p.name()}"/> &mdash; Security Categories and Tag Sets</strong></p>
                                    <c:choose>
                                        <c:when test="${empty meta.tagSets()}">
                                            <p style="color: #888;"><em>No security categories defined in policy.</em></p>
                                        </c:when>
                                        <c:otherwise>
                                            <c:forEach var="ts" items="${meta.tagSets()}" varStatus="tsSt">
                                                <fieldset style="margin-top: 8px; margin-bottom: 12px; padding: 10px; border: 1px solid #ddd; border-radius: 4px;">
                                                    <legend style="font-weight: bold; padding: 0 6px;"><c:out value="${ts.name()}"/> (<code><c:out value="${ts.id()}"/></code>)</legend>
                                                    <table class="jive-table" cellpadding="3" cellspacing="0" border="0" width="100%">
                                                        <thead>
                                                            <tr>
                                                                <th width="5%">Include</th>
                                                                <th width="45%">Category</th>
                                                                <th width="25%">Tag / Type</th>
                                                                <th width="25%">LACV</th>
                                                            </tr>
                                                        </thead>
                                                        <tbody>
                                                            <c:forEach var="cat" items="${ts.categories()}" varStatus="catSt">
                                                                <tr>
                                                                    <td>
                                                                        <input type="checkbox" name="catalogCategories" value="${cat.spec()}" id="cat_cat_${pSt.index}_${tsSt.index}_${catSt.index}"/>
                                                                    </td>
                                                                    <td><label for="cat_cat_${pSt.index}_${tsSt.index}_${catSt.index}"><c:out value="${cat.name()}"/></label></td>
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

                <div id="catalogRawContainer" style="display: none; margin-bottom: 15px;">
                    <p><label for="catalogLabelFile">Upload label file (.xml, .b64, .ber, .der, .txt): </label>
                        <input type="file" id="catalogLabelFile" accept=".xml,.b64,.ber,.der,.txt,text/xml,application/xml,text/plain"/>
                    </p>
                    <p><label for="label">Or paste / edit the raw label payload below (maximum 64 KiB):</label></p>
                    <textarea id="label" name="label" cols="100" rows="8" maxlength="65536"></textarea>
                </div>
            </c:when>
            <c:otherwise>
                <p><label for="format">Format</label>
                    <select id="format" name="format">
                        <c:forEach var="format" items="${formats}"><option value="${format}">${format}</option></c:forEach>
                    </select></p>
                <p><label for="label">Label (maximum 64 KiB of text)</label></p>
                <textarea id="label" name="label" cols="100" rows="10" maxlength="65536" required></textarea>
            </c:otherwise>
        </c:choose>

        <p><label><input type="checkbox" name="isDefault" value="true"/> Make this the catalogue default entry</label></p>
        <p>The label must pass both policy validation and the server clearance check before it can be added.</p>
        <input type="submit" value="Add entry"/>
    </form>
</admin:contentBox>

<script type="text/javascript">
    function toggleCatalogMode() {
        var builderRadio = document.getElementById("catalogModeBuilder");
        if (!builderRadio) return;
        var isBuilder = builderRadio.checked;
        var builderBox = document.getElementById("catalogBuilderContainer");
        var rawBox = document.getElementById("catalogRawContainer");
        if (builderBox && rawBox) {
            builderBox.style.display = isBuilder ? "" : "none";
            rawBox.style.display = isBuilder ? "none" : "";
        }
    }

    function updateCatalogPolicy() {
        var select = document.getElementById("catalogPolicyId");
        if (!select) return;
        var selectedPolicyId = select.value;
        var panels = document.querySelectorAll(".catalog-policy-builder-panel");
        for (var i = 0; i < panels.length; i++) {
            panels[i].style.display = "none";
        }
        var activePanel = document.getElementById("catalog_policy_builder_" + selectedPolicyId);
        if (activePanel) {
            activePanel.style.display = "";
        }
    }

    function setupCatalogFileUpload(fileInputId, textareaId, formatSelectId) {
        var fileInput = document.getElementById(fileInputId);
        var textarea = document.getElementById(textareaId);
        var formatSelect = formatSelectId ? document.getElementById(formatSelectId) : null;
        if (!fileInput || !textarea) return;

        function handleFile(file) {
            if (!file) return;
            if (file.size > 65536) {
                alert("The selected label file exceeds the 64 KiB limit.");
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
                    alert("Failed to read the binary label file.");
                };
                reader.readAsArrayBuffer(file);
            } else {
                var reader = new FileReader();
                reader.onload = function(evt) {
                    var content = evt.target.result;
                    textarea.value = content;
                    if (formatSelect) {
                        var trimmed = content.trim();
                        if (trimmed.startsWith("<") && (trimmed.includes("ConfidentialityInformation") || trimmed.includes("confidentialityinformation") || trimmed.includes("urn:nato:stanag:4774"))) {
                            formatSelect.value = "NATO";
                        } else if (trimmed.startsWith("<")) {
                            formatSelect.value = "XML";
                        } else if (!trimmed.startsWith("<")) {
                            formatSelect.value = "ESS";
                        }
                    }
                };
                reader.onerror = function() {
                    alert("Failed to read the label file.");
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

    document.addEventListener("DOMContentLoaded", function() {
        setupCatalogFileUpload("catalogLabelFile", "label", "format");
    });
</script>
</body>
</html>
