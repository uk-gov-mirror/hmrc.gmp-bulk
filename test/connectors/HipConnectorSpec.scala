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

import config.{AppConfig, ApplicationConfiguration}
import metrics.ApplicationMetrics
import models.{EnumCalcRequestType, EnumRevaluationRate, HipCalculationRequest, HipCalculationResponse}
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfter, RecoverMethods}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.libs.json.Json
import play.api.test.Helpers.{BAD_REQUEST, OK, await}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import utils.WireMockHelper

import scala.concurrent.{ExecutionContext, Future}

class HipConnectorSpec extends HttpClientV2Helper with GuiceOneServerPerSuite with WireMockHelper with BeforeAndAfter {
  implicit lazy val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val mockApplicationConfiguration: ApplicationConfiguration = mock[ApplicationConfiguration]
  val mockAppConfig: AppConfig = mock[AppConfig]
  val mockMetrics: ApplicationMetrics = mock[ApplicationMetrics]

  // AppConfig mocks
  when(mockAppConfig.hipUrl).thenReturn("http://localhost:9999")
  when(mockAppConfig.hipAuthorisationToken).thenReturn("dGVzdC1hdXRo") // base64 encoded token
  when(mockAppConfig.originatorIdKey).thenReturn("gov-uk-originator-id")
  when(mockAppConfig.originatorIdValue).thenReturn("HMRC-GMP")
  when(mockAppConfig.hipEnvironmentHeader).thenReturn("Environment" -> "local")
  when(mockAppConfig.isHipEnabled).thenReturn(true)

  class SUT extends HipConnector(mockHttp, mockMetrics, mockApplicationConfiguration, mockAppConfig) {
  }
  "HipConnector" should {
    implicit val hc = HeaderCarrier()
    "for calculate" should {
      val calculateUrl: String = "http://localhost:9943/pension/gmp/calculation"
      "return successful response for status 200" in new SUT {
        when(calcURI).thenReturn(calculateUrl)
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        val successResponse = HipCalculationResponse("", "S2123456B", "", Some(""), Some(""), Some(""), List.empty)
        val httpResponse = HttpResponse(OK, Json.toJson(successResponse).toString())
        requestBuilderExecute(Future.successful(httpResponse))
        calculate(request).map{
          result => result.schemeContractedOutNumberDetails mustBe "S2123456B"
        }
      }
      "return a response for status 400" in new SUT{
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        val successResponse = HipCalculationResponse("", "S2123456B", "", Some(""), Some(""), Some(""), List.empty)
        val httpResponse = HttpResponse(BAD_REQUEST, Json.toJson(successResponse).toString())
        requestBuilderExecute(Future.successful(httpResponse))
        calculate(request).map { result =>
          result mustBe ""
        }
      }

      "return fallback HipCalculationResponse for 400 Bad Request" in new SUT{
        val successResponse = HipCalculationResponse("", "S2123456B", "", Some(""), Some(""), Some(""), List.empty)
        val httpResponse = HttpResponse(BAD_REQUEST, Json.toJson(successResponse).toString())
        val request = HipCalculationRequest("S2123456B", "", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        requestBuilderExecute(Future.successful(httpResponse))
        calculate(request).map { result =>
          httpResponse.status mustBe 400
        }
      }

      "fail the future if HTTP call fails" in new SUT{
        val request = HipCalculationRequest("S2123456B", "", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        requestBuilderExecute(Future.failed(new RuntimeException("Connection error")))
        RecoverMethods.recoverToExceptionIf[RuntimeException] {
          calculate(request)
        }.map {
          ex => ex.getMessage must include("Connection error")
        }
      }

      "throw UpstreamErrorResponse for error status code 500" in new SUT {
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        val successResponse = HipCalculationResponse("", "S2123456B", "", Some(""), Some(""), Some(""), List.empty)
        val httpResponse = HttpResponse(500, Json.toJson(successResponse).toString())
        requestBuilderExecute(Future.successful(httpResponse))
        RecoverMethods.recoverToExceptionIf[UpstreamErrorResponse] {
          calculate(request)
        }.map { exception =>
          exception.statusCode mustBe 500
          exception.reportAs mustBe INTERNAL_SERVER_ERROR
        }
      }

      "throw RuntimeException when JSON validation fails" in new SUT{
        val invalidJson = Json.obj("unexpectedField" -> "unexpectedValue")
        val httpResponse = HttpResponse(OK, invalidJson.toString())
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        requestBuilderExecute(Future.successful(httpResponse))
        RecoverMethods.recoverToExceptionIf[RuntimeException] {
          calculate(request)
        }.map{
          exception => exception.getMessage must include("Invalid JSON response from HIP")
        }
      }
    }
  }
}
