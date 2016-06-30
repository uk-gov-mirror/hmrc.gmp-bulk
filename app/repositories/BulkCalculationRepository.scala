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

package repositories

import java.util.concurrent.TimeUnit

import config.{ApplicationConfig, MicroserviceGlobal}
import connectors.{EmailConnector, ProcessedUploadTemplate}
import events.BulkEvent
import metrics.Metrics
import models._
import org.joda.time.LocalDateTime
import play.api.Logger
import play.api.libs.json.Json
import play.modules.reactivemongo.MongoDbConnection
import reactivemongo.api.commands.MultiBulkWriteResult
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{DefaultDB, ReadPreference}
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import uk.gov.hmrc.mongo.{ReactiveRepository, Repository}
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.http.HeaderCarrier
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class BulkCalculationMongoRepository(implicit mongo: () => DefaultDB)
  extends ReactiveRepository[BulkCalculationRequest, BSONObjectID](
    "bulk-calculation",
    mongo,
    BulkCalculationRequest.formats) with BulkCalculationRepository {


  val fieldName = "createdAt"
  val createdIndexName = "bulkCalculationRequestExpiry"
  val expireAfterSeconds = "expireAfterSeconds"
  val timeToLive = 2592000 //ApplicationConfig.ttlDuration

  collection.indexesManager.dropAll().map {
    case x =>
      createIndex(fieldName, createdIndexName, timeToLive)
      createIndex(Seq("bulkId"),Some("bulkId"))
      createIndex(Seq("uploadReference"),Some("UploadReference"),sparse=true,unique=true)
      createIndex(Seq("bulkId","lineId"),Some("BulkAndLine"))
      createIndex(Seq("userId"),Some("UserId"))
      createIndex(Seq("lineId"),Some("LineIdDesc"),true)
}

  private def createIndex(fields: Seq[String], createdIndexName: Option[String],descending: Boolean = false, sparse:Boolean = false, unique:Boolean = false) = {
    collection.indexesManager.ensure(Index(fields.map {
      column => (column,
        descending match {
          case true => IndexType.Descending
          case _ => IndexType.Ascending
        })
    },createdIndexName,background = true, sparse=sparse, unique=unique))
  }


  private def createIndex(field: String, indexName: String, ttl: Int): Future[Boolean] = {
    collection.indexesManager.ensure(Index(Seq((field, IndexType.Ascending)), Some(indexName),
      options = BSONDocument(expireAfterSeconds -> ttl))) map {
      result => {
        // $COVERAGE-OFF$
        Logger.debug(s"set [$indexName] with value $ttl -> result : $result")
        // $COVERAGE-ON$
        result
      }
    } recover {
      // $COVERAGE-OFF$
      case e => Logger.error("Failed to set TTL index", e)
        false
      // $COVERAGE-ON$
    }
  }

  override def insertResponseByReference(bulkId: String, lineId: Int, calculationResponse: GmpBulkCalculationResponse): Future[Boolean] = {

    val startTime = System.currentTimeMillis()

    val selector = Json.obj("bulkId" -> bulkId, "lineId" -> lineId)

    val modifier = Json.obj("$set" -> Json.obj("calculationResponse" -> calculationResponse))
    val result = collection.update(selector, modifier)

    result onComplete {
      case _ => metrics.insertResponseByReferenceTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
    }

    result.map {
      lastError => Logger.debug(s"[BulkCalculationRepository][insertResponseByReference] : {bulkResponse: $calculationResponse, result : $lastError }")
        lastError.ok
    }.recover {
      // $COVERAGE-OFF$
      case e => Logger.error("Failed to update request", e)
        false
      // $COVERAGE-ON$
    }
  }

  override def findByReference(uploadReference: String, csvFilter: CsvFilter = CsvFilter.All): Future[Option[BulkCalculationRequest]] = {

    val startTime = System.currentTimeMillis()

    val tryResult = Try {

      val request = collection.find(Json.obj("uploadReference" -> uploadReference)).one[BulkCalculationRequest]

      val result = request.flatMap {
        case Some(br) => {

          val childQuery = csvFilter match {
            case CsvFilter.Failed => Json.obj("bulkId" -> br._id.get, "$or" -> Json.arr(Json.obj("validationErrors" -> Json.obj("$exists" -> true)), Json.obj("calculationResponse.containsErrors" -> true)))
            case CsvFilter.Successful => Json.obj("bulkId" -> br._id.get, "validationErrors" -> Json.obj("$exists" -> false), "calculationResponse.containsErrors" -> false)
            case _ => Json.obj("bulkId" -> br._id.get)
          }
          collection.find(childQuery).sort(Json.obj("lineId" -> 1)).cursor[CalculationRequest](ReadPreference.primary).collect[List]().map {
            calcRequests => Some(br.copy(calculationRequests = calcRequests))
          }
        }
      }

      result onComplete {
        case _ => metrics.findByReferenceTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }

      result
    }

    tryResult match {
      case Success(s) => {
        s.map { x =>
          Logger.debug(s"[BulkCalculationRepository][findByReference] : { uploadReference : $uploadReference, result: $x }")
          x
        }
      }
      case Failure(f) => {
        Logger.debug(s"[BulkCalculationRepository][findByReference]  : { uploadReference : $uploadReference, exception: ${f.getMessage} }")
        Future.successful(None)
      }
    }
  }

  override def findSummaryByReference(uploadReference: String): Future[Option[BulkResultsSummary]] = {

    val startTime = System.currentTimeMillis()

    val tryResult = Try {

      val result = collection.find(Json.obj("uploadReference" -> uploadReference), Json.obj("reference" -> 1, "total" -> 1, "failed" -> 1, "userId" -> 1)).cursor[BulkResultsSummary](ReadPreference.primary).collect[List]()
      result onComplete {
        case _ => metrics.findSummaryByReferenceTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }
      result
    }

    tryResult match {
      case Success(s) => {
        s.map { x =>
          Logger.debug(s"[BulkCalculationRepository][findSummaryByReference] : { uploadReference : $uploadReference, result: $x }")
          x.headOption
        }
      }
      case Failure(f) => {
        Logger.debug(s"[BulkCalculationRepository][findSummaryByReference]  : { uploadReference : $uploadReference, exception: ${f.getMessage} }")
        Future.successful(None)
      }
    }
  }


  override def findByUserId(userId: String): Future[Option[List[BulkPreviousRequest]]] = {

    val startTime = System.currentTimeMillis()

    val tryResult = Try {
      val result = collection.find(Json.obj("userId" -> userId, "complete" -> true), Json.obj("uploadReference" -> 1, "reference" -> 1, "timestamp" -> 1, "createdAt" -> 1)).cursor[BulkPreviousRequest](ReadPreference.primary).collect[List]()

      result onComplete {
        case _ => metrics.findByUserIdTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }
      result
    }

    tryResult match {
      case Success(s) => {
        s.map { x =>
          Logger.debug(s"[BulkCalculationRepository][findByUserId] : { userId : $userId, result: ${x.size} }")
          Some(x)
        }
      }
      case Failure(f) => {
        Logger.debug(s"[BulkCalculationRepository][findByUserId]  : { userId : $userId, exception: ${f.getMessage} }")
        Future.successful(None)
      }
    }
  }


  override def findRequestsToProcess(): Future[Option[List[ProcessReadyCalculationRequest]]] = {

    val startTime = System.currentTimeMillis()

    val testResult = Try {

      val incompleteBulk = collection.find(Json.obj("uploadReference" -> Json.obj("$exists" -> true), "complete" -> Json.obj("$exists" -> false))).sort(Json.obj("_id" -> 1)).cursor[BulkCalculationRequest](ReadPreference.primary).collect[List]()

      incompleteBulk.map {
        bulkList =>
          bulkList.map {
            bulkRequest => {
              val childRequests = collection.find(Json.obj("uploadReference" -> Json.obj("$exists" -> false), "validationErrors" -> Json.obj("$exists" -> false), "bulkId" -> bulkRequest._id.get,
                "validCalculationRequest" -> Json.obj("$exists" -> true),
                "calculationResponse" -> Json.obj("$exists" -> false))).cursor[CalculationRequest](ReadPreference.primary).collect[List](ApplicationConfig.bulkProcessingBatchSize)
              childRequests.map {
                crs => crs.par.map {
                  cr => ProcessReadyCalculationRequest(cr.bulkId.get, cr.lineId, cr.validCalculationRequest.get)
                }
              }
            }
          }
      }
    }

    testResult match {
      case Success(s) => {
        s.flatMap {
          x => {

            val sequenced = Future.sequence(x).map {
              thing => Some(thing.flatten)
            }
            Logger.debug(s"[BulkCalculationRepository][findRequestsToProcess] SUCCESS")

            sequenced onComplete {
              case _ => metrics.findRequestsToProcessTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
            }

            sequenced
          }
        }
      }

      case Failure(f) => {
        Logger.debug(s"[BulkCalculationRepository][findRequestsToProcess] {failed : ${f.getMessage}}")
        metrics.findRequestsToProcessTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
        Future.successful(None)
      }
    }

  }

  override def findAndComplete(): Future[Boolean] = {

    val startTime = System.currentTimeMillis()

    Logger.debug("[BulkCalculationRepository][findAndComplete]: starting ")
    val findResult = Try {

      val incompleteBulk = collection.find(Json.obj("uploadReference" -> Json.obj("$exists" -> true), "complete" -> Json.obj("$exists" -> false))).sort(Json.obj("_id" -> 1)).cursor[BulkCalculationRequest](ReadPreference.primary).collect[List]()

      incompleteBulk onComplete {
        case _ => metrics.findAndCompleteParentTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }

      incompleteBulk.flatMap {
        bulkList =>
          Future.sequence(bulkList.par.map {
            bulkRequest => {
              val childrenStartTime = System.currentTimeMillis()
              val children = collection.count(Some(Json.obj("bulkId" -> bulkRequest._id.get,
                "validCalculationRequest" -> Json.obj("$exists" -> true),
                "calculationResponse" -> Json.obj("$exists" -> false),
                "validationErrors" -> Json.obj("$exists" -> false)))).flatMap {
                crs =>
                  if (crs == 0) {
                    val allChildrenStartTime = System.currentTimeMillis()
                    val allChildren = collection.find(Json.obj("bulkId" -> bulkRequest._id.get)).cursor[CalculationRequest](ReadPreference.primary).collect[List]().map {
                      theseRequests => Some(bulkRequest.copy(calculationRequests = theseRequests))
                    }
                    allChildren onComplete {
                      case _ => metrics.findAndCompleteAllChildrenTimer(System.currentTimeMillis() - allChildrenStartTime, TimeUnit.MILLISECONDS)
                    }
                    allChildren
                  }
                  else Future.successful(None)
              }
              children onComplete {
                case _ => metrics.findAndCompleteChildrenTimer(System.currentTimeMillis() - childrenStartTime, TimeUnit.MILLISECONDS)
              }
              children
            }
          }.toList
          ) map {
            br => br.filter(z => z.isDefined)
          }
      }
    }

    Logger.debug("[BulkCalculationRepository][findAndComplete]: processing")

    findResult match {
      case Success(s) => {
        val result = s.flatMap { requests =>
          Future.sequence(requests.map { request =>

            val totalRequests = request.get.calculationRequests.size
            val failedRequests = request.get.failedRequestCount

            val selector = Json.obj("uploadReference" -> request.get.uploadReference)
            val modifier = Json.obj("$set" -> Json.obj("complete" -> true, "total" -> totalRequests, "failed" -> failedRequests, "createdAt" -> LocalDateTime.now().toString))

            val result = collection.update(selector, modifier)

            result.map {
              writeResult => Logger.debug(s"[BulkCalculationRepository][findAndComplete] : { result : $writeResult }")
                // $COVERAGE-OFF$
                if (writeResult.ok){
                  implicit val hc = HeaderCarrier()
                  val resultsEventResult = auditConnector.sendEvent(new BulkEvent(
                    request.get.userId,
                    totalRequests - failedRequests,
                    request.get.calculationRequests.filter(x => x.validationErrors != None).size,
                    request.get.calculationRequests.filter(x => x.hasNPSErrors).size,
                    totalRequests,
                    request.get.calculationRequests.collect {
                      case x if (x.calculationResponse.isDefined) => x.calculationResponse.get.errorCodes
                    }.flatten,
                    request.get.calculationRequests.collect {
                      case x if (x.validCalculationRequest.isDefined && x.calculationResponse.isDefined) => x.validCalculationRequest.get.scon
                    },
                    request.get.calculationRequests.collect {
                      case x if (x.validCalculationRequest.isDefined && x.calculationResponse.isDefined && x.validCalculationRequest.get.dualCalc.isDefined && x.validCalculationRequest.get.dualCalc.get == 1) => true
                      case x if (x.validCalculationRequest.isDefined && x.calculationResponse.isDefined && x.validCalculationRequest.get.dualCalc.isDefined && x.validCalculationRequest.get.dualCalc.get == 0) => false
                    },
                    request.get.calculationRequests.collect{
                      case x if (x.validCalculationRequest.isDefined && x.calculationResponse.isDefined) => x.validCalculationRequest.get.calctype.get
                    }
                  ))
                  resultsEventResult.onFailure {
                    case e: Throwable => Logger.warn("[BulkCalculationRepository][findAndComplete] : resultsEventResult: " + e.getMessage(), e)
                  }

                  val childSelector = Json.obj("bulkId" -> request.get._id.get)
                  val childModifier = Json.obj("$set" -> Json.obj("createdAt" -> LocalDateTime.now().toString))
                  val childResult = collection.update(childSelector, childModifier, multi = true)
                  childResult.map {
                    childWriteResult => Logger.debug(s"[BulkCalculationRepository][findAndComplete] : { childResult : $childWriteResult }")
                  }

                  emailConnector.sendProcessedTemplatedEmail(ProcessedUploadTemplate(
                    request.get.email,
                    request.get.reference,
                    request.get.createdAt.getOrElse(new LocalDateTime()).toLocalDate,
                    request.get.userId))
                }
                // $COVERAGE-ON$
                writeResult.ok
            }
          }).map {
            x => x.foldLeft(true) {
              _ && _
            }
          }
        }
        result onComplete {
          case _ => {
            metrics.findAndCompleteTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
          }
        }
        result
      }
      case Failure(f) => {
        Logger.warn(s"[BulkCalculationRepository][findAndComplete] failure : { exception: ${f.getMessage} }")
        metrics.findAndCompleteTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
        Future.successful(false)
      }.recover {
        // $COVERAGE-OFF$
        case e: Exception => {
          metrics.findAndCompleteTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
          Logger.warn(s"[BulkCalculationRepository][findAndComplete] recover : { exception: ${e.getMessage} }")
          false
        }
        // $COVERAGE-ON$
      }
    }
  }

  override def findCountRemaining: Future[Option[Int]] = {

    val startTime = System.currentTimeMillis()

    val countResult = Try {
      val result = collection.count(Some(Json.obj("validCalculationRequest" -> Json.obj("$exists" -> true), "calculationResponse" -> Json.obj("$exists" -> false),
        "validationErrors" -> Json.obj("$exists" -> false))))

      result onComplete {
        case _ => metrics.findCountRemainingTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }

      result
    }

    countResult match {
      case Success(s) => {
        s.map {
          x =>
            Logger.debug(s"[BulkCalculationRepository][findCountRemaining : $x] ")
            Some(x)
        }
      }

      case Failure(f) => {
        Logger.debug(s"[BulkCalculationRepository][findCountRemaining] {failed : ${
          f.getMessage
        }}")
        Future.successful(None)
      }
    }
  }

  override def insertBulkDocument(bulkCalculationRequest: BulkCalculationRequest): Future[Boolean] = {

    val startTime = System.currentTimeMillis()

    val strippedBulk: BulkCalculationRequest = bulkCalculationRequest.copy(calculationRequests = Nil, _id = Some(BSONObjectID.generate.stringify))
    val calculationRequests = bulkCalculationRequest.calculationRequests.par.map {
      request => request.copy(bulkId = strippedBulk._id)
    }

    val insertResult = Try {
      val bulkDocs = calculationRequests.map(implicitly[collection.ImplicitlyDocumentProducer](_)).toList
      val insertResult = collection.insert(strippedBulk).flatMap {
        result => collection.bulkInsert(ordered = false)(bulkDocs: _*)
      }
      insertResult onComplete {
        case _ => metrics.insertBulkDocumentTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }

      insertResult
    }

    insertResult match {
      case Success(s) => {
        s.map {
          case x: MultiBulkWriteResult if (x.writeErrors == Nil) =>
            Logger.debug(s"[BulkCalculationRepository][insertBulkDocument : $x] ")
            true
        }.recover {
          case e: Throwable => {
            Logger.debug("Error inserting document", e)
            false
          }
        }
      }

      case Failure(f) => {
        Logger.debug(s"[BulkCalculationRepository][insertBulkDocument] {failed : ${
          f.getMessage
        }}")
        Future.successful(false)
      }
    }
  }
}

trait BulkCalculationRepository extends Repository[BulkCalculationRequest, BSONObjectID] {

  def metrics: Metrics = Metrics

  val emailConnector: EmailConnector = EmailConnector
  val auditConnector: AuditConnector = MicroserviceGlobal.auditConnector

  def insertResponseByReference(reference: String, lineId: Int, calculationResponse: GmpBulkCalculationResponse): Future[Boolean]

  def findByReference(reference: String, filter: CsvFilter = CsvFilter.All): Future[Option[BulkCalculationRequest]]

  def findSummaryByReference(reference: String): Future[Option[BulkResultsSummary]]

  def findByUserId(userId: String): Future[Option[List[BulkPreviousRequest]]]

  def findRequestsToProcess(): Future[Option[List[ProcessReadyCalculationRequest]]]

  def findCountRemaining: Future[Option[Int]]

  def findAndComplete(): Future[Boolean]

  def insertBulkDocument(bulkCalculationRequest: BulkCalculationRequest): Future[Boolean]
}

object BulkCalculationRepository extends MongoDbConnection {
  // $COVERAGE-OFF$
  private lazy val repository = new BulkCalculationMongoRepository

  // $COVERAGE-ON$
  def apply(): BulkCalculationMongoRepository = repository
}
