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

package actors

import com.google.inject.Inject
import config.{AppConfig, ApplicationConfiguration}
import connectors.{DesConnector, DesGetHiddenRecordResponse, HipConnector, IFConnector}
import metrics.ApplicationMetrics
import models.{CalculationResponse, GmpBulkCalculationResponse, HipCalculationFailuresResponse, HipCalculationRequest, HipCalculationResponse, ProcessReadyCalculationRequest}
import org.apache.pekko.actor._
import play.api.Logging
import play.api.http.Status
import repositories.BulkCalculationMongoRepository
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

trait CalculationRequestActorComponent {
  val desConnector: DesConnector
  val ifConnector: IFConnector
  val hipConnector: HipConnector
  val repository: BulkCalculationMongoRepository
  val metrics: ApplicationMetrics
  val applicationConfig: ApplicationConfiguration
  val appConfig: AppConfig
}

class CalculationRequestActor extends Actor with ActorUtils with Logging {

  self: CalculationRequestActorComponent =>

  override def receive: Receive = {
    case request: ProcessReadyCalculationRequest => {

      val origSender = sender()
      val startTime = System.currentTimeMillis()

      desConnector.getPersonDetails(request.validCalculationRequest.get.nino) map {
        case DesGetHiddenRecordResponse =>

          repository.insertResponseByReference(request.bulkId, request.lineId,
            GmpBulkCalculationResponse(List(), 423, None, None, None, containsErrors = true)).map { result =>

            origSender ! result

          }

        case x => {

          val tryCallingDes = Try {
            if(appConfig.isIfsEnabled) {
              ifConnector.calculate(request.validCalculationRequest.get)
            } else if (appConfig.isHipEnabled) {
              val hipRequest = HipCalculationRequest.from(request.validCalculationRequest.get)
              hipConnector.calculateOutcome(userId = "system", hipRequest)(HeaderCarrier())
            } else{
              desConnector.calculate(request.validCalculationRequest.get)
            }
          }

          tryCallingDes match {
            case Success(successfulCall) => {
              successfulCall.map {
                case x: CalculationResponse => {
                  repository.insertResponseByReference(request.bulkId, request.lineId, GmpBulkCalculationResponse.createFromCalculationResponse(x)).map {

                    result => {
                      // $COVERAGE-OFF$
                      metrics.processRequest(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
                      logger.debug(s"[CalculationRequestActor] InsertResponse : $result")
                      // $COVERAGE-ON$
                      origSender ! result
                    }
                  }
                }
                case Right(hipResponse: HipCalculationResponse) => {
                  repository.insertResponseByReference(request.bulkId, request.lineId, GmpBulkCalculationResponse.createFromHipCalculationResponse(hipResponse)).map {

                    result => {
                      // $COVERAGE-OFF$
                      metrics.processRequest(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
                      logger.debug(s"[CalculationRequestActor] InsertResponse : $result")
                      // $COVERAGE-ON$
                      origSender ! result
                    }
                  }
                }
                case Left(hipFailures: HipCalculationFailuresResponse) => {
                  repository.insertResponseByReference(request.bulkId, request.lineId, GmpBulkCalculationResponse.createFromHipFailuresResponse(hipFailures)).map {

                    result => {
                      // $COVERAGE-OFF$
                      metrics.processRequest(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
                      logger.debug(s"[CalculationRequestActor] InsertResponse (422) : $result")
                      // $COVERAGE-ON$
                      origSender ! result
                    }
                  }
                }
              }.recover {

                case e: UpstreamErrorResponse if e.reportAs == Status.BAD_REQUEST => {

                  // $COVERAGE-OFF$
                  val cid = e.headers.get("correlationId").flatMap(_.headOption).getOrElse("n/a")
                  logger.error(s"[CalculationRequestActor] HIP/IF 400 Bad Request (cid: $cid). Error: $e")
                  // $COVERAGE-ON$

                  // Record the response as a failure, which will help out with cyclic processing of messages
                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), 400, None, None, None, containsErrors = true)).map { result =>

                    origSender ! result

                  }
                }

                case e: UpstreamErrorResponse if appConfig.isHipEnabled && e.reportAs == Status.FORBIDDEN => {
                  // $COVERAGE-OFF$
                  val cid = e.headers.get("correlationId").flatMap(_.headOption).getOrElse("n/a")
                  logger.error(s"[CalculationRequestActor] HIP 403 Forbidden (cid: $cid). Error: $e")
                  // $COVERAGE-ON$

                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), 403, None, None, None, containsErrors = true)).map { result =>
                    origSender ! result
                  }
                }

                case e: UpstreamErrorResponse if appConfig.isHipEnabled && e.reportAs == Status.NOT_FOUND => {
                  // $COVERAGE-OFF$
                  val cid = e.headers.get("correlationId").flatMap(_.headOption).getOrElse("n/a")
                  logger.error(s"[CalculationRequestActor] HIP 404 Not Found (cid: $cid). Error: $e")
                  // $COVERAGE-ON$

                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), 404, None, None, None, containsErrors = true)).map { result =>
                    origSender ! result
                  }
                }

                case e: UpstreamErrorResponse if appConfig.isHipEnabled && (e.reportAs == Status.INTERNAL_SERVER_ERROR || e.reportAs == Status.SERVICE_UNAVAILABLE) => {
                  // $COVERAGE-OFF$
                  val cid = e.headers.get("correlationId").flatMap(_.headOption).getOrElse("n/a")
                  logger.error(s"[CalculationRequestActor] HIP ${e.reportAs} Server Error (cid: $cid). Error: $e")
                  // $COVERAGE-ON$

                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), e.reportAs, None, None, None, containsErrors = true)).map { result =>
                    origSender ! result
                  }
                }

                // HIP circuit-breaker path (e.g., 503 mapped to breaker in HipConnector)
                case be: connectors.HipConnector#BreakerException if appConfig.isHipEnabled => {
                  // $COVERAGE-OFF$
                  logger.error(s"[CalculationRequestActor] HIP 503 Service Unavailable via circuit breaker. Error: $be")
                  // $COVERAGE-ON$

                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), Status.SERVICE_UNAVAILABLE, None, None, None, containsErrors = true)).map { result =>
                    origSender ! result
                  }
                }

                // Fallback: match BreakerException by class name to guard against type mismatch
                case e if appConfig.isHipEnabled && e.getClass.getName.endsWith("HipConnector$BreakerException") => {
                  // $COVERAGE-OFF$
                  logger.error(s"[CalculationRequestActor] HIP 503 Service Unavailable via circuit breaker (fallback match). Error: $e")
                  // $COVERAGE-ON$

                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), Status.SERVICE_UNAVAILABLE, None, None, None, containsErrors = true)).map { result =>
                    origSender ! result
                  }
                }

                case e =>
                  // $COVERAGE-OFF$
                  logger.error(s"[CalculationRequestActor] Inserting Failure response failed with error :$e")
                  origSender ! org.apache.pekko.actor.Status.Failure(e)
                // $COVERAGE-ON$
              }
            }

            case Failure(f) => {

              f match {

                // HIP circuit breaker thrown synchronously before Future is returned
                case be: connectors.HipConnector#BreakerException if appConfig.isHipEnabled =>
                  // $COVERAGE-OFF$
                  logger.error(s"[CalculationRequestActor] HIP 503 Service Unavailable via circuit breaker (sync). Error: $be")
                  // $COVERAGE-ON$

                  // Record the response as a failure to allow cyclic processing
                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), Status.SERVICE_UNAVAILABLE, None, None, None, containsErrors = true)).map { _ =>
                      origSender ! true
                    }

                // Fallback match by class name in case of type erasure/mismatch
                case e if appConfig.isHipEnabled && e.getClass.getName.endsWith("HipConnector$BreakerException") =>
                  // $COVERAGE-OFF$
                  logger.error(s"[CalculationRequestActor] HIP 503 Service Unavailable via circuit breaker (sync fallback). Error: $e")
                  // $COVERAGE-ON$

                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), Status.SERVICE_UNAVAILABLE, None, None, None, containsErrors = true)).map { _ =>
                      origSender ! true
                    }

                case _ =>
                  // $COVERAGE-OFF$
                  logger.error(s"[CalculationRequestActor] Calling DES failed with error: ${ f.getMessage }")
                  // $COVERAGE-ON$
                  origSender ! org.apache.pekko.actor.Status.Failure(f)
              }
            }

          }

        }
      } recover {
        case e =>
          // $COVERAGE-OFF$
          logger.error(s"[CalculationRequestActor] Calling getPersonDetails failed with error: ${ e.getMessage }")
        // $COVERAGE-ON$
      }


    }

    case STOP => {
      // $COVERAGE-OFF$
      logger.info(s"[CalculationRequestActor] stop message")
      logger.info("sender: " + sender().getClass)
      // $COVERAGE-ON$
      sender() ! STOP
    }


    case e => {
      // $COVERAGE-OFF$
      logger.info(s"[CalculationRequestActor] Invalid Message : { message : $e}")
      logger.info("sender: " + sender().getClass)
      // $COVERAGE-ON$
      sender() ! org.apache.pekko.actor.Status.Failure(new RuntimeException(s"invalid message: $e"))
    }
  }
}

class DefaultCalculationRequestActor @Inject()(override val repository : BulkCalculationMongoRepository,
                                               override val desConnector : DesConnector,
                                               override val ifConnector: IFConnector,
                                               override val hipConnector: HipConnector,
                                               override val metrics : ApplicationMetrics,
                                               override val applicationConfig: ApplicationConfiguration,
                                               override val appConfig: AppConfig
                                              ) extends CalculationRequestActor with CalculationRequestActorComponent {
}