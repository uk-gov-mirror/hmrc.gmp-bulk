/*
 * Copyright 2016 HM Revenue & Customs
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

package services

import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoPlugin
import repositories.BulkCalculationRepository
import uk.gov.hmrc.lock.{LockRepository, LockKeeper, LockMongoRepository}
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.{ExecutionContext, Future}

trait BulkCompletionService {

  val connection = {

    import play.api.Play.current
    ReactiveMongoPlugin.mongoConnector.db
  }
  val lockrepo = LockMongoRepository(connection)

  val lockKeeper = new LockKeeper {

    override def repo: LockRepository = lockrepo //The repo created before

    override def lockId: String = "bulkcompletion"

    override val forceLockReleaseAfter: org.joda.time.Duration = org.joda.time.Duration.standardMinutes(5)

    // $COVERAGE-OFF$
    override def tryLock[T](body: => Future[T])(implicit ec : ExecutionContext): Future[Option[T]] = {
      Logger.debug("trying to get completion lock")
      repo.lock(lockId, serverId, forceLockReleaseAfter)
        .flatMap { acquired =>
          if (acquired)
            body.map { case x => Logger.debug("got completion lock"); Some(x) }.recover {
              case e => Logger.debug("exception getting lock: " + e.getMessage)
                throw e
            }
          else {
            Logger.debug("couldnt get lock");
            Future.successful(None)
          }
        }.recoverWith { case ex => {
          Logger.debug("exception getting lock: " + ex.getMessage)
          repo.releaseLock(lockId, serverId).flatMap(_ => Future.failed(ex))
        }
      }
    }
    // $COVERAGE-ON$
  }

  // $COVERAGE-OFF$
  lazy val repository: BulkCalculationRepository = BulkCalculationRepository()
  // $COVERAGE-ON$


  def checkForComplete() = {
    Logger.debug("[BulkCompletionService]: starting ")
    lockKeeper.tryLock {
      Logger.debug("[BulkCompletionService]: got lock")
      repository.findAndComplete().map {
        case true => Logger.debug("[BulkCompletionService]: found and completed successfully "); lockrepo.releaseLock(lockKeeper.lockId,lockKeeper.serverId)
        case false => Logger.warn("[BulkCompletionService]: failed "); lockrepo.releaseLock(lockKeeper.lockId,lockKeeper.serverId)
      }

    }.map{
      case Some(thing) => Logger.debug("[BulkCompletionService][receive : obtained mongo lock]")
      // $COVERAGE-OFF$
      case _ => Logger.debug("[BulkCompletionService][receive : failed to obtain mongo lock]")
      // $COVERAGE-ON$
    }
  }

}

object BulkCompletionService extends BulkCompletionService
