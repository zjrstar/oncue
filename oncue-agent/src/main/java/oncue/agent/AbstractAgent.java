/*******************************************************************************
 * Copyright 2013 Michael Marconi
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package oncue.agent;

import static akka.actor.SupervisorStrategy.stop;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import oncue.common.comparators.JobComparator;
import oncue.common.messages.Job;
import oncue.common.messages.Job.State;
import oncue.common.messages.JobFailed;
import oncue.common.messages.JobProgress;
import oncue.common.messages.SimpleMessages.SimpleMessage;
import oncue.common.messages.WorkAvailable;
import oncue.common.messages.WorkResponse;
import oncue.common.settings.Settings;
import oncue.common.settings.SettingsProvider;
import oncue.worker.AbstractWorker;

import scala.concurrent.duration.Duration;
import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.Scheduler;
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Function;

public abstract class AbstractAgent extends UntypedActor {

	// The scheduled heartbeat
	private Cancellable heartbeat;

	// Map jobs in progress to their workers
	protected Map<String, Job> jobsInProgress = new HashMap<>();

	protected LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	final protected Settings settings = SettingsProvider.SettingsProvider
			.get(getContext().system());

	// A probe for testing
	protected ActorRef testProbe;

	// The list of worker types this agent can spawn
	private final Set<String> workerTypes;

	// A scheduled request for new work
	private Cancellable workRequest;

	/**
	 * An agent must be initialised with a list of worker types it is capable of spawning.
	 * 
	 * @param workerTypes is a list of Strings that correspond with the classes of available worker
	 * types.
	 * 
	 * @throws MissingWorkerException thrown if a class representing a worker cannot be found
	 */
	public AbstractAgent(Set<String> workerTypes) {
		for(String workerType : workerTypes) {
			if (fetchWorkerClass(workerType.trim()) == null) {
				workerTypes.remove(workerType);
			}
		}
		this.workerTypes = workerTypes;
	}

	/**
	 * Try to load the Class for the worker of type workerType.
	 * 
	 * @param workerType
	 * @return
	 * @throws MissingWorkerException If the class cannot be instantiated or the class does not
	 * extend AbstractWorker
	 */
	@SuppressWarnings("unchecked")
	private Class<? extends AbstractWorker> fetchWorkerClass(String workerType) {
		try {
			return (Class<? extends AbstractWorker>) Class.forName(workerType);
		} catch (ClassNotFoundException e) {
			log.error(String.format("Cannot find a class for the worker type '%s'", workerType));
			return null;
		} catch (ClassCastException e) {
			throw new IllegalStateException(String.format(
					"The class for worker type '%s' doesn't extend the AbstractWorker base class",
					workerType), e);
		}
	}

	/**
	 * @return a reference to the scheduler
	 */
	protected ActorRef getScheduler() {
		try {
			return getContext().actorFor(settings.SCHEDULER_PATH);
		} catch (NullPointerException e) {
			throw new RuntimeException(
					"Could not get a reference to the Scheduler. Is the system shutting down?", e);
		}
	}

	/**
	 * @return the set of {@linkplain AbstractWorker} types this agent is capable of spawning.
	 */
	public Set<String> getWorkerTypes() {
		return workerTypes;
	}

	public void injectProbe(ActorRef testProbe) {
		this.testProbe = testProbe;
	}

	@Override
	public void onReceive(Object message) {

		if (testProbe != null)
			testProbe.forward(message, getContext());

		if (message.equals(SimpleMessage.AGENT_REGISTERED)) {
			log.info("Registered with scheduler");
			requestWork();
		}

		else if (message instanceof WorkResponse) {
			log.debug("Agent {} got a response to my work request: {}", getSelf().path().toString(),
					message);
			List<Job> jobs = ((WorkResponse) message).getJobs();
			Collections.sort(jobs, new JobComparator());
			for (Job job : jobs) {
				spawnWorker(job);
			}
		}

		else if (message instanceof WorkAvailable) {
			WorkAvailable workAvailable = (WorkAvailable) message;
			log.debug("Agent {} found work available for the following worker types: {}",
					getSelf().path().toString(), workAvailable.getWorkerTypes());
			for (String workerTypeRequired : workAvailable.getWorkerTypes()) {
				if (workerTypes.contains(workerTypeRequired)) {
					requestWork();
					break;
				}
			}
		}

		else if (message instanceof JobProgress) {
			Job job = ((JobProgress) message).getJob();
			log.debug("Worker reported progress of {} on {}", job.getProgress(), job);
			recordProgress((JobProgress) message, getSender());
		}

		else {
			log.error("Unrecognised message: {}", message);
			unhandled(message);
		}
	}

	@Override
	public void postStop() {
		super.postStop();
		heartbeat.cancel();
		if (workRequest != null && !workRequest.isCancelled())
			workRequest.cancel();
		log.info("Shut down.");
	}

	@Override
	public void preStart() {
		super.preStart();
		log.info("{} is running with worker types: {}", getClass().getSimpleName(),
				workerTypes.toString());
		startHeartbeat();
	}

	/*
	 * Tell the agent to begin heartbeating back to the service.
	 */
	public void startHeartbeat() {
		if (heartbeat != null) {
			stopHeartbeat();
		}

		heartbeat = getContext().system().scheduler().schedule(Duration.Zero(),
				settings.AGENT_HEARTBEAT_FREQUENCY, new Runnable() {

					@Override
					public void run() {
						getScheduler().tell(SimpleMessage.AGENT_HEARTBEAT, getSelf());
					}
				}, getContext().dispatcher());
	}

	/**
	 * Stop the agent from heartbeating. Primarily for use with testing, this is a way to simulate
	 * network disconnects.
	 */
	public void stopHeartbeat() {
		heartbeat.cancel();
	}

	/**
	 * Note the progress against a job. If it is complete, remove it from the jobs in progress map.
	 * 
	 * @param jobProgress is the {@linkplain JobProgress} made against a job
	 * @param worker is the {@linkplain AbstractWorker} completing the job
	 */
	private void recordProgress(JobProgress jobProgress, ActorRef worker) {
		getScheduler().tell(jobProgress, getSelf());
		if (jobProgress.getJob().getProgress() == 1.0) {
			jobsInProgress.remove(worker.path().toString());
			scheduleWorkRequest();
		}
	}

	/**
	 * Request work from the {@linkplain Scheduler}.
	 */
	protected abstract void requestWork();

	/**
	 * Schedule a work request to take place to allow for a period of quiesence after job
	 * completion.
	 */
	private void scheduleWorkRequest() {
		if (workRequest != null && !workRequest.isCancelled())
			workRequest.cancel();

		workRequest = getContext().system().scheduler().scheduleOnce(Duration.Zero(),
				new Runnable() {

					@Override
					public void run() {
						requestWork();
					}
				}, getContext().dispatcher());
	}

	/**
	 * Spawn a new worker to complete a job.
	 * 
	 * @param job is the job that a {@linkplain Worker} should complete.
	 */
	@SuppressWarnings("serial")
	private void spawnWorker(Job job) {
		final Class<? extends AbstractWorker> workerClass = fetchWorkerClass(job.getWorkerType());

		if (workerClass == null) {
			sendFailure(job, String.format("Could not find worker for job type %s", workerClass));
			return;
		}

		// The agent has connected to a scheduler and received a job. If this agent thinks that
		// it is already running that job then do nothing. This can happen during a network
		// partition where an agent reconnects and get scheduled the same job that it's already
		// processing.
		boolean jobInProgress = false;
		for (Job activeJob : jobsInProgress.values()) {
			if (job.getId() == activeJob.getId()) {
				jobInProgress = true;
				break;
			}
		}

		if (!jobInProgress) {
			ActorRef worker = getContext().actorOf(new Props(new UntypedActorFactory() {

				@Override
				public Actor create() throws Exception {
					return workerClass.newInstance();
				}
			}), "job-" + job.getId());
			jobsInProgress.put(worker.path().toString(), job);
			worker.tell(job.clone(), getSelf());
		} else {
			log.error("Job {} is already in progress. Ignoring scheduler response", job.getId());
		}
	}

	/**
	 * Extract the job failure reason and notify the scheduler that the job failed
	 * 
	 * @param job
	 */
	private void sendFailure(Job job, String message) {
		job.setState(State.FAILED);
		job.setErrorMessage("A worker failed due to " + message);
		getScheduler().tell(new JobFailed(job), getSelf());
	}

	/**
	 * Allows handling of a worker death. Called when a worker that is owned by this agent has
	 * thrown an exception.
	 * 
	 * @param job The job that was in progress
	 * @param error The Throwable from the worker
	 */
	protected void onWorkerDeath(Job job, Throwable error) {
		// Do nothing by default
	}

	/**
	 * Supervise all workers for unexpected exceptions. When an exception is encountered, tell the
	 * scheduler about it, stop the worker and remove it from the jobs in progress map.
	 */
	@Override
	public SupervisorStrategy supervisorStrategy() {
		return new OneForOneStrategy(0, Duration.Zero(), new Function<Throwable, Directive>() {

			@Override
			public Directive apply(Throwable error) {
				log.error(error, "The worker {} has died a horrible death!", getSender());
				Job job = jobsInProgress.remove(getSender().path().toString());
				onWorkerDeath(job, error);
				sendFailure(job, error.toString());
				return stop();
			}
		});
	}
}
