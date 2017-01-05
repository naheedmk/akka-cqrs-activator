/*
 * Copyright 2017 Branislav Lazic
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

package org.akkacqrs

import java.time.LocalDateTime
import java.util.UUID

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.util.Timeout
import akka.http.scaladsl.server.Directives._

import scala.concurrent.duration._
import akka.pattern._
import akka.stream.scaladsl.Source
import de.heikoseeberger.akkasse.ServerSentEvent
import org.akkacqrs.IssueTrackerWrite._
import org.akkacqrs.PublishSubscribeMediator.Subscribe

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

object HttpServer {

  final case class CreateIssueRequest(description: String)
  final case class UpdateDescriptionRequest(id: UUID, description: String)
  final case class CloseIssueRequest(id: UUID)

  final val Name = "http-server"

  def routes(issueTrackerWriteManager: ActorRef,
             publishSubscribeMediator: ActorRef,
             requestTimeout: FiniteDuration,
             eventBufferSize: Int)(implicit executionContext: ExecutionContext): Route = {
    import de.heikoseeberger.akkahttpcirce.CirceSupport._
    import de.heikoseeberger.akkasse.EventStreamMarshalling._
    import io.circe.generic.auto._
    import io.circe.syntax._
    implicit val timeout = Timeout(requestTimeout)

    def fromEventStream[A: ClassTag](toServerSentEvent: A => ServerSentEvent) = {
      Source
        .actorRef[A](eventBufferSize, OverflowStrategy.dropHead)
        .map(toServerSentEvent)
        .mapMaterializedValue(publishSubscribeMediator ! Subscribe(className[A], _))
    }

    def fromIssueTrackerEvent(event: IssueTrackerEvent): ServerSentEvent = {
      event match {
        case issueCreated: IssueCreated => ServerSentEvent(issueCreated.asJson.noSpaces, "issue-created")
        case issueDescriptionUpdated: IssueDescriptionUpdated =>
          ServerSentEvent(issueDescriptionUpdated.asJson.noSpaces, "issue-description-updated")
        case issueClosed: IssueClosed   => ServerSentEvent(issueClosed.asJson.noSpaces, "issue-closed")
        case issueDeleted: IssueDeleted => ServerSentEvent(issueDeleted.asJson.noSpaces, "issue-deleted")
        case unprocessedIssue: IssueUnprocessed =>
          ServerSentEvent(unprocessedIssue.message.asJson.noSpaces, "issue-unprocessed")
      }
    }

    pathPrefix("issues") {
      post {
        entity(as[CreateIssueRequest]) {
          case CreateIssueRequest(description: String) =>
            onSuccess(issueTrackerWriteManager ? CreateIssue(UUID.randomUUID(), description, LocalDateTime.now())) {
              case IssueCreated(_, _, _)     => complete(StatusCodes.OK, "Issue created.")
              case IssueUnprocessed(message) => complete(StatusCodes.UnprocessableEntity, message)
            }
        }
      } ~ path(JavaUUID) { id =>
        put {
          entity(as[UpdateDescriptionRequest]) {
            case UpdateDescriptionRequest(`id`, description) =>
              onSuccess(issueTrackerWriteManager ? UpdateIssueDescription(id, description, LocalDateTime.now())) {
                case IssueDescriptionUpdated(_, _, _) => complete(StatusCodes.OK, "Issue description updated.")
                case IssueUnprocessed(message)        => complete(StatusCodes.UnprocessableEntity, message)
              }
          }
        } ~ {
          put {
            entity(as[CloseIssueRequest]) {
              case CloseIssueRequest(`id`) =>
                onSuccess(issueTrackerWriteManager ? CloseIssue(id)) {
                  case IssueClosed(_)            => complete("Issue has been closed.")
                  case IssueUnprocessed(message) => complete(StatusCodes.UnprocessableEntity, message)
                }
            }
          }
        } ~ delete {
          onSuccess(issueTrackerWriteManager ? DeleteIssue(id)) {
            case IssueDeleted(_)           => complete("Issue has been deleted.")
            case IssueUnprocessed(message) => complete(StatusCodes.UnprocessableEntity, message)
          }
        }
      } ~ path("event-stream") {
        complete {
          fromEventStream(fromIssueTrackerEvent)
        }
      }
    }
  }

  def props(host: String,
            port: Int,
            requestTimeout: FiniteDuration,
            eventBufferSize: Int,
            issueTrackerWriteManager: ActorRef,
            publishSubscribeMediator: ActorRef) =
    Props(
      new HttpServer(host, port, requestTimeout, eventBufferSize, issueTrackerWriteManager, publishSubscribeMediator)
    )
}

class HttpServer(host: String,
                 port: Int,
                 requestTimeout: FiniteDuration,
                 eventBufferSize: Int,
                 issueTrackerWriteManager: ActorRef,
                 publishSubscribeMediator: ActorRef)
    extends Actor
    with ActorLogging {
  import context.dispatcher
  import HttpServer._
  implicit val timeout      = Timeout(3.seconds)
  implicit val materializer = ActorMaterializer()

  Http(context.system)
    .bindAndHandle(routes(issueTrackerWriteManager, publishSubscribeMediator, requestTimeout, eventBufferSize),
                   host,
                   port)
    .pipeTo(self)

  override def receive: Receive = {
    case Http.ServerBinding(socketAddress) => log.info(s"Server started at: $socketAddress")
  }
}
