package org.opencds.cqf.ruler.ra.r4;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.DetectedIssue;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.ruler.ra.RAConfig;
import org.opencds.cqf.ruler.ra.RAProperties;
import org.opencds.cqf.ruler.test.RestIntegrationTest;
import org.opencds.cqf.ruler.test.utility.Urls;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opencds.cqf.ruler.utility.r4.Parameters.newParameters;
import static org.opencds.cqf.ruler.utility.r4.Parameters.newPart;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { ResolveProviderIT.class, RAConfig.class },
        properties = { "hapi.fhir.fhir_version=r4" })
class ResolveProviderIT extends RestIntegrationTest {

    @Autowired
    private RAProperties myRaProperties;

    @BeforeEach
    public void beforeEach() {
        String ourServerBase = Urls.getUrl(myRaProperties.getReport().getEndpoint(), getPort());
        myRaProperties.getReport().setEndpoint(ourServerBase);
    }

    @Test
    void testResolveProvider() {
        Parameters params = newParameters(
                newPart("periodStart", "2021-01-01"),
                newPart("periodEnd", "2021-12-31"),
                newPart("subject", "Patient/ra-patient02"),
                newPart("measureId", "Measure-RAModelExample01"));

        loadTransaction("resolve-test-bundle.json");

        Parameters result = getClient().operation().onType(MeasureReport.class).named("$davinci-ra.resolve")
                .withParameters(params).useHttpGet().returnResourceType(Parameters.class).execute();

        assertFalse(result.isEmpty());
        assertTrue(result.hasParameter("return"));
        assertEquals(1, result.getParameter().size());
        assertTrue(result.getParameter().get(0).hasResource());
        assertTrue(result.getParameter().get(0).getResource() instanceof Bundle);

        Bundle raBundle = (Bundle) result.getParameter().get(0).getResource();
        assertEquals(Bundle.BundleType.DOCUMENT, raBundle.getType());
        assertTrue(raBundle.hasEntry());
        assertTrue(raBundle.getEntryFirstRep().hasResource());
        assertTrue(raBundle.getEntryFirstRep().getResource() instanceof Composition);
        assertTrue(raBundle.getEntry().size() > 1);
        assertTrue(raBundle.getEntry().get(1).hasResource());
        assertTrue(raBundle.getEntry().get(1).getResource() instanceof DetectedIssue);
        assertTrue(raBundle.getEntry().size() > 2);
        assertTrue(raBundle.getEntry().get(2).hasResource());
        assertTrue(raBundle.getEntry().get(2).getResource() instanceof MeasureReport);
        assertEquals(9, raBundle.getEntry().size());
    }
}