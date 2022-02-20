package org.opencds.cqf.ruler.cr.r4.provider;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.common.base.Strings;

import org.apache.commons.lang3.NotImplementedException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.DetectedIssue;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Measure;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.opencds.cqf.ruler.behavior.ConfigurationUser;
import org.opencds.cqf.ruler.behavior.ResourceCreator;
import org.opencds.cqf.ruler.behavior.r4.ParameterUser;
import org.opencds.cqf.ruler.builder.BundleBuilder;
import org.opencds.cqf.ruler.builder.CodeableConceptSettings;
import org.opencds.cqf.ruler.builder.CompositionBuilder;
import org.opencds.cqf.ruler.builder.DetectedIssueBuilder;
import org.opencds.cqf.ruler.cr.CrProperties;
import org.opencds.cqf.ruler.provider.DaoRegistryOperationProvider;
import org.opencds.cqf.ruler.utility.Canonicals;
import org.opencds.cqf.ruler.utility.Ids;
import org.opencds.cqf.ruler.utility.Operations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.model.api.annotation.Description;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;

public class CareGapsProvider extends DaoRegistryOperationProvider
		implements ParameterUser, ConfigurationUser, ResourceCreator {

	public static final Pattern CARE_GAPS_STATUS = Pattern
			.compile("(open-gap|closed-gap|not-applicable)");
	public static final String CARE_GAPS_REPORT_PROFILE = "http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/indv-measurereport-deqm";
	public static final String CARE_GAPS_BUNDLE_PROFILE = "http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/gaps-bundle-deqm";
	public static final String CARE_GAPS_COMPOSITION_PROFILE = "http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/gaps-composition-deqm";
	public static final String CARE_GAPS_DETECTEDISSUE_PROFILE = "http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/gaps-detectedissue-deqm";
	public static final String CARE_GAPS_GAP_STATUS_EXTENSION = "http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/extension-gapStatus";
	public static final String CARE_GAPS_GAP_STATUS_SYSTEM = "http://hl7.org/fhir/us/davinci-deqm/CodeSystem/gaps-status";
	public static final String CARE_GAPS_MEASUREREPORT_REPORTER_EXTENSION = "http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/extension-reporterGroup";

	public enum CareGapsStatusCode {
		OPEN_GAP("open-gap"), CLOSED_GAP("closed-gap"), NOT_APPLICABLE("not-applicable");

		private final String myValue;

		private CareGapsStatusCode(final String theValue) {
			myValue = theValue;
		}

		@Override
		public String toString() {
			return myValue;
		}
	}

	static final Logger ourLog = LoggerFactory.getLogger(CareGapsProvider.class);

	@Autowired
	private MeasureEvaluateProvider measureEvaluateProvider;

	@Autowired
	CrProperties crProperties;

	/**
	 * Implements the <a href=
	 * "http://build.fhir.org/ig/HL7/davinci-deqm/OperationDefinition-care-gaps.html">$care-gaps</a>
	 * operation found in the
	 * <a href="http://build.fhir.org/ig/HL7/davinci-deqm/index.html">Da Vinci DEQM
	 * FHIR Implementation Guide</a> that overrides the <a href=
	 * "http://build.fhir.org/operation-measure-care-gaps.html">$care-gaps</a>
	 * operation found in the
	 * <a href="http://hl7.org/fhir/R4/clinicalreasoning-module.html">FHIR Clinical
	 * Reasoning Module</a>.
	 * 
	 * The operation calculates measures describing gaps in care. For more details,
	 * reference the <a href=
	 * "http://build.fhir.org/ig/HL7/davinci-deqm/gaps-in-care-reporting.html">Gaps
	 * in Care Reporting</a> section of the
	 * <a href="http://build.fhir.org/ig/HL7/davinci-deqm/index.html">Da Vinci DEQM
	 * FHIR Implementation Guide</a>.
	 * 
	 * A Parameters resource that includes zero to many document bundles that
	 * include Care Gap Measure Reports will be returned.
	 * 
	 * Usage:
	 * URL: [base]/Measure/$care-gaps
	 * 
	 * @param theRequestDetails generally auto-populated by the HAPI server
	 *                          framework.
	 * @param periodStart       the start of the gaps through period
	 * @param periodEnd         the end of the gaps through period
	 * @param topic             the category of the measures that is of interest for
	 *                          the care gaps report
	 * @param subject           a reference to either a Patient or Group for which
	 *                          the gaps in care report(s) will be generated
	 * @param practitioner      a reference to a Practitioner for which the gaps in
	 *                          care report(s) will be generated
	 * @param organization      a reference to an Organization for which the gaps in
	 *                          care report(s) will be generated
	 * @param status            the status code of gaps in care reports that will be
	 *                          included in the result
	 * @param measureId         the id of Measure(s) for which the gaps in care
	 *                          report(s) will be calculated
	 * @param measureIdentifier the identifier of Measure(s) for which the gaps in
	 *                          care report(s) will be calculated
	 * @param measureUrl        the canonical URL of Measure(s) for which the gaps
	 *                          in care report(s) will be calculated
	 * @param program           the program that a provider (either clinician or
	 *                          clinical organization) participates in
	 * @return Parameters of bundles of Care Gap Measure Reports
	 */
	@SuppressWarnings("squid:S00107") // warning for greater than 7 parameters
	@Description(shortDefinition = "$care-gaps", value = "Implements the <a href=\"http://build.fhir.org/ig/HL7/davinci-deqm/OperationDefinition-care-gaps.html\">$care-gaps</a> operation found in the <a href=\"http://build.fhir.org/ig/HL7/davinci-deqm/index.html\">Da Vinci DEQM FHIR Implementation Guide</a> which is an extension of the <a href=\"http://build.fhir.org/operation-measure-care-gaps.html\">$care-gaps</a> operation found in the <a href=\"http://hl7.org/fhir/R4/clinicalreasoning-module.html\">FHIR Clinical Reasoning Module</a>.")
	@Operation(name = "$care-gaps", idempotent = true, type = Measure.class)
	public Parameters careGapsReport(RequestDetails theRequestDetails,
			@OperationParam(name = "periodStart") String periodStart,
			@OperationParam(name = "periodEnd") String periodEnd,
			@OperationParam(name = "topic") List<String> topic,
			@OperationParam(name = "subject") String subject,
			@OperationParam(name = "practitioner") String practitioner,
			@OperationParam(name = "organization") String organization,
			@OperationParam(name = "status") List<String> status,
			@OperationParam(name = "measureId") List<String> measureId,
			@OperationParam(name = "measureIdentifier") List<String> measureIdentifier,
			@OperationParam(name = "measureUrl") List<CanonicalType> measureUrl,
			@OperationParam(name = "program") List<String> program) {

		/*
		 * TODO - topic should allow many and be a union of them
		 * TODO -
		 * "The Server needs to make sure that practitioner is authorized to get the gaps in care report for and know what measures the practitioner are eligible or qualified."
		 */
		validateConfiguration(crProperties);
		validateParameters(theRequestDetails);
		Parameters result = newResource(Parameters.class, "care-gaps-report-" + UUID.randomUUID().toString());
		List<Measure> measures = getMeasures(measureId, measureIdentifier, measureUrl);
		measures.forEach(measure -> {
			if (!measure.hasScoring()) {
				ourLog.info("Measure does not specify a scoring so skipping: {}.", measure.getId());
				measures.remove(measure);
			}
		});

		if (!Strings.isNullOrEmpty(subject)) {
			List<Patient> patients = getPatientListFromSubject(subject);
			(patients)
					.forEach(
							patient -> {
								Parameters.ParametersParameterComponent patientParameter = patientReport(theRequestDetails,
										periodStart, periodEnd,
										topic, patient, status, measures, organization);
								result.addParameter(patientParameter);
							});
		} else {
			// TODO: implement non subject parameters (practitioner and organization)
			throw new NotImplementedException("Non subject parameters have not been implemented.");
		}

		return result;
	}

	@Override
	public void validateConfiguration(Object theConfiguration) {
		if (validated(theConfiguration)) {
			return;
		}
		ConfigurationUser.super.validateConfiguration(theConfiguration);
		checkArgument(!Strings.isNullOrEmpty(((CrProperties) theConfiguration).getMeasureReport().getReporter()),
				"The measure_report.reporter setting is required for the $care-gaps operation.");
	}

	public void validateParameters(RequestDetails theRequestDetails) {
		Operations.validatePeriod(theRequestDetails, "periodStart", "periodEnd");
		Operations.validateCardinality(theRequestDetails, "subject", 0, 1);
		Operations.validateSingularPattern(theRequestDetails, "subject", Operations.PATIENT_OR_GROUP_REFERENCE);
		Operations.validateCardinality(theRequestDetails, "status", 1);
		Operations.validateSingularPattern(theRequestDetails, "status", CARE_GAPS_STATUS);
		Operations.validateExclusive(theRequestDetails, "subject", "organization", "practitioner");
		Operations.validateExclusive(theRequestDetails, "organization", "subject");
		Operations.validateInclusive(theRequestDetails, "practitioner", "organization");
		Operations.validateExclusiveOr(theRequestDetails, "subject", "organization");
		Operations.validateAtLeastOne(theRequestDetails, "measureId", "measureIdentifier", "measureUrl");
	}

	private Parameters.ParametersParameterComponent patientReport(RequestDetails requestDetails, String periodStart,
			String periodEnd,
			List<String> topic, Patient patient, List<String> status, List<Measure> measures, String organization) {

		// for each measure, query evaluatemeasure for the patient

		List<MeasureReport> reports = getReports(requestDetails, periodStart, periodEnd, patient, measures);

		if (reports.isEmpty()) {
			return null;
		}

		List<DetectedIssue> detectedIssues = getDetectedIssues(reports, patient);

		// for each report, add to MR to result

		Bundle bundle = getBundle();

		Composition composition = getComposition(patient, organization);

		Parameters.ParametersParameterComponent patientParameter = new Parameters.ParametersParameterComponent();

		return patientParameter;
	}

	private List<MeasureReport> getReports(RequestDetails requestDetails, String periodStart,
			String periodEnd, Patient patient, List<Measure> measures) {
		List<MeasureReport> reports = new ArrayList<>();

		MeasureReport report = null;
		for (Measure measure : measures) {
			report = measureEvaluateProvider.evaluateMeasure(requestDetails, measure.getIdElement(), periodStart,
					periodEnd, "patient", Ids.simple(patient), null, null, null);

			if (!report.hasGroup()) {
				ourLog.info("Report does not include a group so skipping.\nSubject: {}\nMeasure: {}", Ids.simple(patient),
						Ids.simple(measure));
				continue;
			}

			initializeReport(report, measure.getImprovementNotation());

			reports.add(report);
		}

		return reports;
	}

	private List<DetectedIssue> getDetectedIssues(List<MeasureReport> reports, Patient patient) {
		List<DetectedIssue> detectedIssues = new ArrayList<>();
		reports.forEach(report -> detectedIssues.add(getDetectedIssue(patient, report.getMeasureElement())));
		return detectedIssues;
	}

	private void initializeReport(MeasureReport report, CodeableConcept improvementNotation) {
		report.setId(UUID.randomUUID().toString());
		report.setDate(new Date());
		report.setImprovementNotation(improvementNotation);
		Reference reporter = new Reference().setReference(crProperties.getMeasureReport().getReporter());
		// TODO: figure out what this extension is for
		reporter.addExtension(new Extension().setUrl(CARE_GAPS_MEASUREREPORT_REPORTER_EXTENSION));
		report.setReporter(reporter);
		report.setMeta(new Meta().addProfile(CARE_GAPS_REPORT_PROFILE));
	}

	private Bundle getBundle() {
		return new BundleBuilder<Bundle>(Bundle.class)
				.withProfile(CARE_GAPS_BUNDLE_PROFILE)
				.withType(BundleType.DOCUMENT.toString())
				.build();
	}

	private Composition getComposition(Patient patient, String organization) {
		return new CompositionBuilder<Composition>(Composition.class)
				.withProfile(CARE_GAPS_COMPOSITION_PROFILE)
				.withType(new CodeableConceptSettings().add("http://loinc.org", "96315-7", "Gaps in care report"))
				.withStatus(Composition.CompositionStatus.FINAL.toString())
				.withTitle("Care Gap Report for " + Ids.simplePart(patient))
				.withSubject(Ids.simple(patient))
				.withCustodian(organization) // TODO: check to see if this is correct.
				.withAuthor(Ids.simple(patient)) // TODO: this is wrong figure it out.
				.build();
	}

	private DetectedIssue getDetectedIssue(Patient patient, CanonicalType measure) {
		return new DetectedIssueBuilder<DetectedIssue>(DetectedIssue.class)
				.withProfile(CARE_GAPS_DETECTEDISSUE_PROFILE)
				.withStatus(DetectedIssue.DetectedIssueStatus.FINAL.toString())
				.withCode(new CodeableConceptSettings().add("http://terminology.hl7.org/CodeSystem/v3-ActCode", "CAREGAP",
						"Care Gaps"))
				.withPatient(Ids.simple(patient))
				// TODO: check this is the correct value
				.withEvidenceDetail(Canonicals.getUrl(measure))
				.build();
	}
}
