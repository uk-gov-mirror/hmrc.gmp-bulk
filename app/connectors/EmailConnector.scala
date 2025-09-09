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

import com.google.inject.Inject

import java.time.LocalDate
import play.api.libs.json.{Json, OFormat}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.client.HttpClientV2
import play.api.libs.ws.WSBodyWritables.writeableOf_JsValue

import java.time.format.DateTimeFormatter
import scala.concurrent.{ExecutionContext , Future}

case class ReceivedUploadTemplate(email: String, uploadReference: String)

case class ProcessedUploadTemplate(email: String, uploadReference: String, uploadDate: LocalDate, userId: String)

case class SendTemplatedEmailRequest(to: List[String], templateId: String, parameters: Map[String, String])

object SendTemplatedEmailRequest {
  implicit val format: OFormat[SendTemplatedEmailRequest] = Json.format[SendTemplatedEmailRequest]
}

class EmailConnector @Inject()(http: HttpClientV2,
                               val runModeConfiguration: Configuration,
                               servicesConfig: ServicesConfig,
                               implicit val ec: ExecutionContext) extends Logging {

  def sendReceivedTemplatedEmail(template: ReceivedUploadTemplate)(implicit hc: HeaderCarrier): Future[Boolean] = {

    val request = SendTemplatedEmailRequest(List(template.email), "gmp_bulk_upload_received", Map("fileUploadReference" -> template.uploadReference))

    logger.info(s"[EmailConnector] Sending gmp_bulk_upload_received email")
    sendEmail(request)

  }

  def sendProcessedTemplatedEmail(template: ProcessedUploadTemplate)(implicit hc: HeaderCarrier): Future[Boolean] = {

    val request = SendTemplatedEmailRequest(List(template.email), "gmp_bulk_upload_processed",
      Map(
        "fileUploadReference" -> template.uploadReference,
        "uploadDate" -> template.uploadDate.format(DateTimeFormatter.ofPattern("dd MMMM yyyy")),
        "userId" -> (("*" * 5) + template.userId.takeRight(3)))
    )

    logger.info(s"[EmailConnector] Sending gmp_bulk_upload_processed email")
    sendEmail(request)
  }

  private def sendEmail(request: SendTemplatedEmailRequest)(implicit hc: HeaderCarrier): Future[Boolean] = {

    val url = s"${servicesConfig.baseUrl("email")}/hmrc/email"

    logger.debug(s"[EmailConnector] Sending email to ${request.to.mkString(", ")}")

    http.post(url"$url")
      .setHeader(Seq(("Content-Type", "application/json"))*)
      .withBody(Json.toJson(request))
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case 202 => logger.debug(s"[EmailConnector] Email sent: ${response.body}"); true
          case _ => logger.error(s"[EmailConnector] Email not sent: ${response.body}"); false
        }
      }
  }
}