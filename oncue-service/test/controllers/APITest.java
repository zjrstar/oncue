package controllers;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.GET;
import static play.test.Helpers.POST;
import static play.test.Helpers.charset;
import static play.test.Helpers.contentAsString;
import static play.test.Helpers.contentType;
import static play.test.Helpers.fakeApplication;
import static play.test.Helpers.fakeRequest;
import static play.test.Helpers.route;
import static play.test.Helpers.routeAndCall;
import static play.test.Helpers.start;
import static play.test.Helpers.status;
import static play.test.Helpers.stop;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import oncue.common.messages.EnqueueJob;
import oncue.common.messages.Job;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import play.libs.Json;
import play.mvc.Result;
import play.test.FakeApplication;

public class APITest {

	private final static FakeApplication fakeApplication = fakeApplication();
	private final static ObjectMapper mapper = new ObjectMapper();

	@BeforeClass
	public static void startFakeApplication() {
		start(fakeApplication);
	}

	@AfterClass
	public static void shutdownFakeApplication() {
		stop(fakeApplication);
	}

	@Ignore
	//TODO: fix this up
	@Test
	public void listJobsButNoneHaveBeenQueued() {
		Result result = route(fakeRequest(GET, "/api/jobs"));

		assertThat(status(result)).isEqualTo(OK);
		assertThat(contentType(result)).isEqualTo("application/json");
		assertThat(charset(result)).isEqualTo("utf-8");
		JsonNode json = Json.parse(contentAsString(result));

		// The root node should be 'jobs'
		assertThat(json.findPath("jobs").size() == 1);

		// There should be no jobs
		assertFalse("There should be no jobs", json.findPath("jobs")
				.getElements().hasNext());
	}

	@Test
	public void createJobWithNoParameters() throws JsonParseException,
			JsonMappingException, IOException {
		EnqueueJob enqueueJob = new EnqueueJob("oncue.test.TestWorker");

		/*
		 * TODO: migrate to the 'route' method when we move to Play 2.1.1, which
		 * fixes a bug in the Json payload delivery that causes this test to
		 * fail!
		 * 
		 * Result result = route(fakeRequest(POST, "/api/jobs").withJsonBody(
		 * Json.toJson(enqueueJob)));
		 */

		Result result = routeAndCall(fakeRequest(POST, "/api/jobs")
				.withJsonBody(Json.toJson(enqueueJob)));

		assertEquals(OK, status(result));
		assertEquals("application/json", contentType(result));
		assertEquals("utf-8", charset(result));

		Job job = mapper.readValue(Json.parse(contentAsString(result)),
				Job.class);

		assertEquals("oncue.test.TestWorker", job.getWorkerType());
		assertNotNull(job.getId());
		assertEquals(0.0, job.getProgress());
		assertTrue(job.getParams().isEmpty());
	}

	/**
	 * TODO: Kill the QM and restart it before each test, so run IDs stay
	 * constant!
	 */
	@Test
	public void createJobWithParameters() throws JsonParseException,
			JsonMappingException, IOException {
		Map<String, String> params = new HashMap<>();
		params.put("key1", "Value 1");
		params.put("key2", "Value 2");
		EnqueueJob enqueueJob = new EnqueueJob("oncue.test.TestWorker", params);

		/*
		 * TODO: migrate to the 'route' method when we move to Play 2.1.1, which
		 * fixes a bug in the Json payload delivery that causes this test to
		 * fail!
		 * 
		 * Result result = route(fakeRequest(POST, "/api/jobs").withJsonBody(
		 * Json.toJson(enqueueJob)));
		 */

		Result result = routeAndCall(fakeRequest(POST, "/api/jobs")
				.withJsonBody(Json.toJson(enqueueJob)));

		assertEquals(OK, status(result));
		assertEquals("application/json", contentType(result));
		assertEquals("utf-8", charset(result));

		Job job = mapper.readValue(Json.parse(contentAsString(result)),
				Job.class);

		assertEquals("oncue.test.TestWorker", job.getWorkerType());
		assertNotNull(job.getId());
		assertEquals(0.0, job.getProgress());
		assertEquals("Value 1", job.getParams().get("key1"));
		assertEquals("Value 2", job.getParams().get("key2"));
	}

}