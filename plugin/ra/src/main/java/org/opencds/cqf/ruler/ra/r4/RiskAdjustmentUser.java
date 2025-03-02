package org.opencds.cqf.ruler.ra.r4;

import static com.google.common.base.Preconditions.checkArgument;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.DetectedIssue;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.opencds.cqf.cql.evaluator.fhir.util.Canonicals;
import org.opencds.cqf.cql.evaluator.fhir.util.Ids;
import org.opencds.cqf.ruler.behavior.r4.MeasureReportUser;
import org.opencds.cqf.ruler.ra.RAConstants;
import org.opencds.cqf.ruler.utility.Operations;

import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.util.BundleUtil;

public interface RiskAdjustmentUser extends MeasureReportUser {

	default List<MeasureReport> getMeasureReports(
			String subject, String periodStart, String periodEnd) {
		return search(MeasureReport.class,
				SearchParameterMap.newSynchronous()
						.add(MeasureReport.SP_SUBJECT, new ReferenceParam(subject))
						.add(MeasureReport.SP_PERIOD, new DateRangeParam(periodStart, periodEnd)))
				.getAllResourcesTyped();
	}

	default List<MeasureReport> getMeasureReportsWithMeasureReference(
			String subject, String periodStart, String periodEnd, List<String> measureReference) {
		return getMeasureReports(subject, periodStart, periodEnd).stream().filter(
				report -> {
					if (report.hasMeasure()) {
						for (String ref : measureReference) {
							if (report.getMeasure().endsWith(ref))
								return true;
						}
					}
					return false;
				}).collect(Collectors.toList());
	}

	default List<Bundle> getMostRecentCodingGapReportBundles(String subject) {
		// Something like the following should work, but the composition search
		// parameter doesn't appear to be implemented
		// return search(Bundle.class, SearchParameterMap.newSynchronous()
		// .add(Bundle.SP_TYPE, new TokenParam("document"))
		// .add(Bundle.SP_COMPOSITION, new ReferenceParam("Composition",
		// Composition.SP_SUBJECT, subject))
		// .setSort(new SortSpec(Bundle.SP_TIMESTAMP,
		// SortOrderEnum.DESC))).getAllResourcesTyped();
		return search(Bundle.class, SearchParameterMap.newSynchronous()
				.add(Bundle.SP_TYPE, new TokenParam("document"))
				.add("_profile", new UriParam(RAConstants.CODING_GAP_BUNDLE_URL))
				.setSort(new SortSpec(Bundle.SP_TIMESTAMP, SortOrderEnum.DESC)))
				.getAllResourcesTyped();
	}

	default Bundle getMostRecentCodingGapReportBundle(String subject) {
		return getMostRecentCodingGapReportBundles(subject).stream().filter(
				bundle -> bundle.hasEntry() && bundle.getEntryFirstRep().hasResource()
						&& bundle.getEntryFirstRep().getResource() instanceof Composition
						&& ((Composition) bundle.getEntryFirstRep().getResource()).getSubject().getReference()
								.endsWith(subject))
				.collect(Collectors.toList()).stream().findFirst().orElse(null);
	}

	default Bundle getMostRecentCodingGapReportBundle(String subject, Date periodStart, Date periodEnd) {
		return getMostRecentCodingGapReportBundles(subject).stream().filter(
				bundle -> bundle.hasEntry() && bundle.getEntryFirstRep().hasResource()
						&& bundle.getEntryFirstRep().getResource() instanceof Composition
						&& ((Composition) bundle.getEntryFirstRep().getResource()).getSubject().getReference()
								.endsWith(subject)
						&& bundle.getEntry().stream().anyMatch(
								entry -> entry.hasResource() && entry.getResource() instanceof MeasureReport
										&& ((MeasureReport) entry.getResource()).hasDate()
										&& ((MeasureReport) entry.getResource()).getDate().compareTo(periodStart) >= 0
										&& ((MeasureReport) entry.getResource()).getDate().compareTo(periodEnd) <= 0))
				.collect(Collectors.toList()).stream().findFirst().orElse(null);
	}

	default Bundle getMostRecentCodingGapReportBundle(String subject, String measureId, Date periodStart,
			Date periodEnd) {
		return getMostRecentCodingGapReportBundles(subject).stream().filter(
				bundle -> bundle.hasEntry() && bundle.getEntryFirstRep().hasResource()
						&& bundle.getEntryFirstRep().getResource() instanceof Composition
						&& ((Composition) bundle.getEntryFirstRep().getResource()).getSubject().getReference()
								.endsWith(subject)
						&& bundle.getEntry().stream().anyMatch(
								entry -> entry.hasResource() && entry.getResource() instanceof MeasureReport
										&& ((MeasureReport) entry.getResource()).hasMeasure()
										&& ((MeasureReport) entry.getResource()).getMeasure().endsWith(measureId)
										&& ((MeasureReport) entry.getResource()).hasDate()
										&& ((MeasureReport) entry.getResource()).getDate().compareTo(periodStart) >= 0
										&& ((MeasureReport) entry.getResource()).getDate().compareTo(periodEnd) <= 0))
				.collect(Collectors.toList()).stream().findFirst().orElse(null);
	}

	default List<MeasureReport> getReportsFromBundles(List<Bundle> bundles) {
		List<MeasureReport> reports = new ArrayList<>();
		for (Bundle bundle : bundles) {
			reports.addAll(BundleUtil.toListOfResourcesOfType(getFhirContext(), bundle, MeasureReport.class));
		}
		return reports;
	}

	default Composition getCompositionFromBundle(Bundle bundle) {
		return BundleUtil.toListOfResourcesOfType(
				getFhirContext(), bundle, Composition.class).stream().findFirst().orElse(null);
	}

	default MeasureReport getReportFromBundle(Bundle bundle) {
		return BundleUtil.toListOfResourcesOfType(
				getFhirContext(), bundle, MeasureReport.class).stream().findFirst().orElse(null);
	}

	default List<DetectedIssue> getIssuesFromBundle(Bundle bundle) {
		return BundleUtil.toListOfResourcesOfType(getFhirContext(), bundle, DetectedIssue.class);
	}

	default List<DetectedIssue> getMostRecentIssuesFromBundle(Bundle bundle) {
		List<DetectedIssue> issues = BundleUtil.toListOfResourcesOfType(getFhirContext(), bundle, DetectedIssue.class);
		// Sort issues by lastUpdated date
		issues.sort((issue1, issue2) -> {
			if (issue1.hasMeta() && issue1.getMeta().hasLastUpdated()
					&& issue2.hasMeta() && issue2.getMeta().hasLastUpdated()) {
				return issue2.getMeta().getLastUpdated().compareTo(issue1.getMeta().getLastUpdated());
			}
			throw new IllegalArgumentException(String.format(
					"All DetectedIssue resources within %s must have the lastUpdated meta property",
					bundle.getIdElement()));
		});
		return issues;
	}

	default void updateDetectedIssueStatusByCode(List<DetectedIssue> issues) {
		List<String> visitedCodes = new ArrayList<>();
		issues.forEach(
			issue -> {
				if (issue.hasCode() && issue.getCode().hasCoding() && issue.getCode().getCodingFirstRep().hasCode()) {
					String code = issue.getCode().getCodingFirstRep().getCode();
					if (visitedCodes.contains(code)) {
						issue.setStatus(DetectedIssue.DetectedIssueStatus.CANCELLED);
					}
					else {
						visitedCodes.add(code);
						issue.setStatus(DetectedIssue.DetectedIssueStatus.FINAL);
					}
					issue.getMeta().setLastUpdated(new Date());
				}
			}
		);
	}

	default List<DetectedIssue> getOriginalIssues(String measureReportReference) {
		return search(DetectedIssue.class, SearchParameterMap.newSynchronous()
				.add(DetectedIssue.SP_IMPLICATED, new ReferenceParam(measureReportReference))
				.add("_profile", new UriParam(RAConstants.ORIGINAL_ISSUE_PROFILE_URL)))
				.getAllResourcesTyped();
	}

	default List<DetectedIssue> getAssociatedIssues(String measureReportReference) {
		return search(DetectedIssue.class, SearchParameterMap.newSynchronous()
				.add(DetectedIssue.SP_IMPLICATED, new ReferenceParam(measureReportReference))
				.add("_profile", new UriParam(RAConstants.CLINICAL_EVALUATION_ISSUE_PROFILE_URL)))
				.getAllResourcesTyped();
	}

	default List<DetectedIssue> getAllIssues(String measureReportReference) {
		List<DetectedIssue> allIssues = new ArrayList<>();
		allIssues.addAll(getOriginalIssues(measureReportReference));
		allIssues.addAll(getAssociatedIssues(measureReportReference));
		return allIssues;
	}

	default List<Reference> getEvidenceById(String groupId, MeasureReport report) {
		List<Reference> evidence = new ArrayList<>();
		if (report.hasEvaluatedResource()) {
			report.getEvaluatedResource().forEach(
					resource -> {
						if (resource.hasExtension()) {
							resource.getExtensionsByUrl(RAConstants.GROUP_REFERENCE_URL).forEach(
									extension -> {
										if (extension.getValue().primitiveValue().equals(groupId)) {
											Reference detail = resource.copy();
											detail.removeExtension(RAConstants.GROUP_REFERENCE_URL);
											evidence.add(detail);
										}
									});
						}
					});
		}
		return evidence;
	}

	default Resource getAuthorFromBundle(Bundle bundle, Composition composition) {
		checkArgument(
				composition.hasAuthor() && composition.getAuthorFirstRep() != null,
				String.format("The author element is required for the composition (id=%s).",
						composition.hasIdElement() ? composition.getIdElement().getIdPart() : "null"));

		Reference author = composition.getAuthor().get(0);
		Optional<Bundle.BundleEntryComponent> authorResource = bundle.getEntry().stream()
				.filter(entry -> entry.getResource().hasIdElement() && entry.getResource().getIdElement().getIdPart()
						.equals(author.getReferenceElement().getIdPart()))
				.findFirst();
		checkArgument(
				authorResource.isPresent(),
				String.format("The author resource is a required entry in the bundle (id=%s).",
						bundle.hasIdElement() ? bundle.getIdElement().getIdPart() : "null"));
		return authorResource.get().getResource();
	}

	default DetectedIssue buildOriginalIssueStart(MeasureReport report, String groupId) {
		DetectedIssue originalIssue = new DetectedIssue();
		originalIssue.setIdElement(new IdType(
				"DetectedIssue", report.getIdElement().getIdPart() + "-" + groupId));
		originalIssue.setMeta(new Meta().addProfile(
				RAConstants.ORIGINAL_ISSUE_PROFILE_URL).setLastUpdated(new Date()));
		originalIssue.addExtension().setUrl(RAConstants.GROUP_REFERENCE_URL).setValue(new StringType(groupId));
		originalIssue.setStatus(DetectedIssue.DetectedIssueStatus.PRELIMINARY);
		Optional<MeasureReport.MeasureReportGroupComponent> groupComponent = report.getGroup().stream()
				.filter(group -> group.hasId() && group.getId().equals(groupId)).findFirst();
		checkArgument(
				groupComponent.isPresent(),
				String.format("The code element is required for the MeasureReport.group component (id=%s).", groupId));

		originalIssue.setCode(groupComponent.get().getCode());
		if (report.getSubject().getReference().startsWith("Patient/")) {
			originalIssue.setPatient(report.getSubject());
		}
		originalIssue.addImplicated(new Reference(report.getIdElement()));

		return originalIssue;
	}

	default List<DetectedIssue> buildOriginalIssues(MeasureReport report) {
		List<DetectedIssue> issues = new ArrayList<>();
		if (report.hasGroup()) {
			report.getGroup().forEach(
					group -> issues.add(buildOriginalIssueStart(report, group.getId()).setEvidence(
							getEvidenceById(group.getId(), report).stream().map(
									ref -> new DetectedIssue.DetectedIssueEvidenceComponent().addDetail(ref))
									.collect(Collectors.toList()))));
		}
		return issues;
	}

	default void updateComposition(Composition composition, MeasureReport report, List<DetectedIssue> issues) {
		composition.setMeta(RAConstants.COMPOSITION_META);
		composition.setSection(new ArrayList<>());
		resolveIssues(composition, report, issues);
	}

	default void updateCompositionToFinal(Composition composition, MeasureReport report, List<DetectedIssue> issues) {
		composition.setMeta(RAConstants.COMPOSITION_META);
		composition.setStatus(Composition.CompositionStatus.FINAL);
		composition.setSection(new ArrayList<>());
		resolveIssues(composition, report, issues);
	}

	default void validateApprovePrecondition(List<DetectedIssue> issues) {
		List<String> groupsWithFinalStatusIssues = new ArrayList<>();
		List<String> groups = new ArrayList<>();
		issues.forEach(
				issue -> {
					if (issue.hasExtension(RAConstants.GROUP_REFERENCE_URL)) {
						String groupId = issue.getExtensionByUrl(RAConstants.GROUP_REFERENCE_URL).getValue().primitiveValue();
						if (!groups.contains(groupId)) {
							groups.add(groupId);
						}
						if (issue.hasStatus() && issue.getStatus().toCode().equals("final")) {
							if (groupsWithFinalStatusIssues.contains(groupId)) {
								throw new IllegalArgumentException(
									"Found multiple DetectedIssue resources with a final status referencing the same MeasureReport group element.");
							}
							groupsWithFinalStatusIssues.add(groupId);
						}
					}
				});
		if (groups.size() != groupsWithFinalStatusIssues.size()) {
			throw new IllegalArgumentException(
				"Found MeasureReport group element without a DetectedIssue resource with final status. The $ra.approve-coding-gaps operation must be run prior to this operation."
			);
		}
	}

	default void updateMeasureReportGroup(MeasureReport report, String groupId, CodeableConcept codingGapRequest) {
		report.getGroup().forEach(
				group -> {
					if (group.getId().equals(groupId) && group.hasExtension(RAConstants.EVIDENCE_STATUS_URL)
							&& codingGapRequest.hasCoding() && codingGapRequest.getCodingFirstRep().hasCode()) {
						if (codingGapRequest.getCodingFirstRep().getCode().startsWith("closure")) {
							group.removeExtension(RAConstants.EVIDENCE_STATUS_URL);
							group.addExtension(RAConstants.EVIDENCE_STATUS_CLOSED_EXT);
						} else if (codingGapRequest.getCodingFirstRep().getCode().startsWith("invalidation")) {
							group.removeExtension(RAConstants.EVIDENCE_STATUS_URL);
							group.addExtension(RAConstants.EVIDENCE_STATUS_INVALID_EXT);
						} else {
							throw new IllegalArgumentException("Unknown/invalid coding-gap-request value: "
									+ codingGapRequest.getCodingFirstRep().getCode());
						}
					}
				});
	}

	default void createMeasureReportGroup(MeasureReport report, CodeableConcept hccCode) {
		// ensure no other groups have this code
		report.getGroup().forEach(
				group -> {
					if (group.hasCode() && group.getCode().hasCoding()
							&& group.getCode().getCodingFirstRep().hasCode() && hccCode.hasCoding()
							&& hccCode.getCodingFirstRep().hasCode()
							&& group.getCode().getCodingFirstRep().getCode().equals(hccCode.getCodingFirstRep().getCode())) {
						throw new IllegalArgumentException("The MeasureReport already contains a group with HCC Code: "
								+ hccCode.getCodingFirstRep().getCode());
					}
				});
		MeasureReport.MeasureReportGroupComponent newGroup = new MeasureReport.MeasureReportGroupComponent();
		newGroup.setId("create-" + UUID.randomUUID());
		newGroup.setCode(hccCode);
		newGroup.addExtension(RAConstants.SUSPECT_TYPE_NET_NEW_EXT);
		newGroup.addExtension(RAConstants.EVIDENCE_STATUS_CLOSED_EXT);
		report.addGroup(newGroup);
	}

	default void updateMeasureReportGroups(MeasureReport report, List<DetectedIssue> issues) {
		issues.forEach(
				issue -> {
					// closure and invalidation
					if (issue.hasExtension(RAConstants.GROUP_REFERENCE_URL) && issue.hasModifierExtension()
							&& !issue.getModifierExtensionsByUrl(RAConstants.CODING_GAP_REQUEST_URL).isEmpty()) {
						updateMeasureReportGroup(report,
								issue.getExtensionByUrl(RAConstants.GROUP_REFERENCE_URL).getValue().primitiveValue(),
								(CodeableConcept) issue.getModifierExtensionsByUrl(RAConstants.CODING_GAP_REQUEST_URL).get(0)
										.getValue());
						report.setMeta(RAConstants.PATIENT_REPORT_META);
					}
					// creation
					else if (!issue.getModifierExtensionsByUrl(RAConstants.CODING_GAP_REQUEST_URL).isEmpty()) {
						createMeasureReportGroup(report, issue.getCode());
						report.setMeta(RAConstants.PATIENT_REPORT_META);
					}
				});
	}

	default void resolveIssues(Composition composition, MeasureReport report, List<DetectedIssue> issues) {
		issues.forEach(
				issue -> {
					Composition.SectionComponent section = new Composition.SectionComponent();
					if (issue.hasExtension()) {
						issue.getExtension().forEach(
								extension -> {
									if (extension.hasUrl() && extension.getUrl().equals(RAConstants.GROUP_REFERENCE_URL)) {
										report.getGroup().forEach(
												group -> {
													if (group.hasId() && group.getId().equals(extension.getValue().toString())) {
														section.addEntry(new Reference(issue.getIdElement().toUnqualifiedVersionless()));
														if (group.hasCode()) {
															section.setCode(group.getCode());
														}
													}
												});
										section.setFocus(new Reference(report.getIdElement().toUnqualifiedVersionless()));
									}
								});
						composition.addSection(section);
					}
				});
	}

	default Composition buildComposition(String subject, MeasureReport report,
			List<DetectedIssue> issues, IdType compositionSectionAuthor) {
		Composition composition = new Composition();
		IIdType id = Ids.newId(Composition.class, UUID.randomUUID().toString());
		composition.setId(id);
		composition.setMeta(RAConstants.COMPOSITION_META);
		composition.setIdentifier(
				new Identifier().setSystem("urn:ietf:rfc:3986").setValue("urn:uuid:" + UUID.randomUUID()));
		composition.setStatus(Composition.CompositionStatus.PRELIMINARY)
				.setType(RAConstants.COMPOSITION_TYPE).setSubject(new Reference(subject))
				.setDate(Date.from(Instant.now()))
				.setAuthor(Collections.singletonList(new Reference(compositionSectionAuthor)))
				.setTitle("Risk Adjustment Coding Gaps Report for " + subject);
		resolveIssues(composition, report, issues);
		return composition;
	}

	default Bundle startCodingGapReportBundle() {
		Bundle codingGapReportBundle = new Bundle();
		codingGapReportBundle.setMeta(RAConstants.CODING_GAP_REPORT_BUNDLE_META);
		codingGapReportBundle.setIdentifier(
				new Identifier().setSystem("urn:ietf:rfc:3986").setValue("urn:uuid:" + UUID.randomUUID()));
		codingGapReportBundle.setType(Bundle.BundleType.DOCUMENT);
		codingGapReportBundle.setTimestamp(new Date());
		return codingGapReportBundle;
	}

	private BundleEntryComponent getBundleEntry(String serverBase, Resource resource) {
		return new BundleEntryComponent().setResource(resource)
				.setFullUrl(Operations.getFullUrl(serverBase, resource));
	}

	default Bundle buildCodingGapReportBundle(String serverBase, Composition composition, List<DetectedIssue> issues,
			MeasureReport report, Resource author) {
		Bundle codingGapReportBundle = startCodingGapReportBundle();
		codingGapReportBundle.addEntry(getBundleEntry(serverBase, composition));
		Map<String, Resource> evaluatedResources = new HashMap<>();
		for (DetectedIssue issue : issues) {
			codingGapReportBundle.addEntry(getBundleEntry(serverBase, issue));
			evaluatedResources.putAll(getEvidenceResources(issue));
		}
		codingGapReportBundle.addEntry(getBundleEntry(serverBase, report));

		if (author != null) {
			codingGapReportBundle.addEntry(getBundleEntry(serverBase, author));
		}

		getEvaluatedResources(report, evaluatedResources);

		for (Map.Entry<String, Resource> evaluatedResourcesSet : evaluatedResources.entrySet()) {
			codingGapReportBundle.addEntry(getBundleEntry(serverBase, evaluatedResourcesSet.getValue()));
		}

		return codingGapReportBundle;
	}

	default Bundle buildMissingMeasureReportCodingGapReportBundle(String serverBase, Patient patient) {
		Bundle codingGapReportBundle = startCodingGapReportBundle();
		codingGapReportBundle.addEntry(getBundleEntry(serverBase, patient));
		return codingGapReportBundle;
	}

	default Map<String, Resource> getEvidenceResources(DetectedIssue issue) {
		Map<String, Resource> evidenceResources = new HashMap<>();
		for (DetectedIssue.DetectedIssueEvidenceComponent evidence : issue.getEvidence()) {
			if (evidence.hasDetail()) {
				for (Reference detail : evidence.getDetail()) {
					if (detail.getReference().startsWith("MeasureReport/"))
						continue;
					evidenceResources.put(Ids.simple(new IdType(detail.getReference())),
							read(new IdType(detail.getReference())));
				}
			}
		}

		return evidenceResources;
	}

	default List<String> getMeasureReferences(
			List<String> measureId, List<String> measureIdentifier, List<String> measureUrl) {
		if (measureUrl != null) {
			return measureUrl;
		} else if (measureId != null) {
			return measureId;
		}
		return measureIdentifier;
	}

	default String normalizeMeasureReference(
			List<String> measureId, List<String> measureIdentifier, List<String> measureUrl) {
		String measureReference = getMeasureReferences(measureId, measureIdentifier, measureUrl).get(0);
		if (measureReference.startsWith("Measure/")) {
			return measureReference.replace("Measure/", "");
		} else if (measureReference.startsWith("http")) {
			return Canonicals.getIdPart(measureReference);
		}
		return measureReference;
	}

}
