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

import java.time.LocalDateTime
import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.akkacqrs.IssueTrackerWrite
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

class IssueTrackerWriteSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  import org.akkacqrs.IssueTrackerWrite._

  implicit val system = ActorSystem("issue-tracker-spec-system")

  "When issue is not created then IssueTrackerWrite actor" should {
    val sender             = TestProbe()
    implicit val senderRef = sender.ref
    val uuid               = UUID.randomUUID()
    val description        = "Test description"
    val dateTime           = LocalDateTime.now()

    val issueTrackerWrite = system.actorOf(IssueTrackerWrite.props(uuid))

    "correctly create a new issue" in {
      issueTrackerWrite ! CreateIssue(uuid, description, dateTime)
      sender.expectMsg(IssueCreated(uuid, description, dateTime))
    }

    "not create an issue with same id again" in {
      issueTrackerWrite ! CreateIssue(uuid, description, dateTime)
      sender.expectMsg(IssueUnprocessed("Issue has been already created."))
    }

    "correctly update an issue" in {
      val updatedIssueDescription = "Updated issue description"
      val updatedDateTime         = LocalDateTime.now()
      issueTrackerWrite ! UpdateIssueDescription(uuid, updatedIssueDescription, updatedDateTime)
      sender.expectMsg(IssueDescriptionUpdated(uuid, updatedIssueDescription, updatedDateTime))
    }

    "correctly update an issue again" in {
      val updatedIssueDescription = "Updated issue description second time"
      val updatedDateTime         = LocalDateTime.now()
      issueTrackerWrite ! UpdateIssueDescription(uuid, updatedIssueDescription, updatedDateTime)
      sender.expectMsg(IssueDescriptionUpdated(uuid, updatedIssueDescription, updatedDateTime))
    }

    "correctly close an issue" in {
      issueTrackerWrite ! CloseIssue(uuid)
      sender.expectMsg(IssueClosed(uuid))
    }

    "not close an issue again" in {
      issueTrackerWrite ! CloseIssue(uuid)
      sender.expectMsg(IssueUnprocessed("Issue has been closed."))
    }

    "correctly delete an issue" in {
      issueTrackerWrite ! DeleteIssue(uuid)
      sender.expectMsg(IssueDeleted(uuid))
    }

    "not delete an issue again" in {
      issueTrackerWrite ! DeleteIssue(uuid)
      sender.expectMsg(IssueUnprocessed("Issue has been deleted."))
    }
  }

  "When issue is not being created then IssueTracker actor" should {
    val sender             = TestProbe()
    implicit val senderRef = sender.ref
    val uuid               = UUID.randomUUID()

    val issueTrackerWrite = system.actorOf(IssueTrackerWrite.props(uuid))

    "not update an issue" in {
      issueTrackerWrite ! UpdateIssueDescription(uuid, "Updated description", LocalDateTime.now())
      sender.expectMsg(IssueUnprocessed("Create an issue first."))
    }

    "not close an issue" in {
      issueTrackerWrite ! CloseIssue(uuid)
      sender.expectMsg(IssueUnprocessed("Create an issue first."))
    }

    "not delete an issue" in {
      issueTrackerWrite ! DeleteIssue(uuid)
      sender.expectMsg(IssueUnprocessed("Create an issue first."))
    }
  }
}
