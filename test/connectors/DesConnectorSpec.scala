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

package connectors

import java.util.UUID
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import config.ApplicationConfiguration
import helpers.RandomNino
import metrics.ApplicationMetrics
import models.ValidCalculationRequest
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatest._
import play.api.test.Helpers._
import uk.gov.hmrc.http._
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import utils.WireMockHelper
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import uk.gov.hmrc.http.client.HttpClientV2

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}

class DesConnectorSpec extends HttpClientV2Helper with GuiceOneServerPerSuite with WireMockHelper with BeforeAndAfter {

  private val injector = app.injector
  private val mockMetrics = mock[ApplicationMetrics]
  private val http = injector.instanceOf[HttpClientV2]
  private val servicesConfig = injector.instanceOf[ServicesConfig]
  private val applicationConfig = injector.instanceOf[ApplicationConfiguration]
  private val NGINX_CLIENT_CLOSED_REQUEST = 499

  override def beforeEach(): Unit = {
    super.beforeEach()
    Mockito.reset(mockMetrics)
  }

  def stubServiceGet(url: String, responseStatus: Int, responseBody: String, queryParam: (String, String)*): StubMapping = {
    server.stubFor(get(urlPathEqualTo(url))
      .withQueryParams(queryParam.map(qp => (qp._1, equalTo(qp._2))).toMap.asJava)
      .willReturn(
        aResponse()
        .withStatus(responseStatus)
        .withBody(responseBody)
      )
    )
  }

  class SUT(httpC:HttpClientV2 = http) extends DesConnector(app.configuration, httpC, mockMetrics, servicesConfig, applicationConfig, ExecutionContext.global) {
    override lazy val serviceURL: String = "http://localhost:" + server.port()
    override lazy val citizenDetailsUrl: String = serviceURL
  }

  private val nino = RandomNino.generate
  implicit val hc: HeaderCarrier = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))

  val calcResponseJson: String =
    s"""{
           "nino":"$nino",
           "rejection_reason":0,
           "npsScon":{
              "contracted_out_prefix":"S",
              "ascn_scon":1301234,
              "modulus_19_suffix":"T"
           },
           "npsLgmpcalc":[
              {
                 "scheme_mem_start_date":"1978-04-06",
                 "scheme_end_date":"200-04-05",
                 "revaluation_rate":1,
                 "gmp_cod_post_eightyeight_tot":1.23,
                 "gmp_cod_allrate_tot":7.88,
                 "gmp_error_code":0,
                "reval_calc_switch_ind": 0
              }
           ]
        }"""

  val citizenDetailsJson: String =
            """{
                  "etag" : "115"
                }
            """

  "The Nps Connector" must {

    "calculate" must {

      "return a calculation request" in new SUT {
        val url = s"/pensions/individuals/gmp/scon/S/1234567/T/nino/$nino/surname/BIX/firstname/B/calculation/"
        stubServiceGet(url, OK, calcResponseJson, ("request_earnings" -> "1"), ("calctype" -> "0"))

        val result = await(calculate(ValidCalculationRequest("S1234567T", nino, "Bixby", "Bill", None, Some(0), None, Some(1), None, None)))

        result.npsLgmpcalc.length must be(1)
        Mockito.verify(mockMetrics).registerSuccessfulRequest()
      }

      "return a encoded characters in URLs correctly" in new SUT {
        val encodedSurname = URLEncoder.encode("O'N", StandardCharsets.UTF_8.toString)
        val url = s"/pensions/individuals/gmp/scon/S/1234567/T/nino/$nino/surname/$encodedSurname/firstname/B/calculation/"
        stubServiceGet(url, OK, calcResponseJson, ("request_earnings" -> "1"), ("calctype" -> "0"))

        val request = ValidCalculationRequest("S1234567T", nino, "O'Neill", "Bill", None, Some(0), None, Some(1), None, None)
        val result = await(calculate(request))

        result.npsLgmpcalc.length must be(1)
        Mockito.verify(mockMetrics).registerSuccessfulRequest()
      }

      "return not found when scon does not exist" in new SUT {
        val request = ValidCalculationRequest("S1234567T", nino, "Bixby", "Bill", None, Some(0), None, Some(1), None, None)

        val url = s"/pensions/individuals/gmp/scon/S/1234567/T/nino/$nino/surname/BIX/firstname/B/calculation/"
        stubServiceGet(url, NOT_FOUND, "", ("request_earnings" -> "1"), ("calctype" -> "0"))

        intercept[UpstreamErrorResponse] {
          await(calculate(request))
        }
      }

      "return an error when 400 returned" in new SUT {
        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(BAD_REQUEST, "400")))

        val url = s"/pensions/individuals/gmp/scon/S/1401234/Q/nino/$nino/surname/SMI/firstname/B/calculation/"
        stubServiceGet(url, BAD_REQUEST, "Bad request", ("request_earnings" -> "1"))

        val result = calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, None, None, None, None, None))

        intercept[UpstreamErrorResponse] {
          await(result)
        }
        Mockito.verify(metrics).registerFailedRequest()
      }

      val errorCodes4xx = List(TOO_MANY_REQUESTS, NGINX_CLIENT_CLOSED_REQUEST)
      for (errorCode <- errorCodes4xx) {
        s"return a BreakerException exception when $errorCode returned from DES" in new SUT {
          val request = ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)

          val url = s"""/pensions/individuals/gmp/scon/S/1401234/Q/nino/${request.nino.toUpperCase}/surname/SMI/firstname/B/calculation/"""
          stubServiceGet(url, errorCode, "", ("request_earnings" -> "1"))

          intercept[BreakerException] {
            await(calculate(request))
          }
          Mockito.verify(mockMetrics).registerFailedRequest()
        }
      }

      val errorCodes5xx = List(BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT, INTERNAL_SERVER_ERROR)
      for (errorCode <- errorCodes5xx) {
        s"return a UpstreamErrorResponse exception when $errorCode returned from DES" in new SUT {
          val request = ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)

          val url = s"""/pensions/individuals/gmp/scon/S/1401234/Q/nino/${request.nino.toUpperCase}/surname/SMI/firstname/B/calculation/"""
          stubServiceGet(url, errorCode, "", ("request_earnings" -> "1"))

          intercept[UpstreamErrorResponse] {
            await(calculate(request))
          }
          Mockito.verify(mockMetrics).registerFailedRequest()
        }
      }

      "return a success when 422 returned" in new SUT {
        val url = s"""/pensions/individuals/gmp/scon/S/1401234/Q/nino/$nino/surname/SMI/firstname/B/calculation/"""
        stubServiceGet(url, UNPROCESSABLE_ENTITY, calcResponseJson, ("request_earnings" -> "1"), ("calctype" -> "0"))

        val result = await(calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, Some(0), None, None, None, None)))

        result.rejection_reason must be(0)

        Mockito.verify(mockMetrics).registerStatusCode(UNPROCESSABLE_ENTITY.toString)
        Mockito.verify(mockMetrics).desConnectionTime(ArgumentMatchers.any(), ArgumentMatchers.any())
      }
    }

    "Calling getPersonDetails" should {

      val getPersonDetailTestScenarios = List(
        (OK, citizenDetailsJson, DesGetSuccessResponse),
        (LOCKED, citizenDetailsJson, DesGetHiddenRecordResponse),
        (NOT_FOUND, "", DesGetNotFoundResponse),
        (INTERNAL_SERVER_ERROR, "", DesGetUnexpectedResponse),
        (SERVICE_UNAVAILABLE, "", DesGetUnexpectedResponse),
        (TOO_MANY_REQUESTS, "", DesGetUnexpectedResponse),
        (NGINX_CLIENT_CLOSED_REQUEST, "", DesGetUnexpectedResponse)
      )

      for ((status, body, expected) <- getPersonDetailTestScenarios) {
        s"return a ${expected.getClass.toString} when status from DES is $status" in new SUT {
          val url = s"/citizen-details/$nino/etag"
          stubServiceGet(url, status, body)

          await(getPersonDetails(nino)) must be(expected)
        }
      }

      s"hit metrics mciLockResult when status from DES is 423" in new SUT {
        val url = s"/citizen-details/$nino/etag"
        stubServiceGet(url, LOCKED, citizenDetailsJson)

        await(getPersonDetails(nino))
        Mockito.verify(mockMetrics).mciLockResult()
      }

      "return a DesErrorResponse if any other issues" ignore new SUT(mockHttp) {
        val ex = new Exception("Exception")
        requestBuilderExecute[HttpResponse] {
          Future.failed(ex)
        }

        await(getPersonDetails(nino)) must be(DesGetErrorResponse(ex))
      }

      "return a success response if the MCI flag does not appear in the response" in new SUT {
        val url = s"/citizen-details/$nino/etag"
        stubServiceGet(url, OK, "{}")

        await(getPersonDetails(nino)) must be(DesGetSuccessResponse)
      }
    }
  }
}
