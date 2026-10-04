package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Category;
import io.cridland.spiffing.Classification;
import io.cridland.spiffing.Clearance;
import io.cridland.spiffing.Lacv;
import io.cridland.spiffing.Site;
import io.cridland.spiffing.Spif;
import io.cridland.spiffing.Tag;
import io.cridland.spiffing.TagSet;

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
        List<PolicyTagSetInfo> tagSets,
        Set<String> activeClassificationLacvs,
        Set<String> activeCategorySpecs,
        String displayMarking
    ) {}

    public static void validateCsrf(String method, String cookie, String token) {
        if (!"POST".equals(method) || cookie == null || token == null || token.isEmpty()
            || !MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("The form expired. Please try again.");
        }
    }

    public static SecurityPolicy parsePolicy(Map<String, String> fields) {
        return parsePolicy(fields, null, null);
    }

    public static SecurityPolicy parsePolicy(Map<String, String> fields, Collection<String> classifications, Collection<String> categories) {
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
            boolean hasClassifications = classifications != null && classifications.stream().anyMatch(c -> c != null && !c.isBlank());
            boolean hasCategories = categories != null && categories.stream().anyMatch(c -> c != null && !c.isBlank());
            if (hasClassifications || hasCategories) {
                Clearance c = buildClearance(s, classifications, categories);
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

        String defaultLabel = fields.get("defaultLabel");
        if (defaultLabel != null && defaultLabel.isBlank()) defaultLabel = null;
        String defaultLabelFormatStr = fields.get("defaultLabelFormat");
        LabelFormat defaultLabelFormat = defaultLabel != null && defaultLabelFormatStr != null && !defaultLabelFormatStr.isBlank()
            ? LabelFormat.valueOf(defaultLabelFormatStr) : null;

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
                clearance.addCategory(cat);
            }
        }
        return clearance;
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
                    if (tag.type().name().equalsIgnoreCase("informative")) continue;
                    for (Category cat : tag.categories()) {
                        if (cat.lacv().toString().equals(lacv)) {
                            return cat;
                        }
                    }
                }
            }
        }

        // Fallback: search all tag sets and non-informative tags by category LACV
        for (TagSet ts : spif.tagSets()) {
            for (Tag tag : ts.tags()) {
                if (tag.type().name().equalsIgnoreCase("informative")) continue;
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
        return inspectSpifDocument(policy.spif(), policy.clearance(), policy.clearanceFormat());
    }

    public static PolicyClearanceMetadata inspectSpifDocument(String spifDocument, String rawClearance, LabelFormat format) {
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

        List<PolicyTagSetInfo> tagSetList = new ArrayList<>();
        for (TagSet ts : spif.tagSets()) {
            List<PolicyCategoryInfo> catList = new ArrayList<>();
            for (Tag tag : ts.tags()) {
                if (tag.type().name().equalsIgnoreCase("informative")) {
                    continue; // Informative categories are not clearance privileges
                }
                for (Category cat : tag.categories()) {
                    String spec = ts.id() + "|" + tag.type().name() + "|" + cat.lacv().toString();
                    catList.add(new PolicyCategoryInfo(
                        cat.name(),
                        cat.lacv().toString(),
                        tag.type().name(),
                        ts.id(),
                        tag.name(),
                        spec
                    ));
                }
            }
            if (!catList.isEmpty()) {
                tagSetList.add(new PolicyTagSetInfo(ts.id(), ts.name(), List.copyOf(catList)));
            }
        }

        Set<String> activeLacvs = new LinkedHashSet<>();
        Set<String> activeSpecs = new LinkedHashSet<>();
        String displayMarking = null;

        if (rawClearance != null && !rawClearance.isBlank() && format != null) {
            try {
                Clearance parsed = site.clearance(format.decode(rawClearance), format.format);
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

        return new PolicyClearanceMetadata(
            spif.policyId(),
            spif.name(),
            List.copyOf(classList),
            List.copyOf(tagSetList),
            Collections.unmodifiableSet(activeLacvs),
            Collections.unmodifiableSet(activeSpecs),
            displayMarking
        );
    }
}
