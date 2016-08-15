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

package connectors

import java.net.URLEncoder
import java.util.concurrent.TimeUnit

import config.{ApplicationConfig, WSHttp}
import metrics.Metrics
import models.{CalculationResponse, ValidCalculationRequest}
import play.api.Logger
import play.api.http.Status._
import uk.gov.hmrc.circuitbreaker.{CircuitBreakerConfig, UsingCircuitBreaker}
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.play.http._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

sealed trait DesGetResponse
sealed trait DesPostResponse

case object DesGetSuccessResponse extends DesGetResponse
case object DesGetHiddenRecordResponse extends DesGetResponse
case object DesGetNotFoundResponse extends DesGetResponse
case class DesGetErrorResponse(e: Exception) extends DesGetResponse

trait DesConnector extends ServicesConfig with RawResponseReads with UsingCircuitBreaker {

  private val PrefixStart = 0
  private val PrefixEnd = 1
  private val NumberStart = 1
  private val NumberEnd = 8
  private val SuffixStart = 8
  private val SuffixEnd = 9

  val serviceKey = getConfString("nps.key", "")
  val serviceEnvironment = getConfString("nps.environment", "")
  val http: HttpGet = WSHttp
  val metrics: Metrics

  lazy val serviceURL = baseUrl("nps")
  lazy val desUrl = baseUrl("des")

  val baseURI = "pensions/individuals/gmp"
  val baseSconURI = "pensions/gmp/scon"

  val calcURI = s"$serviceURL/$baseURI"

  def calculate(request: ValidCalculationRequest): Future[CalculationResponse] = {

    val paramMap: Map[String, Option[Any]] = Map(
      "revalrate" -> request.revaluationRate, "revaldate" -> request.revaluationDate, "calctype" -> request.calctype,
      "request_earnings" -> Some(1), "dualcalc" -> request.dualCalc, "term_date" -> request.terminationDate)

    val surname = URLEncoder.encode((if (request.surname.replace(" ", "").length < 3) {
      request.surname.replace(" ", "")
    } else {
      request.surname.replace(" ", "").substring(0, 3)
    }).toUpperCase, "UTF-8")

    val firstname = URLEncoder.encode(request.firstForename.charAt(0).toUpper.toString, "UTF-8")

    val uri =
      s"""$calcURI/scon/${
        request.scon.substring(PrefixStart,
          PrefixEnd).toUpperCase
      }/${
        request.scon.substring(NumberStart,
          NumberEnd)
      }/${
        request.scon.substring(SuffixStart,
          SuffixEnd).toUpperCase
      }/nino/${request.nino.toUpperCase}/surname/$surname/firstname/$firstname/calculation/${buildEncodedQueryString(paramMap)}"""


    Logger.debug(s"[DesConnector][calculate] : $uri")

    val startTime = System.currentTimeMillis()

    val result = withCircuitBreaker(http.GET[HttpResponse](uri)(hc = npsRequestHeaderCarrier, rds = httpReads).map { response =>

      metrics.registerStatusCode(response.status.toString)

      val delta = System.currentTimeMillis() - startTime
      metrics.desConnectionTime(delta, TimeUnit.MILLISECONDS)

      response.status match {
        case OK | UNPROCESSABLE_ENTITY =>
          metrics.registerSuccessfulRequest
          response.json.as[CalculationResponse]

        case errorStatus: Int => {
          Logger.error(s"[DesConnector][calculate] : DES returned code $errorStatus and response body: ${response.body}")
          metrics.registerFailedRequest

          errorStatus match {
            case BAD_REQUEST => throw new Upstream4xxResponse("A 400 Bad Request exception was encountered", errorStatus, BAD_REQUEST)
            case _ => throw new Upstream5xxResponse("DES connector calculate failed", errorStatus, INTERNAL_SERVER_ERROR)
          }

        }
      }
    })

    result
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
      case e: Upstream5xxResponse if e.upstreamResponseCode == 503 && !e.message.contains("digital_rate_limit") => true
      case e: BadGatewayException => true
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
    val url = s"$desUrl/pay-as-you-earn/individuals/${nino.take(8)}"

    Logger.debug(s"[DesConnector][getPersonDetails] Contacting DES at $url")

    http.GET[HttpResponse](url)(implicitly[HttpReads[HttpResponse]], newHc) map { r =>

      metrics.mciConnectionTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)

      (r.json \ "manualCorrespondenceInd").asOpt[Boolean] match {
          case Some(true) =>
            metrics.mciLockResult()
            DesGetHiddenRecordResponse
          case _ => DesGetSuccessResponse
        }

    } recover {
      case e: NotFoundException => DesGetNotFoundResponse
      case e: Exception =>
        Logger.warn(s"[DesConnector][getPersonDetails] Exception thrown getting individual record from DES: $e")
        metrics.mciErrorResult()
        DesGetErrorResponse(e)
    }
  }
}

object DesConnector extends DesConnector {
  // $COVERAGE-OFF$Trivial and never going to be called by a test that uses it's own object implementation
  override val metrics = Metrics
  // $COVERAGE-ON$
}
