/*
 * Copyright 2025 HM Revenue & Customs
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

package connectors

import com.google.inject.Inject
import config.{AppConfig, ApplicationConfiguration, Constants}
import metrics.ApplicationMetrics
import models.{HipCalculationRequest, HipCalculationResponse}
import play.api.http.Status.{BAD_REQUEST, FORBIDDEN, INTERNAL_SERVER_ERROR, NOT_FOUND, OK}
import play.api.i18n.Lang.logger
import play.api.libs.json.{JsError, JsSuccess, Json}
import uk.gov.hmrc.circuitbreaker.{CircuitBreakerConfig, UsingCircuitBreaker}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{BadGatewayException, GatewayTimeoutException, HeaderCarrier, HttpResponse, StringContextOps, UpstreamErrorResponse}

import scala.concurrent.ExecutionContext.Implicits.global
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.util.{Failure, Success}

class HipConnector @Inject()(http: HttpClientV2,
                             val metrics: ApplicationMetrics,
                             appConfig: ApplicationConfiguration,
                             config: AppConfig) extends UsingCircuitBreaker {
  val hipBaseUrl: String = config.hipUrl
  val calcURI = s"$hipBaseUrl/ni/gmp/calculation"
  private implicit val hc: HeaderCarrier = HeaderCarrier()

  def calculate(request: HipCalculationRequest): Future[HipCalculationResponse] = {
    logger.info(s"[calculate] contacting HIP at $calcURI")
    val startTime = System.currentTimeMillis()
    withCircuitBreaker(http.post(url"$calcURI")
      .setHeader(buildHeadersV1(hc): _*)
      .withBody(Json.toJson(request)).execute[HttpResponse].map { response =>
        metrics.registerStatusCode(response.status.toString)
        metrics.hipConnectionTime(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
        response.status match {
          case OK =>
            response.json.validate[HipCalculationResponse] match {
              case JsSuccess(value, _) => value
              case JsError(errors) =>
                val errorFields = errors.map(_._1.toString()).mkString(", ")
                val responseCode = response.status
                val responseBody = response.body.take(1000) // truncate if very long
                val detailedMsg =
                  s"HIP returned invalid JSON (status: $responseCode). Failed to parse fields: $errorFields. Body: $responseBody"
                logger.error(s"[HipConnector][calculate] $detailedMsg")
                throw new RuntimeException(detailedMsg)
            }
          case BAD_REQUEST => logger.warn(s"[HipConnector][calculate] : HIP returned 400 (Bad Request). Body: ${response.body}")
            HipCalculationResponse(
              request.nationalInsuranceNumber,
              BAD_REQUEST.toString,
              "The request was invalid — please check the provided details.",
              None,
              None,
              Some(request.schemeContractedOutNumber),
              Nil
            )
          case FORBIDDEN =>
            logger.warn(s"[HipConnector][calculate] : HIP returned 403 (Forbidden). Body: ${response.body}")
            HipCalculationResponse(
              request.nationalInsuranceNumber,
              FORBIDDEN.toString,
              "You are not authorised to perform this calculation.",
              None,
              None,
              Some(request.schemeContractedOutNumber),
              Nil
            )

          case NOT_FOUND =>
            logger.warn(s"[HipConnector][calculate] : HIP returned 404 (Not Found). Body: ${response.body}")
            HipCalculationResponse(
              request.nationalInsuranceNumber,
              NOT_FOUND.toString,
              "The requested calculation could not be found.",
              None,
              None,
              Some(request.schemeContractedOutNumber),
              Nil
            )

          case errorStatus: Int =>
            logger.error(s"[HipConnector][calculate] : HIP returned $errorStatus and response body: ${response.body}")
            throw UpstreamErrorResponse("HIP connector calculation failed", errorStatus, INTERNAL_SERVER_ERROR)
        }
      }
    )
  }

  private def buildHeadersV1(hc: HeaderCarrier): Seq[(String, String)] =
    Seq(
      Constants.OriginatorIdKey -> config.originatorIdValue,
      "correlationId" -> getCorrelationId,
      "Authorization" -> s"Basic ${config.hipAuthorisationToken}",
      config.hipEnvironmentHeader,
      "X-Originating-System" -> Constants.XOriginatingSystemHeader,
      "X-Receipt-Date" -> DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)),
      "X-Transmitting-System" -> Constants.XTransmittingSystemHeader
    )

  private def getCorrelationId: String =
    UUID.randomUUID().toString

  override protected def circuitBreakerConfig: CircuitBreakerConfig = {
    CircuitBreakerConfig("HipConnector",
      appConfig.numberOfCallsToTriggerStateChange,
      appConfig.unavailablePeriodDuration,
      appConfig.unstablePeriodDuration)
  }

  override protected def breakOnException(t: Throwable): Boolean = {
    t match {
      case _: BreakerException => true
      case _: BadGatewayException => true
      case _: GatewayTimeoutException => true
      case _ => false
    }
  }

  class BreakerException extends Exception
}
