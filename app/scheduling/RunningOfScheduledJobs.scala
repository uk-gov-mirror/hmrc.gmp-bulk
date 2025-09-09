/*
 * Copyright 2024 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package scheduling

import org.apache.pekko.actor.{Cancellable, Scheduler}
import org.apache.commons.lang3.time.StopWatch
import play.api.inject.ApplicationLifecycle
import play.api.{Application, Logging}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

trait RunningOfScheduledJobs extends Logging {

  implicit val ec: ExecutionContext

  val application: Application

  lazy val scheduler: Scheduler = application.actorSystem.scheduler

  lazy val scheduledJobs: Seq[ScheduledJob]

  val applicationLifecycle: ApplicationLifecycle

  private[scheduling] var cancellables: Seq[Cancellable] = Seq.empty

  cancellables = scheduledJobs.map { job =>
    scheduler.scheduleAtFixedRate(job.initialDelay, job.interval)(new Runnable {
      override def run(): Unit = {
        val stopWatch = new StopWatch
        stopWatch.start()
        logger.info(s"Executing job ${job.name}")

        job.execute.onComplete {
          case Success(job.Result(message)) =>
            stopWatch.stop()
            logger.info(s"Completed job ${job.name} in $stopWatch: $message")
          case Failure(throwable) =>
            stopWatch.stop()
            logger.error(s"Exception running job ${job.name} after $stopWatch", throwable)
        }
      }
    })
  }

  applicationLifecycle.addStopHook { () =>
    logger.info(s"Cancelling all scheduled jobs.")
    cancellables.foreach(_.cancel())
    scheduledJobs.foreach { job =>
      logger.info(s"Checking if job ${job.configKey} is running")
      while (Await.result(job.isRunning, 5.seconds)) {
        logger.warn(s"Waiting for job ${job.configKey} to finish")
        Thread.sleep(1000)
      }
      logger.warn(s"Job ${job.configKey} is finished")
    }
    Future.successful(())

  }
}
