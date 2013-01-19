/*******************************************************************************
 * Copyright 2013 Michael Marconi
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package oncue.functional.robustness;

import junit.framework.Assert;
import oncue.agent.UnlimitedCapacityAgent;
import oncue.backingstore.RedisBackingStore;
import oncue.base.AbstractActorSystemTest;
import oncue.messages.internal.EnqueueJob;
import oncue.messages.internal.Job;
import oncue.messages.internal.JobProgress;
import oncue.messages.internal.SimpleMessages.SimpleMessage;
import oncue.queueManager.InMemoryQueueManager;
import oncue.scheduler.SimpleQueuePopScheduler;
import oncue.workers.TestWorker;

import org.junit.Before;
import org.junit.Test;

import redis.clients.jedis.Jedis;
import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.Kill;
import akka.actor.Props;
import akka.actor.UntypedActorFactory;
import akka.testkit.JavaTestKit;

/**
 * It is possible to resurrect a scheduler that was running with a persistent
 * backing store. This test ensures that we can bring a scheduler back from the
 * dead so that no jobs are lost.
 */
public class SchedulerDiesTest extends AbstractActorSystemTest {

	@Before
	public void cleanRedis() {
		Jedis redis = RedisBackingStore.getConnection();
		redis.flushDB();
		RedisBackingStore.releaseConnection(redis);
	}

	@Test
	@SuppressWarnings("serial")
	public void testAgentDiesAndAnotherReplacesIt() {
		new JavaTestKit(system) {
			{
				// Create a scheduler probe
				final JavaTestKit schedulerProbe = new JavaTestKit(system) {
					{
						new IgnoreMsg() {

							@Override
							protected boolean ignore(Object message) {
								if (message.equals(SimpleMessage.DEAD_AGENT) || message instanceof JobProgress)
									return false;
								else
									return true;
							}
						};
					}
				};

				// Create an agent probe
				final JavaTestKit agentProbe = new JavaTestKit(system) {
					{
						new IgnoreMsg() {

							@Override
							protected boolean ignore(Object message) {
								if (message instanceof JobProgress)
									return false;
								else
									return true;
							}
						};
					}
				};

				// Create a queue manager
				ActorRef queueManager = system.actorOf(new Props(InMemoryQueueManager.class),
						settings.QUEUE_MANAGER_NAME);

				// Create a Redis-backed scheduler
				final ActorRef scheduler = system.actorOf(new Props(new UntypedActorFactory() {
					@Override
					public Actor create() throws Exception {
						SimpleQueuePopScheduler scheduler = new SimpleQueuePopScheduler(RedisBackingStore.class);
						scheduler.injectProbe(schedulerProbe.getRef());
						return scheduler;
					}
				}), settings.SCHEDULER_NAME);

				// Create an agent with a probe
				system.actorOf(new Props(new UntypedActorFactory() {

					@Override
					public Actor create() throws Exception {
						UnlimitedCapacityAgent agent = new UnlimitedCapacityAgent();
						agent.injectProbe(agentProbe.getRef());
						return agent;
					}
				}), settings.AGENT_NAME);

				// Enqueue a job
				queueManager.tell(new EnqueueJob(TestWorker.class.getName()), getRef());
				Job job = expectMsgClass(Job.class);

				// Wait for some progress
				schedulerProbe.expectMsgClass(JobProgress.class);

				// Tell the scheduler to commit seppuku
				scheduler.tell(Kill.getInstance(), getRef());

				// Wait until the scheduler dies
				new AwaitCond(duration("5 seconds"), duration("1 second")) {

					@Override
					protected boolean cond() {
						return scheduler.isTerminated();
					}
				};

				// Wait until the job is finished
				for (int i = 0; i < 5; i++) {
					agentProbe.expectMsgClass(JobProgress.class);
				}

				// Resurrect the scheduler
				system.actorOf(new Props(new UntypedActorFactory() {
					@Override
					public Actor create() throws Exception {
						SimpleQueuePopScheduler scheduler = new SimpleQueuePopScheduler(RedisBackingStore.class);
						scheduler.injectProbe(schedulerProbe.getRef());
						return scheduler;
					}
				}), settings.SCHEDULER_NAME);

				// Wait for some progress
				JobProgress jobProgress = schedulerProbe.expectMsgClass(duration("10 seconds"), JobProgress.class);
				Assert.assertEquals(job.getId(), jobProgress.getJob().getId());
			}
		};
	}
}