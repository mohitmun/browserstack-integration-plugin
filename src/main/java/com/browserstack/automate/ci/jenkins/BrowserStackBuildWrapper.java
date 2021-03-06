package com.browserstack.automate.ci.jenkins;

import static com.browserstack.automate.ci.common.logger.PluginLogger.log;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import com.browserstack.automate.ci.common.BrowserStackBuildWrapperOperations;
import com.browserstack.automate.ci.common.analytics.Analytics;
import com.browserstack.automate.ci.jenkins.local.BrowserStackLocalUtils;
import com.browserstack.automate.ci.jenkins.local.JenkinsBrowserStackLocal;
import com.browserstack.automate.ci.jenkins.local.LocalConfig;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractItem;
import hudson.model.BuildListener;
import hudson.model.BuildableItemWithBuildWrappers;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.tasks.BuildWrapper;
import hudson.util.DescribableList;


public class BrowserStackBuildWrapper extends BuildWrapper {

  private static final char CHAR_MASK = '*';

  private final LocalConfig localConfig;

  private String credentialsId;
  private String username;
  private String accesskey;

  @DataBoundConstructor
  public BrowserStackBuildWrapper(String credentialsId, LocalConfig localConfig) {
    this.credentialsId = credentialsId;
    this.localConfig = localConfig;
  }

  @Override
  public Environment setUp(final AbstractBuild build, final Launcher launcher,
      final BuildListener listener) throws IOException, InterruptedException {
    final PrintStream logger = listener.getLogger();

    final BrowserStackCredentials credentials =
        BrowserStackCredentials.getCredentials(build.getProject(), credentialsId);

    BrowserStackBuildAction action = build.getAction(BrowserStackBuildAction.class);
    if (action == null) {
      action = new BrowserStackBuildAction(credentials);
      build.addAction(action);
    }

    if (credentials != null) {
      this.username = credentials.getUsername();
      this.accesskey = credentials.getDecryptedAccesskey();
    }

    AutomateBuildEnvironment buildEnv = new AutomateBuildEnvironment(credentials, launcher, logger);
    if (accesskey != null && this.localConfig != null) {
      try {
        buildEnv.startBrowserStackLocal(build.getFullDisplayName());
      } catch (Exception e) {
        listener.fatalError(e.getMessage());
        throw new IOException(e.getCause());
      }
    }

    recordBuildStats();
    return buildEnv;
  }

  @Override
  public BrowserStackBuildWrapperDescriptor getDescriptor() {
    return (BrowserStackBuildWrapperDescriptor) super.getDescriptor();
  }

  public LocalConfig getLocalConfig() {
    return this.localConfig;
  }

  public String getCredentialsId() {
    return credentialsId;
  }

  public void setCredentialsId(String credentialsId) {
    this.credentialsId = credentialsId;
  }

  private void recordBuildStats() {
    boolean localEnabled = (localConfig != null);
    boolean localPathSet = localEnabled && StringUtils.isNotBlank(localConfig.getLocalPath());
    boolean localOptionsSet = localEnabled && StringUtils.isNotBlank(localConfig.getLocalOptions());
    Analytics.trackBuildRun(localEnabled, localPathSet, localOptionsSet);
  }

  private class AutomateBuildEnvironment extends BuildWrapper.Environment {
    private static final String ENV_JENKINS_BUILD_TAG = "BUILD_TAG";

    private final BrowserStackCredentials credentials;
    private final Launcher launcher;
    private final PrintStream logger;
    private JenkinsBrowserStackLocal browserstackLocal;
    private boolean isTearDownPhase;

    AutomateBuildEnvironment(BrowserStackCredentials credentials, Launcher launcher,
        PrintStream logger) {
      this.credentials = credentials;
      this.launcher = launcher;
      this.logger = logger;
    }

    public void buildEnvVars(Map<String, String> env) {
      BrowserStackBuildWrapperOperations buildWrapperOperations =
          new BrowserStackBuildWrapperOperations(credentials, isTearDownPhase, logger, localConfig,
              browserstackLocal);
      buildWrapperOperations.buildEnvVars(env);
      super.buildEnvVars(env);
    }

    public void startBrowserStackLocal(String buildTag) throws Exception {
      browserstackLocal = new JenkinsBrowserStackLocal(accesskey, localConfig, buildTag);
      log(logger, "Local: Starting BrowserStack Local...");
      browserstackLocal.start(launcher);
      log(logger, "Local: Started");
    }

    public boolean tearDown(AbstractBuild build, BuildListener listener)
        throws IOException, InterruptedException {
      isTearDownPhase = true;
      try {
        BrowserStackLocalUtils.stopBrowserStackLocal(browserstackLocal, launcher, logger);
      } catch (Exception e) {
        throw new IOException(e.getCause());
      }
      return true;
    }

  }

  static BuildWrapperItem<BrowserStackBuildWrapper> findBrowserStackBuildWrapper(
      final Job<?, ?> job) {
    BuildWrapperItem<BrowserStackBuildWrapper> wrapperItem =
        findItemWithBuildWrapper(job, BrowserStackBuildWrapper.class);
    return (wrapperItem != null) ? wrapperItem : null;
  }

  private static <T extends BuildWrapper> BuildWrapperItem<T> findItemWithBuildWrapper(
      final AbstractItem buildItem, Class<T> buildWrapperClass) {
    if (buildItem == null) {
      return null;
    }

    if (buildItem instanceof BuildableItemWithBuildWrappers) {
      BuildableItemWithBuildWrappers buildWrapper = (BuildableItemWithBuildWrappers) buildItem;
      DescribableList<BuildWrapper, Descriptor<BuildWrapper>> buildWrappersList =
          buildWrapper.getBuildWrappersList();

      if (buildWrappersList != null && !buildWrappersList.isEmpty()) {
        return new BuildWrapperItem<T>(buildWrappersList.get(buildWrapperClass), buildItem);
      }
    }

    if (buildItem.getParent() instanceof AbstractItem) {
      return findItemWithBuildWrapper((AbstractItem) buildItem.getParent(), buildWrapperClass);
    }

    return null;
  }

  static class BuildWrapperItem<T> {
    final T buildWrapper;
    final AbstractItem buildItem;

    BuildWrapperItem(T buildWrapper, AbstractItem buildItem) {
      this.buildWrapper = buildWrapper;
      this.buildItem = buildItem;
    }
  }
}
