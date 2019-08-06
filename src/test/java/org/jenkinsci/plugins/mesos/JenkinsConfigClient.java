package org.jenkinsci.plugins.mesos;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jvnet.hudson.test.JenkinsRule.WebClient;

/**
 * A simple client that hacks around the Jenkins configure form submit.
 *
 * <p>It allows to add a simple Mesos Cloud with one label.
 */
public class JenkinsConfigClient {
  final OkHttpClient client;
  final URL jenkinsConfigUrl;

  public JenkinsConfigClient(WebClient jenkinsClient) throws IOException {
    this.client = new OkHttpClient();
    this.jenkinsConfigUrl = jenkinsClient.createCrumbedUrl("configSubmit");
  }

  /**
   * Submits a Jenkins configuration form and adds a Mesos Cloud with one agent specs.
   *
   * @param mesosMasterUrl The URL of the Mesos master to connect to.
   * @param frameworkName The Mesos framework name the plugin should use.
   * @param role
   * @param agentUser
   * @param jenkinsUrl The Jenkins URL used as the base for Mesos tasks to download the Jenkins
   *     agent.
   * @param label The Jenkins node label of the Mesos task.
   * @param mode Jenkins mode, can be "NORMAL" or "EXCLUSIVE".
   * @return A {@link Response} of the {@link OkHttpClient} request.
   * @throws IOException
   * @throws UnsupportedEncodingException
   */
  public Response addMesosCloud(
      String mesosMasterUrl,
      String frameworkName,
      String role,
      String agentUser,
      String jenkinsUrl,
      String label,
      String mode)
      throws IOException, UnsupportedEncodingException {
    final String jsonData =
        addJsonDefaults(Json.createObjectBuilder(), jenkinsUrl)
            .add(
                "jenkins-model-GlobalCloudConfiguration",
                Json.createObjectBuilder()
                    .add(
                        "cloud",
                        Json.createObjectBuilder()
                            .add("mesosMasterUrl", mesosMasterUrl)
                            .add("frameworkName", frameworkName)
                            .add("role", role)
                            .add("agentUser", agentUser)
                            .add("jenkinsUrl", jenkinsUrl)
                            .add(
                                "mesosAgentSpecTemplates",
                                Json.createObjectBuilder()
                                    .add("label", label)
                                    .add("mode", mode)
                                    .add("idleTerminationMinutes", "1")
                                    .add("cpus", "0.1")
                                    .add("mem", "32")
                                    .add("reusable", true)
                                    .add("minExecutors", "1")
                                    .add("maxExecutors", "1")
                                    .add("disk", "0")
                                    .add("executorMem", "1")
                                    .add("remoteFsRoot", "")
                                    .add("agentAttributes", "")
                                    .add("jvmArgs", "")
                                    .add("jnlpArgs", "")
                                    .add("defaultAgent", "")
                                    .add("additionalUris", "")
                                    .add("containerImage", "")
                                    .build())
                            .add("stapler-class", "org.jenkinsci.plugins.mesos.MesosCloud")
                            .add("$class", "org.jenkinsci.plugins.mesos.MesosCloud")
                            .build())
                    .build())
            .add("core:apply", "")
            .build()
            .toString();

    final String formData =
        addFormDefaults(new FormDataBuilder(), jenkinsUrl)
            .add("_.mesosMasterUrl", mesosMasterUrl)
            .add("_.frameworkName", frameworkName)
            .add("_.role", "*")
            .add("_.agentUser", agentUser)
            .add("_.jenkinsUrl", jenkinsUrl)
            .add("_.label", label)
            .add("mode", mode)
            .add("stapler-class", "org.jenkinsci.plugins.mesos.MesosCloud")
            .add("$class", "org.jenkinsci.plugins.mesos.MesosCloud")
            .add("core:apply", "")
            .add("json", jsonData)
            .build();

    final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
    RequestBody rawBody = RequestBody.create(FORM, formData);
    Request request =
        new Request.Builder()
            .url(this.jenkinsConfigUrl.toString())
            .addHeader("Accept", "text/html,application/xhtml+xml")
            .addHeader("Origin", jenkinsUrl)
            .addHeader("Upgrade-Insecure-Requests", "1")
            .post(rawBody)
            .build();
    return this.client.newCall(request).execute();
  }

  /**
   * Adds defaults from a manual form submit with Chrome Dev Tools.
   *
   * <p>The form includes a JSON field with all default configurations.
   *
   * @param builder The json builder that will be changed.
   * @param jenkinsUrl URL for jenkins.
   * @return The changed builder.
   */
  private JsonObjectBuilder addJsonDefaults(JsonObjectBuilder builder, String jenkinsUrl) {
    return builder
        .add("system_message", "")
        .add(
            "jenkins-model-MasterBuildConfiguration",
            Json.createObjectBuilder()
                .add("numExecutors", "2")
                .add("labelString", "")
                .add("mode", "NORMAL")
                .build())
        .add(
            "jenkins-model-GlobalQuietPeriodConfiguration",
            Json.createObjectBuilder().add("quietPeriod", "5").build())
        .add(
            "jenkins-model-GlobalSCMRetryCountConfiguration",
            Json.createObjectBuilder().add("scmCheckoutRetryCount", "0").build())
        .add(
            "jenkins-model-GlobalProjectNamingStrategyConfiguration",
            Json.createObjectBuilder().build())
        .add(
            "jenkins-model-GlobalNodePropertiesConfiguration",
            Json.createObjectBuilder()
                .add("globalNodeProperties", Json.createObjectBuilder().build())
                .build())
        .add(
            "hudson-model-UsageStatistics",
            Json.createObjectBuilder()
                .add("usageStatisticsCollected", Json.createObjectBuilder().build())
                .build())
        .add(
            "jenkins-management-AdministrativeMonitorsConfiguration",
            Json.createObjectBuilder()
                .add(
                    "administrativeMonitor",
                    Json.createArrayBuilder()
                        .add("hudson.PluginManager$PluginCycleDependenciesMonitor")
                        .add("hudson.PluginManager$PluginUpdateMonitor")
                        .add("hudson.PluginWrapper$PluginWrapperAdministrativeMonitor")
                        .add("hudsonHomeIsFull")
                        .add("hudson.diagnosis.NullIdDescriptorMonitor")
                        .add("OldData")
                        .add("hudson.diagnosis.ReverseProxySetupMonitor")
                        .add("hudson.diagnosis.TooManyJobsButNoView")
                        .add("hudson.model.UpdateCenter$CoreUpdateMonitor")
                        .add("hudson.node_monitors.MonitorMarkedNodeOffline")
                        .add("hudson.triggers.SCMTrigger$AdministrativeMonitorImpl")
                        .add("jenkins.CLI")
                        .add("jenkins.diagnosis.HsErrPidList")
                        .add("jenkins.diagnostics.CompletedInitializationMonitor")
                        .add("jenkins.diagnostics.RootUrlNotSetMonitor")
                        .add("jenkins.diagnostics.SecurityIsOffMonitor")
                        .add("jenkins.diagnostics.URICheckEncodingMonitor")
                        .add("jenkins.model.DownloadSettings$Warning")
                        .add("jenkins.model.Jenkins$EnforceSlaveAgentPortAdministrativeMonitor")
                        .add("jenkins.security.RekeySecretAdminMonitor")
                        .add("jenkins.security.UpdateSiteWarningsMonitor")
                        .add(
                            "jenkins.security.apitoken.ApiTokenPropertyDisabledDefaultAdministrativeMonitor")
                        .add(
                            "jenkins.security.apitoken.ApiTokenPropertyEnabledNewLegacyAdministrativeMonitor")
                        .add("legacyApiToken")
                        .add("jenkins.security.csrf.CSRFAdministrativeMonitor")
                        .add("slaveToMasterAccessControl")
                        .add("jenkins.security.s2m.MasterKillSwitchWarning")
                        .add("jenkins.slaves.DeprecatedAgentProtocolMonitor")
                        .build())
                .build())
        .add(
            "jenkins-model-JenkinsLocationConfiguration",
            Json.createObjectBuilder().add("url", jenkinsUrl).add("adminAddress", "").build())
        .add("hudson-task-Shell", Json.createObjectBuilder().add("shell", "").build());
  }

  /**
   * Adds defaults from a manual form submit with Chrome Dev Tools.
   *
   * @param builder The form data builder that will be changed.
   * @param jenkinsUrl URL for jenkins.
   * @return The changed builder.
   * @throws UnsupportedEncodingException
   */
  private FormDataBuilder addFormDefaults(FormDataBuilder builder, String jenkinsUrl)
      throws UnsupportedEncodingException {
    return builder
        .add("system_message", "")
        .add("_.numExecutors", "2")
        .add("_.labelString", "")
        .add("master.mode", "NORMAL")
        .add("_.quietPeriod", "5")
        .add("_.scmCheckoutRetryCount", "0")
        .add("stapler-class", "jenkins.model.ProjectNamingStrategy$PatternProjectNamingStrategy")
        .add("$class", "jenkins.model.ProjectNamingStrategy$PatternProjectNamingStrategy")
        .add("_.namePattern", ".*")
        .add("_.description", "")
        .add("namingStrategy", "1")
        .add("stapler-class", "jenkins.model.ProjectNamingStrategy$DefaultProjectNamingStrategy")
        .add("$class", "jenkins.model.ProjectNamingStrategy$DefaultProjectNamingStrategy")
        .add("_.usageStatisticsCollected", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("administrativeMonitor", "on")
        .add("_.url", jenkinsUrl)
        .add("_.adminAddress", "")
        .add("_.shell", "");
  }

  /** Jenkins submits forms in URL encoding. This builder helps to construct such a request body. */
  private static class FormDataBuilder {
    final List<String> values = new ArrayList<>();

    /**
     * Add a key value pair to the form. Duplicates are allowed. Keys and values are URL encoded.
     *
     * @param key The form field key/name.
     * @param value The value for the key.
     * @return This builder.
     * @throws UnsupportedEncodingException
     */
    public FormDataBuilder add(String key, String value) throws UnsupportedEncodingException {
      final String pair = URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8");
      this.values.add(pair);
      return this;
    }

    /** @return a joined string of all key/value pairs. */
    public String build() {
      return String.join("&", this.values);
    }
  }
}
