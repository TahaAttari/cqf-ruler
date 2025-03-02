package org.opencds.cqf.ruler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import ca.uhn.fhir.rest.client.interceptor.UrlTenantSelectionInterceptor;
import ca.uhn.fhir.rest.server.provider.ProviderConstants;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Application.class, properties = {
		"spring.batch.job.enabled=false",
		"spring.datasource.url=jdbc:h2:mem:dbr4-mt",
		"hapi.fhir.fhir_version=r4",
		"hapi.fhir.subscription.websocket_enabled=true",
		"hapi.fhir.partitioning.partitioning_include_in_search_hashes=false"
})
public class MultitenantServerR4IT {
	private IGenericClient ourClient;
	private FhirContext ourCtx;

	@LocalServerPort
	private int port;

	private static UrlTenantSelectionInterceptor ourClientTenantInterceptor;

	@Test
	public void testCreateAndReadInTenantA() {

		// Create tenant A
		ourClientTenantInterceptor.setTenantId("DEFAULT");
		ourClient
				.operation()
				.onServer()
				.named(ProviderConstants.PARTITION_MANAGEMENT_CREATE_PARTITION)
				.withParameter(Parameters.class, ProviderConstants.PARTITION_MANAGEMENT_PARTITION_ID,
						new IntegerType(1))
				.andParameter(ProviderConstants.PARTITION_MANAGEMENT_PARTITION_NAME, new CodeType("TENANT-A"))
				.execute();

		ourClientTenantInterceptor.setTenantId("TENANT-A");
		Patient pt = new Patient();
		pt.addName().setFamily("Family A");
		ourClient.create().resource(pt).execute().getId();

		Bundle searchResult = ourClient.search().forResource(Patient.class).returnBundle(Bundle.class)
				.cacheControl(new CacheControlDirective().setNoCache(true)).execute();
		assertEquals(1, searchResult.getEntry().size());
		Patient pt2 = (Patient) searchResult.getEntry().get(0).getResource();
		assertEquals("Family A", pt2.getName().get(0).getFamily());
	}

	@Test
	public void testCreateAndReadInTenantB() {

		// Create tenant A
		ourClientTenantInterceptor.setTenantId("DEFAULT");
		ourClient
				.operation()
				.onServer()
				.named(ProviderConstants.PARTITION_MANAGEMENT_CREATE_PARTITION)
				.withParameter(Parameters.class, ProviderConstants.PARTITION_MANAGEMENT_PARTITION_ID,
						new IntegerType(2))
				.andParameter(ProviderConstants.PARTITION_MANAGEMENT_PARTITION_NAME, new CodeType("TENANT-B"))
				.execute();

		ourClientTenantInterceptor.setTenantId("TENANT-B");
		Patient pt = new Patient();
		pt.addName().setFamily("Family B");
		ourClient.create().resource(pt).execute().getId();

		Bundle searchResult = ourClient.search().forResource(Patient.class).returnBundle(Bundle.class)
				.cacheControl(new CacheControlDirective().setNoCache(true)).execute();
		assertEquals(1, searchResult.getEntry().size());
		Patient pt2 = (Patient) searchResult.getEntry().get(0).getResource();
		assertEquals("Family B", pt2.getName().get(0).getFamily());
	}

	@BeforeEach
	void beforeEach() {

		ourClientTenantInterceptor = new UrlTenantSelectionInterceptor();
		ourCtx = FhirContext.forR4Cached();
		ourCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		ourCtx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
		String ourServerBase = "http://localhost:" + port + "/fhir/";
		ourClient = ourCtx.newRestfulGenericClient(ourServerBase);
		ourClient.registerInterceptor(new LoggingInterceptor(true));
		ourClient.registerInterceptor(ourClientTenantInterceptor);
	}
}
