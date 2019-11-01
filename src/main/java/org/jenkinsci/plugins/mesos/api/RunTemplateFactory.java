package org.jenkinsci.plugins.mesos.api;

import static org.apache.mesos.v1.Protos.ContainerInfo.Type.MESOS;
import static org.apache.mesos.v1.Protos.Image.Type.DOCKER;

import com.mesosphere.usi.core.models.TaskBuilder;
import com.mesosphere.usi.core.models.TaskName;
import com.mesosphere.usi.core.models.resources.ResourceRequirement;
import com.mesosphere.usi.core.models.template.FetchUri;
import com.mesosphere.usi.core.models.template.LegacyLaunchRunTemplate;
import com.mesosphere.usi.core.models.template.RunTemplate;
import com.mesosphere.usi.core.models.template.SimpleRunTemplateFactory.SimpleTaskInfoBuilder;
import java.util.List;
import java.util.Optional;
import org.apache.mesos.v1.Protos.ContainerInfo;
import org.apache.mesos.v1.Protos.ContainerInfo.DockerInfo;
import org.apache.mesos.v1.Protos.ContainerInfo.DockerInfo.Network;
import org.apache.mesos.v1.Protos.Image;
import org.apache.mesos.v1.Protos.Offer;
import org.apache.mesos.v1.Protos.Resource;
import org.apache.mesos.v1.Protos.TaskInfo;
import org.apache.mesos.v1.Protos.Volume;
import org.apache.mesos.v1.Protos.Volume.Mode;
import org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.Seq;
import scala.collection.immutable.Map;

/** The builder is used by {@link LaunchCommandBuilder} to construct a USI {@link RunTemplate}. */
public class RunTemplateFactory {

  /**
   * Constructs a {@link RunTemplate} based on the passed parameters.
   *
   * <p>The template uses either the {@link SimpleTaskInfoBuilder} or a custom {@link
   * ContainerInfoTaskInfoBuilder}.
   *
   * @param agentName The name of the Mesos task/Jenkins agent, {@link
   *     LaunchCommandBuilder#withName(String)}.
   * @param requirements The resource requirements for a Jenkins agent.
   * @param shellCommand The shell command built by {@link LaunchCommandBuilder}.
   * @param role The Mesos role the Jenkins agent will assume.
   * @param fetchUris Artifacts that are fetched, eg the Jenkins agent.jar.
   * @param containerInfo Optional information for a Docker or Mesos container.
   * @return the new USI run template.
   */
  static RunTemplate newRunTemplate(
      String agentName,
      List<ResourceRequirement> requirements,
      String shellCommand,
      String role,
      List<FetchUri> fetchUris,
      Optional<MesosAgentSpecTemplate.ContainerInfo> containerInfo) {
    TaskBuilder taskBuilder =
        new SimpleTaskInfoBuilder(
            convertListToSeq(requirements),
            shellCommand,
            role,
            convertListToSeq(fetchUris),
            Option.empty());
    if (containerInfo.isPresent()) {
      taskBuilder = new ContainerInfoTaskInfoBuilder(agentName, taskBuilder, containerInfo.get());
    }
    return new LegacyLaunchRunTemplate(role, taskBuilder);
  }

  private static <T> Seq<T> convertListToSeq(List<T> inputList) {
    return JavaConverters.asScalaIteratorConverter(inputList.iterator()).asScala().toSeq();
  }

  /**
   * This is a small USI {@link TaskBuilder} that wraps the {@link SimpleTaskInfoBuilder} and adds
   * {@link org.apache.mesos.v1.Protos.ContainerInfo} to the Mesos task info if defined.
   */
  public static class ContainerInfoTaskInfoBuilder implements TaskBuilder {

    private static final Logger logger =
        LoggerFactory.getLogger(ContainerInfoTaskInfoBuilder.class);

    public static final String PORT_RESOURCE_NAME = "ports";
    public static final String MESOS_DEFAULT_ROLE = "*";
    public static final Network DEFAULT_NETWORKING = Network.HOST;

    final TaskBuilder simpleTaskInfoBuilder;
    final MesosAgentSpecTemplate.ContainerInfo containerInfo;
    final String agentName;

    /**
     * Constructs a new {@link TaskBuilder}.
     *
     * <p>This is basically a port of JenkinsScheduler.getContainerInfoBuilder from v1.1 of the
     * plugin.
     *
     * @param agentName The name of the Jenkins agent.
     * @param taskInfoBuilder The original {@link SimpleTaskInfoBuilder}.
     * @param containerInfo The additional container information.
     */
    public ContainerInfoTaskInfoBuilder(
        String agentName,
        TaskBuilder taskInfoBuilder,
        MesosAgentSpecTemplate.ContainerInfo containerInfo) {
      this.agentName = agentName;
      this.simpleTaskInfoBuilder = taskInfoBuilder;
      this.containerInfo = containerInfo;
    }

    @Override
    public Seq<ResourceRequirement> resourceRequirements() {
      return this.simpleTaskInfoBuilder.resourceRequirements();
    }

    @Override
    public void buildTask(
        TaskInfo.Builder builder,
        Offer matchedOffer,
        Seq<Resource> taskResources,
        Map<TaskName, Seq<Resource>> peerTaskResources) {
      this.simpleTaskInfoBuilder.buildTask(builder, matchedOffer, taskResources, peerTaskResources);
      this.getContainerInfoBuilder(matchedOffer, builder);
    }

    /**
     * This is the original v1.1 JenkinsScheduler.getContainerInfoBuilder.
     *
     * @param offer The Mesos offer.
     * @param agentName The name of the Jenkins agent
     * @param taskBuilder The Mesos task info builder.
     */
    private void getContainerInfoBuilder(Offer offer, TaskInfo.Builder taskBuilder) {
      ContainerInfo.Type containerType = ContainerInfo.Type.valueOf(this.containerInfo.getType());

      ContainerInfo.Builder containerInfoBuilder =
          ContainerInfo.newBuilder().setType(containerType);

      switch (containerType) {
        case DOCKER:
          logger.info("Launching in Docker Mode:" + this.containerInfo.getDockerImage());
          DockerInfo.Builder dockerInfoBuilder =
              DockerInfo.newBuilder()
                  .setImage(this.containerInfo.getDockerImage())
                  .setPrivileged(this.containerInfo.getDockerPrivilegedMode())
                  .setForcePullImage(this.containerInfo.getDockerForcePullImage());

          dockerInfoBuilder.setNetwork(DEFAULT_NETWORKING);

          //  https://github.com/jenkinsci/mesos-plugin/issues/109
          if (dockerInfoBuilder.getNetwork() != Network.HOST) {
            containerInfoBuilder.setHostname(agentName);
          }

          containerInfoBuilder.setDocker(dockerInfoBuilder);
          break;
        case MESOS:
          logger.info("Launching in UCR Mode:" + this.containerInfo.getDockerImage());

          Image dockerImage =
              Image.newBuilder()
                  .setType(DOCKER)
                  .setDocker(
                      Image.Docker.newBuilder()
                          .setName(this.containerInfo.getDockerImage())
                          .build())
                  .build();

          containerInfoBuilder
              .setType(MESOS)
              .setMesos(ContainerInfo.MesosInfo.newBuilder().setImage(dockerImage).build());

          if (this.containerInfo.getIsDind()) {
            containerInfoBuilder.addVolumes(
                Volume.newBuilder()
                    .setContainerPath("/var/lib/docker")
                    .setHostPath("docker")
                    .setMode(Mode.RW));
          }
          break;

        default:
          logger.warn("Unknown container type:" + this.containerInfo.getType());
      }

      for (MesosAgentSpecTemplate.Volume volume : this.containerInfo.getVolumesOrEmpty()) {
        logger.info("Adding volume '" + volume.getContainerPath() + "'");
        Volume.Builder volumeBuilder =
            Volume.newBuilder()
                .setContainerPath(volume.getContainerPath())
                .setMode(volume.isReadOnly() ? Mode.RO : Mode.RW);
        if (!volume.getHostPath().isEmpty()) {
          volumeBuilder.setHostPath(volume.getHostPath());
        }
        containerInfoBuilder.addVolumes(volumeBuilder.build());
      }

      taskBuilder.setContainer(containerInfoBuilder.build());
    }
  }
}
