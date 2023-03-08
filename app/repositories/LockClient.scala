/*
 * Copyright 2023 HM Revenue & Customs
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

///*
// * Copyright 2023 HM Revenue & Customs
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package repositories
//
//import com.google.inject.{Inject, Singleton}
//import uk.gov.hmrc.mongo.lock.{LockRepository, MongoLockRepository, TimePeriodLockService}
//import play.api.Logging
//
//import java.util.UUID
//import scala.concurrent.{ExecutionContext, Future}
//import scala.concurrent.duration.{Duration, DurationInt}
//
//
//trait LockClient extends Logging with TimePeriodLockService {
//
//  val mongoLockRepository: MongoLockRepository
//  val lockId: String
//  val ttl: Duration
//  def tryLock(body: => Future[Unit])(implicit ec: ExecutionContext): Future[Option[Unit]] = {
//    logger.info("Attempting to build bulkprocessing lock")
//    mongoLockRepository.takeLock(lockId, ownerId, ttl).flatMap{
//      case false => logger.info(s"unable to take lock for $ownerId, bulkprocessing lock already exists")
//        Future.successful(None)
//      case true => logger.info(s"bulkprocessing lock acquired for $ownerId")
//        body.flatMap{_ =>
//          logger.info(s"bulkprocessing lock released for $ownerId")
//          mongoLockRepository.releaseLock(lockId, ownerId).map{
//          Some(_)
//        }}
//    }
//  }
//
//}
