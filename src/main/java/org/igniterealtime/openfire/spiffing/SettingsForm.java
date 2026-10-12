package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Category;
import io.cridland.spiffing.Classification;
import io.cridland.spiffing.Clearance;
import io.cridland.spiffing.Lacv;
import io.cridland.spiffing.Site;
import io.cridland.spiffing.Spif;
import io.cridland.spiffing.Tag;
import io.cridland.spiffing.TagSet;
import io.cridland.spiffing.TagType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Validates form submissions and provides inspection and builder models for the Admin Console settings page.
 */
public final class SettingsForm {
    private SettingsForm() {}

    public record PolicyClassificationInfo(String name, String lacv, long hierarchy, String color, boolean obsolete) {}

    public record PolicyCategoryInfo(String name, String lacv, String tagType, String tagSetId, String tagName, String spec) {}

    public record PolicyTagSetInfo(String id, String name, List<PolicyCategoryInfo> categories) {}

    public record PolicyClearanceMetadata(
        String policyId,
        String policyName,
        List<PolicyClassificationInfo> classifications,
        List<PolicyTagSetInfo> clearanceTagSets,
        Set<String> activeClassificationLacvs,
        Set<String> activeCategorySpecs,
        String displayMarking,
        List<PolicyTagSetInfo> labelTagSets,
        String activeLabelClassificationLacv,
        Set<String> activeLabelCategorySpecs,
        String defaultLabelDisplayMarking
    ) {
        public List<PolicyTagSetInfo> tagSets() {
            return clearanceTagSets;
        }
    }

    public static void validateCsrf(String method, String cookie, String token) {
        if (!"POST".equals(method) || cookie == null || token == null || token.isEmpty()
            || !MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("The form expired. Please try again.");
        }
    }

    public static SecurityPolicy parsePolicy(Map<String, String> fields) {
        return parsePolicy(fields, null, null, null, null);
    }

    public static SecurityPolicy parsePolicy(Map<String, String> fields, Collection<String> classifications, Collection<String> categories) {
        return parsePolicy(fields, classifications, categories, null, null);
    }

    public static SecurityPolicy parsePolicy(
        Map<String, String> fields,
        Collection<String> clearanceClassifications,
        Collection<String> clearanceCategories,
        String defaultLabelClassification,
        Collection<String> defaultLabelCategories
    ) {
        String spif = fields.get("spif");
        if (spif == null || spif.isBlank()) {
            throw new IllegalArgumentException("SPIF document is required.");
        }
        Site site = new Site();
        Spif s;
        try {
            s = site.load(spif);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The document is not a valid Open XML SPIF.", e);
        }

        String clearanceMode = fields.get("clearanceMode");
        String clearance;
        LabelFormat clearanceFormat;

        if ("builder".equalsIgnoreCase(clearanceMode)) {
            String clearanceFormatStr = fields.get("clearanceFormat");
            clearanceFormat = clearanceFormatStr != null && !clearanceFormatStr.isBlank()
                ? LabelFormat.valueOf(clearanceFormatStr) : LabelFormat.XML;
            boolean hasClassifications = clearanceClassifications != null && clearanceClassifications.stream().anyMatch(c -> c != null && !c.isBlank());
            boolean hasCategories = clearanceCategories != null && clearanceCategories.stream().anyMatch(c -> c != null && !c.isBlank());
            if (hasClassifications || hasCategories) {
                Clearance c = buildClearance(s, clearanceClassifications, clearanceCategories);
                clearance = clearanceFormat.encode(c);
            } else {
                clearance = null;
                clearanceFormat = null;
            }
        } else {
            clearance = fields.get("clearance");
            if (clearance != null && clearance.isBlank()) clearance = null;
            String clearanceFormatStr = fields.get("clearanceFormat");
            clearanceFormat = clearance != null && clearanceFormatStr != null && !clearanceFormatStr.isBlank()
                ? LabelFormat.valueOf(clearanceFormatStr) : null;
        }

        String defaultLabelMode = fields.get("defaultLabelMode");
        String defaultLabel;
        LabelFormat defaultLabelFormat;

        if ("builder".equalsIgnoreCase(defaultLabelMode)) {
            String defaultLabelFormatStr = fields.get("defaultLabelFormat");
            defaultLabelFormat = defaultLabelFormatStr != null && !defaultLabelFormatStr.isBlank()
                ? LabelFormat.valueOf(defaultLabelFormatStr) : LabelFormat.XML;
            if (defaultLabelClassification != null && !defaultLabelClassification.isBlank()) {
                io.cridland.spiffing.Label l = buildLabel(s, defaultLabelClassification, defaultLabelCategories);
                defaultLabel = defaultLabelFormat.encode(l);
            } else {
                defaultLabel = null;
                defaultLabelFormat = null;
            }
        } else {
            defaultLabel = fields.get("defaultLabel");
            if (defaultLabel != null && defaultLabel.isBlank()) defaultLabel = null;
            String defaultLabelFormatStr = fields.get("defaultLabelFormat");
            defaultLabelFormat = defaultLabel != null && defaultLabelFormatStr != null && !defaultLabelFormatStr.isBlank()
                ? LabelFormat.valueOf(defaultLabelFormatStr) : null;
        }

        boolean isDefault = Boolean.parseBoolean(fields.get("isDefault"));

        return new SecurityPolicy(s.policyId(), s.name(), spif, clearance, clearanceFormat, defaultLabel, defaultLabelFormat, isDefault);
    }

    public static PeerClearance parsePeerClearance(Map<String, String> fields) {
        return parsePeerClearance(fields, null, null, null);
    }

    public static PeerClearance parsePeerClearance(Map<String, String> fields, Collection<String> classifications, Collection<String> categories, Spif spif) {
        String domain = fields.get("domain");
        if (domain == null) domain = "";
        String policyId = fields.get("policyId");
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("Policy selection is required for peer clearance.");
        }

        String clearanceMode = fields.get("clearanceMode");
        if (clearanceMode == null || clearanceMode.isBlank()) {
            clearanceMode = fields.get("peerClearanceMode");
        }

        String clearance;
        LabelFormat format;
        String formatStr = fields.get("format");
        if (formatStr == null || formatStr.isBlank()) {
            formatStr = fields.get("peerClearanceFormat");
        }

        if ("builder".equalsIgnoreCase(clearanceMode)) {
            format = formatStr != null && !formatStr.isBlank() ? LabelFormat.valueOf(formatStr) : LabelFormat.ESS;
            boolean hasClassifications = classifications != null && classifications.stream().anyMatch(c -> c != null && !c.isBlank());
            boolean hasCategories = categories != null && categories.stream().anyMatch(c -> c != null && !c.isBlank());
            if (!hasClassifications && !hasCategories) {
                throw new IllegalArgumentException("At least one classification or category must be selected for peer clearance in builder mode.");
            }
            if (spif == null) {
                throw new IllegalArgumentException("SPIF policy is required to build peer clearance.");
            }
            Clearance c = buildClearance(spif, classifications, categories);
            clearance = format.encode(c);
        } else {
            clearance = fields.get("clearance");
            if (clearance == null || clearance.isBlank()) {
                clearance = fields.get("peerClearance");
            }
            if (clearance == null || clearance.isBlank()) {
                throw new IllegalArgumentException("Clearance document is required.");
            }
            format = formatStr != null && !formatStr.isBlank() ? LabelFormat.valueOf(formatStr) : LabelFormat.ESS;
        }

        String id = UUID.randomUUID().toString();
        return new PeerClearance(id, domain.trim(), policyId.trim(), clearance, format);
    }

    public static Clearance buildClearance(Spif spif, Collection<String> classificationLacvs, Collection<String> categorySpecs) {
        Objects.requireNonNull(spif, "spif");
        Clearance clearance = new Clearance(spif);
        if (classificationLacvs != null) {
            for (String lacvStr : classificationLacvs) {
                if (lacvStr == null || lacvStr.isBlank()) continue;
                try {
                    clearance.addClassification(Lacv.parse(lacvStr.trim()));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Invalid classification LACV: " + lacvStr, e);
                }
            }
        }
        if (categorySpecs != null) {
            for (String spec : categorySpecs) {
                if (spec == null || spec.isBlank()) continue;
                Category cat = resolveCategory(spif, spec.trim());
                if (cat.tag().type() == TagType.informative) {
                    throw new IllegalArgumentException("Informative categories cannot be used in a clearance: " + spec);
                }
                clearance.addCategory(cat);
            }
        }
        return clearance;
    }

    public static io.cridland.spiffing.Label buildLabel(Spif spif, String classificationLacv, Collection<String> categorySpecs) {
        Objects.requireNonNull(spif, "spif");
        if (classificationLacv == null || classificationLacv.isBlank()) {
            throw new IllegalArgumentException("A classification is required to build a label.");
        }
        Lacv lacv;
        try {
            lacv = Lacv.parse(classificationLacv.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid classification LACV: " + classificationLacv, e);
        }
        io.cridland.spiffing.Label label = new io.cridland.spiffing.Label(spif, lacv);
        if (categorySpecs != null) {
            for (String spec : categorySpecs) {
                if (spec == null || spec.isBlank()) continue;
                Category cat = resolveCategory(spif, spec.trim());
                label.addCategory(cat);
            }
        }
        return label;
    }

    public static Category resolveCategory(Spif spif, String spec) {
        Objects.requireNonNull(spif, "spif");
        Objects.requireNonNull(spec, "spec");

        if (spec.contains("|")) {
            String[] parts = spec.split("\\|", 3);
            if (parts.length == 3) {
                String tagSetId = parts[0];
                String tagType = parts[1];
                String lacv = parts[2];
                TagSet ts = spif.tagSetLookup(tagSetId);
                if (ts == null) {
                    throw new IllegalArgumentException("Tag set not found for id: " + tagSetId);
                }
                for (Tag tag : ts.tags()) {
                    if (tag.type().name().equalsIgnoreCase(tagType)) {
                        for (Category cat : tag.categories()) {
                            if (cat.lacv().toString().equals(lacv)) {
                                return cat;
                            }
                        }
                    }
                }
            }
        } else if (spec.contains(":")) {
            String[] parts = spec.split(":", 2);
            if (parts.length == 2) {
                String tagSetId = parts[0];
                String lacv = parts[1];
                TagSet ts = spif.tagSetLookup(tagSetId);
                if (ts == null) {
                    throw new IllegalArgumentException("Tag set not found for id: " + tagSetId);
                }
                for (Tag tag : ts.tags()) {
                    for (Category cat : tag.categories()) {
                        if (cat.lacv().toString().equals(lacv)) {
                            return cat;
                        }
                    }
                }
            }
        }

        // Fallback: search all tag sets by category LACV or name
        for (TagSet ts : spif.tagSets()) {
            for (Tag tag : ts.tags()) {
                for (Category cat : tag.categories()) {
                    if (cat.lacv().toString().equals(spec) || cat.name().equalsIgnoreCase(spec)) {
                        return cat;
                    }
                }
            }
        }

        throw new IllegalArgumentException("Category not found for spec: " + spec);
    }

    public static PolicyClearanceMetadata inspectPolicy(SecurityPolicy policy) {
        if (policy == null || policy.spif() == null || policy.spif().isBlank()) {
            return null;
        }
        return inspectSpifDocument(policy.spif(), policy.clearance(), policy.clearanceFormat(), policy.defaultLabel(), policy.defaultLabelFormat());
    }

    public static PolicyClearanceMetadata inspectSpifDocument(String spifDocument, String rawClearance, LabelFormat format) {
        return inspectSpifDocument(spifDocument, rawClearance, format, null, null);
    }

    public static PolicyClearanceMetadata inspectSpifDocument(
        String spifDocument,
        String rawClearance,
        LabelFormat clearanceFormat,
        String rawDefaultLabel,
        LabelFormat defaultLabelFormat
    ) {
        if (spifDocument == null || spifDocument.isBlank()) return null;
        Site site = new Site();
        Spif spif;
        try {
            spif = site.load(spifDocument);
        } catch (RuntimeException e) {
            return null;
        }

        List<PolicyClassificationInfo> classList = new ArrayList<>();
        for (Classification c : spif.classifications()) {
            classList.add(new PolicyClassificationInfo(
                c.name(),
                c.lacv().toString(),
                c.hierarchy(),
                c.color(),
                c.obsolete()
            ));
        }

        List<PolicyTagSetInfo> clearanceTagSetList = new ArrayList<>();
        List<PolicyTagSetInfo> labelTagSetList = new ArrayList<>();
        for (TagSet ts : spif.tagSets()) {
            List<PolicyCategoryInfo> clearanceCatList = new ArrayList<>();
            List<PolicyCategoryInfo> labelCatList = new ArrayList<>();
            for (Tag tag : ts.tags()) {
                boolean isInformative = tag.type() == TagType.informative;
                for (Category cat : tag.categories()) {
                    String spec = ts.id() + "|" + tag.type().name() + "|" + cat.lacv().toString();
                    PolicyCategoryInfo catInfo = new PolicyCategoryInfo(
                        cat.name(),
                        cat.lacv().toString(),
                        tag.type().name(),
                        ts.id(),
                        tag.name(),
                        spec
                    );
                    labelCatList.add(catInfo);
                    if (!isInformative) {
                        clearanceCatList.add(catInfo);
                    }
                }
            }
            if (!clearanceCatList.isEmpty()) {
                clearanceTagSetList.add(new PolicyTagSetInfo(ts.id(), ts.name(), List.copyOf(clearanceCatList)));
            }
            if (!labelCatList.isEmpty()) {
                labelTagSetList.add(new PolicyTagSetInfo(ts.id(), ts.name(), List.copyOf(labelCatList)));
            }
        }

        Set<String> activeLacvs = new LinkedHashSet<>();
        Set<String> activeSpecs = new LinkedHashSet<>();
        String displayMarking = null;

        if (rawClearance != null && !rawClearance.isBlank() && clearanceFormat != null) {
            try {
                Clearance parsed = site.clearance(clearanceFormat.decode(rawClearance), clearanceFormat.format);
                if (parsed != null) {
                    for (Lacv lacv : parsed.classifications()) {
                        activeLacvs.add(lacv.toString());
                    }
                    for (Category cat : parsed.categories()) {
                        activeSpecs.add(cat.tag().tagSet().id() + "|" + cat.tag().type().name() + "|" + cat.lacv().toString());
                    }
                    displayMarking = spif.displayMarking(parsed);
                }
            } catch (Exception ignored) {
            }
        }

        String activeLabelClassificationLacv = null;
        Set<String> activeLabelCategorySpecs = new LinkedHashSet<>();
        String defaultLabelDisplayMarking = null;

        if (rawDefaultLabel != null && !rawDefaultLabel.isBlank() && defaultLabelFormat != null) {
            try {
                io.cridland.spiffing.Label parsedLabel = site.label(defaultLabelFormat.decode(rawDefaultLabel), defaultLabelFormat.format);
                if (parsedLabel != null) {
                    if (parsedLabel.classification() != null) {
                        activeLabelClassificationLacv = parsedLabel.classification().lacv().toString();
                    }
                    for (Category cat : parsedLabel.categories()) {
                        activeLabelCategorySpecs.add(cat.tag().tagSet().id() + "|" + cat.tag().type().name() + "|" + cat.lacv().toString());
                    }
                    defaultLabelDisplayMarking = spif.displayMarking(parsedLabel);
                }
            } catch (Exception ignored) {
            }
        }

        return new PolicyClearanceMetadata(
            spif.policyId(),
            spif.name(),
            List.copyOf(classList),
            List.copyOf(clearanceTagSetList),
            Collections.unmodifiableSet(activeLacvs),
            Collections.unmodifiableSet(activeSpecs),
            displayMarking,
            List.copyOf(labelTagSetList),
            activeLabelClassificationLacv,
            Collections.unmodifiableSet(activeLabelCategorySpecs),
            defaultLabelDisplayMarking
        );
    }
}
