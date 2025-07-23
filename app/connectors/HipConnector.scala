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
import play.api.http.Status.{BAD_REQUEST, INTERNAL_SERVER_ERROR, OK}
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

class HipConnector @Inject()(http: HttpClientV2,
                             val metrics: ApplicationMetrics,
                             appConfig: ApplicationConfiguration,
                             config: AppConfig) extends UsingCircuitBreaker {
  val hipBaseUrl: String = config.hipUrl
  val calcURI = s"$hipBaseUrl/pension/gmp/calculation"
  private implicit val hc: HeaderCarrier = HeaderCarrier()

  def calculate(request: HipCalculationRequest)(implicit hc: HeaderCarrier): Future[HipCalculationResponse] = {
    logger.info(s"[calculate] contacting HIP at $calcURI")
    val startTime = System.currentTimeMillis()
    withCircuitBreaker(http.post(url"$calcURI")
      .setHeader(buildHeadersV1(hc): _*)
      .withBody(Json.toJson(request)).execute[HttpResponse].map { response =>
        metrics.registerStatusCode(response.status.toString)
        metrics.desConnectionTime(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
        response.json.validate[HipCalculationResponse] match {
          case JsSuccess(value, _) =>
            response.status match {
              case OK => value
              case BAD_REQUEST => logger.info("[HipConnector][calculate] : NPS returned code 400")
                HipCalculationResponse(request.nationalInsuranceNumber, BAD_REQUEST.toString, "", None, None, Some(request.schemeContractedOutNumber), Nil)
              case errorStatus: Int => logger.error(s"[HipConnector][calculate] : NPS returned code $errorStatus and response body: ${response.body}")
                throw UpstreamErrorResponse("HIP connector calculate failed", errorStatus, INTERNAL_SERVER_ERROR)
            }
          case JsError(errors) => logger.error(s"[HipConnector][calculate] JSON validation failed: $errors")
            throw new RuntimeException("Invalid JSON response from HIP")
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
