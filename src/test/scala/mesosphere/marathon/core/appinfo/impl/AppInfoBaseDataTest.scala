package mesosphere.marathon.core.appinfo.impl

import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.core.appinfo.{ AppInfo, EnrichedTask, TaskCounts, TaskStatsByVersion }
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.health.{ Health, HealthCheckManager }
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.pod.{ HostNetwork, MesosContainer, PodDefinition }
import mesosphere.marathon.core.readiness.ReadinessCheckResult
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.{ ReadOnlyPodRepository, TaskFailureRepository }
import mesosphere.marathon.test.{ MarathonSpec, Mockito }
import mesosphere.marathon.upgrade.DeploymentManager.DeploymentStepInfo
import mesosphere.marathon.upgrade.{ DeploymentPlan, DeploymentStep }
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class AppInfoBaseDataTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  class Fixture {
    val runSpecId = PathId("/test")
    lazy val clock = ConstantClock()
    lazy val taskTracker = mock[InstanceTracker]
    lazy val healthCheckManager = mock[HealthCheckManager]
    lazy val marathonSchedulerService = mock[MarathonSchedulerService]
    lazy val taskFailureRepository = mock[TaskFailureRepository]
    lazy val podRepository = mock[ReadOnlyPodRepository]

    lazy val baseData = new AppInfoBaseData(
      clock,
      taskTracker,
      healthCheckManager,
      marathonSchedulerService,
      taskFailureRepository,
      podRepository
    )

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
      noMoreInteractions(healthCheckManager)
      noMoreInteractions(marathonSchedulerService)
      noMoreInteractions(taskFailureRepository)
    }
  }

  val app = AppDefinition(PathId("/test"))
  val other = AppDefinition(PathId("/other"))
  val pod = PodDefinition(id = PathId("/pod"), networks = Seq(HostNetwork), containers = Seq(
    MesosContainer(name = "ct1", resources = Resources(0.01, 32))
  ))

  test("not embedding anything results in no calls") {
    val f = new Fixture

    When("getting AppInfos without embeds")
    val appInfo = f.baseData.appInfoFuture(app, Set.empty).futureValue

    Then("we get an empty appInfo")
    appInfo should be(AppInfo(app))

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting tasks retrieves tasks from taskTracker and health infos") {
    val f = new Fixture
    Given("three tasks in the task tracker")
    val builder1 = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning()
    val builder2 = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning()
    val builder3 = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning()
    val running1: Task = builder1.pickFirstTask()
    val running2: Task = builder2.pickFirstTask()
    val running3: Task = builder3.pickFirstTask()

    import scala.concurrent.ExecutionContext.Implicits.global
    f.taskTracker.instancesBySpec()(global) returns
      Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(app.id, Iterable(builder1.getInstance(), builder2.getInstance(), builder3.getInstance()))))

    val alive = Health(running2.taskId, lastSuccess = Some(Timestamp(1)))
    val unhealthy = Health(running3.taskId, lastFailure = Some(Timestamp(1)))

    f.healthCheckManager.statuses(app.id) returns Future.successful(
      Map(
        running1.taskId -> Seq.empty,
        running2.taskId -> Seq(alive),
        running3.taskId -> Seq(unhealthy)
      )
    )

    When("requesting AppInfos with tasks")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Tasks)).futureValue

    Then("we get a tasks object in the appInfo")
    appInfo.maybeTasks should not be empty
    appInfo.maybeTasks.get.map(_.appId.toString) should have size 3
    appInfo.maybeTasks.get.map(_.task.taskId.idString).toSet should be (Set(
      running1.taskId.idString, running2.taskId.idString, running3.taskId.idString))

    appInfo should be(AppInfo(app, maybeTasks = Some(
      Seq(
        EnrichedTask(app.id, running1, Seq.empty),
        EnrichedTask(app.id, running2, Seq(alive)),
        EnrichedTask(app.id, running3, Seq(unhealthy))
      )
    )))

    And("the taskTracker should have been called")
    verify(f.taskTracker, times(1)).instancesBySpec()(global)

    And("the healthCheckManager as well")
    verify(f.healthCheckManager, times(1)).statuses(app.id)

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting task counts only retrieves tasks from taskTracker and health stats") {
    val f = new Fixture
    Given("one staged and two running tasks in the taskTracker")
    val stagedBuilder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskStaged()
    val staged: Task = stagedBuilder.pickFirstTask()

    val runningBuilder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning()
    val running: Task = runningBuilder.pickFirstTask()

    val running2Builder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning()
    val running2: Task = running2Builder.pickFirstTask()

    import scala.concurrent.ExecutionContext.Implicits.global
    f.taskTracker.instancesBySpec()(global) returns
      Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(app.id, Iterable(stagedBuilder.getInstance(), runningBuilder.getInstance(), running2Builder.getInstance()))))

    f.healthCheckManager.statuses(app.id) returns Future.successful(
      Map(
        staged.taskId -> Seq(),
        running.taskId -> Seq(Health(running.taskId, lastFailure = Some(Timestamp(1)))),
        running2.taskId -> Seq(Health(running2.taskId, lastSuccess = Some(Timestamp(2))))
      )
    )

    When("requesting AppInfos with counts")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Counts)).futureValue

    Then("we get counts object in the appInfo")
    appInfo should be(AppInfo(app, maybeCounts = Some(
      TaskCounts(tasksStaged = 1, tasksRunning = 2, tasksHealthy = 1, tasksUnhealthy = 1)
    )))

    And("the taskTracker should have been called")
    verify(f.taskTracker, times(1)).instancesBySpec()(global)

    And("the healthCheckManager as well")
    verify(f.healthCheckManager, times(1)).statuses(app.id)

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting deployments does not request anything else") {
    val f = new Fixture
    Given("One related and one unrelated deployment")
    val emptyGroup = Group.empty
    val relatedDeployment = DeploymentPlan(emptyGroup, emptyGroup.copy(apps = Map(app.id -> app)))
    val unrelatedDeployment = DeploymentPlan(emptyGroup, emptyGroup.copy(apps = Map(other.id -> other)))
    f.marathonSchedulerService.listRunningDeployments() returns Future.successful(Seq[DeploymentStepInfo](
      DeploymentStepInfo(relatedDeployment, DeploymentStep(Seq.empty), 1),
      DeploymentStepInfo(unrelatedDeployment, DeploymentStep(Seq.empty), 1)
    ))

    When("Getting AppInfos without counts")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Deployments)).futureValue

    Then("we get an counts in the appInfo")
    appInfo should be(AppInfo(app, maybeDeployments = Some(
      Seq(Identifiable(relatedDeployment.id))
    )))

    And("the marathonSchedulerService should have been called to retrieve the deployments")
    verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting deployments does work if no deployments are running") {
    val f = new Fixture
    Given("No deployments")
    f.marathonSchedulerService.listRunningDeployments() returns Future.successful(
      Seq.empty[DeploymentStepInfo]
    )

    When("Getting AppInfos with deployments")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Deployments)).futureValue

    Then("we get an empty list of deployments")
    appInfo should be(AppInfo(app, maybeDeployments = Some(
      Seq.empty
    )))

    And("the marathonSchedulerService should have been called to retrieve the deployments")
    verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting readiness check results") {
    val f = new Fixture
    Given("One related and one unrelated deployment")
    val emptyGroup = Group.empty
    val deployment = DeploymentPlan(emptyGroup, emptyGroup.copy(apps = Map(app.id -> app)))
    val taskId: Task.Id = Task.Id.forRunSpec(app.id)
    val result = ReadinessCheckResult("foo", taskId, ready = false, None)
    f.marathonSchedulerService.listRunningDeployments() returns Future.successful(Seq[DeploymentStepInfo](
      DeploymentStepInfo(deployment, DeploymentStep(Seq.empty), 1, Map(taskId -> result))
    ))

    When("Getting AppInfos without counts")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Readiness)).futureValue

    Then("we get an counts in the appInfo")
    appInfo should be(AppInfo(app, maybeReadinessCheckResults = Some(
      Seq(result)
    )))

    And("the marathonSchedulerService should have been called to retrieve the deployments")
    verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting lastTaskFailure when one exists") {
    val f = new Fixture
    Given("One last taskFailure")
    f.taskFailureRepository.get(app.id) returns Future.successful(Some(TaskFailureTestHelper.taskFailure))

    When("Getting AppInfos with last task failures")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.LastTaskFailure)).futureValue

    Then("we get the failure in the app info")
    appInfo should be(AppInfo(app, maybeLastTaskFailure = Some(
      TaskFailureTestHelper.taskFailure
    )))

    And("the taskFailureRepository should have been called to retrieve the failure")
    verify(f.taskFailureRepository, times(1)).get(app.id)

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting lastTaskFailure when None exist") {
    val f = new Fixture
    Given("no taskFailure")
    f.taskFailureRepository.get(app.id) returns Future.successful(None)

    When("Getting AppInfos with last task failures")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.LastTaskFailure)).futureValue

    Then("we get no failure in the app info")
    appInfo should be(AppInfo(app))

    And("the taskFailureRepository should have been called to retrieve the failure")
    verify(f.taskFailureRepository, times(1)).get(app.id)

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting taskStats") {
    val f = new Fixture
    Given("one staged and two running tasks in the taskTracker")
    val stagedBuilder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskStaged(stagedAt = Timestamp((f.clock.now() - 10.seconds).toDateTime.getMillis))
    val staged: Task = stagedBuilder.pickFirstTask()
    val runningBuilder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning(stagedAt = Timestamp((f.clock.now() - 11.seconds).toDateTime.getMillis))
    val running: Task = runningBuilder.pickFirstTask()
    val running2Builder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning(stagedAt = Timestamp((f.clock.now() - 11.seconds).toDateTime.getMillis))
    val running2: Task = running2Builder.pickFirstTask()

    import scala.concurrent.ExecutionContext.Implicits.global
    val instances: Set[Instance] = Set(stagedBuilder.getInstance(), runningBuilder.getInstance(), running2Builder.getInstance())
    f.taskTracker.instancesBySpec()(global) returns
      Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(app.id, instances)))

    val statuses: Map[Task.Id, Seq[Health]] = Map(
      staged.taskId -> Seq(),
      running.taskId -> Seq(Health(running.taskId, lastFailure = Some(Timestamp(1)))),
      running2.taskId -> Seq(Health(running2.taskId, lastSuccess = Some(Timestamp(2))))
    )
    f.healthCheckManager.statuses(app.id) returns Future.successful(statuses)

    When("requesting AppInfos with taskStats")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.TaskStats)).futureValue

    Then("we get taskStats object in the appInfo")
    // we check the calculation of the stats in TaskStatsByVersionTest, so we only check some basic stats

    import mesosphere.marathon.api.v2.json.Formats._
    withClue(Json.prettyPrint(Json.toJson(appInfo))) {
      appInfo.maybeTaskStats should not be empty
      appInfo.maybeTaskStats.get.maybeTotalSummary should not be empty
      appInfo.maybeTaskStats.get.maybeTotalSummary.get.counts.tasksStaged should be (1)
      appInfo.maybeTaskStats.get.maybeTotalSummary.get.counts.tasksRunning should be (2)

      appInfo should be(AppInfo(
        app,
        maybeTaskStats = Some(TaskStatsByVersion(f.clock.now(), app.versionInfo, instances.flatMap(_.tasks), statuses))
      ))
    }

    And("the taskTracker should have been called")
    verify(f.taskTracker, times(1)).instancesBySpec()(global)

    And("the healthCheckManager as well")
    verify(f.healthCheckManager, times(1)).statuses(app.id)

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Combining embed options work") {
    val f = new Fixture
    Given("One last taskFailure and no deployments")
    f.taskFailureRepository.get(app.id) returns Future.successful(Some(TaskFailureTestHelper.taskFailure))
    f.marathonSchedulerService.listRunningDeployments() returns Future.successful(
      Seq.empty[DeploymentStepInfo]
    )

    When("Getting AppInfos with last task failures and deployments")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Deployments)).futureValue

    Then("we get the failure in the app info")
    appInfo should be(AppInfo(
      app,
      maybeLastTaskFailure = Some(TaskFailureTestHelper.taskFailure),
      maybeDeployments = Some(Seq.empty)
    ))

    And("the taskFailureRepository should have been called to retrieve the failure")
    verify(f.taskFailureRepository, times(1)).get(app.id)

    And("the marathonSchedulerService should have been called to retrieve the deployments")
    verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  def fakeInstance(pod: PodDefinition)(implicit f: Fixture): Instance = {
    val dummyAgent = Instance.AgentInfo("", None, Nil)
    val instanceId = Instance.Id.forRunSpec(pod.id)
    Instance(
      instanceId = instanceId,
      agentInfo = dummyAgent,
      state = InstanceState(
        status = Condition.Running,
        since = f.clock.now(),
        version = pod.version,
        healthy = None),
      tasksMap = pod.containers.map { ct =>
        val taskId = Task.Id.forInstanceId(instanceId, Some(ct))
        taskId -> Task.LaunchedEphemeral(
          taskId = taskId,
          agentInfo = dummyAgent,
          runSpecVersion = pod.version,
          status = Task.Status.apply(
            stagedAt = f.clock.now(),
            startedAt = Some(f.clock.now()),
            mesosStatus = None,
            taskStatus = Condition.Running),
          hostPorts = Nil)
      }.toMap)
  }

  test("pod statuses xref the correct spec versions") {
    implicit val f = new Fixture
    val v1 = f.clock.now()
    val podspec1 = pod.copy(version = v1)

    f.clock += 1.minute

    // the same as podspec1 but with a new version and a renamed container
    val v2 = f.clock.now()
    val podspec2 = pod.copy(version = v2, containers = pod.containers.map(_.copy(name = "ct2")))

    Given("multiple versions of the same pod specification")
    def findPodSpecByVersion(version: Timestamp): Option[PodDefinition] = {
      if (v1 == version) Some(podspec1)
      else if (v2 == version) Some(podspec2)
      else Option.empty[PodDefinition]
    }

    f.clock += 1.minute

    When("requesting pod instance status")
    val instanceV1 = fakeInstance(podspec1)
    val maybeStatus1 = f.baseData.podInstanceStatus(instanceV1)(findPodSpecByVersion)

    Then("instance referring to v1 spec should reference container ct1")
    maybeStatus1 should be('nonEmpty)
    maybeStatus1.foreach { status =>
      status.containers.size should be(1)
      status.containers(0).name should be("ct1")
    }

    And("instance referring to v2 spec should reference container ct2")
    val instanceV2 = fakeInstance(podspec2)
    val maybeStatus2 = f.baseData.podInstanceStatus(instanceV2)(findPodSpecByVersion)

    maybeStatus2 should be('nonEmpty)
    maybeStatus2.foreach { status =>
      status.containers.size should be(1)
      status.containers(0).name should be("ct2")
    }

    And("instance referring to a bogus version doesn't have any status")
    val v3 = f.clock.now()
    val instanceV3 = fakeInstance(pod.copy(version = v3))
    val maybeStatus3 = f.baseData.podInstanceStatus(instanceV3)(findPodSpecByVersion)

    maybeStatus3 should be ('empty)
  }

}
