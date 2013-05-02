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
package oncue.tests.redis;

import static junit.framework.Assert.assertEquals;
import oncue.common.messages.EnqueueJob;
import oncue.common.messages.Job;
import oncue.tests.base.ActorSystemTest;
import oncue.tests.workers.TestWorker;

import org.junit.Test;

import akka.actor.ActorRef;
import akka.testkit.JavaTestKit;

/**
 * Test to ensure that jobs can be enqueued to Redis. This queue manager
 * implementation also has a Redis queue monitor, which blocks until new jobs
 * are found.
 */
@SuppressWarnings("unused")
public class RedisQueueManagerTest extends ActorSystemTest {
	@Test
	public void testEnqueueNewJob() {
		new JavaTestKit(system) {
			{
				// Create a Redis-backed (see config) queue manager
				ActorRef queueManager = createQueueManager(system, null);

				// Create a scheduler with a probe
				final JavaTestKit schedulerProbe = new JavaTestKit(system) {
					{
						new IgnoreMsg() {

							@Override
							protected boolean ignore(Object message) {
								return !(message instanceof Job);
							}
						};
					}
				};
				createScheduler(system, schedulerProbe.getRef());

				// Enqueue a job
				queueManager.tell(new EnqueueJob(TestWorker.class.getName()), getRef());
				Job job = expectMsgClass(Job.class);

				// Expect the scheduler to see the new job
				Job schedulerJob = schedulerProbe.expectMsgClass(Job.class);

				assertEquals(job.getId(), schedulerJob.getId());
				assertEquals(job.getEnqueuedAt().toString(), schedulerJob.getEnqueuedAt().toString());
				assertEquals(job.getWorkerType(), schedulerJob.getWorkerType());

				/*
				 * Rinse, repeat, in order to ensure that the Future-based
				 * mechanism for picking up new jobs works!
				 */

				// Enqueue a job
				queueManager.tell(new EnqueueJob(TestWorker.class.getName()), getRef());
				expectMsgClass(Job.class);

				// Expect the scheduler to see the new job
				schedulerProbe.expectMsgClass(Job.class);
			}
		};
	}
}
