/*
 * Copyright 2019 HM Revenue & Customs
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

import java.net.URLEncoder
import java.util.concurrent.TimeUnit

import com.google.inject.Inject
import config.ApplicationConfig
import metrics.ApplicationMetrics
import models.{CalculationResponse, ValidCalculationRequest}
import play.api.Mode.Mode
import play.api.http.Status._
import play.api.{Configuration, Environment, Logger}
import uk.gov.hmrc.circuitbreaker.{CircuitBreakerConfig, UsingCircuitBreaker}
import uk.gov.hmrc.play.config.ServicesConfig

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.{BadGatewayException, GatewayTimeoutException, HeaderCarrier, HttpGet, HttpReads, HttpResponse, NotFoundException, Upstream4xxResponse}

sealed trait DesGetResponse
sealed trait DesPostResponse

case object DesGetSuccessResponse extends DesGetResponse
case object DesGetHiddenRecordResponse extends DesGetResponse
case object DesGetNotFoundResponse extends DesGetResponse
case object DesGetUnexpectedResponse extends DesGetResponse
case class DesGetErrorResponse(e: Exception) extends DesGetResponse

class DesConnector @Inject()(environment: Environment,
                             val runModeConfiguration: Configuration,
                             http: HttpGet,
                             val metrics: ApplicationMetrics) extends ServicesConfig with UsingCircuitBreaker {

  implicit val httpReads: HttpReads[HttpResponse] = new HttpReads[HttpResponse] {
    override def read(method: String, url: String, response: HttpResponse) = response
  }

  override protected def mode: Mode = environment.mode

  val serviceKey = getConfString("nps.key", "")
  val serviceEnvironment = getConfString("nps.environment", "")
  lazy val citizenDetailsUrl: String = baseUrl("citizen-details")

  lazy val serviceURL = baseUrl("nps")
  val baseURI = "pensions/individuals/gmp"
  val baseSconURI = "pensions/gmp/scon"

  val calcURI = s"$serviceURL/$baseURI"

  class BreakerException extends Exception

  def calculate(request: ValidCalculationRequest): Future[CalculationResponse] = {
    val url = calcURI + request.uri
    Logger.info(s"[DesConnector][calculate] contacting DES at $url")

    val startTime = System.currentTimeMillis()

    withCircuitBreaker(http.GET[HttpResponse](url, request.queryParams)
      (hc = npsRequestHeaderCarrier, rds = httpReads, ec = ExecutionContext.global).map { response =>

      metrics.registerStatusCode(response.status.toString)
      metrics.desConnectionTime(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)

      response.status match {
        case OK | UNPROCESSABLE_ENTITY =>
          metrics.registerSuccessfulRequest()
          response.json.as[CalculationResponse]

        case errorStatus: Int => {
          Logger.error(s"[DesConnector][calculate] DES URI $url returned code $errorStatus and response body: ${response.body}")
          metrics.registerFailedRequest()

          errorStatus match {
            case e if e>=500 && e<600 => throw new BreakerException
            case TOO_MANY_REQUESTS => throw new BreakerException
            case 499 => throw new BreakerException
            case e => throw new Upstream4xxResponse(s"An error status $errorStatus was encountered", errorStatus, errorStatus)
          }
        }
      }
    })(hc=npsRequestHeaderCarrier)
  }

  private def npsRequestHeaderCarrier: HeaderCarrier = {

    HeaderCarrier(extraHeaders = Seq(
      "Gov-Uk-Originator-Id" -> getConfString("nps.originator-id",""),
      "Authorization" -> s"Bearer $serviceKey",
      "Environment" -> serviceEnvironment))

  }

  private def buildEncodedQueryString(params: Map[String, Any]): String = {
    val encoded = for {
      (name, value) <- params if value != None
      encodedValue = value match {
        case Some(x) => URLEncoder.encode(x.toString, "UTF8")
      }
    } yield name + "=" + encodedValue

    encoded.mkString("?", "&", "")
  }

  override protected def circuitBreakerConfig: CircuitBreakerConfig = {
    CircuitBreakerConfig("DesConnector",
      ApplicationConfig.numberOfCallsToTriggerStateChange,
      ApplicationConfig.unavailablePeriodDuration,
      ApplicationConfig.unstablePeriodDuration)
  }

  override protected def breakOnException(t: Throwable): Boolean = {
    t match {
      // $COVERAGE-OFF$
      case _: BreakerException => true
      case _: BadGatewayException => true
      case _: GatewayTimeoutException => true
      case _ => false
      // $COVERAGE-ON$
    }
  }

  def getPersonDetails(nino: String)(implicit hc: HeaderCarrier): Future[DesGetResponse] = {

    val newHc = HeaderCarrier(extraHeaders = Seq(
      "Gov-Uk-Originator-Id" -> getConfString("des.originator-id",""),
      "Authorization" -> s"Bearer $serviceKey",
      "Environment" -> serviceEnvironment))

    val startTime = System.currentTimeMillis()
    val url = s"$citizenDetailsUrl/citizen-details/$nino/etag"

    Logger.debug(s"[DesConnector][getPersonDetails] Contacting DES at $url")

    http.GET[HttpResponse](url)(implicitly[HttpReads[HttpResponse]], newHc, ec = ExecutionContext.global) map { response =>
      metrics.mciConnectionTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)

      response.status match {
          case LOCKED =>
            metrics.mciLockResult()
            DesGetHiddenRecordResponse
          case NOT_FOUND => DesGetNotFoundResponse
          case OK => DesGetSuccessResponse
          case INTERNAL_SERVER_ERROR => DesGetUnexpectedResponse
          case _ => DesGetUnexpectedResponse
        }

    } recover {
      case e: NotFoundException => DesGetNotFoundResponse
      case e: Exception =>
        Logger.error(s"[DesConnector][getPersonDetails] Exception thrown getting individual record from DES: $e")
        metrics.mciErrorResult()
        DesGetErrorResponse(e)
    }
  }
}
