package org.jenkinsci.plugins.mesos;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import com.mesosphere.usi.core.models.PodId;
import com.mesosphere.usi.core.models.PodStatus;
import com.mesosphere.usi.core.models.PodStatusUpdatedEvent;
import com.mesosphere.usi.core.models.TaskId;
import hudson.model.Descriptor;
import hudson.model.Node;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.mesos.v1.Protos.TaskID;
import org.apache.mesos.v1.Protos.TaskState;
import org.apache.mesos.v1.Protos.TaskStatus;
import org.jenkinsci.plugins.mesos.fixture.AgentSpecMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import scala.Option;

@ExtendWith(TestUtils.JenkinsParameterResolver.class)
public class MesosJenkinsAgentTest {

  static ActorSystem system = ActorSystem.create("agent-test");
  static ActorMaterializer materializer = ActorMaterializer.create(system);

  @Test
  void shortcircuitWaitUntilOnline(TestUtils.JenkinsRule j)
      throws Descriptor.FormException, IOException {
    // Given a Mesos Jenkins agent.
    final MesosJenkinsAgent agent =
        new MesosJenkinsAgent(
            null,
            "failed-agent",
            AgentSpecMother.simple,
            "A failing agent.",
            new URL("http://localhost:8080"),
            5,
            false,
            Collections.emptyList(),
            Duration.ofMinutes(5));

    // And we are waiting for it to come online.
    final CompletableFuture<Node> futureNode = agent.waitUntilOnlineAsync(materializer);

    // When the agent receives a fails task status event.
    PodId podId = new PodId("failed-agent");
    TaskStatus taskStatus =
        TaskStatus.newBuilder()
            .setTaskId(TaskID.newBuilder().setValue("failed-agent-1234").build())
            .setState(TaskState.TASK_FAILED)
            .setMessage("could not start agent.jar")
            .build();
    scala.collection.immutable.Map<TaskId, TaskStatus> taskStatusMap =
        new scala.collection.immutable.Map.Map1(new TaskId("failed-agent-1234"), taskStatus);
    PodStatus status = new PodStatus(podId, taskStatusMap);
    PodStatusUpdatedEvent failed = new PodStatusUpdatedEvent(podId, Option.apply(status));
    agent.update(failed);

    // Then we finish immediately.
    ExecutionException exception = assertThrows(ExecutionException.class, () -> futureNode.get());
    assertThat(exception.getCause(), is(instanceOf(IllegalStateException.class)));
    assertThat(
        exception.getCause().getMessage(),
        is(equalTo("Agent failed-agent became TASK_FAILED: could not start agent.jar")));
  }
}
